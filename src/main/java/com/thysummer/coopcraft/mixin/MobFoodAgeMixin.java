package com.thysummer.coopcraft.mixin;

import com.thysummer.coopcraft.civilization.SettlementFoodAge;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The final interaction entry point precedes species-specific feeding, healing and breeding. */
@Mixin(Mob.class)
public abstract class MobFoodAgeMixin {
	// This also blocks villager dialogs while holding marked cargo; an unmarked hand is unchanged.
	@Inject(method = "interact(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
		at = @At("HEAD"), cancellable = true)
	private void coopcraft$preventMarkedFoodInteraction(Player player, InteractionHand hand,
		CallbackInfoReturnable<InteractionResult> cir) {
		if (SettlementFoodAge.isMarked(player.getItemInHand(hand))) cir.setReturnValue(InteractionResult.FAIL);
	}
}
