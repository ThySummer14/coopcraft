package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.DemographyConfig;
import com.thysummer.coopcraft.civilization.simulation.DemographySystem;
import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceBatch;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.net.SettlementOverviewPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementHouseholdsTest {
	private SettlementSavedData data;
	private Settlement settlement;
	private SettlementSimulationState state;

	@BeforeEach
	void setUp() {
		data = new SettlementSavedData();
		settlement = data.getOrCreate(new BlockPos(4, 64, 8), "Testhaven", 0);
		state = data.getOrCreateSimulation(settlement);
	}

	@Test
	void observesKnownResidenceWithoutLoadedEntitiesOrInferringCare() {
		UUID firstParent = UUID.randomUUID();
		UUID secondParent = UUID.randomUUID();
		UUID child = UUID.randomUUID();
		state.mapResident(firstParent, false, 80);
		state.mapResident(secondParent, false, 80);
		state.mapResidentWithParents(child, 80, firstParent, secondParent);
		String firstPerson = state.residentId(firstParent).orElseThrow();
		String secondPerson = state.residentId(secondParent).orElseThrow();
		String childPerson = state.residentId(child).orElseThrow();

		SettlementHouseholds.Observation observation = SettlementHouseholds.observe(state, child).orElseThrow();

		assertEquals(2, observation.residence().memberIds().size());
		assertTrue(observation.residence().memberIds().containsAll(List.of(firstPerson, childPerson)));
		assertFalse(observation.residence().memberIds().contains(secondPerson));
		assertEquals(2, state.world().person(childPerson).parents().size());
		assertTrue(observation.needsCare());
		assertTrue(observation.care().isEmpty());
		assertTrue(observation.provisioning().isEmpty());
		assertEquals(1, SettlementHouseholds.observe(state, secondParent).orElseThrow().residence().memberIds().size());
		assertTrue(SettlementHouseholds.observe(state, UUID.randomUUID()).isEmpty());
	}

	@Test
	void readsActualCareProvidedByANonRelative() {
		UUID caregiver = UUID.randomUUID();
		UUID child = UUID.randomUUID();
		state.mapResident(caregiver, false, 80);
		state.mapResident(child, true, 80);
		String caregiverPerson = state.residentId(caregiver).orElseThrow();
		String childPerson = state.residentId(child).orElseThrow();
		state.world().person(caregiverPerson).setTrust(childPerson, 30);

		new DemographySystem(state.world(), DemographyConfig.enabledDefaults()).organizeCare();

		SettlementHouseholds.Observation childObservation = SettlementHouseholds.observe(state, child).orElseThrow();
		assertEquals(1, childObservation.care().size());
		CareLink care = childObservation.care().getFirst();
		assertEquals(caregiverPerson, care.caregiverId());
		assertEquals(childPerson, care.dependentId());
		assertEquals(2, care.hours());
		assertFalse(state.world().person(caregiverPerson).householdId().equals(state.world().person(childPerson).householdId()));
		assertEquals(childObservation.care(), SettlementHouseholds.observe(state, caregiver).orElseThrow().care());
		assertEquals(1, state.world().ledger().events().stream().filter(event -> event.type() == EventType.CARE_PROVIDED).count());
	}

	@Test
	void preservesRealProvisioningAcrossReloadAndFiltersItByDay() {
		UUID recipient = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		state.mapResident(recipient, false, 40);
		state.mapResident(other, false, 80);
		String recipientPerson = state.residentId(recipient).orElseThrow();
		ResourceBatch source = state.world().createInitialBatch(ResourceType.FISH, 5, state.campOwner());
		long request = state.world().recordShareRequest(recipientPerson, settlement.id().toString(), 40);
		state.world().allocateCommon(settlement.id().toString(), recipientPerson, source.batchId(), 2, request);

		SettlementHouseholds.Observation expected = SettlementHouseholds.observe(state, recipient).orElseThrow();
		assertEquals(1, expected.provisioning().size());
		ProvisioningLink provisioning = expected.provisioning().getFirst();
		assertEquals(settlement.id().toString(), provisioning.sourceCampId());
		assertEquals(recipientPerson, provisioning.recipientId());
		assertEquals(ResourceType.FISH, provisioning.resourceType());
		assertEquals(2, provisioning.quantity());
		assertEquals(3, source.quantity());
		assertTrue(SettlementHouseholds.observe(state, other).orElseThrow().provisioning().isEmpty());

		int eventCount = state.world().ledger().events().size();
		assertEquals(expected, SettlementHouseholds.observe(state, recipient).orElseThrow());
		assertEquals(eventCount, state.world().ledger().events().size());
		SettlementSavedData restored = SettlementSavedData.load(data.save(new CompoundTag(), null), null);
		SettlementSimulationState restoredState = restored.simulationOf(settlement).orElseThrow();
		assertEquals(expected, SettlementHouseholds.observe(restoredState, recipient).orElseThrow());
		assertEquals(eventCount, restoredState.world().ledger().events().size());
		assertEquals(3, restoredState.world().batch(source.batchId()).quantity());
		restoredState.world().assertConservation();

		restoredState.world().advanceDay();
		assertTrue(SettlementHouseholds.observe(restoredState, recipient).orElseThrow().provisioning().isEmpty());
	}

	@Test
	void readsLegacySimulationWithoutEventHistory() {
		UUID resident = UUID.randomUUID();
		state.mapResident(resident, false, 80);
		CompoundTag tag = data.save(new CompoundTag(), null);
		tag.getList("Simulations", ListTag.TAG_COMPOUND).getCompound(0).remove("Events");

		SettlementSavedData restored = SettlementSavedData.load(tag, null);
		SettlementHouseholds.Observation observation = SettlementHouseholds.observe(
			restored.simulationOf(settlement).orElseThrow(), resident
		).orElseThrow();

		assertEquals(1, observation.residence().memberIds().size());
		assertTrue(observation.care().isEmpty());
		assertTrue(observation.provisioning().isEmpty());
	}

	@Test
	void convertsObservationToResidentPayloadWithNamesAndWorkStatus() {
		UUID parent = UUID.randomUUID();
		UUID otherParent = UUID.randomUUID();
		UUID child = UUID.randomUUID();
		state.mapResident(parent, false, 80);
		state.mapResident(otherParent, false, 80);
		state.mapResidentWithParents(child, 80, parent, otherParent);
		new DemographySystem(state.world(), DemographyConfig.enabledDefaults()).organizeCare();
		String childPerson = state.residentId(child).orElseThrow();
		ResourceBatch source = state.world().createInitialBatch(ResourceType.FISH, 5, state.campOwner());
		long request = state.world().recordShareRequest(childPerson, settlement.id().toString(), 40);
		state.world().allocateCommon(settlement.id().toString(), childPerson, source.batchId(), 2, request);
		BlockPos target = new BlockPos(-10, 64, 20);

		SettlementOverviewPayload.ResidentObservation observation = Civilization.toResidentObservation(
			data, settlement, child, true, new VillagerWorkGoal.WorkStatus("build", target, null), Map.of(parent, "Custom Parent"), 0
		);

		assertTrue(observation.residenceKnown());
		assertEquals(List.of("Custom Parent"), observation.coResidents());
		assertEquals(1, observation.coResidentCount());
		assertEquals(1, observation.care().size());
		assertEquals(new SettlementOverviewPayload.CareObservation("Custom Parent", 2, false), observation.care().getFirst());
		assertEquals(2, observation.provisioningUnits());
		assertTrue(observation.foodAvailable());
		assertTrue(observation.needsCare());
		assertEquals("build", observation.action());
		assertEquals("", observation.blocker());
		assertEquals(target.asLong(), observation.actionTarget());
	}

	@Test
	void realCareUsesMinecraftDayInsteadOfSimulationDay() {
		UUID caregiver = UUID.randomUUID();
		UUID child = UUID.randomUUID();
		state.mapResident(caregiver, false, 80);
		state.mapResident(child, true, 80);
		String caregiverPerson = state.residentId(caregiver).orElseThrow();
		String childPerson = state.residentId(child).orElseThrow();
		state.world().recordCareProvided(caregiverPerson, childPerson, 1, Map.of("minecraftDay", "7", "source", "test"));

		assertTrue(SettlementHouseholds.observe(state, child, 0).orElseThrow().care().isEmpty());
		SettlementHouseholds.Observation observation = SettlementHouseholds.observe(state, child, 7).orElseThrow();
		assertEquals(1, observation.care().size());
		assertEquals(7, observation.care().getFirst().day());
		assertEquals(1, observation.care().getFirst().hours());
		assertEquals(caregiverPerson, observation.care().getFirst().caregiverId());
	}
}
