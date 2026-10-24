package com.thysummer.coopcraft.client;

import com.thysummer.coopcraft.CoopCraft;
import com.thysummer.coopcraft.net.CoordsSharedPayload;
import com.thysummer.coopcraft.net.ShareCoordsPayload;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import org.lwjgl.glfw.GLFW;

public final class CoordsSharing {
	private static KeyMapping shareCoordsKey;

	private CoordsSharing() {
	}

	public static void register() {
		shareCoordsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.coopcraft.share_coords",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_V,
			"category.coopcraft"
		));

		ClientPlayNetworking.registerGlobalReceiver(CoordsSharedPayload.TYPE, (payload, context) ->
			context.client().execute(() -> showCoords(context.client(), payload))
		);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (shareCoordsKey.consumeClick()) {
				if (client.player != null
					&& client.screen == null
					&& ClientPlayNetworking.canSend(ShareCoordsPayload.TYPE)) {
					ClientPlayNetworking.send(new ShareCoordsPayload());
				}
			}
		});
	}

	private static void showCoords(Minecraft client, CoordsSharedPayload payload) {
		Component coords = Component.literal(payload.x() + ", " + payload.y() + ", " + payload.z())
			.withStyle(style -> style
				.withColor(ChatFormatting.AQUA)
				.withClickEvent(new ClickEvent(
					ClickEvent.Action.COPY_TO_CLIPBOARD,
					payload.x() + " " + payload.y() + " " + payload.z()
				))
				.withHoverEvent(new HoverEvent(
					HoverEvent.Action.SHOW_TEXT,
					Component.translatable("coopcraft.coords.copy")
				))
			);

		Component message = Component.translatable(
			"coopcraft.coords.shared",
			payload.sender(),
			dimensionName(payload.dimension()),
			coords
		).withStyle(ChatFormatting.GRAY);

		client.gui.getChat().addMessage(message);
		CoopCraft.LOGGER.debug("Received coords from {} in {}", payload.sender(), payload.dimension());
	}

	private static Component dimensionName(String dimension) {
		return switch (dimension) {
			case "minecraft:overworld" -> Component.translatable("coopcraft.dimension.overworld");
			case "minecraft:the_nether" -> Component.translatable("coopcraft.dimension.the_nether");
			case "minecraft:the_end" -> Component.translatable("coopcraft.dimension.the_end");
			default -> Component.literal(dimension);
		};
	}
}
