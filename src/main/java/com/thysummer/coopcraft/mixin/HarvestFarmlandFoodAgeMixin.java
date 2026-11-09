package com.thysummer.coopcraft.mixin;

import com.thysummer.coopcraft.civilization.SettlementVanillaFoodGuard;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.behavior.HarvestFarmland;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(HarvestFarmland.class)
public abstract class HarvestFarmlandFoodAgeMixin {
	// Villager planting directly places a block and shrinks the stack, bypassing item-use hooks.
	@Redirect(method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/Villager;J)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/SimpleContainer;getItem(I)Lnet/minecraft/world/item/ItemStack;"),
		require = 1, allow = 1)
	private ItemStack coopcraft$skipMarkedSeed(SimpleContainer inventory, int slot) {
		return SettlementVanillaFoodGuard.usableFoodView(inventory, slot);
	}
}
