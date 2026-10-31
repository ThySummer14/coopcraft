package com.thysummer.coopcraft.civilization.simulation;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimitiveSocietySimulationTest {
	@Test
	void identicalSeedsReplayEventsNeedsAndReports() {
		PrimitiveSocietySimulation first = RiverValleyScenario.create(42);
		PrimitiveSocietySimulation second = RiverValleyScenario.create(42);

		first.runDays(40);
		second.runDays(40);

		assertEquals(first.world().ledger().events(), second.world().ledger().events());
		assertEquals(first.reports(), second.reports());
		assertEquals(first.world().inventoryTotals(), second.world().inventoryTotals());
		assertEquals(first.world().people().stream().map(Person::hunger).toList(),
			second.world().people().stream().map(Person::hunger).toList());
		assertEquals(first.world().people().stream().map(person -> person.trustIn("person-00")).toList(),
			second.world().people().stream().map(person -> person.trustIn("person-00")).toList());
	}

	@Test
	void sharingAndRefusalEmergeFromInventoryAndRelationships() {
		PrimitiveSocietySimulation simulation = RiverValleyScenario.create(7);

		simulation.runDays(60);

		long requests = count(simulation, EventType.SHARE_REQUEST);
		assertTrue(requests > 0);
		assertTrue(count(simulation, EventType.SHARE) > 0);
		assertTrue(count(simulation, EventType.SHARE_REFUSED) > 0);
		assertTrue(simulation.reports().stream().mapToLong(DailyReport::shareAcceptances).sum() > 0);
		assertEquals(15, simulation.workReservations().size());
	}

	@Test
	void sharingComparisonReportsDifferentOutcomesWithoutPrescribingAWinner() {
		PrimitiveSocietySimulation sharing = RiverValleyScenario.create(41);
		SimulationConfig noSharingConfig = SimulationConfig.defaults().withSharing(false).withPublicStorage(false);
		PrimitiveSocietySimulation noSharing = RiverValleyScenario.create(41, noSharingConfig);

		sharing.runDays(200);
		noSharing.runDays(200);

		long sharingHungerDays = sharing.world().people().stream().mapToLong(Person::hungerDays).sum();
		long isolatedHungerDays = noSharing.world().people().stream().mapToLong(Person::hungerDays).sum();
		assertNotEquals(isolatedHungerDays, sharingHungerDays);
		assertTrue(count(sharing, EventType.SHARE) > 0);
		assertEquals(0, count(noSharing, EventType.SHARE));
		assertEquals(0, count(noSharing, EventType.SHARE_REQUEST));
		sharing.world().assertConservation();
		noSharing.world().assertConservation();
	}

	@Test
	void thousandDayRunsConserveResourcesAndTraceEveryConsumption() {
		for (long seed : new long[] {7, 41, 1337}) {
			PrimitiveSocietySimulation simulation = RiverValleyScenario.create(seed);

			List<DailyReport> reports = simulation.runDays(1000);

			assertEquals(1000, reports.size());
			assertEquals(1000, simulation.world().day());
			for (DailyReport report : reports) {
				assertEquals(24, report.hungryPeople() + report.foodAccessCount());
				assertTrue(report.foodAccessRate(24) >= 0 && report.foodAccessRate(24) <= 1);
			}
			for (ResourceBatch batch : simulation.world().batches()) {
				assertTrue(batch.quantity() > 0);
			}
			for (ResourcePatch patch : simulation.world().patchesForCamp(RiverValleyScenario.CAMP_ID)) {
				assertTrue(patch.stock() >= 0 && patch.stock() <= patch.carryingCapacity());
			}
			for (SimulationEvent event : simulation.world().ledger().events()) {
				if (event.type() == EventType.CONSUME || event.type() == EventType.FIELD_CONSUMPTION) {
					assertFalse(simulation.world().ledger().traceOrigins(event.eventId()).isEmpty(),
						"Consumption without a material origin: " + event.eventId());
				} else if (event.type() == EventType.NORM_ADOPTED || event.type() == EventType.NORM_REVISED) {
					assertTrue(simulation.world().ledger().traceAncestry(event.eventId()).stream()
						.anyMatch(cause -> cause.type() == EventType.SHARE_REFUSED || cause.type() == EventType.SHORTAGE_CONFLICT));
				} else if (event.type() == EventType.NORM_ENFORCED) {
					assertTrue(Integer.parseInt(event.context().get("allocatedUnits"))
						<= Integer.parseInt(event.context().get("cap")));
				}
			}
			simulation.world().assertConservation();
		}
	}

	private static long count(PrimitiveSocietySimulation simulation, EventType type) {
		return simulation.world().ledger().events().stream().filter(event -> event.type() == type).count();
	}
}
