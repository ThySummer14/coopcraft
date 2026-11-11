package com.thysummer.coopcraft.mixin;

import com.thysummer.coopcraft.civilization.SettlementFoodAge;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Guard direct eat calls as well as normal item use without consuming or rewriting the stack. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityFoodAgeMixin {
	@Inject(method = "eat(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;",
		at = @At("HEAD"), cancellable = true)
	private void coopcraft$preventMarkedFoodEat(Level level, ItemStack stack,
		CallbackInfoReturnable<ItemStack> cir) {
		if (SettlementFoodAge.isMarked(stack)) cir.setReturnValue(stack);
	}

	@Inject(method = "eat(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/food/FoodProperties;)Lnet/minecraft/world/item/ItemStack;",
		at = @At("HEAD"), cancellable = true)
	private void coopcraft$preventMarkedFoodEffects(Level level, ItemStack stack, FoodProperties food,
		CallbackInfoReturnable<ItemStack> cir) {
		if (SettlementFoodAge.isMarked(stack)) cir.setReturnValue(stack);
	}
}
