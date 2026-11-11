package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.UUID;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Explicit local release of evidenced dead loans. The player must carry the actual tools home. */
final class SettlementShearsReclaimCommands {
	static final int MAX_DIMENSIONS = 32;
	private SettlementShearsReclaimCommands() { }

	static int execute(CommandContext<CommandSourceStack> context, String operation) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel level = player.serverLevel();
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Shears reclaim commands require the server thread");
		boolean mutation = operation.equals("reclaim-shears");
		if (!mutation && !operation.equals("shears")) return 0;
		if (mutation && (!source.hasPermission(2) || !player.hasPermissions(2))) return commonFailure(source, "permission");
		if (source.getLevel() != level || !source.getPosition().equals(player.position())) return failure(source, "actual_position");
		if (!actualPlayer(source, player, level, mutation)) return failure(source, "player_unavailable");
		UUID recordId = parseId(StringArgumentType.getString(context, "cargo_record"));
		if (recordId == null) return commonFailure(source, "cargo_invalid");
		SettlementCargoRecovery recovery = SettlementCargoRecovery.get(level);
		if (!recovery.available()) return commonFailure(source, "cargo_unavailable");
		var snapshot = recovery.inspect(recordId).orElse(null);
		if (snapshot == null) return commonFailure(source, "cargo_invalid");
		SettlementSavedData data = SettlementSavedData.get(level);
		if (!mutation) return inspect(source, level, data, recovery, recordId);

