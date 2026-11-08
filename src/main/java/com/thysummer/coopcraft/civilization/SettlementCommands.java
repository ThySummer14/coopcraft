package com.thysummer.coopcraft.civilization;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

/** All targets, positions and containers are resolved by the server, never supplied as arguments. */
public final class SettlementCommands {
	static final List<String> FEATURES = List.of("survival", "production", "skills", "growth", "norms");
	// Explicit adapter whitelist: adding an enum value must not silently expose a new handoff.
	static final List<ResourceType> RESOURCES = List.of(ResourceType.WILD_FRUIT, ResourceType.TUBERS,
		ResourceType.FISH, ResourceType.GAME, ResourceType.WOOD, ResourceType.STONE,
		ResourceType.STICK, ResourceType.SMOOTH_STONE);

	private SettlementCommands() {}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
			dispatcher.register(SettlementCommands.<CommandSourceStack>commandTree(source -> source.hasPermission(2), SettlementCommands::execute)));
	}

	@FunctionalInterface
	interface Handler<S> {
		int run(CommandContext<S> context, String action, String option) throws CommandSyntaxException;
	}

	/** Generic tree lets unit tests exercise the actual grammar and permission predicates without a server. */
	static <S> LiteralArgumentBuilder<S> commandTree(Predicate<S> admin, Handler<S> handler) {
		var settlement = LiteralArgumentBuilder.<S>literal("settlement");
		for (String action : List.of("status", "materials")) {
			settlement.then(LiteralArgumentBuilder.<S>literal(action).executes(ctx -> handler.run(ctx, action, "")));
		}
		var simulation = LiteralArgumentBuilder.<S>literal("simulation").requires(admin);
		for (String feature : FEATURES) {
			simulation.then(LiteralArgumentBuilder.<S>literal(feature)
				.then(RequiredArgumentBuilder.<S, Boolean>argument("enabled", BoolArgumentType.bool())
					.executes(ctx -> handler.run(ctx, "simulation", feature))));
		}
		settlement.then(simulation);
		for (String action : List.of("import", "export")) {
			var transfer = LiteralArgumentBuilder.<S>literal(action).requires(admin);
			for (ResourceType type : RESOURCES) {
				String name = type.name().toLowerCase(Locale.ROOT);
				transfer.then(LiteralArgumentBuilder.<S>literal(name)
					.then(RequiredArgumentBuilder.<S, Integer>argument("count", IntegerArgumentType.integer(1, 4096))
						.executes(ctx -> handler.run(ctx, action, name))));
			}
			settlement.then(transfer);
		}
		return LiteralArgumentBuilder.<S>literal("coop").then(settlement);
	}

	private static int execute(CommandContext<CommandSourceStack> context, String action, String option)
		throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		var player = source.getPlayerOrException();
		var level = player.serverLevel();
		// Vanilla command execution is synchronous. Refuse accidental off-thread invocation as well.
		if (!level.getServer().isSameThread()) {
			throw new IllegalStateException("Settlement commands require the server thread");
		}
		SettlementSavedData data = SettlementSavedData.get(level);
		Settlement settlement = nearest(data.all(), player.position()).orElse(null);
		if (settlement == null) {
			source.sendFailure(message("no_settlement"));
			return 0;
		}
		if (action.equals("status") || action.equals("materials")) {
			showStatus(source, data, settlement);
			showMaterials(source, data, settlement, SettlementStockpile.getStockpile(level, data, settlement));
			return 1;
		}
		if (!source.hasPermission(2)) {
			source.sendFailure(message("permission"));
			return 0;
		}
		if (action.equals("simulation")) {
			boolean enabled = BoolArgumentType.getBool(context, "enabled");
			try {
				switch (option) {
					case "survival" -> SettlementSimulationService.setSurvivalEnabled(level, data, settlement, enabled);
					case "production" -> SettlementSimulationService.setProductionEnabled(data, settlement, enabled);
					case "skills" -> SettlementSimulationService.setSkillsEnabled(data, settlement, enabled);
					case "growth" -> SettlementSimulationService.setGrowthEnabled(level, data, settlement, enabled);
					case "norms" -> SettlementSimulationService.setNormsEnabled(data, settlement, enabled);
					default -> throw new IllegalArgumentException("Unknown simulation feature");
				}
			} catch (IllegalStateException exception) {
				source.sendFailure(message(option.equals("survival") ? "needs_residents" : "needs_survival"));
				return 0;
			}
			send(source, "changed", settlement.name(), option, enabled);
			if (option.equals("survival")) {
				send(source, enabled ? "survival_on" : "survival_off");
			}
			return 1;
		}
		Container stockpile = SettlementStockpile.getStockpile(level, data, settlement);
		if (stockpile == null) {
			source.sendFailure(message("stockpile_unavailable"));
			return 0;
		}
		ResourceType type = ResourceType.valueOf(option.toUpperCase(Locale.ROOT));
		int requested = IntegerArgumentType.getInteger(context, "count");
		int moved = action.equals("import")
			? SettlementSimulationService.importFromStockpile(data, settlement, stockpile, type, requested)
			: export(data, settlement, stockpile, type, requested);
		send(source, "transferred", settlement.name(), action, option, moved, requested);
		return moved;
	}

	static Optional<Settlement> nearest(List<Settlement> settlements, Vec3 position) {
		return settlements.stream()
			.filter(settlement -> settlement.center().getCenter().distanceToSqr(position) <= 64.0 * 64.0)
			.min(Comparator.comparingDouble((Settlement settlement) -> settlement.center().getCenter().distanceToSqr(position))
				.thenComparing(settlement -> settlement.id().toString()));
	}

	static int export(SettlementSavedData data, Settlement settlement, Container stockpile, ResourceType type, int requested) {
		if (requested < 1 || requested > 4096 || !RESOURCES.contains(type)) {
			throw new IllegalArgumentException("Invalid resource handoff");
		}
		var state = data.simulationOf(settlement).orElse(null);
		if (state == null) {
			return 0;
		}
		int moved = 0;
		// Snapshot preserves world batch order while exports mutate the underlying batches.
		for (var batch : List.copyOf(state.world().batchesAt(state.campOwner()))) {
			if (batch.resourceType() != type || batch.quantity() <= 0) {
				continue;
			}
			int amount = (int) Math.min(requested - moved, batch.quantity());
			int transferred = SettlementSimulationService.exportToStockpile(data, settlement, batch.batchId(), amount, stockpile);
			moved += transferred;
			if (moved == requested || transferred < amount) {
				break;
			}
		}
		return moved;
	}

	private static void showStatus(CommandSourceStack source, SettlementSavedData data, Settlement settlement) {
		send(source, "status", settlement.name(), settlement.center().toShortString(),
			SettlementSimulationService.isSurvivalEnabled(data, settlement),
			SettlementSimulationService.isProductionEnabled(data, settlement),
			SettlementSimulationService.isSkillsEnabled(data, settlement),
			SettlementSimulationService.isGrowthEnabled(data, settlement),
			SettlementSimulationService.isNormsEnabled(data, settlement));
		var housing = ConstructionPlanner.housingPlan(data, settlement);
		send(source, "housing", housing.housingCapacity(), housing.registeredMembers(), housing.missingBeds());
	}

	private static void showMaterials(CommandSourceStack source, SettlementSavedData data, Settlement settlement, Container stockpile) {
		var state = data.simulationOf(settlement).orElse(null);
		Map<String, Long> real = new LinkedHashMap<>();
		if (stockpile != null) {
			for (int slot = 0; slot < stockpile.getContainerSize(); slot++) {
				var stack = stockpile.getItem(slot);
				if (!stack.isEmpty()) {
					real.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), (long) stack.getCount(), Long::sum);
				}
			}
			send(source, "real", real.toString());
		} else {
			send(source, "stockpile_unavailable");
		}
		Map<String, Long> abstractStock = new LinkedHashMap<>();
		if (state != null) {
			for (var batch : state.world().batchesAt(state.campOwner())) {
				abstractStock.merge(batch.resourceType().name().toLowerCase(Locale.ROOT), batch.quantity(), Long::sum);
			}
		}
		send(source, "abstract", abstractStock.toString());
		var job = data.getConstructionJob(settlement).orElse(null);
		if (job != null) {
			showProject(source, job.blueprintId(), job.nextIndex(), true, stockpile, state);
		}
		boolean pending = false;
		for (var project : ConstructionPlanner.projects(data, settlement)) {
			if (job == null || !project.blueprintId().equals(job.blueprintId())) {
				showProject(source, project.blueprintId(), 0, false, stockpile, state);
				pending = true;
			}
		}
		if (job == null && !pending) {
			send(source, "no_projects");
		}
	}

	private static void showProject(CommandSourceStack source, ResourceLocation id, int nextIndex, boolean active,
		Container stockpile, SettlementSimulationState state) {
		var plan = BlueprintManager.get(id);
		if (plan == null || nextIndex > plan.instructions().size()) {
			send(source, "plan_unavailable", id.toString());
			return;
		}
		var remaining = ConstructionPlanner.remainingMaterials(plan, nextIndex);
		Map<String, Integer> required = new LinkedHashMap<>();
		remaining.forEach((item, count) -> required.put(BuiltInRegistries.ITEM.getKey(item).toString(), count));
		send(source, active ? "active_project" : "pending_project", id.toString(), required.toString());
		if (stockpile == null) {
			send(source, "gap_unknown");
			return;
		}
		Map<Item, Long> available = new LinkedHashMap<>();
		remaining.keySet().forEach(item -> available.put(item, ConstructionMaterials.available(stockpile, state, item)));
		Map<String, Long> missing = new LinkedHashMap<>();
		materialGaps(remaining, available).forEach((item, count) -> missing.put(BuiltInRegistries.ITEM.getKey(item).toString(), count));
		send(source, "gaps", missing.toString());
	}

	static <T> Map<T, Long> materialGaps(Map<T, Integer> required, Map<T, Long> available) {
		Map<T, Long> missing = new LinkedHashMap<>();
		required.forEach((item, count) -> {
			long gap = Math.max(0, count.longValue() - available.getOrDefault(item, 0L));
			if (gap > 0) {
				missing.put(item, gap);
			}
		});
		return missing;
	}

	private static Component message(String key, Object... arguments) {
		return Component.translatable("coopcraft.command.settlement." + key, arguments);
	}

	private static void send(CommandSourceStack source, String key, Object... arguments) {
		source.sendSuccess(() -> message(key, arguments), false);
	}
}
