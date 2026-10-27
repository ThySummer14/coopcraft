package com.thysummer.coopcraft;

import com.thysummer.coopcraft.automation.Automation;
import com.thysummer.coopcraft.net.CoopNetworking;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CoopCraft implements ModInitializer {
	public static final String MOD_ID = "coopcraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		Automation.register();
		CoopNetworking.registerPayloads();

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof ServerPlayer player) {
				CoopNetworking.broadcastDeathPoint(player);
			}
		});

		// 队友血量每秒推一次。心跳式轮询而不是监听伤害事件：
		// 回血、自然恢复、药水效果都不走伤害路径，只在受伤时推送会看到过期的血条。
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 20 == 0) {
				CoopNetworking.broadcastTeamHealth(server);
			}
		});

		LOGGER.info("CoopCraft initialized");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
