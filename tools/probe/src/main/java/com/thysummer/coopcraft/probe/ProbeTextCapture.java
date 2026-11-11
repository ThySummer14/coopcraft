package com.thysummer.coopcraft.probe;

import java.util.LinkedHashSet;
import java.util.List;

import net.minecraft.client.Minecraft;

public final class ProbeTextCapture {
	private static final int MAX_TEXTS = 500;
	private static final LinkedHashSet<String> TEXTS = new LinkedHashSet<>();
	private static String screenKey = "";

	private ProbeTextCapture() {
	}

	public static synchronized void add(String text) {
		if (text == null || text.isBlank()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		String key = client.screen == null ? "<hud>" : Integer.toHexString(System.identityHashCode(client.screen));
		if (!key.equals(screenKey)) {
			screenKey = key;
			TEXTS.clear();
		}
		if (TEXTS.size() < MAX_TEXTS) {
			TEXTS.add(text);
		}
	}

	public static synchronized List<String> snapshot() {
		return List.copyOf(TEXTS);
	}

	public static synchronized void clear() {
		TEXTS.clear();
	}
}
