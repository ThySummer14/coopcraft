package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.Norm;
import com.thysummer.coopcraft.civilization.simulation.NormProposal;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.civilization.simulation.SocialInformation;
import com.thysummer.coopcraft.civilization.simulation.StorageOwner;
import com.thysummer.coopcraft.civilization.simulation.WorkType;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementCompositePersistenceTest {
	// Children sort before both parents in the serialized resident list.
	private static final UUID CHILD = new UUID(0, 1);
	private static final UUID FIRST = new UUID(0, 2);
	private static final UUID SECOND = new UUID(0, 3);
	private static final UUID OTHER = new UUID(0, 4);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void familySocialStateAndSplitInventorySurviveThreeWholeSaveRoundTrips() {
		Fixture f = fixture();
		assertTrue(f.state().mapResidentWithParents(CHILD, 41, FIRST, SECOND));
		f.data().registerMember(f.settlement(), CHILD, 7);
		f.person(CHILD).initializeAge(Person.AgeBand.CHILD, 17);
		f.person(FIRST).initializeAge(Person.AgeBand.ADULT, 12345);
		f.person(FIRST).setTrust(f.person(SECOND).id(), 83);
		f.person(SECOND).setTrust(f.person(FIRST).id(), 29);
		f.person(FIRST).restoreSocialState(17, 2, 4);
		for (WorkType work : WorkType.values()) {
			f.person(FIRST).setSkill(work, 1.25 + work.ordinal() * 0.125);
		}
		var world = f.state().world();
		var common = world.importExternalBatch(ResourceType.TUBERS, 12, f.state().campOwner());
		long request = world.recordShareRequest(f.person(FIRST).id(), f.state().campOwner().id(), 40);
		var split = world.allocateCommon(f.state().campOwner().id(), f.person(FIRST).id(), common.batchId(), 3, request);
		f.state().recordFoodAllocation(f.person(FIRST).id(), 3);
		world.consume(f.person(FIRST).id(), split.batchId(), 1, EventType.CONSUME);
		world.importExternalBatch(ResourceType.WILD_FRUIT, 5, StorageOwner.person(f.person(OTHER).id()));
		world.exportExternalBatch(f.state().campOwner(), common.batchId(), 2);
		f.data().setLeader(f.settlement(), FIRST);
		f.data().setSatiety(f.settlement(), CHILD, 41);
		f.data().setStockpile(f.settlement(), BlockPos.ZERO.east());
		f.data().setHearth(f.settlement(), BlockPos.ZERO.west());
		f.data().addHousingBed(f.settlement(), BlockPos.ZERO.north());
		f.data().setConstructionJob(f.settlement(), new ConstructionJob(ConstructionPlanner.STONE_COTTAGE, BlockPos.ZERO.south(5), 7));

		List<SimulationEvent> events = world.ledger().events();
		for (int pass = 0; pass < 3; pass++) {
			f = reloadUnchanged(f);
			assertEquals(List.of(f.person(FIRST).id(), f.person(SECOND).id()), f.person(CHILD).parents());
			assertEquals(f.person(FIRST).householdId(), f.person(CHILD).householdId());
			assertEquals(3, f.state().world().households().size());
			assertEquals(2, f.state().world().peopleInHousehold(f.person(FIRST).householdId()).size());
			assertEquals(83, f.person(FIRST).trustIn(f.person(SECOND).id()));
			assertEquals(29, f.person(SECOND).trustIn(f.person(FIRST).id()));
			assertEquals(17, f.person(CHILD).ageDays());
			assertEquals(12345, f.person(FIRST).ageDays());
			assertEquals(17, f.person(FIRST).grievance());
			assertEquals(2, f.person(FIRST).socialHours());
			assertEquals(4, f.person(FIRST).previousSocialHours());
			for (WorkType work : WorkType.values()) {
				assertEquals(1.25 + work.ordinal() * 0.125, f.person(FIRST).skill(work));
			}
			assertEquals(events, f.state().world().ledger().events());
			assertEquals(9L, f.state().world().inventoryTotals().get(ResourceType.TUBERS).longValue());
			assertEquals(5L, f.state().world().inventoryTotals().get(ResourceType.WILD_FRUIT).longValue());
			assertEquals(0, f.state().remainingFoodUnits(f.person(FIRST).id()));
			assertEquals(Set.of(common.batchId()), f.state().world().batch(split.batchId()).parentBatchIds());
			assertFalse(f.state().mapResidentWithParents(CHILD, 99, FIRST, SECOND));
			assertEquals(events, f.state().world().ledger().events());
		}
		// Resuming after reload must use the saved next IDs, not replay a birth or an import.
		long nextBatch = f.state().world().nextBatchId();
		var added = f.state().world().importExternalBatch(ResourceType.TUBERS, 1, f.state().campOwner());
		assertEquals("batch-" + nextBatch, added.batchId());
		assertEquals(events.getLast().eventId() + 1, added.originEventId());
		reloadUnchanged(f);
	}

	@Test
	void revisionHistoryOpenProposalAndInformationProvenanceRemainUnchanged() {
		Fixture f = fixture();
		String first = f.person(FIRST).id();
		String second = f.person(SECOND).id();
		String other = f.person(OTHER).id();
		String camp = f.state().campOwner().id();
		// A small current-schema checkpoint, not a claimed historical save or a social simulation.
		List<SimulationEvent> history = List.of(
			event(1, EventType.SHORTAGE_CONFLICT, first, Map.of("campId", camp)),
			event(2, EventType.NORM_PROPOSED, first, Map.of("proposalId", "adopt", "normId", Norm.NEED_FIRST_COMMON_STORE), 1L),
			event(3, EventType.NORM_ADOPTED, first, Map.of("proposalId", "adopt", "normId", Norm.NEED_FIRST_COMMON_STORE), 2L),
			event(4, EventType.NORM_PROPOSED, second, Map.of("proposalId", "revise", "normId", Norm.NEED_FIRST_COMMON_STORE), 1L, 3L),
			event(5, EventType.NORM_REVISED, second, Map.of("proposalId", "revise", "normId", Norm.NEED_FIRST_COMMON_STORE), 4L),
			event(6, EventType.NORM_PROPOSED, first, Map.of("proposalId", "pending", "normId", Norm.CARE_FIRST_COMMON_STORE), 1L)
		);
		f.state().world().restoreState(9, 1, List.of(), history);
		List<Norm.Revision> revisions = List.of(
			new Norm.Revision(9, "adopt", 3, 0, 2, Norm.Revision.Action.ADOPTED),
			new Norm.Revision(9, "revise", 5, 2, 4, Norm.Revision.Action.REVISED)
		);
		f.state().world().registerNorm(new Norm(Norm.NEED_FIRST_COMMON_STORE, camp, 4,
			Set.of(first, second), Set.of(other), Set.of(first, second, other), 67, Norm.Status.ACTIVE, revisions));
		Map<String, NormProposal.Position> positions = Map.of(first, NormProposal.Position.SUPPORT,
			second, NormProposal.Position.SUPPORT, other, NormProposal.Position.OPPOSE);
		f.state().world().registerProposal(NormProposal.restore("adopt", camp, Norm.NEED_FIRST_COMMON_STORE,
			first, 9, 1, 2, 0, 2, NormProposal.Status.ADOPTED, positions));
		f.state().world().registerProposal(NormProposal.restore("revise", camp, Norm.NEED_FIRST_COMMON_STORE,
			second, 9, 1, 4, 1, 3, NormProposal.Status.ADOPTED, positions));
		f.state().world().registerProposal(NormProposal.restore("pending", camp, Norm.CARE_FIRST_COMMON_STORE,
			first, 9, 1, 1, 0, 1, NormProposal.Status.OPEN,
			Map.of(first, NormProposal.Position.SUPPORT, other, NormProposal.Position.UNDECIDED)));
		SocialInformation original = new SocialInformation("revision:5", SocialInformation.Kind.NORM_REVISION,
			second, Norm.NEED_FIRST_COMMON_STORE, 5, 9, null, 1.0);
		f.person(SECOND).learnInformation(original);
		f.person(FIRST).learnInformation(original.propagate(9, second));
		f.person(FIRST).observeNorm(Norm.NEED_FIRST_COMMON_STORE);
		f.person(FIRST).observeNorm("neighbor-requests");
		f.person(FIRST).restoreSocialState(7, 3, 2);
		var stock = f.state().world().importExternalBatch(ResourceType.TUBERS, 8, f.state().campOwner());
		long request = f.state().world().recordShareRequest(first, camp, 40);
		f.state().world().allocateCommon(camp, first, stock.batchId(), 3, request);
		f.state().recordFoodAllocation(first, 3);

		for (int pass = 0; pass < 3; pass++) {
			f = reloadUnchanged(f);
			assertEquals(revisions, f.state().world().norm(camp, Norm.NEED_FIRST_COMMON_STORE).orElseThrow().revisionHistory());
			assertEquals(NormProposal.Status.OPEN, f.state().world().proposal("pending").orElseThrow().status());
			assertEquals(1, f.state().world().proposal("pending").orElseThrow().rounds());
			assertEquals(positions, f.state().world().proposal("revise").orElseThrow().positions());
			assertEquals(Set.of(first, other), f.state().world().proposal("pending").orElseThrow().knownBy());
			assertEquals(original, f.person(SECOND).information("revision:5").orElseThrow());
			assertEquals(original.propagate(9, second), f.person(FIRST).information("revision:5").orElseThrow());
			assertEquals(history.subList(0, 5), f.state().world().ledger().traceAncestry(5));
			assertEquals(3, f.person(FIRST).socialHours());
			assertEquals(1, f.state().remainingFoodUnits(first));
		}
	}

	@Test
	void missingOptionalResidentFieldsUseConstructorDefaultsThenStabilize() {
		Fixture f = fixture();
		f.state().mapResidentWithParents(CHILD, 41, FIRST, SECOND);
		f.data().registerMember(f.settlement(), CHILD, 7);
		CompoundTag input = f.data().save(new CompoundTag(), null);
		ListTag residents = input.getList("Simulations", ListTag.TAG_COMPOUND).getCompound(0)
			.getList("Residents", ListTag.TAG_COMPOUND);
		// Exercise the loader's explicit optional-field branches without inventing a historical version.
		for (int i = 0; i < residents.size(); i++) {
			CompoundTag resident = residents.getCompound(i);
			resident.remove("AgeDays");
			resident.remove("Skills");
			resident.remove("Social");
			resident.remove("Parents");
		}
		SettlementSavedData loaded = SettlementSavedData.load(input, null);
		f = new Fixture(loaded, loaded.find(BlockPos.ZERO).orElseThrow());
		Person restored = f.person(FIRST);
		assertEquals(30 * 360, restored.ageDays());
		for (WorkType work : WorkType.values()) {
			assertEquals(1.0, restored.skill(work));
		}
		assertEquals(0, restored.grievance());
		assertEquals(0, restored.socialHours());
		assertEquals(0, restored.previousSocialHours());
		assertTrue(restored.trustedPeople().isEmpty());
		assertTrue(restored.information().isEmpty());
		assertTrue(restored.observedNorms().isEmpty());
		assertTrue(restored.parents().isEmpty());
		assertEquals(8 * 360, f.person(CHILD).ageDays());
		assertTrue(f.person(CHILD).parents().isEmpty());
		// A birth event is evidence, not an instruction for the loader to reconstruct missing fields.
		assertEquals(1, f.state().world().ledger().events().size());
		assertEquals(EventType.BIRTH, f.state().world().ledger().events().getFirst().type());
		for (int pass = 0; pass < 3; pass++) {
			f = reloadUnchanged(f);
		}
	}

	private static Fixture fixture() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Checkpoint", 0);
		Fixture f = new Fixture(data, settlement);
		for (UUID id : List.of(FIRST, SECOND, OTHER)) {
			data.registerMember(settlement, id, 0);
			f.state().mapResident(id, false, 20);
		}
		for (int day = 0; day < 7; day++) {
			f.state().world().advanceDay();
		}
		f.state().setSurvivalEnabled(true, 7);
		f.state().setProductionEnabled(true);
		f.state().setGrowthEnabled(true);
		f.state().setSkillsEnabled(true);
		f.state().setNormsEnabled(true);
		return f;
	}

	private static SimulationEvent event(long id, EventType type, String actor, Map<String, String> context, Long... causes) {
		return new SimulationEvent(id, 9, type, List.of(actor), List.of(), List.of(), context, List.of(causes));
	}

	private static Fixture reloadUnchanged(Fixture f) {
		f.state().world().assertConservation();
		CompoundTag before = f.data().save(new CompoundTag(), null);
		assertEquals(19, before.getInt("DataVersion"));
		CompoundTag untouchedInput = before.copy();
		var inventory = f.state().world().inventoryTotals();
		var nutrition = f.state().world().nutritionTotals();
		SettlementSavedData loaded = SettlementSavedData.load(before, null);
		Fixture restored = new Fixture(loaded, loaded.find(BlockPos.ZERO).orElseThrow());
		assertEquals(untouchedInput, before, "Loading must not mutate its input checkpoint");
		assertEquals(before, loaded.save(new CompoundTag(), null), "Whole current-schema NBT must remain equal");
		assertEquals(inventory, restored.state().world().inventoryTotals());
		assertEquals(nutrition, restored.state().world().nutritionTotals());
		restored.state().world().assertConservation();
		return restored;
	}

	private record Fixture(SettlementSavedData data, Settlement settlement) {
		SettlementSimulationState state() {
			return data.getOrCreateSimulation(settlement);
		}

		Person person(UUID id) {
			return state().world().person(state().residentId(id).orElseThrow());
		}
	}
}
