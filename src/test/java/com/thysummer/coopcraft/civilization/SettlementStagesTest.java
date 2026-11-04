package com.thysummer.coopcraft.civilization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettlementStagesTest {
	@Test
	void emptyLandIsPrimitive() {
		assertEquals(SettlementStage.PRIMITIVE, SettlementStages.classify(0, 0, 0, 2, false));
	}

	@Test
	void villageCoreIsAgricultural() {
		assertEquals(SettlementStage.AGRICULTURAL, SettlementStages.classify(4, 0, 3, 3, false));
		assertEquals(SettlementStage.AGRICULTURAL, SettlementStages.classify(0, 8, 0, 2, false));
	}

	@Test
	void villageStructureCountsAsSettled() {
		assertEquals(SettlementStage.AGRICULTURAL, SettlementStages.classify(0, 0, 0, 2, true));
	}

	@Test
	void denseVillageBecomesTown() {
		assertEquals(SettlementStage.TOWN, SettlementStages.classify(8, 8, 5, 6, false));
		assertEquals(SettlementStage.TOWN, SettlementStages.classify(12, 20, 6, 8, true));
	}

	@Test
	void largeButSparseVillageStaysAgricultural() {
		assertEquals(SettlementStage.AGRICULTURAL, SettlementStages.classify(8, 4, 5, 6, false));
	}
}
