package com.thysummer.coopcraft.civilization;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.Norm;
import com.thysummer.coopcraft.civilization.simulation.NormProposal;
import com.thysummer.coopcraft.civilization.simulation.NormSystem;
import com.thysummer.coopcraft.civilization.simulation.Person;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettlementGenerosityPersonalityTest {
	private static final UUID RESIDENT = generatedResident(false);
	private static final UUID GENERATED_GENEROUS = generatedResident(true);
	private static final UUID NEIGHBOR = new UUID(1, 1);
	private static final String PROPOSAL = "saved-generosity-proposal";

	@Test
	void savedGenerousTraitOverridesUuidDefaultsAndSurvivesReload() {
		Fixture fixture = fixture(RESIDENT, Set.of(VillagerTrait.GENEROUS), 2, true);
		assertFalse(VillagerPersonality.fromVillager(RESIDENT).traits().contains(VillagerTrait.GENEROUS));
		NormSystem.ProposalOpinion opinion = opinion(fixture.state, RESIDENT);
		assertEquals(-3, generosity(opinion), 1e-9);
		SettlementSimulationState neutral = SettlementSimulationState.load(fixture.settlement, fixture.state.save());
		assertEquals(0, generosity(opinion(neutral, RESIDENT)), 1e-9);
		assertEquals(opinion(neutral, RESIDENT).score() - 3, opinion.score(), 1e-9);

		Fixture restored = reload(save(fixture.data));
		assertEquals(Set.of(VillagerTrait.GENEROUS), restored.data.personalityOf(restored.settlement, RESIDENT).traits());
		assertEquals(opinion, opinion(restored.state, RESIDENT));
		assertEquals(save(fixture.data), save(restored.data));
	}

	@Test
	void traitOnlyWeightsKnownMutualAidForHungryOtherHouseholds() {
		assertEquals(3, generosity(opinion(fixture(RESIDENT, Set.of(VillagerTrait.GENEROUS), 4, true).state, RESIDENT)), 1e-9);
		assertEquals(0, generosity(opinion(fixture(RESIDENT, Set.of(VillagerTrait.GENEROUS), 3, true).state, RESIDENT)), 1e-9);
		Fixture unknown = fixture(RESIDENT, Set.of(VillagerTrait.GENEROUS), 2, false);
		assertEquals(0, generosity(opinion(unknown.state, RESIDENT)), 1e-9);
		person(unknown.state, RESIDENT).observeNorm("neighbor-requests");
		assertEquals(-3, generosity(opinion(unknown.state, RESIDENT)), 1e-9);
		person(unknown.state, NEIGHBOR).setHunger(0);
		person(unknown.state, RESIDENT).setHunger(100);
		assertEquals(0, generosity(opinion(unknown.state, RESIDENT)), 1e-9);
	}

	@Test
	void removalAndForeignMembershipImmediatelyBecomeNeutral() {
		Fixture fixture = fixture(RESIDENT, Set.of(VillagerTrait.GENEROUS), 2, true);
		fixture.data.removeMember(RESIDENT);
		assertEquals(0, generosity(opinion(fixture.state, RESIDENT)), 1e-9);
		Settlement other = fixture.data.getOrCreate(new BlockPos(500, 0, 0), "Other", 0);
		fixture.data.registerMember(other, RESIDENT, 0);
		assertEquals(0, generosity(opinion(fixture.state, RESIDENT)), 1e-9);
	}

	@Test
	void standaloneAndForeignSettlementStatesRemainNeutral() {
		Fixture fixture = fixture(RESIDENT, Set.of(VillagerTrait.GENEROUS), 2, true);
		SettlementSimulationState standalone = SettlementSimulationState.create(fixture.settlement);
		seed(standalone, RESIDENT, 2, true);
		assertEquals(0, generosity(opinion(standalone, RESIDENT)), 1e-9);
		CompoundTag tag = save(fixture.data);
		tag.remove("Simulations");
		SettlementSavedData data = SettlementSavedData.load(tag, null);
		Settlement foreign = new Settlement(fixture.settlement.id(), fixture.settlement.center(), "Foreign record", 0);
		SettlementSimulationState foreignState = data.getOrCreateSimulation(foreign);
		seed(foreignState, RESIDENT, 2, true);
		assertEquals(0, generosity(opinion(foreignState, RESIDENT)), 1e-9);
	}

	@Test
	void sameIdPersonCannotBorrowTheMappedResidentsPersonality() {
		Fixture fixture = fixture(RESIDENT, Set.of(VillagerTrait.GENEROUS), 2, true);
		Person resident = person(fixture.state, RESIDENT);
		Person foreign = new Person(resident.id(), resident.campId(), resident.householdId(), Person.AgeBand.ADULT, 0, 50);
		foreign.observeNorm("neighbor-requests");
		CompoundTag before = save(fixture.data);
		assertTrue(fixture.state.norms().inspectProposal(foreign, PROPOSAL).isEmpty());
		assertEquals(-3, generosity(opinion(fixture.state, RESIDENT)), 1e-9);
		assertEquals(before, save(fixture.data));
	}

	@Test
	void missingTraitsUseExistingLoaderFallbackButExplicitEmptyTraitsStayEmpty() {
		Fixture fixture = fixture(GENERATED_GENEROUS, Set.of(), 2, true);
		assertTrue(VillagerPersonality.fromVillager(GENERATED_GENEROUS).traits().contains(VillagerTrait.GENEROUS));
		assertEquals(0, generosity(opinion(fixture.state, GENERATED_GENEROUS)), 1e-9);
		assertEquals(0, generosity(opinion(reload(save(fixture.data)).state, GENERATED_GENEROUS)), 1e-9);

		CompoundTag legacy = save(fixture.data);
		legacy.getList("Members", ListTag.TAG_COMPOUND).getCompound(0).getCompound("Personality").remove("Traits");
		Fixture restored = reload(legacy);
		assertEquals(VillagerPersonality.fromVillager(GENERATED_GENEROUS).traits(),
			restored.data.personalityOf(restored.settlement, GENERATED_GENEROUS).traits());
		assertEquals(-3, generosity(opinion(restored.state, GENERATED_GENEROUS)), 1e-9);
	}

	@Test
	void unrelatedTraitsDoNotChangeOpinion() {
		NormSystem.ProposalOpinion neutral = opinion(fixture(RESIDENT, Set.of(), 2, true).state, RESIDENT);
		for (VillagerTrait trait : VillagerTrait.values()) {
			if (trait == VillagerTrait.GENEROUS) continue;
			assertEquals(neutral, opinion(fixture(RESIDENT, Set.of(trait), 2, true).state, RESIDENT), trait.id());
		}
	}

	@Test
	void bothPersonalityContributionsShareTheAbsoluteBudgetAfterReload() {
		Fixture fixture = fixture(RESIDENT, Set.of(VillagerTrait.GENEROUS), 2, true);
		person(fixture.state, RESIDENT).setHunger(100);
		CompoundTag tag = save(fixture.data);
		tag.getList("Members", ListTag.TAG_COMPOUND).getCompound(0).getCompound("Personality").putInt("Selfishness", 100);
		NormSystem.ProposalOpinion opinion = opinion(reload(tag).state, RESIDENT);
		double selfishness = opinion.factors().stream().filter(factor -> factor.kind() == NormSystem.ProposalFactorKind.SELFISHNESS)
			.mapToDouble(NormSystem.ProposalFactor::contribution).sum();
		assertTrue(selfishness < 0);
		assertTrue(generosity(opinion) < 0);
		assertEquals(5, Math.abs(selfishness) + Math.abs(generosity(opinion)), 1e-9);
	}

	@Test
	void queriesDoNotDirtyDataChangeHistoryOrCreateSimulationStates() {
		Fixture fixture = fixture(RESIDENT, Set.of(VillagerTrait.GENEROUS), 2, true);
		Settlement empty = fixture.data.getOrCreate(new BlockPos(500, 0, 0), "No simulation", 0);
		CompoundTag before = save(fixture.data);
		fixture.data.setDirty(false);
		for (int i = 0; i < 10; i++) {
			assertEquals(-3, generosity(opinion(fixture.state, RESIDENT)), 1e-9);
			assertTrue(fixture.data.simulationOf(empty).isEmpty());
			assertEquals(before, save(fixture.data));
			assertFalse(fixture.data.isDirty());
		}
	}

	private static Fixture fixture(UUID resident, Set<VillagerTrait> traits, int cap, boolean knowsMutualAid) {
		SettlementSavedData original = new SettlementSavedData();
		Settlement settlement = original.getOrCreate(BlockPos.ZERO, "Generosity", 0);
		original.registerMember(settlement, resident, 0);
		CompoundTag tag = save(original);
		tag.getList("Members", ListTag.TAG_COMPOUND).getCompound(0).put("Personality",
			new VillagerPersonality(50, 50, 50, PersonalityArchetype.BALANCED, traits).save());
		SettlementSavedData data = SettlementSavedData.load(tag, null);
		settlement = data.find(BlockPos.ZERO).orElseThrow();
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		seed(state, resident, cap, knowsMutualAid);
		return new Fixture(data, settlement, state);
	}

	private static void seed(SettlementSimulationState state, UUID residentId, int cap, boolean knowsMutualAid) {
		state.mapResident(residentId, false, 100);
		state.mapResident(NEIGHBOR, false, 0);
		Person resident = person(state, residentId);
		Person neighbor = person(state, NEIGHBOR);
		if (knowsMutualAid) resident.observeNorm("neighbor-requests");
		long request = state.world().recordShareRequest(neighbor.id(), neighbor.campId(), 40);
		long refusal = state.world().recordShareRefusal(request, neighbor.id(), neighbor.campId(), 20);
		state.world().registerProposal(NormProposal.restore(PROPOSAL, resident.campId(), Norm.NEED_FIRST_COMMON_STORE,
			neighbor.id(), 0, refusal, cap, 0, 0, NormProposal.Status.OPEN, Map.of(
				resident.id(), NormProposal.Position.UNDECIDED, neighbor.id(), NormProposal.Position.SUPPORT)));
	}

	private static UUID generatedResident(boolean generous) {
		for (int i = 1; i <= 10000; i++) {
			UUID id = new UUID(0, i);
			if (VillagerPersonality.fromVillager(id).traits().contains(VillagerTrait.GENEROUS) == generous) return id;
		}
		throw new AssertionError("No suitable generated personality fixture");
	}

	private static Fixture reload(CompoundTag tag) {
		SettlementSavedData data = SettlementSavedData.load(tag, null);
		Settlement settlement = data.find(BlockPos.ZERO).orElseThrow();
		return new Fixture(data, settlement, data.simulationOf(settlement).orElseThrow());
	}

	private static Person person(SettlementSimulationState state, UUID resident) {
		return state.world().person(state.residentId(resident).orElseThrow());
	}

	private static NormSystem.ProposalOpinion opinion(SettlementSimulationState state, UUID resident) {
		return state.norms().inspectProposal(person(state, resident), PROPOSAL).orElseThrow().opinion().orElseThrow();
	}

	private static double generosity(NormSystem.ProposalOpinion opinion) {
		return opinion.factors().stream().filter(factor -> factor.kind() == NormSystem.ProposalFactorKind.GENEROSITY)
			.mapToDouble(NormSystem.ProposalFactor::contribution).sum();
	}

	private static CompoundTag save(SettlementSavedData data) {
		return data.save(new CompoundTag(), null);
	}

	private record Fixture(SettlementSavedData data, Settlement settlement, SettlementSimulationState state) {}
}