		UUID settlementId = parseId(StringArgumentType.getString(context, "cargo_settlement"));
		Settlement camp = settlementId == null ? null : data.settlementById(settlementId);
		if (!data.canonicalSettlement(camp)) return failure(source, "settlement_invalid");
		BlockPos position = snapshot.position();
		if (!atRecord(level, player, position)) return failure(source, "not_at_place");
		if (!borrowerAbsent(level, data, snapshot.deceasedId())) return failure(source, "borrower_present");
		var result = recovery.reclaimShears(recordId, level.dimension().location(), camp.id(),
			new SettlementCargoCommands.MainInventory(player.getInventory()),
			() -> authority(source, player, level, recovery, data, camp, recordId, snapshot.deceasedId(), position));
		if (result.moved() > 0) {
			source.sendSuccess(() -> message("recovered", camp.name(), result.moved(), result.remaining()), true);
			return result.moved();
		}
		return switch (result.status()) {
			case UNAVAILABLE -> commonFailure(source, "cargo_unavailable");
			case INVALID -> failure(source, "invalid");
			case NO_CAPACITY -> failure(source, "no_capacity");
			case NOT_AT_PLACE, RECOVERED -> failure(source, "changed");
		};
	}

	/** Reads stored evidence only. Neither inspection nor its command grammar grants a release. */
	private static int inspect(CommandSourceStack source, ServerLevel level, SettlementSavedData data,
		SettlementCargoRecovery recovery, UUID recordId) {
		var claims = recovery.reclaimableShears(recordId, level.dimension().location());
		if (claims.isEmpty()) source.sendSuccess(() -> message("none"), false);
		for (var claim : claims) {
			Settlement camp = data.settlementById(claim.camp());
			if (data.canonicalSettlement(camp))
				source.sendSuccess(() -> message("row", camp.name(), claim.camp().toString(), claim.count()), false);
			else source.sendSuccess(() -> message("camp_unavailable", claim.camp().toString(), claim.count()), false);
		}
		source.sendSuccess(() -> message("scope"), false);
		if (!claims.isEmpty()) source.sendSuccess(() -> message("hint", recordId.toString()), false);
		return claims.size();
	}

	/** Re-evaluated by the custody transfer immediately before every mutation boundary. */
	private static boolean authority(CommandSourceStack source, ServerPlayer player, ServerLevel level,
		SettlementCargoRecovery recovery, SettlementSavedData data, Settlement camp, UUID recordId,
		UUID deceasedId, BlockPos position) {
		try {
			if (!actualPlayer(source, player, level, true) || !atRecord(level, player, position)
				|| SettlementCargoRecovery.get(level) != recovery || !recovery.available()
				|| SettlementSavedData.get(level) != data || !data.canonicalSettlement(camp)) return false;
			var current = recovery.inspect(recordId).orElse(null);
			return current != null && current.deceasedId().equals(deceasedId) && current.position().equals(position)
				&& borrowerAbsent(level, data, deceasedId);
		} catch (RuntimeException incompleteRead) {
			return false;
		}
	}

	private static boolean actualPlayer(CommandSourceStack source, ServerPlayer player, ServerLevel level, boolean mutation) {
		return level.getServer().isSameThread() && source.getEntity() == player && player.level() == level
			&& source.getLevel() == level && source.getPosition().equals(player.position())
			&& playerAllowed(mutation, source.hasPermission(2), player.hasPermissions(2),
				level.getServer().getPlayerList().getPlayer(player.getUUID()) == player
					&& player.connection != null && player.connection.isAcceptingMessages(),
				player.isAlive(), player.isRemoved(), player.isSpectator());
	}

	static boolean playerAllowed(boolean mutation, boolean sourceOperator, boolean playerOperator,
		boolean onlineIdentity, boolean alive, boolean removed, boolean spectator) {
		return (!mutation || sourceOperator && playerOperator) && onlineIdentity && alive && !removed && !spectator;
	}

	private static boolean atRecord(ServerLevel level, ServerPlayer player, BlockPos position) {
		return localArrival(available(level, player.blockPosition()), available(level, position),
			player.distanceToSqr(position.getCenter()));
	}

	static boolean localArrival(boolean playerPositionValid, boolean recordPositionValid, double distanceSquared) {
		return playerPositionValid && recordPositionValid && Double.isFinite(distanceSquared)
			&& distanceSquared >= 0 && distanceSquared <= 9;
	}

	private static boolean available(ServerLevel level, BlockPos position) {
		return !level.isOutsideBuildHeight(position) && level.getWorldBorder().isWithinBounds(position)
			&& level.isLoaded(position) && level.isPositionEntityTicking(position);
	}

	/** Bounded loaded-entity and SavedData reads only. Absence never supplies the death evidence. */
	private static boolean borrowerAbsent(ServerLevel currentLevel, SettlementSavedData currentData, UUID deceasedId) {
		try {
			var sources = new ArrayList<SettlementMembershipClaims.Source>();
			boolean foundCurrent = false;
			int visited = 0;
			for (ServerLevel level : currentLevel.getServer().getAllLevels()) {
				if (!withinDimensionLimit(++visited) || level == null) return false;
				SettlementSavedData data = SettlementSavedData.get(level);
				if (level == currentLevel) {
					if (data != currentData) return false;
					foundCurrent = true;
				}
				var entity = level.getEntity(deceasedId);
				if (entity != null && !entity.isRemoved() && entity.isAlive()) return false;
				sources.add(new SettlementMembershipClaims.Source(level.dimension(), data));
			}
			return foundCurrent && noClaims(SettlementMembershipClaims.resolve(sources, currentLevel.dimension(), deceasedId));
		} catch (RuntimeException incompleteRead) {
			return false;
		}
	}

	static boolean noClaims(SettlementMembershipClaims.Resolution claims) {
		return claims != null && claims.status() == SettlementMembershipClaims.Status.NONE && claims.claims().isEmpty();
	}
	static boolean withinDimensionLimit(int visited) { return visited > 0 && visited <= MAX_DIMENSIONS; }

	static UUID parseId(String value) { return SettlementCargoCommands.parseId(value); }
	private static Component message(String key, Object... args) {
		return Component.translatable("coopcraft.command.settlement.cargo_shears_" + key, args);
	}
	private static int failure(CommandSourceStack source, String key) { source.sendFailure(message(key)); return 0; }
	private static int commonFailure(CommandSourceStack source, String key) {
		source.sendFailure(Component.translatable("coopcraft.command.settlement." + key)); return 0;
	}
}
