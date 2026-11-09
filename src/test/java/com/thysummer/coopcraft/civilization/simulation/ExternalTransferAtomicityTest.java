package com.thysummer.coopcraft.civilization.simulation;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalTransferAtomicityTest {
	private static final StorageOwner OWNER = StorageOwner.camp("camp");

	@Test
	void invalidImportMetadataAndCausesLeaveEveryFieldUnchangedAndPermitRetry() {
		SimulationWorld world = world();
		Map<String, String> nullKey = new LinkedHashMap<>();
		nullKey.put(null, "value");
		Map<String, String> nullValue = new LinkedHashMap<>();
		nullValue.put("key", null);
		for (List<String> actors : Arrays.asList(null, List.of(" "), Arrays.asList((String) null))) {
			unchanged(world, () -> world.importExternalBatch(ResourceType.WILD_FRUIT, 2, OWNER, actors, Map.of()));
		}
		for (Map<String, String> context : Arrays.asList(null, nullKey, nullValue, Map.of(" ", "value"))) {
			unchanged(world, () -> world.importExternalBatch(ResourceType.WILD_FRUIT, 2, OWNER, List.of(), context));
		}
		for (List<Long> causes : Arrays.asList(null, Arrays.asList((Long) null), List.of(0L), List.of(1L), List.of(99L))) {
			unchanged(world, () -> world.importExternalBatch(ResourceType.WILD_FRUIT, 2, OWNER, List.of(), Map.of(), causes));
		}
		unchanged(world, () -> world.importExternalBatch(null, 1, OWNER));
		unchanged(world, () -> world.importExternalBatch(ResourceType.WILD_FRUIT, 0, OWNER));
		unchanged(world, () -> world.importExternalBatch(ResourceType.WILD_FRUIT, 1, null));
		unchanged(world, () -> world.importExternalBatch(ResourceType.WILD_FRUIT, 1, StorageOwner.camp("missing")));
		unchanged(world, () -> world.importExternalBatch(ResourceType.WILD_FRUIT, Long.MAX_VALUE, OWNER));
		ResourceBatch batch = world.importExternalBatch(ResourceType.WILD_FRUIT, 2, OWNER,
			List.of("external:farmer"), Map.of("holder", "wrong", "source", "farm"), List.of());
		assertEquals("batch-1", batch.batchId());
		assertEquals(OWNER.toString(), world.ledger().lastEvent().orElseThrow().context().get("holder"));
		assertEquals("farm", world.ledger().lastEvent().orElseThrow().context().get("source"));
		assertRoundTrip(world);
	}

	@Test
	void partialAndFullExportsRejectBadMetadataBeforeMutationThenKeepCausality() {
		SimulationWorld world = world();
		ResourceBatch batch = world.importExternalBatch(ResourceType.WILD_FRUIT, 5, OWNER);
		for (long amount : List.of(2L, 5L)) {
			unchanged(world, () -> world.exportExternalBatch(OWNER, batch.batchId(), amount, null, Map.of()));
			unchanged(world, () -> world.exportExternalBatch(OWNER, batch.batchId(), amount, List.of(), null));
			unchanged(world, () -> world.exportExternalBatch(OWNER, batch.batchId(), amount, List.of(" "), Map.of()));
			unchanged(world, () -> world.exportExternalBatch(OWNER, batch.batchId(), amount, List.of(), Map.of("", "bad")));
		}
		unchanged(world, () -> world.exportExternalBatch(null, batch.batchId(), 1));
		unchanged(world, () -> world.exportExternalBatch(OWNER, null, 1));
		unchanged(world, () -> world.exportExternalBatch(OWNER, batch.batchId(), 6));
		Snapshot before = snapshot(world);
		world.validateExternalExport(OWNER, batch.batchId(), 2, List.of("external:builder"), Map.of());
		world.validateExternalImport(ResourceType.WILD_FRUIT, 2, OWNER, List.of(), Map.of(), List.of(1L));
		assertEquals(before, snapshot(world));
		assertEquals(2, world.exportExternalBatch(OWNER, batch.batchId(), 2, List.of("external:builder"), Map.of("batchId", "wrong")));
		assertEquals(3, batch.quantity());
		assertEquals(2, batch.lastEventId());
		assertEquals(List.of(1L), world.ledger().lastEvent().orElseThrow().causalLinks());
		assertEquals(batch.batchId(), world.ledger().lastEvent().orElseThrow().context().get("batchId"));
		assertRoundTrip(world);
		world.exportExternalBatch(OWNER, batch.batchId(), 3);
		assertTrue(world.findBatch(batch.batchId()).isEmpty());
		assertEquals(List.of(2L), world.ledger().lastEvent().orElseThrow().causalLinks());
		assertRoundTrip(world);
	}

	@Test
	void exhaustedOrOccupiedIdsAndBackwardsTimeCannotChangeInventory() throws Exception {
		SimulationWorld world = world();
		ResourceBatch batch = world.importExternalBatch(ResourceType.WILD_FRUIT, 5, OWNER);
		for (long id : List.of(0L, -1L, Long.MAX_VALUE, 1L)) {
			set(world, "nextBatchId", id);
			unchanged(world, () -> world.importExternalBatch(ResourceType.WILD_FRUIT, 1, OWNER));
		}
		set(world, "nextBatchId", 2L);
		for (long id : List.of(0L, -1L, Long.MAX_VALUE)) {
			set(world.ledger(), "nextEventId", id);
			unchanged(world, () -> world.importExternalBatch(ResourceType.WILD_FRUIT, 1, OWNER));
			unchanged(world, () -> world.exportExternalBatch(OWNER, batch.batchId(), 2));
			unchanged(world, () -> world.exportExternalBatch(OWNER, batch.batchId(), 5));
		}
		set(world.ledger(), "nextEventId", 3L);
		unchanged(world, () -> world.importExternalBatch(ResourceType.WILD_FRUIT, 1, OWNER, List.of(), Map.of(), List.of(2L)));
		set(world.ledger(), "nextEventId", 2L);
		world.advanceDay();
		world.importExternalBatch(ResourceType.WILD_FRUIT, 1, OWNER);
		set(world, "day", 0L);
		unchanged(world, () -> world.importExternalBatch(ResourceType.WILD_FRUIT, 1, OWNER));
		unchanged(world, () -> world.exportExternalBatch(OWNER, batch.batchId(), 2));
		set(world, "day", 1L);
		world.exportExternalBatch(OWNER, batch.batchId(), 5);
		assertRoundTrip(world);
	}

	@Test
	void exportRejectsUnrepresentableNutritionAndUnknownBatchCause() throws Exception {
		SimulationWorld world = world();
		ResourceBatch batch = world.importExternalBatch(ResourceType.WILD_FRUIT, 1, OWNER);
		set(batch, "quantity", Long.MAX_VALUE);
		Snapshot before = snapshot(world);
		assertThrows(IllegalArgumentException.class, () -> world.exportExternalBatch(OWNER, batch.batchId(), Long.MAX_VALUE));
		assertEquals(before, snapshot(world));
		set(batch, "quantity", 1L);
		set(batch, "lastEventId", 99L);
		before = snapshot(world);
		assertThrows(IllegalArgumentException.class, () -> world.exportExternalBatch(OWNER, batch.batchId(), 1));
		assertEquals(before, snapshot(world));
		set(batch, "lastEventId", 1L);
		world.exportExternalBatch(OWNER, batch.batchId(), 1);
		assertRoundTrip(world);
	}

	private static SimulationWorld world() {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp("camp", "Test Camp"));
		return world;
	}

	private static void unchanged(SimulationWorld world, Runnable operation) {
		Snapshot before = snapshot(world);
		assertThrows(IllegalArgumentException.class, operation::run);
		assertEquals(before, snapshot(world));
		world.assertConservation();
	}

	private static Snapshot snapshot(SimulationWorld world) {
		return new Snapshot(world.day(), world.nextBatchId(), world.ledger().nextEventId(),
			world.batches().stream().map(batch -> new SimulationWorld.RestoredBatch(
				batch.batchId(), batch.resourceType(), batch.quantity(), batch.nutritionPerUnit(), batch.quality(),
				batch.holder(), batch.location(), batch.sourcePatchId(), batch.originEventId(), batch.parentBatchIds(),
				batch.harvestDay(), batch.expiryDay(), batch.lastEventId())).toList(), world.ledger().events());
	}

	private static void assertRoundTrip(SimulationWorld world) {
		world.assertConservation();
		Snapshot before = snapshot(world);
		SimulationWorld restored = world();
		restored.restoreState(before.day(), before.nextBatchId(), before.batches(), before.events());
		assertEquals(before, snapshot(restored));
		restored.assertConservation();
	}

	private static void set(Object target, String fieldName, long value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.setLong(target, value);
	}

	private record Snapshot(long day, long nextBatchId, long nextEventId,
		List<SimulationWorld.RestoredBatch> batches, List<SimulationEvent> events) {}
}
