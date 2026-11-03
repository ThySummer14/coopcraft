package com.thysummer.coopcraft.waypoint;

import java.util.List;
import java.util.Optional;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.thysummer.coopcraft.CoopCraft;
import com.thysummer.coopcraft.net.WaypointAddPayload;
import com.thysummer.coopcraft.net.WaypointListPayload;
import com.thysummer.coopcraft.net.WaypointRemovePayload;
import com.thysummer.coopcraft.waystation.Waystations;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 路径点的服务端入口：收包、发列表、注册命令。
 */
public final class Waypoints {
	private Waypoints() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(WaypointAddPayload.TYPE, (payload, context) ->
			context.server().execute(() -> add(context.player(), payload.name()))
		);

		ServerPlayNetworking.registerGlobalReceiver(WaypointRemovePayload.TYPE, (payload, context) ->
			context.server().execute(() -> remove(context.player(), payload.id()))
		);

		// 新来的玩家要拿到完整的列表，否则地图上一片空白。
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			server.execute(() -> send(handler.getPlayer()))
		);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(Commands.literal("coop")
				.then(Commands.literal("waypoint")
					.then(Commands.literal("add")
						.then(Commands.argument("name", StringArgumentType.greedyString())
							.executes(context -> {
								add(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "name"));
								return 1;
							})
						)
					)
					.then(Commands.literal("remove")
						.then(Commands.argument("name", StringArgumentType.string())
							.suggests((context, builder) -> {
								for (WaypointListPayload.Entry entry : data(context.getSource()).entries()) {
									builder.suggest(entry.name());
								}
								return builder.buildFuture();
							})
							.executes(context -> {
								removeByName(context.getSource(), StringArgumentType.getString(context, "name"));
								return 1;
							})
						)
					)
					.then(Commands.literal("list")
						.executes(context -> {
							for (WaypointListPayload.Entry entry : data(context.getSource()).entries()) {
								context.getSource().sendSuccess(() -> Component.translatable(
									"coopcraft.waypoint.list_entry",
									entry.name(),
									entry.dimension(),
									entry.x(),
									entry.y(),
									entry.z()
								), false);
							}
							return 1;
						})
					)
				)
				.then(Waystations.command())
			)
		);
	}

	private static WaypointSavedData data(CommandSourceStack source) {
		return WaypointSavedData.get(source.getServer());
	}

	private static WaypointSavedData data(MinecraftServer server) {
		return WaypointSavedData.get(server);
	}

	private static void add(ServerPlayer player, String name) {
		WaypointSavedData data = data(player.level().getServer());
		String dimension = player.level().dimension().location().toString();

		if (data.isFull()) {
			player.sendSystemMessage(Component.translatable("coopcraft.waypoint.full", Integer.toString(WaypointSavedData.MAX_WAYPOINTS)));
			return;
		}

		Optional<WaypointListPayload.Entry> near = data.findNear(dimension, player.blockPosition());
		if (near.isPresent()) {
			player.sendSystemMessage(Component.translatable("coopcraft.waypoint.exists", near.get().name()));
			return;
		}

		WaypointListPayload.Entry entry = data.add(name, dimension, player.blockPosition(), player.getScoreboardName());
		player.sendSystemMessage(Component.translatable(
			"coopcraft.waypoint.added",
			entry.name(),
			entry.x(),
			entry.y(),
			entry.z()
		));
		broadcast(player.level().getServer());
		CoopCraft.LOGGER.debug("{} added waypoint {}", player.getScoreboardName(), entry.name());
	}

	private static void remove(ServerPlayer player, int id) {
		WaypointSavedData data = data(player.level().getServer());
		Optional<WaypointListPayload.Entry> entry = data.find(id);
		if (entry.isEmpty()) {
			player.sendSystemMessage(Component.translatable("coopcraft.waypoint.missing"));
			return;
		}
		data.remove(id);
		player.sendSystemMessage(Component.translatable("coopcraft.waypoint.removed", entry.get().name()));
		broadcast(player.level().getServer());
	}

	private static void removeByName(CommandSourceStack source, String name) {
		WaypointSavedData data = data(source);
		List<WaypointListPayload.Entry> matches = data.matching(name).stream()
			.filter(entry -> entry.name().equals(name))
			.toList();
		if (matches.isEmpty()) {
			source.sendFailure(Component.translatable("coopcraft.waypoint.missing_name", name));
			return;
		}
		if (matches.size() > 1) {
			source.sendFailure(Component.translatable("coopcraft.waypoint.ambiguous", name, matches.size()));
			return;
		}
		WaypointListPayload.Entry entry = matches.get(0);
		data.remove(entry.id());
		source.sendSuccess(() -> Component.translatable("coopcraft.waypoint.removed", entry.name()), true);
		broadcast(source.getServer());
	}

	private static void send(ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, WaypointListPayload.TYPE)) {
			return;
		}
		ServerPlayNetworking.send(player, new WaypointListPayload(data(player.level().getServer()).entries()));
	}

	public static void broadcast(MinecraftServer server) {
		if (server == null) {
			return;
		}
		WaypointListPayload payload = new WaypointListPayload(data(server).entries());
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(player, WaypointListPayload.TYPE)) {
				ServerPlayNetworking.send(player, payload);
			}
		}
	}
}
