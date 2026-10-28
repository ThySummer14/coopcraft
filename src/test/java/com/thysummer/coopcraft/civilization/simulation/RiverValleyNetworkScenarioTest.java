package com.thysummer.coopcraft.civilization.simulation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiverValleyNetworkScenarioTest {
	@Test
	void networkScenarioReplaysAndAllowsDifferentMigrationOutcomes() {
		PrimitiveSocietySimulation first = RiverValleyNetworkScenario.create(7);
		PrimitiveSocietySimulation replay = RiverValleyNetworkScenario.create(7);
		first.runDays(300);
		replay.runDays(300);

		assertEquals(first.world().ledger().events(), replay.world().ledger().events());
		assertEquals(first.reports(), replay.reports());
		assertTrue(count(first, EventType.MIGRATED) > 0);
		assertTrue(first.world().peopleInCamp(RiverValleyNetworkScenario.RIDGE_CAMP_ID).size() > 0);
		assertTrue(first.world().ledger().events().stream()
			.anyMatch(event -> event.type() == EventType.INFORMATION_SHARED && event.context().containsKey("routeId")));

		PrimitiveSocietySimulation otherSeed = RiverValleyNetworkScenario.create(41);
		otherSeed.runDays(300);
		assertNotEquals(count(first, EventType.MIGRATED), count(otherSeed, EventType.MIGRATED));
		first.world().assertConservation();
		otherSeed.world().assertConservation();
	}

	@Test
	void networkThousandDaysConservesResourcesAndTracesMigration() {
		PrimitiveSocietySimulation simulation = RiverValleyNetworkScenario.create(7);

		simulation.runDays(1000);

		for (SimulationEvent event : simulation.world().ledger().events()) {
			if (event.type() == EventType.MIGRATED) {
				assertTrue(simulation.world().ledger().traceAncestry(event.eventId()).stream()
					.anyMatch(cause -> cause.type() == EventType.MIGRATION_DECIDED));
			}
			if (event.type() == EventType.CONSUME || event.type() == EventType.FIELD_CONSUMPTION) {
				assertTrue(!simulation.world().ledger().traceOrigins(event.eventId()).isEmpty());
			}
		}
		simulation.world().assertConservation();
	}

	private static long count(PrimitiveSocietySimulation simulation, EventType type) {
		return simulation.world().ledger().events().stream().filter(event -> event.type() == type).count();
	}
}
