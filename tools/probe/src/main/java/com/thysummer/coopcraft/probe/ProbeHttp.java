package com.thysummer.coopcraft.probe;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProbeHttp {
	private static final Logger LOGGER = LoggerFactory.getLogger("coopcraft-probe");
	private static HttpServer server;
	private static String token = "";

	private ProbeHttp() {
	}

	public interface Handler {
		JsonObject handle(JsonObject body) throws Exception;
	}

	public static synchronized void start(int port, String tokenValue, Map<String, Handler> routes) throws IOException {
		if (server != null) {
			return;
		}
		token = tokenValue == null ? "" : tokenValue;
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		server.setExecutor(Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, "coopcraft-probe-http");
			thread.setDaemon(true);
			return thread;
		}));
		for (Map.Entry<String, Handler> route : routes.entrySet()) {
			server.createContext(route.getKey(), exchange -> handle(exchange, route.getValue()));
		}
		server.start();
		LOGGER.info("Probe HTTP listening on 127.0.0.1:{}", port);
	}

	public static synchronized void stop() {
		if (server != null) {
			server.stop(0);
			server = null;
			LOGGER.info("Probe HTTP stopped");
		}
	}

	private static void handle(HttpExchange exchange, Handler handler) {
		try {
			if (!authorized(exchange)) {
				send(exchange, 401, ProbeJson.error("invalid probe token"));
				return;
			}
			String method = exchange.getRequestMethod();
			if (!"GET".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
				send(exchange, 405, ProbeJson.error("method not allowed"));
				return;
			}
			JsonObject body = new JsonObject();
			String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			if (!raw.isBlank()) {
				try {
					body = JsonParser.parseString(raw).getAsJsonObject();
				} catch (RuntimeException error) {
					send(exchange, 400, ProbeJson.error("invalid json body"));
					return;
				}
			}
			JsonObject result;
			try {
				result = handler.handle(body);
			} catch (Exception error) {
				LOGGER.warn("Probe handler failed", error);
				result = ProbeJson.error(error.getClass().getSimpleName() + ": " + error.getMessage());
			}
			boolean ok = !result.has("ok") || result.get("ok").getAsBoolean();
			send(exchange, ok ? 200 : 400, result);
		} catch (Exception error) {
			LOGGER.warn("Probe request failed", error);
			try {
				send(exchange, 500, ProbeJson.error(error.getClass().getSimpleName() + ": " + error.getMessage()));
			} catch (Exception ignored) {
			}
		} finally {
			exchange.close();
		}
	}

	private static boolean authorized(HttpExchange exchange) {
		if (token.isEmpty()) {
			return true;
		}
		String supplied = exchange.getRequestHeaders().getFirst("X-Probe-Token");
		if (supplied == null) {
			String query = exchange.getRequestURI().getQuery();
			if (query != null) {
				for (String part : query.split("&")) {
					int split = part.indexOf('=');
					if (split > 0 && part.substring(0, split).equals("token")) {
						supplied = URLDecoder.decode(part.substring(split + 1), StandardCharsets.UTF_8);
						break;
					}
				}
			}
		}
		return token.equals(supplied);
	}

	private static void send(HttpExchange exchange, int status, JsonObject body) throws IOException {
		byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.getResponseBody().flush();
	}
}
