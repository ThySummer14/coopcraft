package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/** Dimension-local /coop protect commands; no settlement, chunk scan, or land-ownership claim. */
public final class PlayerProtectionCommands {
	static final int PAGE_SIZE = 16;
	private static final PlayerProtectionSelection SELECTIONS = new PlayerProtectionSelection();
	private static boolean registered;

	private PlayerProtectionCommands() {}

	public static void register() {
		if (registered) return;
		registered = true;
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
			dispatcher.register(PlayerProtectionCommands.<CommandSourceStack>commandTree(
				source -> eligible(source.getPlayer()), PlayerProtectionCommands::execute)));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 20 == 0) pruneSelections(server);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SELECTIONS.forget(handler.player.getUUID()));
		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
			if (SELECTIONS.cancel(player.getUUID(), now(player.serverLevel().getServer())).status() == PlayerProtectionSelection.Status.CANCELLED) {
				player.sendSystemMessage(statusMessage(PlayerProtectionSelection.Status.DIMENSION_CHANGED));
			}
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (SELECTIONS.cancel(oldPlayer.getUUID(), now(newPlayer.serverLevel().getServer())).status()
				== PlayerProtectionSelection.Status.CANCELLED) newPlayer.sendSystemMessage(message("player_unavailable"));
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> SELECTIONS.clear());
	}

	@FunctionalInterface
	interface Handler<S> {
		int run(CommandContext<S> context, String action) throws CommandSyntaxException;
	}

	/** Tests execute the real grammar/requirements using plain sources without a running server. */
	static <S> LiteralArgumentBuilder<S> commandTree(Predicate<S> player, Handler<S> handler) {
		var protect = LiteralArgumentBuilder.<S>literal("protect")
			.executes(ctx -> handler.run(ctx, "help"));
		protect.then(LiteralArgumentBuilder.<S>literal("start").requires(player)
			.then(RequiredArgumentBuilder.<S, String>argument("name", StringArgumentType.string())
				.executes(ctx -> handler.run(ctx, "start"))));
		for (String action : List.of("confirm", "cancel")) {
			protect.then(LiteralArgumentBuilder.<S>literal(action).requires(player)
				.executes(ctx -> handler.run(ctx, action)));
		}
		protect.then(LiteralArgumentBuilder.<S>literal("list")
			.executes(ctx -> handler.run(ctx, "list"))
			.then(RequiredArgumentBuilder.<S, Integer>argument("page", IntegerArgumentType.integer(1, 8))
				.executes(ctx -> handler.run(ctx, "list"))));
		for (String action : List.of("show", "remove")) {
			var operation = LiteralArgumentBuilder.<S>literal(action);
			if (action.equals("remove")) operation.requires(player);
			protect.then(operation.then(RequiredArgumentBuilder.<S, String>argument("id", StringArgumentType.word())
				.executes(ctx -> handler.run(ctx, action))));
		}
		return LiteralArgumentBuilder.<S>literal("coop").then(protect);
	}

	/**
	 * Call FIRST in Civilization.onUseBlock, before reading block state or any older marking path.
	 * PASS means no server-side selection; every other result MUST be returned unchanged by the caller.
	 * Offhand, non-selection gestures, and a draft invalidated by this click return FAIL, never PASS.
	 */
	public static InteractionResult interceptUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		requireServerThread(serverLevel.getServer());
		var result = SELECTIONS.click(actor(serverPlayer), now(serverLevel.getServer()),
			hand == InteractionHand.MAIN_HAND, player.isShiftKeyDown(), player.getMainHandItem().isEmpty(),
			hit.getBlockPos().getX(), hit.getBlockPos().getZ(),
			(x, z) -> serverLevel.isLoaded(hit.getBlockPos()) && withinBorder(serverLevel, x, z));
		if (!result.consumesClick()) return InteractionResult.PASS;
		// Offhand packets can follow the main-hand click. Consume silently and never choose a corner.
		if (hand != InteractionHand.MAIN_HAND || result.status() == PlayerProtectionSelection.Status.CLOSED_GESTURE) {
			return InteractionResult.FAIL;
		}
		for (Component line : selectionMessages(result, serverLevel.getMinBuildHeight(), serverLevel.getMaxBuildHeight() - 1)) {
			serverPlayer.sendSystemMessage(line);
		}
		return result.status() == PlayerProtectionSelection.Status.FIRST_CORNER
			|| result.status() == PlayerProtectionSelection.Status.PREVIEW ? InteractionResult.CONSUME : InteractionResult.FAIL;
	}

	private static int execute(CommandContext<CommandSourceStack> context, String action) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		requireServerThread(source.getServer());
		ServerPlayer player = source.getPlayer();
		ServerLevel level = player == null ? source.getLevel() : player.serverLevel();
		if (player != null && !List.of("start", "confirm", "cancel").contains(action)) {
			var checked = SELECTIONS.inspect(actor(player), now(source.getServer()));
			if (checked.status() == PlayerProtectionSelection.Status.EXPIRED
				|| checked.status() == PlayerProtectionSelection.Status.DIMENSION_CHANGED
				|| checked.status() == PlayerProtectionSelection.Status.PLAYER_UNAVAILABLE) send(source, statusMessage(checked.status()));
		}
		if (action.equals("help")) {
			send(source, message("help"));
			send(source, message("scope"));
			return 1;
		}
		if (action.equals("cancel")) {
			if (!eligible(player)) return failure(source, message("player_unavailable"));
			var result = SELECTIONS.cancel(player.getUUID(), now(player.serverLevel().getServer()));
			if (result.status() == PlayerProtectionSelection.Status.NO_SELECTION) return failure(source, statusMessage(result.status()));
			send(source, statusMessage(result.status()));
			return 1;
		}
		SettlementSavedData data = SettlementSavedData.get(level);
		if (action.equals("list")) {
			if (data.protectedAreas().blocked()) return failure(source, message("data_unavailable"));
			for (Component line : listMessages(data.protectedAreas(), level.dimension().location().toString(), page(context))) send(source, line);
			return 1;
		}
		if (action.equals("show") || action.equals("remove")) {
			UUID id = parseId(StringArgumentType.getString(context, "id"));
			if (id == null) return failure(source, message("invalid_id"));
			if (data.protectedAreas().blocked()) return failure(source, message("data_unavailable"));
			if (action.equals("show")) {
				var area = data.protectedAreas().find(id).orElse(null);
				if (area == null) return failure(source, message("not_found"));
				for (Component line : areaMessages(area, level.dimension().location().toString(),
					level.getMinBuildHeight(), level.getMaxBuildHeight() - 1)) send(source, line);
				return 1;
			}
			if (!eligible(player)) return failure(source, message("player_unavailable"));
			// Both current operator status and the stored creator are rechecked for every removal.
			var status = data.removeProtectedArea(id, player.getUUID(), source.hasPermission(2));
			if (status != PlayerProtectedAreas.Status.REMOVED) return failure(source, dataStatusMessage(status));
			send(source, message("removed", id.toString()));
			return 1;
		}
		if (!eligible(player)) return failure(source, message("player_unavailable"));
		if (action.equals("start")) {
			pruneSelections(source.getServer());
			var areas = data.protectedAreas();
			if (areas.blocked()) return failure(source, message("data_unavailable"));
			if (areas.size() >= PlayerProtectedAreas.MAX_AREAS) return failure(source, message("dimension_limit"));
			if (areas.ownedCount(player.getUUID()) >= PlayerProtectedAreas.MAX_PER_OWNER) return failure(source, message("owner_limit"));
			var result = SELECTIONS.start(actor(player), StringArgumentType.getString(context, "name"), now(source.getServer()));
			if (result.status() != PlayerProtectionSelection.Status.STARTED) return failure(source, statusMessage(result.status()));
			send(source, message("started", result.draft().name()));
			send(source, message("scope"));
			return 1;
		}
		if (action.equals("confirm")) {
			var result = SELECTIONS.confirm(actor(player), now(source.getServer()),
				(x, z) -> withinBorder(player.serverLevel(), x, z),
				(owner, name, bounds) -> SettlementSavedData.get(player.serverLevel()).addProtectedArea(owner, name,
					bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ()));
			if (result.saved() == null) return failure(source, statusMessage(result.selection().status()));
			if (result.saved().status() != PlayerProtectedAreas.Status.ADDED) return failure(source, dataStatusMessage(result.saved().status()));
			send(source, message("added", result.saved().area().name(), result.saved().area().id().toString()));
			for (Component line : areaMessages(result.saved().area(), level.dimension().location().toString(),
				level.getMinBuildHeight(), level.getMaxBuildHeight() - 1)) send(source, line);
			return 1;
		}
		return 0;
	}

	static List<Component> selectionMessages(PlayerProtectionSelection.Result result, int minY, int maxY) {
		var draft = result.draft();
		if (result.status() == PlayerProtectionSelection.Status.FIRST_CORNER) {
			return List.of(message("first_corner", draft.first().x(), draft.first().z()));
		}
		if (result.status() == PlayerProtectionSelection.Status.PREVIEW) {
			var bounds = draft.bounds();
			return List.of(message("preview", draft.name()), rangeMessage(draft.dimension(), bounds, minY, maxY),
				message("scope"), message("confirm_help"));
		}
		return List.of(statusMessage(result.status()));
	}

	static List<Component> areaMessages(PlayerProtectedAreas.Area area, String dimension, int minY, int maxY) {
		return List.of(message("area", area.name(), area.id().toString(), area.ownerId().toString()),
			rangeMessage(dimension, new PlayerProtectionSelection.Bounds(area.minX(), area.minZ(), area.maxX(), area.maxZ()), minY, maxY),
			message("scope"));
	}

	static List<Component> listMessages(PlayerProtectedAreas areas, String dimension, int requestedPage) {
		int pages = Math.max(1, (areas.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		int page = Math.min(Math.max(1, requestedPage), pages);
		var messages = new ArrayList<Component>();
		messages.add(message("list", dimension, areas.size(), page, pages));
		for (var area : areas.all().subList((page - 1) * PAGE_SIZE, Math.min(page * PAGE_SIZE, areas.size()))) {
			messages.add(message("list_entry", area.id().toString(), area.name(), area.ownerId().toString(),
				area.minX(), area.minZ(), area.maxX(), area.maxZ()));
		}
		messages.add(message("scope"));
		return List.copyOf(messages);
	}

	private static Component rangeMessage(String dimension, PlayerProtectionSelection.Bounds bounds, int minY, int maxY) {
		return message("range", dimension, bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ(),
			bounds.width(), bounds.depth(), minY, maxY);
	}

	static UUID parseId(String raw) {
		if (raw == null || raw.length() != 36) return null;
		try {
			UUID id = UUID.fromString(raw);
			return id.toString().equalsIgnoreCase(raw) ? id : null;
		} catch (IllegalArgumentException invalid) { return null; }
	}

	private static <S> int page(CommandContext<S> context) {
		try { return IntegerArgumentType.getInteger(context, "page"); }
		catch (IllegalArgumentException missing) { return 1; }
	}

	private static boolean eligible(ServerPlayer player) {
		return player != null && player.isAlive() && !player.isSpectator()
			&& player.getServer() != null && player.getServer().getPlayerList().getPlayer(player.getUUID()) == player;
	}

	private static PlayerProtectionSelection.Actor actor(ServerPlayer player) {
		return player == null ? null : new PlayerProtectionSelection.Actor(player.getUUID(),
			player.serverLevel().dimension().location().toString(), eligible(player));
	}

	private static boolean withinBorder(ServerLevel level, int x, int z) {
		return level.getWorldBorder().isWithinBounds(new BlockPos(x, level.getMinBuildHeight(), z));
	}

	private static void pruneSelections(MinecraftServer server) {
		for (var closed : SELECTIONS.prune(now(server), id -> actor(server.getPlayerList().getPlayer(id)))) {
			ServerPlayer player = server.getPlayerList().getPlayer(closed.playerId());
			if (player != null) player.sendSystemMessage(statusMessage(closed.reason()));
		}
	}

	private static long now(MinecraftServer server) { return server.overworld().getGameTime(); }
	private static void requireServerThread(MinecraftServer server) {
		if (!server.isSameThread()) throw new IllegalStateException("Protection commands require the server thread");
	}
	private static Component statusMessage(PlayerProtectionSelection.Status status) {
		return message(status.name().toLowerCase(Locale.ROOT));
	}
	private static Component dataStatusMessage(PlayerProtectedAreas.Status status) {
		return message(status.name().toLowerCase(Locale.ROOT));
	}
	static Component message(String key, Object... args) { return Component.translatable("coopcraft.command.protect." + key, args); }
	private static void send(CommandSourceStack source, Component line) { source.sendSuccess(() -> line, false); }
	private static int failure(CommandSourceStack source, Component line) { source.sendFailure(line); return 0; }
}
