package com.thysummer.coopcraft.mixin;

import com.thysummer.coopcraft.civilization.SettlementShearsEquipment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla Brain display tasks must not overwrite a real public tool between work-goal ticks. */
@Mixin(Mob.class)
public abstract class MobShearsEquipmentMixin {
	@Inject(method = "setItemSlot(Lnet/minecraft/world/entity/EquipmentSlot;Lnet/minecraft/world/item/ItemStack;)V",
		at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
	private void coopcraft$protectBorrowedShears(EquipmentSlot slot, ItemStack replacement, CallbackInfo ci) {
		// Client equipment packets are authoritative server state, never a competing Brain display.
		if ((Object) this instanceof Villager villager && !villager.level().isClientSide && slot == EquipmentSlot.MAINHAND
			&& SettlementShearsEquipment.blocksMainHandChange(villager, villager.getMainHandItem(), replacement)) ci.cancel();
	}
}
