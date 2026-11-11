package com.thysummer.coopcraft.automation;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

final class ToolContainer extends SimpleContainer {
	private final Runnable changeListener;

	ToolContainer(Runnable changeListener) {
		super(1);
		this.changeListener = changeListener;
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return isTool(stack);
	}

	@Override
	public int getMaxStackSize() {
		return 1;
	}

	@Override
	public void setChanged() {
		super.setChanged();
		changeListener.run();
	}

	static boolean isTool(ItemStack stack) {
		return !stack.isEmpty() && stack.isDamageableItem();
	}
}
