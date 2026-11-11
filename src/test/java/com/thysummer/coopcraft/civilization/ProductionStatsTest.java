package com.thysummer.coopcraft.civilization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionStatsTest {
	@Test
	void keepsDefaultIntervalWithoutEnoughEvidence() {
		ProductionStats stats = ProductionStats.initial().observe(0).observe(10);

		assertEquals(2, stats.cycles());
		assertEquals(40, stats.recommendedIntervalTicks());
		assertFalse(stats.adaptiveLearned());
		assertFalse(stats.selfImproved());
	}

	@Test
	void increasesIntervalWhenProductionIsTooFrequent() {
		ProductionStats stats = ProductionStats.initial().observe(0).observe(10).observe(20);

		assertEquals(80, stats.recommendedIntervalTicks());
		assertTrue(stats.adaptiveLearned());
		assertEquals(0, stats.improvedSamples());
		assertFalse(stats.selfImproved());
	}

	@Test
	void learnsShorterIntervalAndVerifiesLaterImprovement() {
		ProductionStats stats = ProductionStats.initial().observe(0).observe(80).observe(160);

		assertEquals(20, stats.recommendedIntervalTicks());
		assertTrue(stats.adaptiveLearned());
		assertEquals(80, stats.baselineCycleTicks());
		assertFalse(stats.selfImproved());

		stats = stats.observe(180);
		assertEquals(1, stats.improvedSamples());
		assertFalse(stats.selfImproved());
		stats = stats.observe(200);

		assertEquals(2, stats.improvedSamples());
		assertEquals(20, stats.improvedCycleTicks());
		assertTrue(stats.selfImproved());
	}

	@Test
	void productionStatisticsSurviveNbtReload() {
		ProductionStats stats = ProductionStats.initial().observe(0).observe(80).observe(160).observe(180).observe(200);

		assertEquals(stats, ProductionStats.load(stats.save()));
	}
}
