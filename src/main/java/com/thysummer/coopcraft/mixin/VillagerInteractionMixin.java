package com.thysummer.coopcraft.mixin;

import com.thysummer.coopcraft.civilization.VillagerInteraction;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Villager.class)
public abstract class VillagerInteractionMixin {
	// Mob.interact has already given name tags and spawn eggs their original priority here.
	@Inject(method = "mobInteract", at = @At("HEAD"), cancellable = true)
	private void coopcraft$routeResidentInteraction(Player player, InteractionHand hand,
		CallbackInfoReturnable<InteractionResult> cir) {
		InteractionResult result = VillagerInteraction.onMobInteract((Villager) (Object) this, player, hand);
		if (result != InteractionResult.PASS) cir.setReturnValue(result);
	}
}
