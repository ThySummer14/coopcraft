package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.mojang.brigadier.arguments.StringArgumentType;
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
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.phys.AABB;

/** Saves individual consent only. Never shears, consumes tools, creates drops, or schedules work. */
final class SettlementShearingCommands {
	private static final SettlementShearingPreview PREVIEWS = new SettlementShearingPreview();
	private static boolean registered;
	private SettlementShearingCommands() {}
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
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Shearing commands require the server thread");
		boolean mutation = !operation.isEmpty() && !operation.equals("nearby");
		if (mutation && (!source.hasPermission(2) || !player.hasPermissions(2))) {
			PREVIEWS.forget(player.getUUID());
			return failure(source, "permission");
		}
		if (source.getLevel() != level || !source.getPosition().equals(player.position())) {
			if (mutation) PREVIEWS.forget(player.getUUID());
			return failure(source, "actual_position");
		}
		var actor = new SettlementShearingPreview.Actor(player.getUUID(), player, level.dimension().location().toString(),
			player.isAlive() && !player.isSpectator() && level.getServer().getPlayerList().getPlayer(player.getUUID()) == player);
		if (mutation && !actor.eligible()) {
			PREVIEWS.forget(player.getUUID());
			return failure(source, "player_unavailable");
		}
		if (operation.equals("cancel")) {
			var status = PREVIEWS.cancel(player.getUUID()).status();
			if (status != SettlementShearingPreview.Status.CANCELLED) return failure(source, key(status));
			send(source, message(key(status)));
			return 1;
		}
		SettlementSavedData data = SettlementSavedData.get(level);
		Settlement camp = SettlementCommands.nearest(data.all(), player.position()).orElse(null);
		if (camp == null) {
			if (mutation) PREVIEWS.forget(player.getUUID());
			source.sendFailure(Component.translatable("coopcraft.command.settlement.no_settlement"));
			return 0;
		}
		if (operation.isEmpty()) {
			for (Component line : statusMessages(data, camp)) send(source, line);
			return 1;
		}
		if (operation.equals("nearby")) {
			send(source, message("nearby_header"));
			// getEntitiesOfClass queries loaded entity sections only; no chunk-loading calls.
			var candidates = level.getEntitiesOfClass(Sheep.class, player.getBoundingBox().inflate(8),
				sheep -> !sheep.isRemoved() && sheep.distanceToSqr(player) <= 64);
			candidates.sort(Comparator.comparingDouble(sheep -> sheep.distanceToSqr(player)));
			for (Sheep sheep : candidates.stream().limit(8).toList()) send(source, message("candidate", sheep.getUUID().toString(), sheep.getX(), sheep.getY(), sheep.getZ(), sheep.getColor().getName()));
			if (candidates.isEmpty()) send(source, message("nearby_none"));
			send(source, message("scope"));
			return 1;
		}
		if (data.shearingPermitsBlocked()) {
			PREVIEWS.forget(player.getUUID());
			return failure(source, "preserved_unsupported");
		}
		long now = level.getServer().overworld().getGameTime();
		if (operation.equals("confirm")) {
			var result = PREVIEWS.confirm(actor, data, camp, data.shearingRevision(), now,
				id -> target(level, player, data, camp, id), draft -> data.permitShearing(camp, player.getUUID(), draft.sheepId()));
			if (result.saved() == null) return failure(source, key(result.preview().status()));
			var status = result.saved().status();
			if (status != SettlementShearingPermits.Status.CREATED && status != SettlementShearingPermits.Status.REPLACED
				&& status != SettlementShearingPermits.Status.UNCHANGED) return failure(source, key(status));
			send(source, message("saved", result.preview().draft().sheepId().toString()));
			send(source, message("scope"));
			return 1;
		}
		UUID id;
		try { id = parseUuid(StringArgumentType.getString(context, "sheep_uuid")); }
		catch (IllegalArgumentException exception) { PREVIEWS.forget(player.getUUID()); return failure(source, "invalid_uuid"); }
		if (operation.equals("revoke")) {
			PREVIEWS.forget(player.getUUID());
			// Stored records suffice: do not resolve the sheep or require current protection clearance.
			var status = data.revokeShearing(camp, id);
			if (status != SettlementShearingPermits.Status.REMOVED && status != SettlementShearingPermits.Status.NOT_FOUND)
				return failure(source, key(status));
			send(source, message(key(status)));
			return 1;
		}
		if (operation.equals("preview")) {
			var existing = data.shearingPermitOf(id).orElse(null);
			if (existing != null && !existing.settlementId().equals(camp.id())) {
				PREVIEWS.forget(player.getUUID());
				return failure(source, "other_settlement");
			}
			var result = PREVIEWS.preview(actor, data, camp, data.shearingRevision(), target(level, player, data, camp, id), now);
			if (result.status() != SettlementShearingPreview.Status.PREVIEW) return failure(source, key(result.status()));
			Sheep sheep = (Sheep) result.draft().sheepIdentity();
			send(source, message("preview", camp.name(), camp.id().toString(), id.toString(), sheep.getX(), sheep.getY(), sheep.getZ()));
			send(source, message("scope"));
			send(source, message("confirm_help", Math.max(0, (SettlementShearingPreview.TTL_TICKS - (now - result.draft().createdTick()) + 19) / 20)));
			return 1;
		}
		return 0;
	}
	static UUID parseUuid(String value) {
		UUID result = UUID.fromString(value);
		if (!result.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("Expected full UUID");
		return result;
	}
	private static SettlementShearingPreview.Target target(ServerLevel level, ServerPlayer player, SettlementSavedData data, Settlement camp, UUID id) {
		var entity = level.getEntity(id);
		if (!(entity instanceof Sheep sheep)) return null;
		double dx = sheep.getX() - camp.center().getX(), dz = sheep.getZ() - camp.center().getZ();
		boolean eligible = sheep.level() == level && !sheep.isRemoved() && sheep.isAlive() && !sheep.isBaby()
			&& sheep.getColor() == DyeColor.WHITE && sheep.distanceToSqr(player) <= 64 && dx * dx + dz * dz <= 4096
			&& available(level, player.blockPosition()) && available(level, sheep.blockPosition())
			&& withinBorder(level, sheep.getBoundingBox()) && withinBorder(level, player.getBoundingBox())
			&& !protectedBox(data.protectedAreas(), sheep.getBoundingBox());
		return new SettlementShearingPreview.Target(id, sheep, eligible);
	}
	static boolean protectedBox(PlayerProtectedAreas protection, AABB box) {
		if (protection.blocked()) return true;
		for (var area : protection.all()) {
			if (box.maxX >= area.minX() && box.minX <= (double) area.maxX() + 1
				&& box.maxZ >= area.minZ() && box.minZ <= (double) area.maxZ() + 1) return true;
		}
		return false;
	}
	private static boolean available(ServerLevel level, BlockPos pos) {
		return !level.isOutsideBuildHeight(pos) && level.isLoaded(pos) && level.isPositionEntityTicking(pos)
			&& level.getWorldBorder().isWithinBounds(pos);
	}
	private static boolean withinBorder(ServerLevel level, AABB box) {
		var border = level.getWorldBorder();
		return box.minX >= border.getMinX() && box.maxX <= border.getMaxX()
			&& box.minZ >= border.getMinZ() && box.maxZ <= border.getMaxZ();
	}
	static List<Component> statusMessages(SettlementSavedData data, Settlement camp) {
		if (data.shearingPermitsBlocked()) return List.of(message("preserved_unsupported"), message("scope"));
		var lines = new ArrayList<Component>();
		var permits = data.shearingPermitsOf(camp);
		lines.add(message("status", camp.name(), permits.size()));
		for (var permit : permits) lines.add(message("permit", permit.sheepId().toString(), permit.authorizer().toString()));
		lines.add(message("scope"));
		lines.add(message("help"));
		return List.copyOf(lines);
	}
	private static String key(Enum<?> status) { return status.name().toLowerCase(Locale.ROOT); }
	private static Component message(String key, Object... args) { return Component.translatable("coopcraft.command.settlement.shearing_" + key, args); }
	private static void send(CommandSourceStack source, Component line) { source.sendSuccess(() -> line, false); }
	private static int failure(CommandSourceStack source, String key) { source.sendFailure(message(key)); return 0; }
}
