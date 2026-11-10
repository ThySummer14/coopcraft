package com.thysummer.coopcraft.probe;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProbeClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("coopcraft-probe");

	@Override
	public void onInitializeClient() {
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ProbeHttp.stop());
		try {
			ProbeHttp.start(ProbeConfig.clientPort(), ProbeConfig.token(), ProbeClientApi.routes());
		} catch (Exception error) {
			LOGGER.error("Failed to start CoopCraft client probe", error);
		}
	}
}
