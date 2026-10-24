package com.thysummer.coopcraft;

import com.thysummer.coopcraft.net.CoopNetworking;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.ResourceLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CoopCraft implements ModInitializer {
	public static final String MOD_ID = "coopcraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		CoopNetworking.registerPayloads();
		LOGGER.info("CoopCraft initialized");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
