package com.thysummer.coopcraft.civilization;

import java.util.function.Predicate;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/** Single-input personal recipes, committed only when the entire output fits after consumption. */
final class SettlementPersonalCrafting {

	private SettlementPersonalCrafting() {
	}

	/** Read-only preview. An external ingredient must fit before it can be consumed. */
	static boolean canCraft(Container inventory, Predicate<ItemStack> ingredient,
		ItemStack externalIngredient, ItemStack output) {
		return preview(inventory, ingredient, externalIngredient, output) != null;
	}

	/**
	 * Rechecks current contents after withdrawal, then commits a complete recipe on the server thread.
	 * As with stockpile transfers, this assumes standard, non-reentrant Container mutation semantics.
	 * Failure leaves any already withdrawn ingredient in the backpack for a later attempt.
	 */
	static boolean craft(Container inventory, Predicate<ItemStack> ingredient, ItemStack output) {
		SimpleContainer result = preview(inventory, ingredient, ItemStack.EMPTY, output);
		if (result == null) {
			return false;
		}
		for (int slot = 0; slot < result.getContainerSize(); slot++) {
			if (!ItemStack.matches(inventory.getItem(slot), result.getItem(slot))) {
				inventory.setItem(slot, result.getItem(slot).copy());
			}
		}
		inventory.setChanged();
		return true;
	}

	private static SimpleContainer preview(Container inventory, Predicate<ItemStack> ingredient,
		ItemStack externalIngredient, ItemStack output) {
		if (output.isEmpty()) {
			return null;
		}
		SimpleContainer result = copy(inventory);
		int inputSlot = firstIngredient(result, ingredient);
		if (inputSlot < 0) {
			if (externalIngredient.isEmpty() || !ingredient.test(externalIngredient)
				|| SettlementStockpile.insert(result, externalIngredient.copyWithCount(1)) != 1) {
				return null;
			}
			inputSlot = firstIngredient(result, ingredient);
		}
		if (inputSlot < 0 || result.removeItem(inputSlot, 1).getCount() != 1) {
			return null;
		}
		return SettlementStockpile.insert(result, output) == output.getCount() ? result : null;
	}

	private static int firstIngredient(Container inventory, Predicate<ItemStack> ingredient) {
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty() && ingredient.test(stack)) {
				return slot;
			}
		}
		return -1;
	}

	private static SimpleContainer copy(Container source) {
		SimpleContainer copy = new SimpleContainer(source.getContainerSize()) {
			@Override
			public boolean canPlaceItem(int slot, ItemStack stack) { return source.canPlaceItem(slot, stack); }
			@Override
			public int getMaxStackSize() { return source.getMaxStackSize(); }
			@Override
			public int getMaxStackSize(ItemStack stack) { return source.getMaxStackSize(stack); }
		};
		for (int slot = 0; slot < source.getContainerSize(); slot++) {
			copy.setItem(slot, source.getItem(slot).copy());
		}
		return copy;
	}
}
