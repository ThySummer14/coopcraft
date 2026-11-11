package com.thysummer.coopcraft.mixin;

import com.thysummer.coopcraft.civilization.SettlementFoodAge;
import com.thysummer.coopcraft.civilization.SettlementVanillaFoodGuard;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Villager.class)
public abstract class VillagerFoodAgeMixin {
	@Inject(method = "wantsToPickUp(Lnet/minecraft/world/item/ItemStack;)Z",
		at = @At("HEAD"), cancellable = true)
	private void coopcraft$rejectMarkedPickup(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (SettlementFoodAge.isMarked(stack)) cir.setReturnValue(false);
	}

	@Inject(method = "countFoodPointsInInventory()I", at = @At("HEAD"), cancellable = true)
	private void coopcraft$excludeMarkedFoodPoints(CallbackInfoReturnable<Integer> cir) {
		SimpleContainer inventory = ((Villager) (Object) this).getInventory();
		if (SettlementVanillaFoodGuard.containsMarked(inventory)) {
			cir.setReturnValue(SettlementVanillaFoodGuard.foodPoints(inventory, Villager.FOOD_POINTS));
		}
	}

	// Filtering only this read leaves marked cargo in place and lets later ordinary slots feed normally.
	@Redirect(method = "eatUntilFull()V", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/SimpleContainer;getItem(I)Lnet/minecraft/world/item/ItemStack;"),
		require = 1, allow = 1)
	private ItemStack coopcraft$skipMarkedMeal(SimpleContainer inventory, int slot) {
		return SettlementVanillaFoodGuard.usableFoodView(inventory, slot);
	}
}
