package com.thysummer.coopcraft;

import com.thysummer.coopcraft.automation.Automation;
import com.thysummer.coopcraft.civilization.Civilization;
import com.thysummer.coopcraft.civilization.SettlementSavedData;
import com.thysummer.coopcraft.net.CoopNetworking;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CoopCraft implements ModInitializer {
	public static final String MOD_ID = "coopcraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		Automation.register();
		Civilization.register();
		CoopNetworking.registerPayloads();

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof ServerPlayer player) {
				CoopNetworking.broadcastDeathPoint(player);
			}
			if (entity instanceof Villager villager && entity.level() instanceof ServerLevel serverLevel) {
				SettlementSavedData.get(serverLevel).removeMember(villager.getUUID());
			}
		});

		LOGGER.info("CoopCraft initialized");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
