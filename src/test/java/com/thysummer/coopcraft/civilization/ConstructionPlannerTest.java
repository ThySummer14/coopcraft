package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;

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

	@Test
	void plansHearthBeforeHousing() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(new BlockPos(4, 64, 8), "Testhaven", 0);
		data.registerMember(settlement, UUID.randomUUID(), 0);

		assertEquals(
			List.of(ConstructionPlanner.CAMPFIRE_HEARTH, ConstructionPlanner.STONE_COTTAGE),
			ConstructionPlanner.projects(data, settlement).stream().map(ConstructionPlanner.Project::blueprintId).toList()
		);
	}

	@Test
	void builtHearthAndEnoughBedsLeaveNoProjects() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(new BlockPos(4, 64, 8), "Testhaven", 0);
		UUID member = UUID.randomUUID();
		data.registerMember(settlement, member, 0);
		data.setHearth(settlement, new BlockPos(5, 64, 9));

		assertEquals(
			List.of(ConstructionPlanner.STONE_COTTAGE),
			ConstructionPlanner.projects(data, settlement).stream().map(ConstructionPlanner.Project::blueprintId).toList()
		);

		data.addHousingBed(settlement, new BlockPos(4, 64, 4));
		assertTrue(ConstructionPlanner.projects(data, settlement).isEmpty());
	}
}
