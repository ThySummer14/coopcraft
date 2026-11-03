package com.thysummer.coopcraft.civilization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionPlannerTest {
	@Test
	void noMembersNeedNoHousing() {
		ConstructionPlanner.HousingPlan plan = new ConstructionPlanner.HousingPlan(0, 0);

		assertEquals(0, plan.missingBeds());
		assertFalse(plan.needsHousing());
	}

	@Test
	void membersWithoutBedsNeedHousing() {
		assertEquals(1, new ConstructionPlanner.HousingPlan(1, 0).missingBeds());
		assertEquals(1, new ConstructionPlanner.HousingPlan(2, 1).missingBeds());
		assertTrue(new ConstructionPlanner.HousingPlan(3, 2).needsHousing());
	}

	@Test
	void enoughBedsStopHousing() {
		assertFalse(new ConstructionPlanner.HousingPlan(1, 1).needsHousing());
		assertFalse(new ConstructionPlanner.HousingPlan(2, 2).needsHousing());
		assertEquals(0, new ConstructionPlanner.HousingPlan(2, 3).missingBeds());
	}

	@Test
	void rejectsNegativeCounts() {
		assertThrows(IllegalArgumentException.class, () -> new ConstructionPlanner.HousingPlan(-1, 0));
		assertThrows(IllegalArgumentException.class, () -> new ConstructionPlanner.HousingPlan(0, -1));
	}
}
