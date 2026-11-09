package com.thysummer.coopcraft.civilization.simulation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiverValleyNetworkScenarioTest {
	@Test
	void networkScenarioReplaysAndAllowsDifferentFoodOutcomes() {
		PrimitiveSocietySimulation first = RiverValleyNetworkScenario.create(7);
		PrimitiveSocietySimulation replay = RiverValleyNetworkScenario.create(7);
		first.runDays(300);
		replay.runDays(300);

		assertEquals(first.world().ledger().events(), replay.world().ledger().events());
		assertEquals(first.reports(), replay.reports());
		// Migration is conditional on food insecurity, not guaranteed for a fixed seed.
		// Population is conserved across the network, regardless of camp distribution.
		assertEquals(36, first.world().people().size());
		assertTrue(first.world().ledger().events().stream()
			.anyMatch(event -> event.type() == EventType.INFORMATION_SHARED && event.context().containsKey("routeId")));

		PrimitiveSocietySimulation otherSeed = RiverValleyNetworkScenario.create(41);
		otherSeed.runDays(300);
		assertEquals(36, otherSeed.world().people().size());
		assertNotEquals(first.reports(), otherSeed.reports());
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

}
