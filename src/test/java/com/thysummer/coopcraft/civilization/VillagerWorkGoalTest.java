package com.thysummer.coopcraft.civilization;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagerWorkGoalTest {
	@Test
	void removesOnlyRequestedItems() {
		SimpleContainer inventory = new SimpleContainer(2);
		inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 5));
		inventory.setItem(1, new ItemStack(Items.STONE, 4));

		assertEquals(3, VillagerWorkGoal.removeItems(inventory, Items.COBBLESTONE, 3));
		assertEquals(2, inventory.getItem(0).getCount());
		assertEquals(4, inventory.getItem(1).getCount());
	}

	@Test
	void foragesOnlyWithoutFoodAndAfterCooldown() {
		assertFalse(VillagerWorkGoal.needsForage(true, 0, 0));
		assertTrue(VillagerWorkGoal.needsForage(false, 0, 0));
		assertFalse(VillagerWorkGoal.needsForage(false, 199, 200));
		assertTrue(VillagerWorkGoal.needsForage(false, 200, 200));
	}

	@Test
	void onlyMatureBerryBushesAreHarvestable() {
		BlockState bush = Blocks.SWEET_BERRY_BUSH.defaultBlockState();
		assertFalse(VillagerWorkGoal.isRipeBerryBush(bush.setValue(SweetBerryBushBlock.AGE, 0)));
		assertFalse(VillagerWorkGoal.isRipeBerryBush(bush.setValue(SweetBerryBushBlock.AGE, 1)));
		assertTrue(VillagerWorkGoal.isRipeBerryBush(bush.setValue(SweetBerryBushBlock.AGE, 2)));
		assertTrue(VillagerWorkGoal.isRipeBerryBush(bush.setValue(SweetBerryBushBlock.AGE, 3)));
		assertFalse(VillagerWorkGoal.isRipeBerryBush(Blocks.AIR.defaultBlockState()));
	}
}
