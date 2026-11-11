package com.thysummer.coopcraft.mixin;

import java.util.List;
import java.util.Optional;

import com.thysummer.coopcraft.civilization.SettlementVanillaFoodGuard;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla recipes cannot represent this cargo's source clock or preserve its harvest date. */
@Mixin(RecipeManager.class)
public abstract class RecipeManagerFoodAgeMixin {
	@Inject(method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/Optional;",
		at = @At("HEAD"), cancellable = true)
	private <I extends RecipeInput, T extends Recipe<I>> void coopcraft$guardRecipe(
		RecipeType<T> type, I input, Level level, CallbackInfoReturnable<Optional<RecipeHolder<T>>> cir
	) {
		if (SettlementVanillaFoodGuard.containsMarked(input)) cir.setReturnValue(Optional.empty());
	}

	@Inject(method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;",
		at = @At("HEAD"), cancellable = true)
	private <I extends RecipeInput, T extends Recipe<I>> void coopcraft$guardRecipeId(
		RecipeType<T> type, I input, Level level, ResourceLocation id,
		CallbackInfoReturnable<Optional<RecipeHolder<T>>> cir
	) {
		if (SettlementVanillaFoodGuard.containsMarked(input)) cir.setReturnValue(Optional.empty());
	}

	// Cached furnace/campfire checks use this overload. Guard before accepting the cached holder.
	@Inject(method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/crafting/RecipeHolder;)Ljava/util/Optional;",
		at = @At("HEAD"), cancellable = true)
	private <I extends RecipeInput, T extends Recipe<I>> void coopcraft$guardCachedRecipe(
		RecipeType<T> type, I input, Level level, RecipeHolder<T> cached,
		CallbackInfoReturnable<Optional<RecipeHolder<T>>> cir
	) {
		if (SettlementVanillaFoodGuard.containsMarked(input)) cir.setReturnValue(Optional.empty());
	}

	@Inject(method = "getRecipesFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/List;",
		at = @At("HEAD"), cancellable = true)
	private <I extends RecipeInput, T extends Recipe<I>> void coopcraft$guardRecipeList(
		RecipeType<T> type, I input, Level level, CallbackInfoReturnable<List<RecipeHolder<T>>> cir
	) {
		if (SettlementVanillaFoodGuard.containsMarked(input)) cir.setReturnValue(List.of());
	}
}
