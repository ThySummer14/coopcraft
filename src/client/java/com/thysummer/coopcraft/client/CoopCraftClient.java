package com.thysummer.coopcraft.client;

import com.thysummer.coopcraft.CoopCraft;
import com.thysummer.coopcraft.net.AdvancementToastPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.components.toasts.AdvancementToast;

public class CoopCraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(AdvancementToastPayload.TYPE, (payload, context) -> {
			// 必须在客户端主线程构造 toast —— 渲染相关的类不保证线程安全。
			context.client().execute(() ->
				context.client().getToasts().addToast(new AdvancementToast(payload.advancement())));
		});

		CoordsSharing.register();

		CoopCraft.LOGGER.info("CoopCraft client initialized");
	}
}
