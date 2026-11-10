package com.thysummer.coopcraft.civilization;

import java.util.LinkedHashMap;
import java.util.Map;
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

class SettlementOpinionPersonalityTest {
	private static final UUID RESIDENT = new UUID(0, 1);
	private static final String PROPOSAL = "saved-personality-proposal";
	// Explicitly differ from the UUID-derived personality, even if generation changes later.
	private static final int SAVED_SELFISHNESS = VillagerPersonality.fromVillager(RESIDENT).selfishness() == 100 ? 0 : 100;

	@Test
	void bindsPersistedPersonalityForNewSimulationAndAfterReload() {
		Fixture fixture = fixture();
		assertNotEquals(VillagerPersonality.fromVillager(RESIDENT).selfishness(), SAVED_SELFISHNESS);
		NormSystem.ProposalOpinion opinion = opinion(fixture.state);
		assertEquals(adjustment(SAVED_SELFISHNESS), selfishness(opinion), 1e-9);

		SettlementSimulationState neutral = SettlementSimulationState.load(fixture.settlement, fixture.state.save());
		assertEquals(0, selfishness(opinion(neutral)), 1e-9);
		assertEquals(opinion(neutral).score() + adjustment(SAVED_SELFISHNESS), opinion.score(), 1e-9);

		SettlementSavedData restored = SettlementSavedData.load(save(fixture.data), null);
		Settlement restoredSettlement = restored.find(BlockPos.ZERO).orElseThrow();
		SettlementSimulationState restoredState = restored.simulationOf(restoredSettlement).orElseThrow();
		assertEquals(SAVED_SELFISHNESS, restored.personalityOf(restoredSettlement, RESIDENT).selfishness());
		assertEquals(opinion, opinion(restoredState));
	}

	@Test
	void readsLiveMembershipInsteadOfCachingOrRegeneratingMissingPersonality() {
		Fixture fixture = fixture();
		fixture.data.removeMember(RESIDENT);
		assertEquals(0, selfishness(opinion(fixture.state)), 1e-9);

		Settlement other = fixture.data.getOrCreate(new BlockPos(500, 0, 0), "Other", 0);
		fixture.data.registerMember(other, RESIDENT, 0);
		assertEquals(0, selfishness(opinion(fixture.state)), 1e-9);

		fixture.data.removeMember(RESIDENT);
		fixture.data.registerMember(fixture.settlement, RESIDENT, 0);
		assertEquals(adjustment(fixture.data.personalityOf(fixture.settlement, RESIDENT).selfishness()),
			selfishness(opinion(fixture.state)), 1e-9);
	}

	@Test
	void standaloneAndForeignSettlementStatesStayNeutral() {
		Fixture fixture = fixture();
		SettlementSimulationState standalone = SettlementSimulationState.create(fixture.settlement);
		seed(standalone);
		assertEquals(0, selfishness(opinion(standalone)), 1e-9);
		assertEquals(0, selfishness(opinion(SettlementSimulationState.load(fixture.settlement, fixture.state.save()))), 1e-9);

		// An equal-ID foreign record must not confer ownership of a simulation.
		SettlementSavedData otherData = SettlementSavedData.load(withoutSimulation(save(fixture.data)), null);
		Settlement foreign = new Settlement(fixture.settlement.id(), fixture.settlement.center(), "Foreign record", 0);
		SettlementSimulationState foreignState = otherData.getOrCreateSimulation(foreign);
		seed(foreignState);
		assertEquals(0, selfishness(opinion(foreignState)), 1e-9);
	}

	@Test
	void unmappedPeopleDoNotInheritMemberPersonality() {
		Fixture fixture = fixture(Map.of("unmapped", NormProposal.Position.SUPPORT));
		Person resident = person(fixture.state);
		Person unmapped = new Person("unmapped", resident.campId(), resident.householdId(), Person.AgeBand.ADULT, 100, 50);
		fixture.state.world().registerPerson(unmapped);
		assertEquals(0, selfishness(fixture.state.norms().inspectProposal(unmapped, PROPOSAL).orElseThrow().opinion().orElseThrow()), 1e-9);
	}

