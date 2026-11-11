package com.thysummer.coopcraft.mixin;

import com.thysummer.coopcraft.civilization.SettlementFoodAge;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Marked food remains logistics cargo until vanilla consumers can validate its source clock. */
@Mixin(ItemStack.class)
public abstract class ItemStackFoodAgeMixin {
	@Inject(method = "use(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResultHolder;",
		at = @At("HEAD"), cancellable = true)
	private void coopcraft$preventMarkedFoodUse(Level level, Player player, InteractionHand hand,
		CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
		ItemStack stack = (ItemStack) (Object) this;
		if (SettlementFoodAge.isMarked(stack)) cir.setReturnValue(InteractionResultHolder.fail(stack));
	}

	// Recheck at completion: components can change after an ordinary use has already started.
	@Inject(method = "finishUsingItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;",
		at = @At("HEAD"), cancellable = true)
	private void coopcraft$preventMarkedFoodFinish(Level level, LivingEntity entity,
		CallbackInfoReturnable<ItemStack> cir) {
		ItemStack stack = (ItemStack) (Object) this;
		if (SettlementFoodAge.isMarked(stack)) cir.setReturnValue(stack);
	}

	// Planting a potato or berry would discard its clock and manufacture an unmarked harvest.
	@Inject(method = "useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;",
		at = @At("HEAD"), cancellable = true)
	private void coopcraft$preventMarkedFoodPlanting(UseOnContext context,
		CallbackInfoReturnable<InteractionResult> cir) {
		ItemStack stack = (ItemStack) (Object) this;
		if (SettlementFoodAge.isMarked(stack)) cir.setReturnValue(InteractionResult.FAIL);
	}
}
