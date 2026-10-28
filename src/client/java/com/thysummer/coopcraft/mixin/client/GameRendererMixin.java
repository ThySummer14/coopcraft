package com.thysummer.coopcraft.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.thysummer.coopcraft.client.CaptureHelper;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在第一人称手部绘制结束之后抓帧。
 *
 * <p>{@code WorldRenderEvents.END} 在这之前触发——那时世界画完了，但
 * {@code GameRenderer#renderItemInHand} 还没跑，所以抓到的图里没有手也没有武器，
 * 用来调姿态完全没有意义。这个注入点在 {@code renderItemInHand} 返回处，
 * 此时世界和手都已经进了帧缓冲，而 GUI 还没画，正好是"只有手和武器"的画面。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "renderItemInHand", at = @At("RETURN"))
	private void coopcraft$captureAfterHands(Camera camera, float partialTick, Matrix4f projection, CallbackInfo ci) {
		CaptureHelper.onAfterHands();
	}
}
