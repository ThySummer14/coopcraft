package com.thysummer.coopcraft.probe;

import com.google.gson.JsonObject;

public final class ProbeJson {
	private ProbeJson() {
	}

	public static JsonObject ok() {
		JsonObject json = new JsonObject();
		json.addProperty("ok", true);
		return json;
	}

	public static JsonObject error(String message) {
		JsonObject json = new JsonObject();
		json.addProperty("ok", false);
		json.addProperty("error", message == null ? "unknown error" : message);
		return json;
	}
}
