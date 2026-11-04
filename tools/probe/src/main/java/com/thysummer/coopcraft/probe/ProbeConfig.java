package com.thysummer.coopcraft.probe;

public final class ProbeConfig {
	public static final int DEFAULT_CLIENT_PORT = 39002;
	public static final int DEFAULT_SERVER_PORT = 39001;
	public static final String DEFAULT_TOKEN = "coopcraft";

	private ProbeConfig() {
	}

	public static int clientPort() {
		return Integer.getInteger("coopcraft.probe.clientPort", DEFAULT_CLIENT_PORT);
	}

	public static int serverPort() {
		return Integer.getInteger("coopcraft.probe.serverPort", DEFAULT_SERVER_PORT);
	}

	public static String token() {
		return System.getProperty("coopcraft.probe.token", DEFAULT_TOKEN);
	}
}
