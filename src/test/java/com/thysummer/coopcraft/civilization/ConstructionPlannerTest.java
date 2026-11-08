package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionPlannerTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void remainingMaterialsFollowProgressAndDoNotChargeMultipartTwice() {
		ConstructionPlan plan = new ConstructionPlan(ConstructionPlanner.STONE_COTTAGE, List.of(
			new ConstructionInstruction(BlockPos.ZERO, Blocks.OAK_PLANKS.defaultBlockState(), Items.OAK_PLANKS, true),
			new ConstructionInstruction(new BlockPos(1, 0, 0), Blocks.OAK_DOOR.defaultBlockState(), Items.OAK_DOOR, true),
			new ConstructionInstruction(new BlockPos(1, 1, 0), Blocks.OAK_DOOR.defaultBlockState(), Items.OAK_DOOR, false)));
		assertEquals(java.util.Map.of(Items.OAK_PLANKS, 1, Items.OAK_DOOR, 1), ConstructionPlanner.remainingMaterials(plan, 0));
		assertEquals(java.util.Map.of(Items.OAK_DOOR, 1), ConstructionPlanner.remainingMaterials(plan, 1));
		assertTrue(ConstructionPlanner.remainingMaterials(plan, 2).isEmpty());
		assertTrue(ConstructionPlanner.remainingMaterials(plan, 3).isEmpty());
		assertThrows(IllegalArgumentException.class, () -> ConstructionPlanner.remainingMaterials(plan, -1));
		assertThrows(IllegalArgumentException.class, () -> ConstructionPlanner.remainingMaterials(plan, 4));
	}

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
	void agriculturalSettlementSkipsPrimitiveHearth() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(new BlockPos(4, 64, 8), "Testhaven", 0);
		data.registerMember(settlement, UUID.randomUUID(), 0);
		data.setStage(settlement, SettlementStage.AGRICULTURAL);

		assertEquals(
			List.of(ConstructionPlanner.STONE_COTTAGE),
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
