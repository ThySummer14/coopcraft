package com.thysummer.coopcraft.client;

import com.thysummer.coopcraft.CoopCraft;
import com.thysummer.coopcraft.net.AdvancementToastPayload;
import com.thysummer.coopcraft.net.TeamHealthPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.gui.components.toasts.AdvancementToast;

public class CoopCraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(AdvancementToastPayload.TYPE, (payload, context) -> {
			// 必须在客户端主线程构造 toast —— 渲染相关的类不保证线程安全。
			context.client().execute(() ->
				context.client().getToasts().addToast(new AdvancementToast(payload.advancement())));
		});

		ClientPlayNetworking.registerGlobalReceiver(TeamHealthPayload.TYPE, (payload, context) ->
			context.client().execute(() -> TeamHealthHud.update(payload.entries())));

		// 断线时清空，否则退回主菜单会残留上一次的队友血条。
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TeamHealthHud.clear());

		HudRenderCallback.EVENT.register((graphics, tickCounter) -> TeamHealthHud.render(graphics));

		CoordsSharing.register();
		DeathPoints.register();
		SettlementScreens.register();
		ResidentDialogScreens.register();
		SettlementFoodTooltips.register();
		BiomeTitles.register();
		WaypointClient.register();

		// 原版第一人称手部/工具动画：不再接管 ItemInHandRenderer。
		ClientTickEvents.END_CLIENT_TICK.register(client -> CaptureHelper.tick());

		CaptureHelper.initialize();

		CoopCraft.LOGGER.info("CoopCraft client initialized");
	}
}
