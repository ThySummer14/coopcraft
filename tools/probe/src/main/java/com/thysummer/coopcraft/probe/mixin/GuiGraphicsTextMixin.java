package com.thysummer.coopcraft.probe.mixin;

import com.thysummer.coopcraft.probe.ProbeTextCapture;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GuiGraphics.class)
public class GuiGraphicsTextMixin {
	@Inject(method = "drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I", at = @At("HEAD"))
	private void probe$captureString(Font font, String text, int x, int y, int color, boolean shadow,
		CallbackInfoReturnable<Integer> callback) {
		ProbeTextCapture.add(text);
	}

	@Inject(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I", at = @At("HEAD"))
	private void probe$captureComponent(Font font, Component text, int x, int y, int color, boolean shadow,
		CallbackInfoReturnable<Integer> callback) {
		ProbeTextCapture.add(text.getString());
	}

	@Inject(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)I", at = @At("HEAD"))
	private void probe$captureSequence(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow,
		CallbackInfoReturnable<Integer> callback) {
		ProbeTextCapture.add(probe$sequenceText(text));
	}

	@Inject(method = "drawCenteredString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V", at = @At("HEAD"))
	private void probe$captureCenteredString(Font font, String text, int x, int y, int color, CallbackInfo callback) {
		ProbeTextCapture.add(text);
	}

	@Inject(method = "drawCenteredString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V", at = @At("HEAD"))
	private void probe$captureCenteredComponent(Font font, Component text, int x, int y, int color, CallbackInfo callback) {
		ProbeTextCapture.add(text.getString());
	}

	private static String probe$sequenceText(FormattedCharSequence sequence) {
		StringBuilder builder = new StringBuilder();
		sequence.accept((index, style, codePoint) -> {
			builder.appendCodePoint(codePoint);
			return true;
		});
		return builder.toString();
	}
}
