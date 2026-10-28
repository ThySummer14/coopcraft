package com.thysummer.coopcraft.client;

import com.thysummer.coopcraft.net.SettlementOverviewPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class SettlementScreens {
	private SettlementScreens() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(SettlementOverviewPayload.TYPE, (payload, context) ->
			context.client().execute(() -> context.client().setScreen(new SettlementScreen(payload)))
		);
	}
}
