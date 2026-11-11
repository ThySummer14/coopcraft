package com.thysummer.coopcraft.mixin;

import com.thysummer.coopcraft.civilization.SettlementCargoRecovery;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The committed death precedes zombie conversion, loot rules, vanishing and equipment randomization. */
@Mixin(LivingEntity.class)
public abstract class VillagerLoanDeathMixin {
	@Inject(method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V",
		at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;dead:Z",
			opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER), require = 1, allow = 1)
	private void coopcraft$preserveCommittedVillagerDeath(DamageSource source, CallbackInfo ci) {
		if ((Object) this instanceof Villager villager && villager.level() instanceof ServerLevel level) {
			SettlementCargoRecovery.captureBeforeDeath(level, villager);
		}
	}
}
