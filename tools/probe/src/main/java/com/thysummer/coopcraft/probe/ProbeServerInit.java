package com.thysummer.coopcraft.probe;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProbeServerInit implements DedicatedServerModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("coopcraft-probe");

	@Override
	public void onInitializeServer() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			try {
				ProbeHttp.start(ProbeConfig.serverPort(), ProbeConfig.token(), ProbeServerApi.routes(server));
			} catch (Exception error) {
				LOGGER.error("Failed to start CoopCraft server probe", error);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> ProbeHttp.stop());
	}
}
