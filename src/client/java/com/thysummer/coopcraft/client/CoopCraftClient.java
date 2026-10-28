package com.thysummer.coopcraft.client;

import com.thysummer.coopcraft.CoopCraft;
import com.thysummer.coopcraft.client.anim.ToolAnimationController;
import com.thysummer.coopcraft.net.AdvancementToastPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
		DeathPoints.register();
		SettlementScreens.register();

		// 在这里主动取一次实例，触发类初始化——按键必须在客户端初始化阶段完成注册，
		// 否则不会出现在「选项 → 控制」里，也没办法改键。
		ToolAnimationController animationController = ToolAnimationController.INSTANCE;
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			animationController.tick();
			CaptureHelper.tick();
		});

		CaptureHelper.initialize();

		CoopCraft.LOGGER.info("CoopCraft client initialized");
	}
}
