package com.thysummer.coopcraft.client;

import com.thysummer.coopcraft.CoopCraft;
import com.thysummer.coopcraft.net.DeathPointPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class DeathPoints {
	private DeathPoints() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(DeathPointPayload.TYPE, (payload, context) ->
			context.client().execute(() -> {
				LocationChat.show(
					context.client(),
					"coopcraft.death.shared",
					payload.player(),
					payload.dimension(),
					payload.x(),
					payload.y(),
					payload.z(),
					"coopcraft.death.copy"
				);
				CoopCraft.LOGGER.debug("Received death point of {} in {}", payload.player(), payload.dimension());
			})
		);
	}
}
