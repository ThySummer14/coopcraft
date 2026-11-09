package com.thysummer.coopcraft.mixin;

import com.thysummer.coopcraft.civilization.SettlementVanillaFoodGuard;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.behavior.TradeWithVillager;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(TradeWithVillager.class)
public abstract class TradeWithVillagerFoodAgeMixin {
	// Vanilla shrinks the donor then creates a new ItemStack(item, count), dropping age components.
	@Redirect(method = "throwHalfStack(Lnet/minecraft/world/entity/npc/Villager;Ljava/util/Set;Lnet/minecraft/world/entity/LivingEntity;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/SimpleContainer;getItem(I)Lnet/minecraft/world/item/ItemStack;"),
		require = 1, allow = 1)
	private static ItemStack coopcraft$skipMarkedGift(SimpleContainer inventory, int slot) {
		return SettlementVanillaFoodGuard.usableFoodView(inventory, slot);
	}
}
