package com.thysummer.coopcraft.mixin;

import com.thysummer.coopcraft.civilization.SettlementMembershipClaims;
import com.thysummer.coopcraft.civilization.VillagerInteraction;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.Merchant;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MerchantMenu.class)
public abstract class MerchantMenuMixin {
	@Shadow @Final private Merchant trader;

	/** Vanilla validates before handling trade selection/clicks and closes invalid menus on tick.
	 * Its removed() returns input slots; do not return the result preview or compensate manually. */
	@Inject(method = "stillValid", at = @At("HEAD"), cancellable = true)
	private void coopcraft$rejectResidentTrade(Player player, CallbackInfoReturnable<Boolean> cir) {
		if (trader instanceof Villager villager && villager.level() instanceof ServerLevel level
			&& VillagerInteraction.blocksVanillaTrade(SettlementMembershipClaims.resolve(level, villager.getUUID()))) {
			cir.setReturnValue(false);
		}
	}
}
