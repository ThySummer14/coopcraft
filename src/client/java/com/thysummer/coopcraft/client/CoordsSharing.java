package com.thysummer.coopcraft.client;

import com.thysummer.coopcraft.CoopCraft;
import com.thysummer.coopcraft.net.CoordsSharedPayload;
import com.thysummer.coopcraft.net.ShareCoordsPayload;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;

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
			context.client().execute(() -> {
				LocationChat.show(
					context.client(),
					"coopcraft.coords.shared",
					payload.sender(),
					payload.dimension(),
					payload.x(),
					payload.y(),
					payload.z(),
					"coopcraft.coords.copy"
				);
				CoopCraft.LOGGER.debug("Received coords from {} in {}", payload.sender(), payload.dimension());
			})
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
}
