package com.thysummer.coopcraft.civilization.simulation;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationWorldTest {
	private SimulationWorld world;

	@BeforeEach
	void setUp() {
		world = new SimulationWorld();
		world.registerCamp(new Camp("camp", "Test Camp"));
		world.registerHousehold(new Household("h1", "camp"));
		world.registerHousehold(new Household("h2", "camp"));
		world.registerPerson(new Person("p1", "camp", "h1", Person.AgeBand.ADULT, 60, 50));
		world.registerPerson(new Person("p2", "camp", "h2", Person.AgeBand.ADULT, 60, 50));
		world.registerPatch(new ResourcePatch(
			"patch", "camp", new ResourcePatch.Position(0, 64, 0), "test", ResourceType.WILD_FRUIT,
			50, 100, 0.1
		));
	}

	@Test
	void splitTransferAndConsumptionPreserveMaterialOrigin() {
		ResourceBatch source = world.createInitialBatch(ResourceType.WILD_FRUIT, 10, StorageOwner.household("h1"));
		long request = world.recordShareRequest("p2", "p1", 40);

		SimulationWorld.TransferResult moved = world.transfer(
			"p1", source.batchId(), 4, StorageOwner.household("h2"), EventType.SHARE, request
		);
		world.recordShareAcceptance("p2", "p1");

		assertEquals(6, source.quantity());
		assertEquals(4, world.batch(moved.batchId()).quantity());
		assertEquals(source.originEventId(), world.batch(moved.batchId()).originEventId());
		assertTrue(world.batch(moved.batchId()).parentBatchIds().contains(source.batchId()));
		assertEquals(1, world.person("p2").reciprocityWith("p1"));

		world.consume("p2", moved.batchId(), 2, EventType.CONSUME);
		SimulationEvent consumption = world.ledger().events().getLast();
		assertEquals(EventType.CONSUME, consumption.type());
		assertEquals(List.of(source.originEventId()), world.ledger().traceOrigins(consumption.eventId()).stream()
			.map(SimulationEvent::eventId).toList());
		assertEquals(8, world.inventoryTotals().get(ResourceType.WILD_FRUIT));
		assertEquals(8 * 18, world.nutritionTotals().get(ResourceType.WILD_FRUIT));
		world.assertConservation();
	}

	@Test
	void rejectsUnauthorizedAndUnstructuredCampWithdrawalsWithoutChanges() {
		ResourceBatch householdBatch = world.createInitialBatch(ResourceType.TUBERS, 5, StorageOwner.household("h1"));
		ResourceBatch campBatch = world.createInitialBatch(ResourceType.FISH, 5, StorageOwner.camp("camp"));
		int eventCount = world.ledger().events().size();

		assertThrows(IllegalArgumentException.class, () -> world.transfer(
			"p2", householdBatch.batchId(), 1, StorageOwner.household("h2"), EventType.SHARE, 0
		));
		assertThrows(IllegalArgumentException.class, () -> world.transfer(
			"p1", campBatch.batchId(), 1, StorageOwner.household("h1"), EventType.STORE, 0
		));
		assertEquals(5, householdBatch.quantity());
		assertEquals(5, campBatch.quantity());
		assertEquals(eventCount, world.ledger().events().size());
		world.assertConservation();
	}

	@Test
	void commonAllocationUsesRequestAndRealInventory() {
		ResourceBatch campBatch = world.createInitialBatch(ResourceType.FISH, 5, StorageOwner.camp("camp"));
		long request = world.recordShareRequest("p1", "camp", 40);

		SimulationWorld.TransferResult allocation = world.allocateCommon("camp", "p1", campBatch.batchId(), 2, request);
		world.consume("p1", allocation.batchId(), 2, EventType.CONSUME);

		assertEquals(3, campBatch.quantity());
		assertFalse(world.findBatch(allocation.batchId()).isPresent());
		assertEquals(EventType.SHARE, world.ledger().event(allocation.eventId()).orElseThrow().type());
		assertEquals(60, world.availableNutrition(StorageOwner.camp("camp")));
		world.assertConservation();
	}

	@Test
	void consumedBatchCannotBeConsumedAgain() {
		ResourceBatch batch = world.createInitialBatch(ResourceType.WILD_FRUIT, 2, StorageOwner.household("h1"));
		world.consume("p1", batch.batchId(), 2, EventType.CONSUME);

		assertEquals(24, world.person("p1").hunger());
		assertFalse(world.findBatch(batch.batchId()).isPresent());
		assertThrows(IllegalArgumentException.class, () -> world.consume("p1", batch.batchId(), 1, EventType.CONSUME));
		assertEquals(0, world.inventoryTotals().get(ResourceType.WILD_FRUIT));
		world.assertConservation();
	}

	@Test
	void spoiledFoodLeavesInventoryThroughAnEvent() {
		ResourceBatch batch = world.createInitialBatch(ResourceType.WILD_FRUIT, 3, 50,
			StorageOwner.household("h1"), 0, 0);
		world.advanceDay();

		assertEquals(1, world.spoilExpired());
		assertFalse(world.findBatch(batch.batchId()).isPresent());
		assertEquals(EventType.SPOIL, world.ledger().events().getLast().type());
		assertEquals(0, world.inventoryTotals().get(ResourceType.WILD_FRUIT));
		world.assertConservation();
	}

	@Test
	void productionAndRegenerationKeepPatchAndBatchLedgersBalanced() {
		ResourceBatch produced = world.harvest("p1", "patch", 6, 5, 2.0, 1.0).orElseThrow();

		assertEquals(5, produced.quantity());
		assertEquals(45, world.patch("patch").stock());
		assertEquals(52, world.person("p1").energy());
		world.regeneratePatches(1.0);
		assertTrue(world.patch("patch").stock() > 45);
		assertEquals(5, world.inventoryTotals().get(ResourceType.WILD_FRUIT));
		world.assertConservation();
	}

	@Test
	void externalHandoffPreservesOriginAndHolderAuthority() {
		ResourceBatch imported = world.importExternalBatch(ResourceType.TUBERS, 5, StorageOwner.camp("camp"));

		assertEquals(5, imported.quantity());
		assertEquals(EventType.EXTERNAL_IMPORT, world.ledger().event(imported.originEventId()).orElseThrow().type());
		assertTrue(world.ledger().traceOrigins(imported.originEventId()).stream()
			.anyMatch(event -> event.type() == EventType.EXTERNAL_IMPORT));
		assertThrows(IllegalArgumentException.class, () -> world.exportExternalBatch(
			StorageOwner.household("h1"), imported.batchId(), 1
		));
		assertEquals(5, imported.quantity());

		world.exportExternalBatch(StorageOwner.camp("camp"), imported.batchId(), 2);
		assertEquals(3, imported.quantity());
		SimulationEvent export = world.ledger().events().getLast();
		assertEquals(EventType.EXTERNAL_EXPORT, export.type());
		assertTrue(world.ledger().traceOrigins(export.eventId()).stream()
			.anyMatch(event -> event.eventId() == imported.originEventId()));
		assertEquals(60, world.person("p1").hunger());
		world.assertConservation();

		world.exportExternalBatch(StorageOwner.camp("camp"), imported.batchId(), 3);
		assertFalse(world.findBatch(imported.batchId()).isPresent());
		assertEquals(0, world.inventoryTotals().get(ResourceType.TUBERS));
		world.assertConservation();
	}

	@Test
	void restoredExternalStateContinuesWithoutDuplicatingBatches() {
		ResourceBatch imported = world.importExternalBatch(ResourceType.TUBERS, 5, StorageOwner.camp("camp"));
		world.exportExternalBatch(StorageOwner.camp("camp"), imported.batchId(), 2);
		SimulationWorld.RestoredBatch restoredBatch = new SimulationWorld.RestoredBatch(
			imported.batchId(), imported.resourceType(), imported.quantity(), imported.nutritionPerUnit(), imported.quality(),
			imported.holder(), imported.location(), imported.sourcePatchId(), imported.originEventId(),
			imported.parentBatchIds(), imported.harvestDay(), imported.expiryDay(), imported.lastEventId()
		);
		SimulationWorld restored = new SimulationWorld();
		restored.registerCamp(new Camp("camp", "Test Camp"));

		restored.restoreState(world.day(), world.nextBatchId(), List.of(restoredBatch), world.ledger().events());

		assertEquals(3, restored.batch(imported.batchId()).quantity());
		assertEquals(world.ledger().events(), restored.ledger().events());
		assertEquals(world.nextBatchId(), restored.nextBatchId());
		restored.exportExternalBatch(StorageOwner.camp("camp"), imported.batchId(), 3);
		assertFalse(restored.findBatch(imported.batchId()).isPresent());
		assertTrue(restored.ledger().traceOrigins(restored.ledger().events().getLast().eventId()).stream()
			.anyMatch(event -> event.eventId() == imported.originEventId()));
		restored.assertConservation();
	}

	@Test
	void productionResourcesUseTheSameConservationRules() {
		world.registerPatch(new ResourcePatch(
			"wood", "camp", new ResourcePatch.Position(2, 64, 0), "test", ResourceType.WOOD,
			10, 20, 0.1
		));

		ResourceBatch produced = world.harvest("p1", "wood", 6, 4, 2.0, 1.0).orElseThrow();

		assertEquals(ResourceType.WOOD, produced.resourceType());
		assertEquals(0, produced.nutrition());
		assertEquals(4, produced.quantity());
		assertEquals(6, world.patch("wood").stock());
		world.assertConservation();
	}
}
