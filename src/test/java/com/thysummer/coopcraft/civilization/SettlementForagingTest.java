package com.thysummer.coopcraft.civilization;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementForagingTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void foragesOnlyWhenRequiredAndAfterCooldown() {
		assertFalse(SettlementForaging.needsForage(false, 0, 0));
		assertTrue(SettlementForaging.needsForage(true, 0, 0));
		assertFalse(SettlementForaging.needsForage(true, 199, 200));
		assertTrue(SettlementForaging.needsForage(true, 200, 200));
	}

	@Test
	void onlyMatureBerryBushesAreHarvestable() {
		BlockState bush = Blocks.SWEET_BERRY_BUSH.defaultBlockState();
		assertFalse(SettlementForaging.isRipe(bush.setValue(SweetBerryBushBlock.AGE, 0)));
		assertFalse(SettlementForaging.isRipe(bush.setValue(SweetBerryBushBlock.AGE, 1)));
		assertTrue(SettlementForaging.isRipe(bush.setValue(SweetBerryBushBlock.AGE, 2)));
		assertTrue(SettlementForaging.isRipe(bush.setValue(SweetBerryBushBlock.AGE, 3)));
		assertFalse(SettlementForaging.isRipe(Blocks.AIR.defaultBlockState()));
	}
}
