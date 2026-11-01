package com.thysummer.coopcraft.mixin;

import java.util.Optional;

import com.thysummer.coopcraft.civilization.SettlementSimulationService;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.VillagerMakeLove;
import net.minecraft.world.entity.npc.Villager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VillagerMakeLove.class)
public abstract class VillagerMakeLoveMixin {
	@Inject(method = "breed", at = @At("RETURN"))
	private void coopcraft$recordBirth(
		ServerLevel level,
		Villager firstParent,
		Villager secondParent,
		CallbackInfoReturnable<Optional<Villager>> cir
	) {
		cir.getReturnValue().ifPresent(child -> SettlementSimulationService.recordBirth(level, firstParent, secondParent, child));
	}
}
