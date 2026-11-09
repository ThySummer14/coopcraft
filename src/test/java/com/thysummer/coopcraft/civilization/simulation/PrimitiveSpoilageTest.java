package com.thysummer.coopcraft.civilization.simulation;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimitiveSpoilageTest {
	@Test
	void surplusIsEdibleOnExpiryDayThenSpoilsWithoutBeingConsumed() {
		for (StorageOwner owner : List.of(StorageOwner.person("p1"), StorageOwner.household("h1"))) {
			SimulationWorld world = worldWithPeople(1);
			ResourceBatch surplus = world.createInitialBatch(ResourceType.FISH, 10, 50, owner, 0, 0);
			PrimitiveSocietySimulation simulation = new PrimitiveSocietySimulation(
				world, SimulationConfig.defaults().withSharing(false), 7);

			DailyReport expiryDay = simulation.simulateDay();
			assertEquals(40, expiryDay.consumedNutrition());
			assertEquals(0, expiryDay.spoiledNutrition());
			assertEquals(8, world.batch(surplus.batchId()).quantity());

			DailyReport overdueDay = simulation.simulateDay();
			assertEquals(0, overdueDay.consumedNutrition());
			assertEquals(160, overdueDay.spoiledNutrition());
			assertTrue(world.findBatch(surplus.batchId()).isEmpty());
			assertEquals(35, world.person("p1").hunger());
			assertEquals(2, simulation.reports().size());
			assertEquals(200, expiryDay.consumedNutrition() + overdueDay.spoiledNutrition());
			world.assertConservation();
		}
	}

	@Test
	void expiredCommonStockDoesNotTakeFreshFoodOrNormQuota() {
		SimulationWorld world = worldWithPeople(3);
		ResourceBatch expired = world.createInitialBatch(ResourceType.FISH, 10, 50, StorageOwner.camp("camp"), 0, 0);
		ResourceBatch fresh = world.createInitialBatch(ResourceType.FISH, 10, 50, StorageOwner.camp("camp"), 0, 2);
		world.registerNorm(new Norm(Norm.NEED_FIRST_COMMON_STORE, "camp", 2,
			Set.of("p2", "p3"), Set.of(), Set.of("p1", "p2", "p3"), 50, Norm.Status.ACTIVE, List.of()));
		world.advanceDay();
		PrimitiveSocietySimulation simulation = new PrimitiveSocietySimulation(world, SimulationConfig.defaults(), 7);

		DailyReport report = simulation.simulateDay();

		assertEquals(120, report.consumedNutrition());
		assertEquals(200, report.spoiledNutrition());
		assertEquals(4, world.batch(fresh.batchId()).quantity());
		assertTrue(world.people().stream().allMatch(person -> person.hunger() == 0));
		assertOnlySpoiled(world, expired);
		assertEquals(3, world.ledger().eventsAtDay(1).stream().filter(event -> event.type() == EventType.NORM_ENFORCED).count());
		assertConsumptionBeforeSpoilage(world);
		world.assertConservation();
	}

	@Test
	void donorSkipsExpiredBatchAndSharesFreshSurplus() {
		SimulationWorld world = worldWithPeople(2);
		world.person("p1").setTrust("p2", 100);
		world.person("p2").setTrust("p1", 100);
		ResourceBatch expired = world.createInitialBatch(ResourceType.FISH, 10, 50, StorageOwner.household("h2"), 0, 0);
		ResourceBatch fresh = world.createInitialBatch(ResourceType.FISH, 10, 50, StorageOwner.household("h2"), 0, 2);
		world.advanceDay();
		SimulationConfig config = new SimulationConfig(35, 6, 8, 0.5, 75, 20, 10, -100, true, false, 1);
		PrimitiveSocietySimulation simulation = new PrimitiveSocietySimulation(world, config, 7);

		DailyReport report = simulation.simulateDay();

		assertEquals(1, report.shareAcceptances());
		assertEquals(80, report.consumedNutrition());
		assertEquals(200, report.spoiledNutrition());
		assertEquals(6, world.batch(fresh.batchId()).quantity());
		assertTrue(world.people().stream().allMatch(person -> person.hunger() == 0));
		assertOnlySpoiled(world, expired);
		assertConsumptionBeforeSpoilage(world);
		world.assertConservation();
	}

	private static SimulationWorld worldWithPeople(int count) {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp("camp", "Test Camp"));
		for (int i = 1; i <= count; i++) {
			world.registerHousehold(new Household("h" + i, "camp"));
			world.registerPerson(new Person("p" + i, "camp", "h" + i, Person.AgeBand.ADULT, 0, 50));
		}
		return world;
	}

	private static void assertOnlySpoiled(SimulationWorld world, ResourceBatch expired) {
		List<SimulationEvent> uses = world.ledger().events().stream()
			.filter(event -> event.inputs().stream().anyMatch(flow -> flow.batchId().equals(expired.batchId())))
			.toList();
		assertEquals(1, uses.size());
		assertEquals(EventType.SPOIL, uses.getFirst().type());
		assertEquals(200, uses.getFirst().inputs().stream().mapToLong(ResourceFlow::nutrition).sum());
	}

	private static void assertConsumptionBeforeSpoilage(SimulationWorld world) {
		long firstSpoil = world.ledger().eventsAtDay(1).stream()
			.filter(event -> event.type() == EventType.SPOIL).mapToLong(SimulationEvent::eventId).min().orElseThrow();
		assertTrue(world.ledger().eventsAtDay(1).stream().filter(event -> event.type() == EventType.CONSUME)
			.allMatch(event -> event.eventId() < firstSpoil));
	}
}
