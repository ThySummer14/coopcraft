package com.thysummer.coopcraft.civilization;

import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.ResourceType;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
	void foragesOnlyWithoutFoodAndAfterCooldown() {
		assertFalse(SettlementForaging.needsForage(true, false, 0, 0));
		assertTrue(SettlementForaging.needsForage(false, false, 0, 0));
		assertFalse(SettlementForaging.needsForage(false, false, 199, 200));
		assertTrue(SettlementForaging.needsForage(false, false, 200, 200));
	}

	@Test
	void foragerReplenishesLowReserveEvenWhenFoodRemains() {
		assertTrue(SettlementForaging.needsForage(true, true, 200, 200));
		assertFalse(SettlementForaging.needsForage(true, true, 199, 200));
		assertFalse(SettlementForaging.needsForage(true, false, 200, 200));
		assertTrue(SettlementForaging.needsForage(false, true, 200, 200));
	}

	@Test
	void reserveUsesOnlyTheActiveFoodAuthority() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Berries", 0);
		data.registerMember(settlement, UUID.randomUUID(), 0);
		SimpleContainer chest = new SimpleContainer(1);
		chest.setItem(0, new ItemStack(Items.SWEET_BERRIES, 8));
		assertFalse(SettlementForaging.reserveLow(data, settlement, chest));
		chest.removeItem(0, 1);
		assertTrue(SettlementForaging.reserveLow(data, settlement, chest));

		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		state.setSurvivalEnabled(true, 0);
		chest.setItem(0, new ItemStack(Items.BREAD, 64));
		assertTrue(SettlementForaging.reserveLow(data, settlement, chest), "Real food is not abstract food");
		state.world().importExternalBatch(ResourceType.WILD_FRUIT, 3, state.campOwner());
		chest.clearContent();
		assertFalse(SettlementForaging.reserveLow(data, settlement, chest), "Abstract berries satisfy the reserve");
		state.world().assertConservation();
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
