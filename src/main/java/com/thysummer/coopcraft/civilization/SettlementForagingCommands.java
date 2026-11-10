package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Explicit, sweet-berry-only consent. Coordinates always come from the actual player, never /execute. */
final class SettlementForagingCommands {
	private static final SettlementForagingPreview PREVIEWS = new SettlementForagingPreview();
	private static boolean registered;

	private SettlementForagingCommands() {}

	static void registerLifecycle() {
		if (registered) return;
		registered = true;
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PREVIEWS.forget(handler.player.getUUID()));
		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> PREVIEWS.forget(player.getUUID()));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> PREVIEWS.forget(oldPlayer.getUUID()));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> PREVIEWS.clear());
	}

	static int execute(CommandContext<CommandSourceStack> context, String operation) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel level = player.serverLevel();
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Foraging commands require the server thread");
		if (source.getLevel() != level) {
			PREVIEWS.forget(player.getUUID());
			return failure(source, message("actual_dimension"));
		}
		if (!operation.isEmpty() && !source.hasPermission(2)) return failure(source, message("permission"));
		var actor = new SettlementForagingPreview.Actor(player.getUUID(), player, level.dimension().location().toString(),
			player.isAlive() && !player.isSpectator() && level.getServer().getPlayerList().getPlayer(player.getUUID()) == player);
		long now = level.getServer().overworld().getGameTime();
		if (operation.equals("cancel")) {
			var result = PREVIEWS.cancel(player.getUUID());
			if (result.status() != SettlementForagingPreview.Status.CANCELLED) return failure(source, statusMessage(result.status()));
			send(source, statusMessage(result.status()));
			return 1;
		}
		SettlementSavedData data = SettlementSavedData.get(level);
		Settlement settlement = SettlementCommands.nearest(data.all(), player.position()).orElse(null);
		if (settlement == null) {
			if (!operation.isEmpty()) PREVIEWS.forget(player.getUUID());
			return failure(source, Component.translatable("coopcraft.command.settlement.no_settlement"));
		}
		if (operation.isEmpty()) {
			for (Component line : statusMessages(data, settlement, actor.dimension())) send(source, line);
			return 1;
		}
		if (!actor.eligible()) {
			PREVIEWS.forget(player.getUUID());
			return failure(source, message("player_unavailable"));
		}
		if (data.foragingAreasBlocked()) {
			PREVIEWS.forget(player.getUUID());
			return failure(source, message("preserved_unsupported"));
		}
		if (operation.equals("preview")) {
			var result = PREVIEWS.preview(actor, settlement, player.blockPosition(), IntegerArgumentType.getInteger(context, "radius"),
				data.foragingAreaOf(settlement).orElse(null), now, (x, z) -> withinBorder(level, x, z));
			if (result.status() != SettlementForagingPreview.Status.PREVIEW) return failure(source, statusMessage(result.status()));
			for (Component line : previewMessages(result.draft(), now)) send(source, line);
			if (data.protectedAreas().blocked()) send(source, message("protection_unavailable"));
			return 1;
		}
		if (operation.equals("confirm")) {
			var result = PREVIEWS.confirm(actor, now, settlement, data.foragingAreaOf(settlement).orElse(null),
				(x, z) -> withinBorder(level, x, z), draft -> data.permitForagingArea(settlement, player.getUUID(), draft.center(), draft.radius()));
			if (result.saved() == null) return failure(source, statusMessage(result.preview().status()));
			var status = result.saved().status();
			if (status != SettlementForagingAreas.Status.CREATED && status != SettlementForagingAreas.Status.REPLACED
				&& status != SettlementForagingAreas.Status.UNCHANGED) return failure(source, dataMessage(status));
			if (status != SettlementForagingAreas.Status.UNCHANGED) PREVIEWS.forgetSettlement(actor.dimension(), settlement.id());
			send(source, dataMessage(status));
			for (Component line : statusMessages(data, settlement, actor.dimension())) send(source, line);
			return 1;
		}
		if (operation.equals("revoke")) {
			PREVIEWS.forget(player.getUUID());
			var status = data.clearForagingArea(settlement);
			if (status != SettlementForagingAreas.Status.REMOVED && status != SettlementForagingAreas.Status.NOT_FOUND) {
				return failure(source, dataMessage(status));
			}
			PREVIEWS.forgetSettlement(actor.dimension(), settlement.id());
			send(source, dataMessage(status));
			return 1;
		}
		return 0;
	}

	static Component summaryMessage(SettlementSavedData data, Settlement settlement) {
		if (data.foragingAreasBlocked() || data.protectedAreas().blocked()) return message("summary_unavailable");
		var area = data.foragingAreaOf(settlement).orElse(null);
		if (area == null) return message("summary_none");
		if (!SettlementForagingAreas.validRange(settlement, new BlockPos(area.centerX(), 0, area.centerZ()), area.radius())) {
			return message("summary_unavailable");
		}
		return message("summary_configured");
	}

	static List<Component> statusMessages(SettlementSavedData data, Settlement settlement, String dimension) {
		if (data.foragingAreasBlocked()) return List.of(message("preserved_unsupported"), message("scope"));
		var lines = new ArrayList<Component>();
		var area = data.foragingAreaOf(settlement).orElse(null);
		if (area == null) lines.add(message("none", settlement.name()));
		else {
			lines.add(message("area", settlement.name(), area.authorizer().toString()));
			lines.add(rangeMessage(dimension, area.centerX(), area.centerZ(), area.radius()));
			if (!SettlementForagingAreas.validRange(settlement, new BlockPos(area.centerX(), 0, area.centerZ()), area.radius())) {
				lines.add(message("invalid_saved_range"));
			}
		}
		if (data.protectedAreas().blocked()) lines.add(message("protection_unavailable"));
		lines.add(message("scope"));
		lines.add(message("help"));
		return List.copyOf(lines);
	}

	static List<Component> previewMessages(SettlementForagingPreview.Draft draft, long now) {
		return List.of(message("preview", draft.settlement().name(), draft.settlement().id().toString()),
			rangeMessage(draft.dimension(), draft.center().getX(), draft.center().getZ(), draft.radius()),
			message(draft.previousArea() == null ? "preview_new" : "preview_replace"), message("scope"),
			message("confirm_help", Math.max(0, (SettlementForagingPreview.TTL_TICKS - (now - draft.createdTick()) + 19) / 20)));
	}

	private static Component rangeMessage(String dimension, int x, int z, int radius) {
		return message("range", dimension, x, z, radius, x - radius, z - radius, x + radius, z + radius);
	}
	private static boolean withinBorder(ServerLevel level, int x, int z) {
		return level.getWorldBorder().isWithinBounds(new BlockPos(x, level.getMinBuildHeight(), z));
	}
	private static Component statusMessage(SettlementForagingPreview.Status status) {
		return message(status.name().toLowerCase(Locale.ROOT));
	}
	private static Component dataMessage(SettlementForagingAreas.Status status) {
		return message(status.name().toLowerCase(Locale.ROOT));
	}
	private static Component message(String key, Object... args) { return Component.translatable("coopcraft.command.settlement.foraging_" + key, args); }
	private static void send(CommandSourceStack source, Component line) { source.sendSuccess(() -> line, false); }
	private static int failure(CommandSourceStack source, Component line) { source.sendFailure(line); return 0; }
}
