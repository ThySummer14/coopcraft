package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.function.BooleanSupplier;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/** Shared all-or-nothing inventory boundary for food harvesting. */
final class SettlementHarvestCommit {
	private SettlementHarvestCommit() {}

	/** Complete insertion plan, then successful world mutation, then inventory commit. No overflow drops.
	 * Scope: standard non-reentrant SimpleContainer on the server thread, not crash atomicity. */
	static boolean commit(SimpleContainer inventory, List<ItemStack> output, BooleanSupplier changeWorld) {
		SimpleContainer planned = new SimpleContainer(inventory.getContainerSize());
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) planned.setItem(slot, inventory.getItem(slot).copy());
		for (ItemStack stack : output) {
			if (!planned.addItem(stack.copy()).isEmpty()) return false;
		}
		if (!changeWorld.getAsBoolean()) return false;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) inventory.setItem(slot, planned.getItem(slot).copy());
		inventory.setChanged();
		return true;
	}
}
