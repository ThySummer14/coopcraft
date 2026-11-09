package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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
import net.minecraft.world.entity.npc.Villager;
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
		for (String action : List.of("status", "materials", "rations")) {
			settlement.then(LiteralArgumentBuilder.<S>literal(action).executes(ctx -> handler.run(ctx, action, "")));
		}
		settlement.then(LiteralArgumentBuilder.<S>literal("meeting")
			.executes(ctx -> handler.run(ctx, "meeting", ""))
			.then(LiteralArgumentBuilder.<S>literal("hold").executes(ctx -> handler.run(ctx, "meeting", "hold"))));
		settlement.then(LiteralArgumentBuilder.<S>literal("events")
			.executes(ctx -> handler.run(ctx, "events", ""))
			.then(RequiredArgumentBuilder.<S, Integer>argument("count", IntegerArgumentType.integer(1, SettlementEvents.MAX_COUNT))
				.executes(ctx -> handler.run(ctx, "events", "count"))));
		settlement.then(LiteralArgumentBuilder.<S>literal("construction")
			.executes(ctx -> handler.run(ctx, "construction", ""))
			.then(LiteralArgumentBuilder.<S>literal("sites").executes(ctx -> handler.run(ctx, "construction", "sites")))
			.then(LiteralArgumentBuilder.<S>literal("adopt").requires(admin)
				.then(RequiredArgumentBuilder.<S, String>argument("fingerprint", StringArgumentType.word())
					.executes(ctx -> handler.run(ctx, "construction", "adopt")))));
		var reserves = LiteralArgumentBuilder.<S>literal("reserves")
			.executes(ctx -> handler.run(ctx, "reserves", ""));
		for (FoodReserveTarget target : FoodReserveTarget.values()) {
			reserves.then(LiteralArgumentBuilder.<S>literal(target.id()).requires(admin)
				.executes(ctx -> handler.run(ctx, "reserves", target.id())));
		}
		settlement.then(reserves);
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
		if (action.equals("meeting")) {
			SettlementMeetings.Result result = option.equals("hold")
				? SettlementMeetings.hold(level, data, settlement, player)
				: SettlementMeetings.inspect(level, data, settlement, player);
			Component feedback = message("meeting_" + result.status());
			if (option.equals("hold") && !result.held()) source.sendFailure(feedback);
			else source.sendSuccess(() -> feedback, false);
			send(source, "meeting_day", result.coreDay(), result.lastMeetingDay() == result.coreDay()
				? message("meeting_used") : message("meeting_unused"));
			if (!result.held() && !result.discussion().proposalId().isEmpty() && !result.status().equals("invalid_proposal")) {
				send(source, "meeting_summary", result.discussion().participants(),
					result.discussion().adultCount(), result.discussion().round());
			}
			if (!result.held() && !result.location().isEmpty()) send(source, "meeting_location", result.location());
			for (Component line : SettlementMeetingFeedback.messages(result)) source.sendSuccess(() -> line, false);
			if (result.held()) send(source, "meeting_outcome", Component.translatable("coopcraft.simulation.meeting." + result.discussion().outcome()));
			return option.equals("hold") && !result.held() ? 0 : 1;
		}
		if (action.equals("construction") && option.isEmpty()) {
			showConstruction(source, data, settlement);
			return 1;
		}
		if (action.equals("construction") && option.equals("sites")) {
			if (data.getConstructionJob(settlement).isPresent()) {
				send(source, "construction_sites_existing");
			} else {
				for (Component line : constructionSiteMessages(ConstructionSystem.inspectNewHousingSites(level, data, settlement))) {
					source.sendSuccess(() -> line, false);
				}
			}
			return 1;
		}
		if (action.equals("events")) {
			for (Component line : SettlementEvents.messages(level, data, settlement, eventCount(context, option))) {
				source.sendSuccess(() -> line, false);
			}
			return 1;
		}
		if (action.equals("reserves") && option.isEmpty()) {
			showReserves(source, level, data, settlement);
			return 1;
		}
		if (action.equals("rations")) {
			return showRations(source, data, settlement);
		}
		if (action.equals("status") || action.equals("materials")) {
			showStatus(source, data, settlement);
			ConstructionSystem.inspect(level, data, settlement).ifPresent(readiness ->
				source.sendSuccess(() -> constructionMessage(readiness), false));
			showMaterials(source, data, settlement, SettlementStockpile.getStockpile(level, data, settlement));
			return 1;
		}
		if (!source.hasPermission(2)) {
			source.sendFailure(message("permission"));
			return 0;
		}
		if (action.equals("construction") && option.equals("adopt")) {
			String fingerprint = adoptionFingerprint(context);
			if (!validFingerprint(fingerprint)) {
				source.sendFailure(message("construction_adopt_invalid_fingerprint"));
				return 0;
			}
			var result = ConstructionSystem.adoptLegacyPlan(level, data, settlement, fingerprint);
			Component feedback = adoptionMessage(result);
			if (result.status().equals("adopted")) {
				source.sendSuccess(() -> feedback, false);
				return 1;
			}
			source.sendFailure(feedback);
			return 0;
		}
		if (action.equals("reserves")) {
			boolean changed = SettlementReserves.setTarget(data, settlement, FoodReserveTarget.byId(option));
			send(source, changed ? "reserves_changed" : "reserves_unchanged");
			showReserves(source, level, data, settlement);
			return 1;
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

	static <S> String adoptionFingerprint(CommandContext<S> context) {
		return StringArgumentType.getString(context, "fingerprint");
	}

	static boolean validFingerprint(String fingerprint) {
		return fingerprint != null && fingerprint.matches("v1-[0-9a-f]{64}");
	}

	private static void showConstruction(CommandSourceStack source, SettlementSavedData data, Settlement settlement) {
		var job = data.getConstructionJob(settlement).orElse(null);
		if (job == null) {
			send(source, "construction_adopt_no_job");
			return;
		}
		var plan = BlueprintManager.get(job.blueprintId());
		source.sendSuccess(() -> savedConstructionMessage(job, plan), false);
		send(source, "construction_adopt_help");
	}

	/** Uses saved identity/progress only; a changed blueprint never supplies remaining requirements. */
	static Component savedConstructionMessage(ConstructionJob job, ConstructionPlan plan) {
		Object saved = job.planFingerprint().isEmpty() ? message("construction_unknown") : job.planFingerprint();
		Object current = plan == null ? message("construction_unknown") : plan.fingerprint();
		return message("construction_saved", job.blueprintId().toString(), job.origin().toShortString(),
			job.nextIndex(), saved, current, job.compatibilityBlocker(plan).equals("ready")
				? message("construction_compatible") : constructionBlocker(job.compatibilityBlocker(plan)));
	}

	static Component adoptionMessage(ConstructionPlanAdoption.Result result) {
		Component feedback = message("construction_adopt_" + result.status());
		return result.position() == null ? feedback
			: message("construction_adopt_position", feedback, result.position().toShortString());
	}

	static Component constructionBlocker(String blocker) {
		return Component.translatable("coopcraft.interaction.blocker.construction_" + blocker);
	}

	/** Projects an immutable read-only survey; it cannot claim a site or start a saved job. */
	static List<Component> constructionSiteMessages(List<ConstructionSite.Result> results) {
		if (results.isEmpty()) return List.of(message("construction_sites_none"));
		List<Component> lines = new ArrayList<>();
		Map<ResourceLocation, List<ConstructionSite.Result>> projects = new LinkedHashMap<>();
		for (var result : results) projects.computeIfAbsent(result.blueprintId(), ignored -> new ArrayList<>()).add(result);
		for (var project : projects.entrySet()) {
			if (project.getValue().stream().allMatch(result -> result.reason().equals("plan_unavailable"))) {
				lines.add(message("construction_sites_plan_unavailable", project.getKey().toString()));
				continue;
			}
			var ready = project.getValue().stream().filter(ConstructionSite.Result::ready).findFirst();
			if (ready.isPresent()) {
				lines.add(message("construction_sites_ready", project.getKey().toString(), ready.get().origin().toShortString()));
				continue;
			}
			lines.add(message("construction_sites_blocked", project.getKey().toString(), project.getValue().size()));
			Map<String, List<ConstructionSite.Result>> reasons = new LinkedHashMap<>();
			for (var result : project.getValue()) reasons.computeIfAbsent(result.reason(), ignored -> new ArrayList<>()).add(result);
			for (var reason : reasons.entrySet()) {
				var first = reason.getValue().getFirst();
				Object position = first.position() == null ? message("construction_unknown") : first.position().toShortString();
				lines.add(message("construction_sites_reason", reason.getValue().size(),
					message("construction_site_" + reason.getKey()), first.origin().toShortString(), position));
			}
		}
		lines.add(message("construction_sites_scope"));
		return List.copyOf(lines);
	}

	static <S> int eventCount(CommandContext<S> context, String option) {
		return option.equals("count") ? IntegerArgumentType.getInteger(context, "count") : SettlementEvents.DEFAULT_COUNT;
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

	static final int RATION_ROW_LIMIT = 32;

	/** Name is resolved only from a currently loaded real villager in the player's dimension. */
	static record RationRow(Component name, SettlementRations.Snapshot snapshot) {}

	private static int showRations(CommandSourceStack source, SettlementSavedData data, Settlement settlement)
		throws CommandSyntaxException {
		if (!SettlementSimulationService.isSurvivalEnabled(data, settlement)) {
			send(source, "rations_disabled");
			return 0;
		}
		var level = source.getPlayerOrException().serverLevel();
		List<RationRow> eligible = new ArrayList<>();
		// UUID lookup only: no world scan, chunk load, reconciliation or daily-ledger reset.
		for (var snapshot : SettlementRations.inspect(data, settlement)) {
			if (level.getEntity(snapshot.villagerId()) instanceof Villager villager
				&& rationVisible(villager.isAlive(), villager.position(), settlement.center().getCenter())) {
				eligible.add(new RationRow(villager.getName(), snapshot));
			}
		}
		var rows = visibleRationRows(eligible);
		send(source, "rations_header", settlement.name(), rows.size(), eligible.size());
		send(source, "rations_explanation");
		for (var row : rows) {
			source.sendSuccess(() -> rationMessage(row), false);
		}
		if (eligible.size() > rows.size()) {
			send(source, "rations_truncated", eligible.size() - rows.size(), RATION_ROW_LIMIT);
		}
		return 1;
	}

	static boolean rationVisible(boolean alive, Vec3 position, Vec3 center) {
		return alive && position.distanceToSqr(center) <= 64.0 * 64.0;
	}

	static List<RationRow> visibleRationRows(List<RationRow> eligible) {
		return eligible.stream().sorted(Comparator.comparing(row -> row.snapshot().villagerId().toString()))
			.limit(RATION_ROW_LIMIT).toList();
	}

	/** Nutrition is food content, not a claim about the resident's actual hunger recovery. */
	static Component rationMessage(RationRow row) {
		var snapshot = row.snapshot();
		Object nutrition = snapshot.foodNutritionReceived().isPresent()
			? snapshot.foodNutritionReceived().getAsLong() : message("rations_unknown");
		return message("rations_row", row.name(), snapshot.allocatedUnits(), snapshot.dailyUnitCap(),
			snapshot.remainingUnits(), nutrition, snapshot.hunger(), snapshot.day());
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
			String blocker = job.compatibilityBlocker(BlueprintManager.get(job.blueprintId()));
			if (blocker.equals("ready")) {
				showProject(source, job.blueprintId(), job.nextIndex(), true, stockpile, state);
			} else {
				send(source, "construction_materials_blocked", job.blueprintId().toString(), constructionBlocker(blocker));
			}
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
		if (plan == null || nextIndex < 0 || nextIndex > plan.instructions().size()) {
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

	/** Formatting a snapshot never resolves a target, loads chunks, or changes a saved project. */
	static Component constructionMessage(ConstructionReadiness readiness) {
		Object total = List.of("plan_unavailable", "plan_unverified", "plan_changed").contains(readiness.blocker())
			? message("construction_unknown") : readiness.total();
		Object position = readiness.position() == null
			? message("construction_unknown") : readiness.position().toShortString();
		return message("construction_progress", readiness.blueprintId().toString(),
			readiness.nextIndex(), total, position,
			Component.translatable("coopcraft.interaction.blocker.construction_" + readiness.blocker()));
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

	private static void showReserves(CommandSourceStack source, net.minecraft.server.level.ServerLevel level, SettlementSavedData data, Settlement settlement) {
		var snapshot = SettlementReserves.inspect(data, settlement,
			SettlementStockpile.getStockpile(level, data, settlement));
		source.sendSuccess(() -> reservesMessage(settlement.name(), snapshot), false);
		send(source, "reserves_hint");
	}

	static Component reservesMessage(String name, SettlementReserves.Snapshot snapshot) {
		return message("reserves_status", name, message("reserves_" + snapshot.target().id()), snapshot.residents(),
			snapshot.target().pointsPerMember(), message(snapshot.survival() ? "reserves_nutrition" : "reserves_points"),
			snapshot.current().isPresent() ? snapshot.current().getAsLong() : message("reserves_unavailable"),
			snapshot.goal(), snapshot.shortfall().isPresent() ? snapshot.shortfall().getAsLong() : message("reserves_unavailable"));
	}

	private static Component message(String key, Object... arguments) {
		return Component.translatable("coopcraft.command.settlement." + key, arguments);
	}

	private static void send(CommandSourceStack source, String key, Object... arguments) {
		source.sendSuccess(() -> message(key, arguments), false);
	}
}
