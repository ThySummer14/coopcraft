package com.thysummer.coopcraft.probe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProbeServerApi {
	private static final Logger LOGGER = LoggerFactory.getLogger("coopcraft-probe");

	private ProbeServerApi() {
	}

	public static Map<String, ProbeHttp.Handler> routes(MinecraftServer server) {
		return Map.of(
			"/status", body -> onServer(server, () -> status(server)),
			"/command", body -> onServer(server, () -> command(server, body)),
			"/world/block", body -> onServer(server, () -> block(server, body)),
			"/log/tail", body -> onServer(server, () -> logTail(body))
		);
	}

	private static JsonObject onServer(MinecraftServer server, Supplier<JsonObject> action) throws Exception {
		CompletableFuture<JsonObject> future = new CompletableFuture<>();
		server.execute(() -> {
			try {
				future.complete(action.get());
			} catch (Throwable error) {
				future.completeExceptionally(error);
			}
		});
		return future.get(5, TimeUnit.SECONDS);
	}

	private static JsonObject status(MinecraftServer server) {
		JsonObject json = ProbeJson.ok();
		json.addProperty("tick", server.getTickCount());
		json.addProperty("gameTime", server.overworld().getGameTime());
		json.addProperty("dayTime", server.overworld().getDayTime());
		json.addProperty("running", server.isRunning());
		json.addProperty("players", server.getPlayerList().getPlayerCount());
		json.addProperty("maxPlayers", server.getPlayerList().getMaxPlayers());
		JsonArray names = new JsonArray();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			names.add(player.getName().getString());
		}
		json.add("playerNames", names);
		return json;
	}

	private static JsonObject command(MinecraftServer server, JsonObject body) {
		String command = body.has("cmd") ? body.get("cmd").getAsString()
			: body.has("command") ? body.get("command").getAsString() : "";
		if (command.isBlank()) {
			return ProbeJson.error("empty command");
		}
		List<String> messages = new ArrayList<>();
		int[] result = {0};
		CommandSource source = new CommandSource() {
			@Override
			public void sendSystemMessage(Component message) {
				messages.add(message.getString());
			}

			@Override
			public boolean acceptsSuccess() {
				return true;
			}

			@Override
			public boolean acceptsFailure() {
				return true;
			}

			@Override
			public boolean shouldInformAdmins() {
				return false;
			}
		};
		CommandSourceStack stack = server.createCommandSourceStack()
			.withSource(source)
			.withCallback((CommandResultCallback) (success, value) -> result[0] = value);
		server.getCommands().performPrefixedCommand(stack, command);
		JsonObject json = ProbeJson.ok();
		json.addProperty("command", command);
		json.addProperty("result", result[0]);
		JsonArray output = new JsonArray();
		for (String message : messages) {
			output.add(message);
		}
		json.add("output", output);
		return json;
	}

	private static JsonObject block(MinecraftServer server, JsonObject body) {
		if (!body.has("x") || !body.has("y") || !body.has("z")) {
			return ProbeJson.error("world/block requires x/y/z");
		}
		ServerLevel level = server.overworld();
		BlockPos pos = new BlockPos(body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt());
		BlockState state = level.getBlockState(pos);
		JsonObject json = ProbeJson.ok();
		json.addProperty("x", pos.getX());
		json.addProperty("y", pos.getY());
		json.addProperty("z", pos.getZ());
		json.addProperty("loaded", level.isLoaded(pos));
		json.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		json.addProperty("state", state.toString());
		return json;
	}

	private static JsonObject logTail(JsonObject body) {
		int lines = body.has("n") ? Math.max(1, Math.min(2000, body.get("n").getAsInt())) : 200;
		Path path = Path.of(System.getProperty("user.dir"), "logs", "latest.log");
		if (!Files.exists(path)) {
			return ProbeJson.error("log file not found: " + path.toAbsolutePath());
		}
		try {
			List<String> all = Files.readAllLines(path, StandardCharsets.UTF_8);
			int from = Math.max(0, all.size() - lines);
			JsonArray output = new JsonArray();
			for (String line : all.subList(from, all.size())) {
				output.add(line);
			}
			JsonObject json = ProbeJson.ok();
			json.addProperty("path", path.toAbsolutePath().toString());
			json.addProperty("count", output.size());
			json.add("lines", output);
			return json;
		} catch (Exception error) {
			LOGGER.warn("Failed to read server log", error);
			return ProbeJson.error("failed to read log: " + error.getMessage());
		}
	}
}
