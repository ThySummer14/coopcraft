package com.thysummer.coopcraft.civilization;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementFarmingTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void farmingStartsWithAgriculturalSettlements() {
		assertFalse(SettlementFarming.supportsStage(SettlementStage.PRIMITIVE));
		assertTrue(SettlementFarming.supportsStage(SettlementStage.AGRICULTURAL));
		assertTrue(SettlementFarming.supportsStage(SettlementStage.TOWN));
	}

	@Test
	void recognizesOnlyMatureCropsThatResidentsCanEatDirectly() {
		for (Block block : new Block[] {Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS}) {
			CropBlock crop = (CropBlock) block;
			assertTrue(SettlementFarming.isMature(crop.getStateForAge(crop.getMaxAge())));
			assertFalse(SettlementFarming.isMature(crop.defaultBlockState()));
		}
		CropBlock wheat = (CropBlock) Blocks.WHEAT;
		assertFalse(SettlementFarming.isMature(wheat.getStateForAge(wheat.getMaxAge())));
		assertFalse(SettlementFarming.isMature(Blocks.NETHER_WART.defaultBlockState()));
	}

	@Test
	void plantingRequiresEmptyFarmland() {
		assertTrue(SettlementFarming.isPlantingSite(Blocks.FARMLAND.defaultBlockState(), Blocks.AIR.defaultBlockState()));
		assertFalse(SettlementFarming.isPlantingSite(Blocks.FARMLAND.defaultBlockState(), Blocks.POTATOES.defaultBlockState()));
		assertFalse(SettlementFarming.isPlantingSite(Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState()));
	}
}
