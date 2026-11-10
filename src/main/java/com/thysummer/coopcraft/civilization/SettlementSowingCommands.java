package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/** Single-cell permission editing only. Neither block mutation nor simulation creation. */
final class SettlementSowingCommands {
	private SettlementSowingCommands() {}
	static int execute(CommandContext<CommandSourceStack> context, String operation) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException(); ServerLevel level = player.serverLevel();
		boolean mutation = !operation.isEmpty();
		if (!SettlementTeachingScene.actualPlayer(source, player, level, mutation)) return failure(source, "player_unavailable");
		SettlementSavedData data = SettlementSavedData.get(level);
		Settlement camp = SettlementCommands.nearest(data.all(), player.position()).orElse(null);
		if (camp == null) {
			source.sendFailure(Component.translatable("coopcraft.command.settlement.no_settlement")); return 0;
		}
		if (!mutation) { for (Component line : statusMessages(data, camp)) send(source, line); return 1; }
		if (data.sowingPlots().blocked()) return failure(source, "preserved_unsupported");
		BlockPos pos = new BlockPos(IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "y"), IntegerArgumentType.getInteger(context, "z"));
		if (!SettlementSavedData.withinSowingRange(camp, pos)) return failure(source, "invalid");
		// Revocation intentionally does not inspect target blocks, protection, loaded chunks, or proximity.
		if (operation.equals("mark") && !markReady(level, data, camp, player, pos)) return failure(source, "invalid_target");
		if (!operation.equals("mark") && !operation.equals("revoke")) return 0;
		// Validate the actual online OP again immediately before editing the permission record.
		if (!SettlementTeachingScene.actualPlayer(source, player, level, true)) return failure(source, "player_unavailable");
		var status = operation.equals("mark") ? data.addSowingPlot(camp, pos) : data.removeSowingPlot(camp, pos);
		if (status != SettlementSowingPlots.Status.CREATED && status != SettlementSowingPlots.Status.UNCHANGED
			&& status != SettlementSowingPlots.Status.REMOVED && status != SettlementSowingPlots.Status.NOT_FOUND)
			return failure(source, status.name().toLowerCase(Locale.ROOT));
		send(source, message(status.name().toLowerCase(Locale.ROOT)));
		send(source, message("scope")); return 1;
	}
	static boolean markReady(ServerLevel level, SettlementSavedData data, Settlement camp, ServerPlayer player, BlockPos pos) {
		// All loaded/ticking/height/border/protection tests precede either block-state read.
		return SettlementSavedData.withinSowingRange(camp, pos) && player.distanceToSqr(pos.getCenter()) <= 36
			&& available(level, pos) && available(level, pos.below())
			&& !data.protectedAreas().blocked() && !data.protectedAreas().contains(pos) && !data.protectedAreas().contains(pos.below())
			&& level.getBlockState(pos).is(Blocks.AIR) && level.getBlockState(pos.below()).is(Blocks.FARMLAND);
	}
	private static boolean available(ServerLevel level, BlockPos pos) {
		return !level.isOutsideBuildHeight(pos) && level.isLoaded(pos) && level.isPositionEntityTicking(pos)
			&& level.getWorldBorder().isWithinBounds(pos);
	}
	static List<Component> statusMessages(SettlementSavedData data, Settlement camp) {
		if (data.sowingPlots().blocked()) return List.of(message("preserved_unsupported"), message("scope"));
		var lines = new ArrayList<Component>();
		var records = data.sowingPlots().all().stream().filter(p -> p.campId().equals(camp.id())).toList();
		lines.add(message("status", camp.name(), records.size()));
		for (var p : records) lines.add(message("plot", p.position().getX(), p.position().getY(), p.position().getZ(), data.canUseSowingPlot(camp, p.position())));
		lines.add(message("scope")); lines.add(message("help")); return List.copyOf(lines);
	}
	private static Component message(String key, Object... args) { return Component.translatable("coopcraft.command.settlement.planting_" + key, args); }
	private static void send(CommandSourceStack source, Component line) { source.sendSuccess(() -> line, false); }
	private static int failure(CommandSourceStack source, String key) { source.sendFailure(message(key)); return 0; }
}
