package com.thysummer.coopcraft.civilization;

import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.thysummer.coopcraft.civilization.simulation.WorkType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;

/** Explicit UUID pair; inspecting never grants future authority or selects a substitute resident. */
final class SettlementTeachingCommands {
	static final int MAX_CANDIDATES = 16;
	private SettlementTeachingCommands() { }

	static int execute(CommandContext<CommandSourceStack> context, String operation) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();
		var level = player.serverLevel();
		boolean mutation = operation.equals("hold");
		if (!operation.isEmpty() && !mutation && !operation.equals("inspect")) return 0;
		if (mutation && (!source.hasPermission(2) || !player.hasPermissions(2))) return failure(source, "permission");
		if (!SettlementTeachingScene.actualPlayer(source, player, level, mutation)) return failure(source, "player_unavailable");
		var data = SettlementSavedData.get(level);
		if (operation.isEmpty()) {
			send(source, "help");
			send(source, "scope");
			// Loaded nearby entity sections only; never traverse all resident entities or load a chunk.
			var candidates = level.getEntitiesOfClass(Villager.class, new AABB(player.blockPosition()).inflate(8), v -> !v.isBaby() && v.isAlive());
			candidates.sort(Comparator.comparingDouble(v -> v.distanceToSqr(player)));
			int shown = 0;
			for (Villager candidate : candidates) {
				if (shown >= MAX_CANDIDATES) break;
				var claim = SettlementMembershipClaims.resolve(level, candidate.getUUID()).uniqueClaim().orElse(null);
				if (claim == null || claim.data() != data || !claim.dimension().equals(level.dimension())) continue;
				var camp = claim.settlement();
				if (!SettlementTeachingScene.placeStatus(level, data, camp, player).equals("ready")
					|| !SettlementTeachingScene.residentReady(level, data, camp, data.getCraftingStation(camp).orElse(null), candidate)) continue;
				var person = SettlementTeachingScene.person(data, camp, candidate.getUUID());
				send(source, "candidate", candidate.getName(), candidate.getUUID().toString(), number(person.skill(WorkType.CRAFT)), camp.name());
				shown++;
			}
			if (shown == 0) send(source, "none");
			return 1;
		}
		UUID teacherId = parseId(StringArgumentType.getString(context, "teacher_uuid"));
		UUID learnerId = parseId(StringArgumentType.getString(context, "learner_uuid"));
		if (teacherId == null || learnerId == null) return failure(source, "invalid_uuid");
		if (teacherId.equals(learnerId)) return failure(source, "same_resident");
		var claim = SettlementMembershipClaims.resolve(level, teacherId).uniqueClaim().orElse(null);
		if (claim == null || claim.data() != data || !claim.dimension().equals(level.dimension())) return failure(source, "not_member");
		var camp = claim.settlement();
		if (!(level.getEntity(teacherId) instanceof Villager teacher) || !(level.getEntity(learnerId) instanceof Villager learner)) return failure(source, "resident_unavailable");
		var scene = new SettlementTeachingScene(level, data, camp, teacher, learner);
		String status = scene.status(source, player, mutation);
		if (!status.equals("ready")) return failure(source, status);
		var result = mutation ? SettlementCraftTeaching.teach(data, camp, teacherId, learnerId, () -> scene.authority(source, player))
			: SettlementCraftTeaching.inspect(data, camp, teacherId, learnerId);
		if (!result.status().equals(mutation ? "taught" : "ready")) return failure(source, result.status());
		send(source, result.status());
		send(source, "summary", teacher.getName(), learner.getName(), result.coreDay(), number(result.teacherSkill()),
			number(result.skillBefore()), number(result.skillAfter()), result.teacherHours(), result.learnerHours());
		send(source, "scope");
		if (!mutation) send(source, "hold_hint", teacherId.toString(), learnerId.toString());
		return 1;
	}
	static UUID parseId(String value) { return SettlementCargoCommands.parseId(value); }
	private static String number(double value) { return String.format(Locale.ROOT, "%.3f", value); }
	private static void send(CommandSourceStack source, String key, Object... args) { source.sendSuccess(() -> message(key, args), false); }
	private static Component message(String key, Object... args) { return Component.translatable("coopcraft.command.settlement.teaching_" + key, args); }
	private static int failure(CommandSourceStack source, String key) { source.sendFailure(message(key)); return 0; }
}
