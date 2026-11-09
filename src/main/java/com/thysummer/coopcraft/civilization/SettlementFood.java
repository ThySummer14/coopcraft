package com.thysummer.coopcraft.civilization;

import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Food points for the real-inventory settlement loop, without changing vanilla breeding. */
public final class SettlementFood {
	private SettlementFood() {
	}

	public static int points(Item item) {
		// Foraged berries must feed residents even when the optional simulation is disabled.
		// Keep existing vanilla ration values; one berry restores five settlement satiety.
		return item == Items.SWEET_BERRIES ? 1 : Villager.FOOD_POINTS.getOrDefault(item, 0);
	}

	/** Age-marked stock is an explicit simulation handoff, never an ordinary settlement ration. */
	public static int points(ItemStack stack) {
		return stack == null || stack.isEmpty() || SettlementFoodAge.isMarked(stack) ? 0 : points(stack.getItem());
	}
}