	@Test
	void repeatedInquiriesDoNotDirtyDataChangeHistoryOrCreateStates() {
		Fixture fixture = fixture();
		Settlement empty = fixture.data.getOrCreate(new BlockPos(500, 0, 0), "No simulation", 0);
		CompoundTag before = save(fixture.data);
		fixture.data.setDirty(false);
		for (int i = 0; i < 10; i++) {
			assertEquals(adjustment(SAVED_SELFISHNESS), selfishness(opinion(fixture.state)), 1e-9);
			assertTrue(fixture.data.simulationOf(empty).isEmpty());
			assertEquals(before, save(fixture.data));
			assertFalse(fixture.data.isDirty());
		}
		assertEquals(NormProposal.Position.SUPPORT, fixture.state.world().proposal(PROPOSAL).orElseThrow().positions().get(person(fixture.state).id()));
	}

	@Test
	void legacyMissingPersonalityUsesExistingMemberLoaderFallback() {
		Fixture fixture = fixture();
		CompoundTag tag = save(fixture.data);
		tag.getList("Members", ListTag.TAG_COMPOUND).getCompound(0).remove("Personality");
		SettlementSavedData restored = SettlementSavedData.load(tag, null);
		Settlement settlement = restored.find(BlockPos.ZERO).orElseThrow();
		assertEquals(adjustment(VillagerPersonality.fromVillager(RESIDENT).selfishness()),
			selfishness(opinion(restored.simulationOf(settlement).orElseThrow())), 1e-9);
	}

	private static Fixture fixture() {
		return fixture(Map.of());
	}

	private static Fixture fixture(Map<String, NormProposal.Position> additionalKnownPositions) {
		SettlementSavedData original = new SettlementSavedData();
		Settlement settlement = original.getOrCreate(BlockPos.ZERO, "Personality", 0);
		original.registerMember(settlement, RESIDENT, 0);
		CompoundTag tag = save(original);
		tag.getList("Members", ListTag.TAG_COMPOUND).getCompound(0)
			.put("Personality", new VillagerPersonality(50, SAVED_SELFISHNESS, 50).save());
		SettlementSavedData data = SettlementSavedData.load(tag, null);
		settlement = data.find(BlockPos.ZERO).orElseThrow();
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		seed(state, additionalKnownPositions);
		return new Fixture(data, settlement, state);
	}

	private static void seed(SettlementSimulationState state) {
		seed(state, Map.of());
	}

	private static void seed(SettlementSimulationState state, Map<String, NormProposal.Position> additionalKnownPositions) {
		state.mapResident(RESIDENT, false, 0);
		Person resident = person(state);
		long request = state.world().recordShareRequest(resident.id(), resident.campId(), 40);
		long refusal = state.world().recordShareRefusal(request, resident.id(), resident.campId(), 20);
		Map<String, NormProposal.Position> positions = new LinkedHashMap<>(additionalKnownPositions);
		positions.put(resident.id(), NormProposal.Position.SUPPORT);
		state.world().registerProposal(NormProposal.restore(PROPOSAL, resident.campId(), Norm.NEED_FIRST_COMMON_STORE,
			resident.id(), 0, refusal, 2, 0, 0, NormProposal.Status.OPEN, positions));
	}

	private static Person person(SettlementSimulationState state) {
		return state.world().person(state.residentId(RESIDENT).orElseThrow());
	}

	private static NormSystem.ProposalOpinion opinion(SettlementSimulationState state) {
		return state.norms().inspectProposal(person(state), PROPOSAL).orElseThrow().opinion().orElseThrow();
	}

	private static double selfishness(NormSystem.ProposalOpinion opinion) {
		return opinion.factors().stream().filter(factor -> factor.kind() == NormSystem.ProposalFactorKind.SELFISHNESS)
			.mapToDouble(NormSystem.ProposalFactor::contribution).sum();
	}

	private static double adjustment(int selfishness) {
		// The hungry resident and household lose one food unit under the proposal (3 -> 2).
		return (selfishness - 50) / 250.0 * -25;
	}

	private static CompoundTag save(SettlementSavedData data) {
		return data.save(new CompoundTag(), null);
	}

	private static CompoundTag withoutSimulation(CompoundTag tag) {
		tag.remove("Simulations");
		return tag;
	}

	private record Fixture(SettlementSavedData data, Settlement settlement, SettlementSimulationState state) {}
}
