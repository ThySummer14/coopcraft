package com.thysummer.coopcraft.civilization;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;

public final class AiPlayers {
	private static final double OBSERVATION_RADIUS_SQUARED = 64.0 * 64.0;
	private static final long OBSERVATION_INTERVAL_TICKS = 40;
	private static final Map<UUID, Long> LAST_OBSERVATIONS = new HashMap<>();

	private AiPlayers() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(Commands.literal("coop")
				.then(Commands.literal("agent")
					.then(Commands.literal("observe").executes(context -> observe(context.getSource().getPlayerOrException())))
				)
			)
		);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LAST_OBSERVATIONS.remove(handler.getPlayer().getUUID()));
	}

	private static int observe(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		SettlementSavedData data = SettlementSavedData.get(level);
		Optional<Settlement> nearest = data.all().stream()
			.filter(settlement -> settlement.center().distSqr(player.blockPosition()) <= OBSERVATION_RADIUS_SQUARED)
			.min(java.util.Comparator.comparingDouble(settlement -> settlement.center().distSqr(player.blockPosition())));
		if (nearest.isEmpty()) {
			player.sendSystemMessage(Component.translatable("coopcraft.agent.no_settlement"));
			return 0;
		}
		long gameTime = level.getGameTime();
		Long previous = LAST_OBSERVATIONS.get(player.getUUID());
		if (previous != null && gameTime - previous < OBSERVATION_INTERVAL_TICKS) {
			player.sendSystemMessage(Component.translatable("coopcraft.agent.cooldown"));
			return 0;
		}

		Settlement settlement = nearest.get();
		CivilizationEras.refresh(level, data, settlement);
		Container stockpile = SettlementStockpile.getStockpile(level, data, settlement);
		List<String> publicAffairs = SettlementCoordination.publicAffairs(level, data, settlement);
		int food = SettlementStockpile.communalFoodPoints(stockpile);
		int resources = SettlementStockpile.communalResourceCount(stockpile);
		String nextStep = Civilization.nextStep(
			stockpile != null, publicAffairs, data.stageOf(settlement), data.getHearth(settlement).isPresent(),
			data.housingCapacity(settlement), data.registeredCount(settlement),
			data.getCraftingStation(settlement).isPresent(), resources
		);
		String json = observationJson(data, settlement, stockpile != null, food, resources, publicAffairs, nextStep).toString();
		player.sendSystemMessage(Component.literal("[CoopCraft AI] " + json));
		LAST_OBSERVATIONS.put(player.getUUID(), gameTime);
		return 1;
	}

	static JsonObject observationJson(
		SettlementSavedData data,
		Settlement settlement,
		boolean stockpilePresent,
		int food,
		int resources,
		List<String> publicAffairs,
		String nextStep
	) {
		JsonObject root = new JsonObject();
		root.addProperty("version", 1);
		root.addProperty("settlement_id", settlement.id().toString());
		root.addProperty("settlement_name", settlement.name());
		root.add("center", position(settlement.center()));
		root.addProperty("era", data.eraOf(settlement).id().toString());
		root.addProperty("origin", data.originOf(settlement).id());
		CivilizationEras.nextMilestone(data, settlement).ifPresentOrElse(
			milestone -> root.addProperty("next_era_milestone", milestone.toString()),
			() -> root.add("next_era_milestone", null)
		);
		root.addProperty("settlement_scale", data.stageOf(settlement).id());
		root.addProperty("registered_residents", data.registeredCount(settlement));
		root.addProperty("housing_beds", data.housingCapacity(settlement));
		root.addProperty("hearth", data.getHearth(settlement).isPresent());
		root.addProperty("crafting_station", data.getCraftingStation(settlement).isPresent());
		root.addProperty("grinding_station", data.getGrindingStation(settlement).isPresent());
		JsonObject stockpile = new JsonObject();
		stockpile.addProperty("present", stockpilePresent);
		stockpile.addProperty("food_points", food);
		stockpile.addProperty("resource_count", resources);
		root.add("stockpile", stockpile);
		JsonArray affairs = new JsonArray();
		publicAffairs.forEach(affairs::add);
		root.add("public_affairs", affairs);
		root.addProperty("next_step", nextStep);
		return root;
	}

	private static JsonObject position(BlockPos pos) {
		JsonObject json = new JsonObject();
		json.addProperty("x", pos.getX());
		json.addProperty("y", pos.getY());
		json.addProperty("z", pos.getZ());
		return json;
	}
}
