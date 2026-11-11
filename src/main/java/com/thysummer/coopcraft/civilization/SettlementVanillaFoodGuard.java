package com.thysummer.coopcraft.civilization;

import java.util.Map;

import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeInput;

/** Read-only views for vanilla paths that cannot preserve or validate settlement food age. */
public final class SettlementVanillaFoodGuard {
	private SettlementVanillaFoodGuard() { }

	/** Keep the original reference for ordinary food; never remove or replace a quarantined slot. */
	public static ItemStack usableFoodView(Container inventory, int slot) {
		ItemStack stack = inventory.getItem(slot);
		return SettlementFoodAge.isMarked(stack) ? ItemStack.EMPTY : stack;
	}

	public static boolean containsMarked(Container inventory) {
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (SettlementFoodAge.isMarked(inventory.getItem(slot))) return true;
		}
		return false;
	}

	/** Recipe lookup must not turn marked ingredients into a result without their age components. */
	public static boolean containsMarked(RecipeInput input) {
		for (int slot = 0; slot < input.size(); slot++) {
			if (SettlementFoodAge.isMarked(input.getItem(slot))) return true;
		}
		return false;
	}

	/** Uses the caller's vanilla values, including ordinary customized food; no age clock is read. */
	public static int foodPoints(Container inventory, Map<Item, Integer> values) {
		int points = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = usableFoodView(inventory, slot);
			if (!stack.isEmpty()) points += values.getOrDefault(stack.getItem(), 0) * stack.getCount();
		}
		return points;
	}
}
