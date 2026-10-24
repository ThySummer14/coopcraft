package com.thysummer.coopcraft.client;

import net.fabricmc.api.ClientModInitializer;

import com.thysummer.coopcraft.CoopCraft;

public class CoopCraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		CoopCraft.LOGGER.info("CoopCraft client initialized");
	}
}
