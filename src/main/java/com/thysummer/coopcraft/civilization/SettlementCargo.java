package com.thysummer.coopcraft.civilization;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Shared classification for physical communal material cargo, not food or personal tools. */
public final class SettlementCargo {
	private SettlementCargo() { }

	public static boolean isMaterial(ItemStack stack) {
		return stack != null && !stack.isEmpty() && !SettlementWorkbenchCargo.isMarked(stack)
			&& (stack.is(ItemTags.LOGS)
			|| stack.is(Items.COBBLESTONE) || stack.is(Items.STICK) || stack.is(Items.SMOOTH_STONE));
	}

	public static boolean hasCargo(Container inventory) {
		if (inventory == null) return false;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (isMaterial(inventory.getItem(slot))) return true;
		}
		return false;
	}
}
