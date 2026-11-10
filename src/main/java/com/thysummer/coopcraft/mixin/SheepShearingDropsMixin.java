package com.thysummer.coopcraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.thysummer.coopcraft.civilization.SettlementShearingDrops;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Only the vanilla sheep shear expression, never every Entity.spawnAtLocation return. */
@Mixin(Sheep.class)
public abstract class SheepShearingDropsMixin {
	@WrapMethod(method = "shear(Lnet/minecraft/sounds/SoundSource;)V")
	private void coopcraft$trackDirectShear(SoundSource source, Operation<Void> original) {
		Sheep sheep = (Sheep) (Object) this;
		SettlementShearingDrops.enter(sheep);
		boolean completed = false;
		try {
			original.call(source);
			completed = true;
		} finally {
			SettlementShearingDrops.exit(sheep, completed);
		}
	}

	@ModifyExpressionValue(method = "shear(Lnet/minecraft/sounds/SoundSource;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/Sheep;spawnAtLocation(Lnet/minecraft/world/level/ItemLike;I)Lnet/minecraft/world/entity/item/ItemEntity;"),
		require = 1, allow = 1)
	private ItemEntity coopcraft$captureActualShearedWool(ItemEntity original) {
		return SettlementShearingDrops.capture((Sheep) (Object) this, original);
	}
}
