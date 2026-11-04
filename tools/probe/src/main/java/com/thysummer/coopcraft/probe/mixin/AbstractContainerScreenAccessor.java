package com.thysummer.coopcraft.probe.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
	@Accessor("leftPos")
	int probe$leftPos();

	@Accessor("topPos")
	int probe$topPos();

	@Invoker("slotClicked")
	void probe$slotClicked(Slot slot, int slotId, int mouseButton, ClickType type);
}
