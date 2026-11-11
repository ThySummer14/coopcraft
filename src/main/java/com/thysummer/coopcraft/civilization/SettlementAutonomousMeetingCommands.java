package com.thysummer.coopcraft.civilization;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Explicit opt-in only; reads and disabling an uninitialized settlement never create simulation state. */
final class SettlementAutonomousMeetingCommands {
	private SettlementAutonomousMeetingCommands() { }

	static int execute(CommandSourceStack source, ServerPlayer player, SettlementSavedData data,
		Settlement settlement, String operation) {
		boolean mutation = !operation.isEmpty();
		if (mutation && (!source.hasPermission(2) || !player.hasPermissions(2))) return failure(source, "permission");
		if (!SettlementTeachingScene.actualPlayer(source, player, player.serverLevel(), mutation)) return failure(source, "player_unavailable");
		String result = apply(data, settlement, operation);
		if (result.equals("blocked_format") || result.equals("needs_modes") || result.equals("invalid_operation")) return failure(source, result);
		source.sendSuccess(() -> message(result), false);
		return 1;
	}

	static String inspect(SettlementSavedData data, Settlement settlement) {
		var state = data.simulationOf(settlement).orElse(null);
		if (state == null) return "off";
		if (!state.autonomousMeetingsAvailable()) return "blocked_format";
		if (!state.autonomousMeetingsEnabled()) return "off";
		return state.survivalEnabled() && state.normsEnabled() ? "on" : "paused";
	}

	static String apply(SettlementSavedData data, Settlement settlement, String operation) {
		if (operation.isEmpty()) return inspect(data, settlement);
		if (!operation.equals("on") && !operation.equals("off")) return "invalid_operation";
		var state = data.simulationOf(settlement).orElse(null);
		if (state != null && !state.autonomousMeetingsAvailable()) return "blocked_format";
		boolean enabled = operation.equals("on");
		if (enabled && (state == null || !state.survivalEnabled() || !state.normsEnabled())) return "needs_modes";
		if (state != null && state.autonomousMeetingsEnabled() != enabled) {
			state.setAutonomousMeetingsEnabled(enabled);
			data.setDirty();
		}
		return inspect(data, settlement);
	}

	private static Component message(String key) {
		return Component.translatable("coopcraft.command.settlement.meeting_autonomy_" + key);
	}
	private static int failure(CommandSourceStack source, String key) { source.sendFailure(message(key)); return 0; }
}
