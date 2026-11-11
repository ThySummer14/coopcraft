package com.thysummer.coopcraft.civilization.simulation;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgedFoodImportTest {
	private static final StorageOwner HOLDER = StorageOwner.household("home");

	private SimulationWorld worldAt(long day) {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp("camp", "Camp"));
		world.registerHousehold(new Household("home", "camp"));
		world.registerPerson(new Person("person", "camp", "home", Person.AgeBand.ADULT, 60, 50));
		world.restoreState(day, 1, List.of(), List.of());
		return world;
	}

	private ResourceBatch importFood(SimulationWorld world, ResourceType type, long quantity, long harvestDay) {
		return world.importExternalFoodBatch(type, quantity, HOLDER, harvestDay, List.of(), Map.of(), List.of());
	}

	@Test
	void delayedImportRetainsAgeWhileEventOccursToday() {
		SimulationWorld world = worldAt(7);
		ResourceBatch cause = world.importExternalBatch(ResourceType.WOOD, 1, HOLDER);
		ResourceBatch food = world.importExternalFoodBatch(ResourceType.TUBERS, 4, HOLDER, 2,
			List.of("external-producer"), Map.of("activity", "gather", "holder", "supplied"),
			List.of(cause.originEventId(), cause.originEventId()));
		SimulationEvent event = world.ledger().event(food.originEventId()).orElseThrow();
		assertEquals(2, food.harvestDay());
		assertEquals(10, food.expiryDay());
		assertEquals(7, event.day());
		assertEquals(EventType.EXTERNAL_IMPORT, event.type());
		assertEquals(List.of("external-producer"), event.actors());
		assertEquals(Map.of("activity", "gather", "holder", HOLDER.toString()), event.context());
		assertEquals(List.of(cause.originEventId()), event.causalLinks());
		assertEquals(4, event.outputs().getFirst().quantity());
		world.assertConservation();
	}

	@Test
	void expiryDayIsUsableButNextDayIsRejectedWithoutMutation() {
		SimulationWorld world = worldAt(3);
		ResourceBatch food = importFood(world, ResourceType.WILD_FRUIT, 3, 0);
		assertEquals(3, food.expiryDay());
		assertEquals(54, world.availableNutrition(HOLDER));
		world.advanceDay();
		assertRejected(world, () -> importFood(world, ResourceType.WILD_FRUIT, 2, 0));
		assertEquals(0, world.availableNutrition(HOLDER));
		int hunger = world.person("person").hunger();
		for (EventType type : List.of(EventType.CONSUME, EventType.FIELD_CONSUMPTION)) {
			assertRejected(world, () -> world.consume("person", food.batchId(), 1, type));
		}
		assertEquals(hunger, world.person("person").hunger());
		assertEquals(3, food.quantity());
		assertEquals(1, world.batches().size());
		assertEquals(1, world.ledger().events().size());
		assertEquals(1, world.spoilExpired());
		assertFalse(world.findBatch(food.batchId()).isPresent());
		assertEquals(EventType.SPOIL, world.ledger().events().getLast().type());
		world.assertConservation();
	}

	@Test
	void partialTransferConsumptionAndSpoilageKeepOriginalDates() {
		SimulationWorld world = worldAt(2);
		ResourceBatch food = importFood(world, ResourceType.WILD_FRUIT, 8, 0);
		SimulationWorld.TransferResult result = world.transfer("person", food.batchId(), 3,
			StorageOwner.person("person"), EventType.STORE, 0);
		ResourceBatch moved = world.batch(result.batchId());
		assertEquals(5, food.quantity());
		assertEquals(0, moved.harvestDay());
		assertEquals(3, moved.expiryDay());
		assertEquals(food.originEventId(), moved.originEventId());
		assertTrue(moved.parentBatchIds().contains(food.batchId()));
		world.consume("person", moved.batchId(), 1, EventType.CONSUME);
		assertEquals(2, moved.quantity());
		world.advanceDay();
		assertEquals(0, world.spoilExpired());
		world.advanceDay();
		assertEquals(2, world.spoilExpired());
		assertTrue(world.batches().isEmpty());
		world.assertConservation();
	}

	@Test
	void safeExpirySaturatesAtLargestSimulationDay() {
		SimulationWorld world = worldAt(Long.MAX_VALUE);
		ResourceBatch food = importFood(world, ResourceType.TUBERS, 1, Long.MAX_VALUE - 2);
		assertEquals(Long.MAX_VALUE, food.expiryDay());
		assertEquals(Long.MAX_VALUE - 2, food.harvestDay());
		assertEquals(Long.MAX_VALUE, world.ledger().events().getLast().day());
		assertEquals(24, world.availableNutrition(HOLDER));
		assertEquals(0, world.spoilExpired());
		world.assertConservation();
	}

	@Test
	void invalidStockAndOwnersNeverConsumeIds() {
		SimulationWorld world = worldAt(2);
		assertRejected(world, () -> importFood(world, ResourceType.WILD_FRUIT, 1, -1));
		assertRejected(world, () -> importFood(world, ResourceType.WILD_FRUIT, 1, 3));
		assertRejected(world, () -> importFood(world, ResourceType.WOOD, 1, 0));
		assertRejected(world, () -> importFood(world, null, 1, 0));
		assertRejected(world, () -> importFood(world, ResourceType.FISH, 0, 0));
		assertRejected(world, () -> importFood(world, ResourceType.FISH, -1, 0));
		for (StorageOwner owner : Arrays.asList(null, StorageOwner.camp("missing"),
			StorageOwner.household("missing"), StorageOwner.person("missing"))) {
			assertRejected(world, () -> world.importExternalFoodBatch(ResourceType.FISH, 1, owner, 0,
				List.of(), Map.of(), List.of()));
		}
		ResourceBatch valid = importFood(world, ResourceType.FISH, 1, 2);
		assertEquals("batch-1", valid.batchId());
		assertEquals(1, valid.originEventId());
	}

	@Test
	void invalidMetadataNeverConsumesIdsOrStock() {
		SimulationWorld world = worldAt(2);
		ResourceBatch first = importFood(world, ResourceType.FISH, 3, 1);
		for (List<String> actors : Arrays.asList(null, List.of(" "), Arrays.asList((String) null))) {
			assertRejected(world, () -> world.importExternalFoodBatch(ResourceType.FISH, 1, HOLDER, 0,
				actors, Map.of(), List.of()));
		}
		Map<String, String> nullKey = new HashMap<>();
		nullKey.put(null, "value");
		Map<String, String> nullValue = new HashMap<>();
		nullValue.put("key", null);
		for (Map<String, String> context : Arrays.asList(null, Map.of(" ", "value"), nullKey, nullValue)) {
			assertRejected(world, () -> world.importExternalFoodBatch(ResourceType.FISH, 1, HOLDER, 0,
				List.of(), context, List.of()));
		}
		for (List<Long> links : Arrays.asList(null, List.of(0L), List.of(-1L), List.of(2L),
			List.of(Long.MAX_VALUE), Arrays.asList((Long) null))) {
			assertRejected(world, () -> world.importExternalFoodBatch(ResourceType.FISH, 1, HOLDER, 0,
				List.of(), Map.of(), links));
		}
		assertEquals(3, first.quantity());
		ResourceBatch next = importFood(world, ResourceType.FISH, 1, 0);
		assertEquals("batch-2", next.batchId());
		assertEquals(2, next.originEventId());
		world.assertConservation();
	}

	@Test
	void successfulPreflightIsReadOnlyAndSameDayImportMatchesLegacy() {
		SimulationWorld world = worldAt(2);
		world.validateExternalFoodImport(ResourceType.FISH, 2, HOLDER, 2,
			List.of("person"), Map.of("activity", "fish"), List.of());
		assertEquals(1, world.nextBatchId());
		assertTrue(world.batches().isEmpty());
		assertTrue(world.ledger().events().isEmpty());
		SimulationWorld legacy = worldAt(2);
		ResourceBatch food = world.importExternalFoodBatch(ResourceType.FISH, 2, HOLDER, 2,
			List.of("person"), Map.of("activity", "fish"), List.of());
		ResourceBatch original = legacy.importExternalBatch(ResourceType.FISH, 2, HOLDER,
			List.of("person"), Map.of("activity", "fish"), List.of());
		assertEquals(original.harvestDay(), food.harvestDay());
		assertEquals(original.expiryDay(), food.expiryDay());
		assertEquals(legacy.ledger().events(), world.ledger().events());
	}

	@Test
	void overflowPreflightRejectsBeforeMutationAndAllFoodTypesRetainSeparateHarvests() {
		SimulationWorld world = worldAt(2);
		for (ResourceType type : List.of(ResourceType.WILD_FRUIT, ResourceType.TUBERS,
			ResourceType.FISH, ResourceType.GAME)) {
			long overflowingQuantity = Long.MAX_VALUE / type.nutritionPerUnit() + 1;
			assertRejected(world, () -> world.validateExternalFoodImport(type, overflowingQuantity,
				HOLDER, 0, List.of(), Map.of(), List.of()));
			assertRejected(world, () -> importFood(world, type, overflowingQuantity, 0));
			ResourceBatch older = importFood(world, type, 2, 0);
			ResourceBatch newer = importFood(world, type, 3, 1);
			assertFalse(older.batchId().equals(newer.batchId()));
			assertEquals(2, older.quantity());
			assertEquals(3, newer.quantity());
			assertEquals(0, older.harvestDay());
			assertEquals(1, newer.harvestDay());
			assertEquals(type.shelfLifeDays(), older.expiryDay());
			assertEquals(type.shelfLifeDays() + 1, newer.expiryDay());
		}
		assertEquals(8, world.batches().size());
		assertEquals(8, world.ledger().events().size());
		assertEquals(9, world.nextBatchId());
		assertEquals(9, world.ledger().nextEventId());
		world.assertConservation();

		SimulationWorld exhaustedIds = worldAt(2);
		exhaustedIds.restoreState(2, Long.MAX_VALUE, List.of(), List.of());
		assertRejected(exhaustedIds, () -> exhaustedIds.validateExternalFoodImport(ResourceType.FISH,
			1, HOLDER, 0, List.of(), Map.of(), List.of()));
		assertRejected(exhaustedIds, () -> importFood(exhaustedIds, ResourceType.FISH, 1, 0));
	}

	private void assertRejected(SimulationWorld world, Runnable action) {
		long nextBatch = world.nextBatchId();
		long nextEvent = world.ledger().nextEventId();
		List<ResourceBatch> batches = world.batches();
		List<SimulationEvent> events = world.ledger().events();
		assertThrows(IllegalArgumentException.class, action::run);
		assertEquals(nextBatch, world.nextBatchId());
		assertEquals(nextEvent, world.ledger().nextEventId());
		assertEquals(batches, world.batches());
		assertEquals(events, world.ledger().events());
	}
}
