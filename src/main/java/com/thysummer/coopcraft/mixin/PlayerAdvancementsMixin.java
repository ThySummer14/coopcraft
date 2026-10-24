package com.thysummer.coopcraft.mixin;

import com.thysummer.coopcraft.net.CoopNetworking;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在玩家「刚刚完成」一个进度时通知其他队友。
 *
 * <p>注入点选在 {@code award()} 内部唯一的 {@code AdvancementRewards#grant} 调用之后：
 * 这行代码只在「之前没完成、现在完成了」的分支里执行（见 1.21.1 源码
 * {@code if (!wasDone && progress.isDone())}），所以不需要自己判断是不是首次达成，
 * 也不会因为重复调用 {@code award()} 而重复播报。
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {
	@Shadow
	private ServerPlayer player;

	@Inject(
		method = "award",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/advancements/AdvancementRewards;grant(Lnet/minecraft/server/level/ServerPlayer;)V",
			shift = At.Shift.AFTER
		)
	)
	private void coopcraft$broadcastAdvancementToast(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
		CoopNetworking.broadcastAdvancementToast(this.player, holder);
	}
}
