package com.thysummer.coopcraft.civilization;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
