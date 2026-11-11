package com.thysummer.coopcraft.probe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
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
			"/world/settlement-data", body -> onServer(server, () -> settlementData(server)),
			"/world/settlement-survey", body -> onServer(server, () -> settlementSurvey(server, body)),
			"/world/resident-goal", body -> onServer(server, () -> residentGoal(server, body)),
			"/world/blueprints", body -> onServer(server, ProbeServerApi::blueprints),
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
		return future.get(20, TimeUnit.SECONDS);
	}

	private static JsonObject settlementSurvey(MinecraftServer server, JsonObject body) {
		if (!body.has("x") || !body.has("y") || !body.has("z")) {
			return ProbeJson.error("world/settlement-survey requires x/y/z");
		}
		ServerLevel level = server.overworld();
		BlockPos center = new BlockPos(body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt());
		try {
			Class<?> stages = Class.forName("com.thysummer.coopcraft.civilization.SettlementStages");
			var survey = stages.getDeclaredMethod("survey", ServerLevel.class, BlockPos.class, int.class, int.class);
			survey.setAccessible(true);
			Object result = survey.invoke(null, level, center, 0, 32);
			JsonObject json = ProbeJson.ok();
			for (String field : List.of("beds", "farmland", "plantedCrops", "wheatCrops", "workstationTypes", "villageStructure", "smithy")) {
				var accessor = result.getClass().getDeclaredMethod(field);
				accessor.setAccessible(true);
				Object value = accessor.invoke(result);
				if (value instanceof Number number) {
					json.addProperty(field, number);
				} else if (value instanceof Boolean bool) {
					json.addProperty(field, bool);
				}
			}
			return json;
		} catch (ReflectiveOperationException error) {
			return ProbeJson.error("Settlement survey unavailable: " + error);
		}
	}

	private static JsonObject settlementData(MinecraftServer server) {
		var factory = new net.minecraft.world.level.saveddata.SavedData.Factory<>(
			ProbeServerApi::emptySavedData,
			(tag, registries) -> emptySavedData(),
			null
		);
		ServerLevel level = server.overworld();
		CompoundTag tag = level.getDataStorage().computeIfAbsent(factory, "coopcraft_settlements")
			.save(new CompoundTag(), level.registryAccess());
		JsonObject json = ProbeJson.ok();
		json.add("data", NbtOps.INSTANCE.convertTo(com.mojang.serialization.JsonOps.INSTANCE, tag));
		return json;
	}

	private static JsonObject residentGoal(MinecraftServer server, JsonObject body) {
		if (!body.has("uuid")) {
			return ProbeJson.error("world/resident-goal requires uuid");
		}
		java.util.UUID villagerId;
		try {
			villagerId = java.util.UUID.fromString(body.get("uuid").getAsString());
		} catch (IllegalArgumentException error) {
			return ProbeJson.error("invalid villager uuid");
		}
		if (!(server.overworld().getEntity(villagerId) instanceof net.minecraft.world.entity.npc.Villager villager)) {
			return ProbeJson.error("villager is not loaded: " + villagerId);
		}
		try {
			Class<?> goalClass = Class.forName("com.thysummer.coopcraft.civilization.VillagerWorkGoal");
			Object status = goalClass.getMethod("statusOf", net.minecraft.world.entity.npc.Villager.class).invoke(null, villager);
			JsonObject json = ProbeJson.ok();
			json.addProperty("uuid", villagerId.toString());
			json.addProperty("action", String.valueOf(status.getClass().getMethod("action").invoke(status)));
			json.addProperty("blocker", String.valueOf(status.getClass().getMethod("blocker").invoke(status)));
			json.addProperty("target", String.valueOf(status.getClass().getMethod("target").invoke(status)));
			JsonArray inventory = new JsonArray();
			for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
				var stack = villager.getInventory().getItem(slot);
				if (stack.isEmpty()) {
					continue;
				}
				JsonObject item = new JsonObject();
				item.addProperty("slot", slot);
				item.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
				item.addProperty("count", stack.getCount());
				inventory.add(item);
			}
			json.add("inventory", inventory);
			Class<?> accessor = Class.forName("com.thysummer.coopcraft.mixin.MobAccessor");
			Object selector = accessor.getMethod("getGoalSelector").invoke(villager);
			for (Object wrapped : (Iterable<?>) selector.getClass().getMethod("getAvailableGoals").invoke(selector)) {
				Object goal = wrapped.getClass().getMethod("getGoal").invoke(wrapped);
				if (goalClass.isInstance(goal)) {
					var debug = goalClass.getDeclaredMethod("debugState");
					debug.setAccessible(true);
					json.addProperty("debug", String.valueOf(debug.invoke(goal)));
				}
			}
			return json;
		} catch (ReflectiveOperationException error) {
			return ProbeJson.error("Resident goal inspection unavailable: " + error);
		}
	}

	private static JsonObject blueprints() {
		try {
			Class<?> manager = Class.forName("com.thysummer.coopcraft.civilization.BlueprintManager");
			JsonObject json = ProbeJson.ok();
			for (String id : new String[] {"campfire_hearth", "stone_cottage"}) {
				Object plan = manager.getMethod("get", net.minecraft.resources.ResourceLocation.class).invoke(
					null, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("coopcraft", id)
				);
				json.addProperty(id, String.valueOf(plan));
			}
			return json;
		} catch (ReflectiveOperationException error) {
			return ProbeJson.error("Blueprint inspection unavailable: " + error);
		}
	}

	private static net.minecraft.world.level.saveddata.SavedData emptySavedData() {
		return new net.minecraft.world.level.saveddata.SavedData() {
			@Override
			public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
				return tag;
			}
		};
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
		json.addProperty("entityTicking", level.isPositionEntityTicking(pos));
		json.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		json.addProperty("state", state.toString());
		if (level.getBlockEntity(pos) != null) {
			json.add("blockEntity", NbtOps.INSTANCE.convertTo(
				com.mojang.serialization.JsonOps.INSTANCE,
				level.getBlockEntity(pos).saveWithoutMetadata(level.registryAccess())
			));
		}
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
