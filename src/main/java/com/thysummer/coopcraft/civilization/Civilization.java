package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.Norm;
import com.thysummer.coopcraft.civilization.simulation.NormProposal;
import com.thysummer.coopcraft.civilization.simulation.ResourceFlow;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.mixin.MobAccessor;
import com.thysummer.coopcraft.net.SettlementOverviewPayload;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

public final class Civilization {
	private static final int FOUNDING_RADIUS = 32;
	private static final int RESIDENT_RADIUS = 64;
	private static final int MIN_FOUNDERS = 2;

	private Civilization() {
	}

	public static void register() {
		SettlementCommands.register();
		ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new BlueprintManager());
		UseBlockCallback.EVENT.register(Civilization::onUseBlock);
		UseEntityCallback.EVENT.register(VillagerInteraction::onUseEntity);
		ServerTickEvents.END_SERVER_TICK.register(Civilization::tick);
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			if (entity instanceof Villager villager) VillagerWorkGoal.cancelMeetingInvitation(villager);
		});
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof Villager villager) {
				GoalSelector goals = ((MobAccessor) villager).getGoalSelector();
				if (goals.getAvailableGoals().stream().noneMatch(goal -> goal.getGoal() instanceof VillagerWorkGoal)) {
					goals.addGoal(8, new VillagerWorkGoal(villager));
				}
			}
		});
	}

	private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (!level.isLoaded(hit.getBlockPos())) {
			return InteractionResult.PASS;
		}
		BlockState state = level.getBlockState(hit.getBlockPos());
		if (state.is(Blocks.POTATOES) || state.is(Blocks.FARMLAND)) {
			return onUseCropPlot(player, level, hand, hit);
		}
		if (state.getBlock() instanceof BedBlock) {
			return onUseBed(player, level, hand, hit);
		}
		if (state.is(Blocks.BELL)) {
			return onUseBell(player, level, hit);
		}
		if (state.is(Blocks.CHEST) || state.is(Blocks.BARREL)) {
			return onUseStockpile(player, level, hit);
		}
		if (state.is(Blocks.CRAFTING_TABLE)) {
			return onUseCraftingStation(player, level, hit);
		}
		if (state.is(Blocks.STONECUTTER)) {
			return onUseGrindingStation(player, level, hit);
		}
		if (state.getBlock() instanceof CampfireBlock) {
			return onUseHearth(player, level, hit);
		}
		if (state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE) || state.is(BlockTags.LOGS)) {
			return onUseResource(player, level, hit);
		}
		return InteractionResult.PASS;
	}

	static InteractionResult onUseCropPlot(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (hand != InteractionHand.MAIN_HAND || !player.isShiftKeyDown() || !player.getMainHandItem().isEmpty()) {
			return InteractionResult.PASS;
		}
		if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;
		BlockPos clicked = hit.getBlockPos();
		if (!level.isLoaded(clicked)) return InteractionResult.PASS;
		BlockState clickedState = level.getBlockState(clicked);
		boolean farmland = clickedState.is(Blocks.FARMLAND);
		if (!farmland && !clickedState.is(Blocks.POTATOES)) return InteractionResult.PASS;
		BlockPos plot = farmland ? clicked.above() : clicked;
		if (!level.isLoaded(plot)) return InteractionResult.PASS;
		SettlementSavedData data = SettlementSavedData.get(serverLevel);
		Optional<Settlement> settlement = data.find(plot);
		if (settlement.isEmpty()) return InteractionResult.PASS;
		// Farmland only cancels an existing mark; new plots need an actual planted potato.
		if (farmland && !data.resourcesOf(settlement.get()).contains(plot)) return InteractionResult.PASS;
		boolean added = data.toggleResource(settlement.get(), plot);
		player.displayClientMessage(Component.translatable("coopcraft.settlement.crop_" + (added ? "marked" : "unmarked"), settlement.get().name()), true);
		return InteractionResult.SUCCESS;
	}

	static InteractionResult onUseBed(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (hand != InteractionHand.MAIN_HAND || !player.isShiftKeyDown() || !player.getMainHandItem().isEmpty()) {
			return InteractionResult.PASS;
		}
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		return registerHousingBed(serverPlayer, serverLevel, SettlementSavedData.get(serverLevel), hit.getBlockPos());
	}

	/** Explicit player registration only: never discovers other beds or creates a settlement. */
	static InteractionResult registerHousingBed(ServerPlayer player, ServerLevel level, SettlementSavedData data, BlockPos pos) {
		var bed = SettlementHousing.inspectBed(level, pos);
		if (bed.status() != SettlementHousing.BedStatus.VALID) {
			player.displayClientMessage(Component.translatable(bed.status() == SettlementHousing.BedStatus.UNLOADED
				? "coopcraft.settlement.bed_unloaded" : "coopcraft.settlement.bed_invalid"), true);
			return InteractionResult.SUCCESS;
		}
		// Both clicks must select the same settlement, including at radius and nearest-owner boundaries.
		Optional<Settlement> settlement = data.find(bed.foot());
		if (settlement.isEmpty()) {
			player.displayClientMessage(Component.translatable("coopcraft.settlement.bed_no_settlement"), true);
			return InteractionResult.SUCCESS;
		}
		String key = switch (SettlementHousing.registerBed(level, data, settlement.get(), bed.foot())) {
			case ADDED -> "bed_registered";
			case ALREADY_REGISTERED -> "bed_already_registered";
			case OTHER_SETTLEMENT -> "bed_other_settlement";
			case INVALID -> "bed_invalid";
			case UNLOADED -> "bed_unloaded";
		};
		player.displayClientMessage(Component.translatable("coopcraft.settlement." + key, settlement.get().name()), true);
		return InteractionResult.SUCCESS;
	}

	private static InteractionResult onUseBell(Player player, Level level, BlockHitResult hit) {
		BlockPos pos = hit.getBlockPos();
		if (player.isShiftKeyDown()) {
			return InteractionResult.PASS;
		}
		if (level.isClientSide) {
			return InteractionResult.SUCCESS;
		}
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}

		SettlementSavedData data = SettlementSavedData.get(serverLevel);
		Optional<Settlement> existing = data.find(pos);
		boolean created = existing.isEmpty();
		Settlement settlement;
		if (existing.isPresent()) {
			settlement = existing.get();
		} else {
			List<SettlementMembership.Candidate> candidates = findResidents(serverLevel, pos, FOUNDING_RADIUS).stream()
				.map(villager -> SettlementMembership.observe(serverLevel, pos, villager)).toList();
			if (SettlementMembership.unclaimedFoundersGlobally(serverLevel, candidates).size() < MIN_FOUNDERS) {
				serverPlayer.displayClientMessage(Component.translatable("coopcraft.settlement.need_villagers"), true);
				return InteractionResult.SUCCESS;
			}
			settlement = data.getOrCreate(serverLevel, pos);
		}

		List<Villager> residents = data.registerAndGetLoadedResidents(serverLevel, settlement,
			findResidents(serverLevel, settlement.center(), RESIDENT_RADIUS));
		SettlementStage stage = SettlementStages.initialize(serverLevel, data, settlement, residents);
		if (created) {
			serverPlayer.sendSystemMessage(Component.translatable(
				"coopcraft.settlement.created_stage", settlement.name(), Component.translatable(stage.translationKey())
			));
		}
		if (!ServerPlayNetworking.canSend(serverPlayer, SettlementOverviewPayload.TYPE)) {
			serverPlayer.displayClientMessage(Component.translatable("coopcraft.settlement.client_required"), true);
			return InteractionResult.SUCCESS;
		}

		java.util.UUID leaderId = data.leaderOf(settlement).orElse(null);
		List<SettlementOverviewPayload.ResidentInfo> summaries = residents.stream()
			.map(villager -> toResidentInfo(serverLevel, data, settlement, leaderId, villager))
			.sorted((first, second) -> Boolean.compare(second.leader(), first.leader()))
			.limit(SettlementOverviewPayload.MAX_RESIDENTS)
			.toList();
		var stockpile = SettlementStockpile.getStockpile(serverLevel, data, settlement);
		ServerPlayNetworking.send(serverPlayer, new SettlementOverviewPayload(
			settlement.name(),
			settlement.center().asLong(),
			settlement.foundedDay(),
			residents.size(),
			data.registeredCount(settlement),
			data.housingCapacity(settlement),
			data.getHearth(settlement).map(BlockPos::asLong).orElse(-1L),
			SettlementStockpile.communalFoodPoints(stockpile),
			SettlementStockpile.communalResourceCount(stockpile),
			data.socialOrganizationOf(settlement),
			stage,
			summaries,
			simulationEvents(serverLevel, data, settlement)
		));
		return InteractionResult.SUCCESS;
	}

	private static InteractionResult onUseStockpile(Player player, Level level, BlockHitResult hit) {
		if (!player.isShiftKeyDown()) {
			return InteractionResult.PASS;
		}
		if (level.isClientSide) {
			return InteractionResult.PASS;
		}
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}

		SettlementSavedData data = SettlementSavedData.get(serverLevel);
		Optional<Settlement> settlement = data.find(hit.getBlockPos());
		if (settlement.isEmpty()) {
			return InteractionResult.PASS;
		}

		data.setStockpile(settlement.get(), hit.getBlockPos());
		serverPlayer.displayClientMessage(Component.translatable("coopcraft.settlement.stockpile_set", settlement.get().name()), true);
		return InteractionResult.SUCCESS;
	}

	private static InteractionResult onUseCraftingStation(Player player, Level level, BlockHitResult hit) {
		return onUseStation(player, level, hit, false);
	}

	private static InteractionResult onUseGrindingStation(Player player, Level level, BlockHitResult hit) {
		return onUseStation(player, level, hit, true);
	}

	private static InteractionResult onUseHearth(Player player, Level level, BlockHitResult hit) {
		if (!player.isShiftKeyDown() || !player.getMainHandItem().isEmpty()) {
			return InteractionResult.PASS;
		}
		if (level.isClientSide) {
			return InteractionResult.PASS;
		}
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}

		SettlementSavedData data = SettlementSavedData.get(serverLevel);
		Optional<Settlement> settlement = data.find(hit.getBlockPos());
		if (settlement.isEmpty()) {
			return InteractionResult.PASS;
		}

		data.setHearth(settlement.get(), hit.getBlockPos());
		serverPlayer.displayClientMessage(Component.translatable("coopcraft.settlement.hearth_set", settlement.get().name()), true);
		return InteractionResult.SUCCESS;
	}

	private static InteractionResult onUseStation(Player player, Level level, BlockHitResult hit, boolean grinding) {
		if (!player.isShiftKeyDown()) {
			return InteractionResult.PASS;
		}
		if (level.isClientSide) {
			return InteractionResult.PASS;
		}
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}

		SettlementSavedData data = SettlementSavedData.get(serverLevel);
		Optional<Settlement> settlement = data.find(hit.getBlockPos());
		if (settlement.isEmpty()) {
			return InteractionResult.PASS;
		}

		if (grinding) {
			data.setGrindingStation(settlement.get(), hit.getBlockPos());
		} else {
			data.setCraftingStation(settlement.get(), hit.getBlockPos());
		}
		serverPlayer.displayClientMessage(Component.translatable(
			grinding ? "coopcraft.grinding_station.set" : "coopcraft.crafting_station.set", settlement.get().name()
		), true);
		return InteractionResult.SUCCESS;
	}

	private static InteractionResult onUseResource(Player player, Level level, BlockHitResult hit) {
		if (!player.isShiftKeyDown()) {
			return InteractionResult.PASS;
		}
		if (level.isClientSide) {
			return InteractionResult.PASS;
		}
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}

		SettlementSavedData data = SettlementSavedData.get(serverLevel);
		Optional<Settlement> settlement = data.find(hit.getBlockPos());
		if (settlement.isEmpty()) {
			return InteractionResult.PASS;
		}

		boolean added = data.toggleResource(settlement.get(), hit.getBlockPos());
		serverPlayer.displayClientMessage(Component.translatable(added ? "coopcraft.resource.marked" : "coopcraft.resource.unmarked"), true);
		return InteractionResult.SUCCESS;
	}

	private static void tick(MinecraftServer server) {
		for (ServerLevel level : server.getAllLevels()) {
			SettlementSavedData data = SettlementSavedData.get(level);
			SettlementResourceDiscovery.tick(level, data);
			for (Settlement settlement : data.all()) {
				SettlementHousing.tick(level, data, settlement, level.getGameTime());
				SettlementSimulationService.tick(level, data, settlement);
				data.foodRequests().flush(data, settlement, level.getGameTime(), level.getDayTime() / 24000L);
				SettlementNeeds.tick(level, data, settlement, level.getGameTime());
				SettlementStockpile.tick(level, data, settlement, level.getGameTime());
				SettlementStages.tick(level, data, settlement, level.getGameTime());
			}
		}
	}

	private static List<Villager> findResidents(ServerLevel level, BlockPos center, int radius) {
		AABB area = new AABB(center).inflate(radius);
		double radiusSquared = (double) radius * radius;
		List<Villager> villagers = new ArrayList<>(level.getEntities(
			EntityType.VILLAGER,
			area,
			villager -> villager.distanceToSqr(center.getCenter()) <= radiusSquared
		));
		villagers.sort(Comparator.comparing(villager -> villager.getUUID().toString()));
		return villagers;
	}

	private static List<SettlementOverviewPayload.SimulationEventInfo> simulationEvents(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement
	) {
		return SettlementSimulationService.recentEvents(data, settlement, SettlementOverviewPayload.MAX_SIMULATION_EVENTS)
			.stream()
			.map(event -> toSimulationEventInfo(level, data, settlement, event))
			.toList();
	}

	static SettlementOverviewPayload.SimulationEventInfo toSimulationEventInfo(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		SimulationEvent event
	) {
		List<ResourceFlow> flows = event.outputs().isEmpty() ? event.inputs() : event.outputs();
		String resourceType = flows.stream().findFirst().map(flow -> flow.resourceType().name()).orElse("NONE");
		long quantity = flows.stream().mapToLong(ResourceFlow::quantity).sum();
		List<SettlementOverviewPayload.SimulationEventDetail> details = new ArrayList<>();
		if (SettlementMeetingDetails.isMeeting(event)) {
			// Even malformed marked evidence must not fall through to mutable proposal positions.
			return new SettlementOverviewPayload.SimulationEventInfo(event.day(), event.type().name(), resourceType,
				quantity, SettlementMeetingDetails.project(event, actors -> residentNames(level, actors)));
		}
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		String campId = state == null ? "" : state.campOwner().id();
		Optional<NormProposal> proposal = state == null || !event.context().containsKey("proposalId")
			? Optional.empty() : state.world().proposal(event.context().get("proposalId"));
		if (event.type() == EventType.BIRTH && event.actors().size() == 3) {
			addDetail(details, "parents", residentNames(level, event.actors().subList(0, 2)));
			addDetail(details, "child", residentName(level, event.actors().get(2)));
		} else if (proposal.isPresent()) {
			addDetail(details, "proposer", residentName(level, proposal.get().proposerId()));
		} else if (!event.actors().isEmpty()) {
			addDetail(details, "actor", residentName(level, event.actors().getFirst()));
		}
		List<SettlementOverviewPayload.SimulationEventDetail> refusalDetails = FoodRefusalDetails.project(event);
		if (!refusalDetails.isEmpty()) {
			// One resident plus five refusal details fits the existing six-detail wire limit.
			details.addAll(refusalDetails);
			return new SettlementOverviewPayload.SimulationEventInfo(event.day(), event.type().name(), resourceType, quantity, details);
		}
		if (event.context().containsKey("normId")) {
			addDetail(details, "rule", "coopcraft.simulation.norm." + event.context().get("normId"));
		}
		String recipeId = event.context().get("recipe");
		String recipeLabel = recipeId;
		for (SettlementMaterialCrafting.Recipe recipe : SettlementMaterialCrafting.Recipe.values()) {
			if (recipe.id().equals(recipeId)) {
				recipeLabel = recipe.output().getDescriptionId();
				break;
			}
		}
		addDetail(details, "recipe", recipeLabel);
		addCraftingDetails(details, event);
		if ("build".equals(event.context().get("activity"))) {
			addDetail(details, "project", event.context().get("blueprint"));
			addDetail(details, "construction_step", event.context().get("index"));
		}
		addDetail(details, "item", event.context().get("item"));
		addDetail(details, "player", event.context().get("player"));
		var giftReserves = GiftReserveDetails.project(event);
		if (details.size() + giftReserves.size() <= SettlementOverviewPayload.MAX_EVENT_DETAILS) {
			// Normal gifts: resident + item + player + two recorded reserve facts + reason = six.
			details.addAll(giftReserves);
		}
		addDetail(details, "work_type", event.context().containsKey("workType")
			? "coopcraft.simulation.worktype." + event.context().get("workType") : null);
		if (event.context().containsKey("skillBefore") && event.context().containsKey("skillAfter")) {
			addDetail(details, "skill", event.context().get("skillBefore") + " → " + event.context().get("skillAfter"));
		}
		if (event.context().containsKey("previousCap") && event.context().containsKey("newCap")) {
			addDetail(details, "cap", event.context().get("previousCap") + " → " + event.context().get("newCap"));
		} else if (event.type() == EventType.COMMON_MEAL_INTERRUPTED) {
			// allocatedUnits belongs to this meal's evidence; this label describes the daily total.
			if (event.context().containsKey("dailyAllocatedUnits") && event.context().containsKey("cap"))
				addDetail(details, "allocation_units", event.context().get("dailyAllocatedUnits") + "/" + event.context().get("cap"));
		} else if (event.context().containsKey("allocatedUnits") && event.context().containsKey("cap")) {
			addDetail(details, "allocation", event.context().get("allocatedUnits") + "/" + event.context().get("cap"));
			addDetail(details, "care_bonus", event.context().get("careBonus"));
		} else {
			addDetail(details, "cap", event.context().get("cap"));
		}
		addDetail(details, "round", event.context().get("round"));
		addDetail(details, "participants", event.context().get("participants"));
		if (event.type() == EventType.NORM_DELIBERATED || event.type() == EventType.NORM_REJECTED) {
			proposal.ifPresent(value -> {
				addDetail(details, "supporters", residentNames(level, positions(value, NormProposal.Position.SUPPORT)));
				addDetail(details, "opponents", residentNames(level, positions(value, NormProposal.Position.OPPOSE)));
			});
		} else if (event.type() == EventType.NORM_ADOPTED || event.type() == EventType.NORM_REVISED
			|| event.type() == EventType.NORM_ENFORCED) {
			Optional<Norm> norm = state == null ? Optional.empty() : state.world().norm(campId, event.context().get("normId"));
			norm.ifPresent(value -> {
				addDetail(details, "supporters", residentNames(level, value.supporters()));
				addDetail(details, "opponents", residentNames(level, value.opponents()));
			});
		}
		if (event.context().containsKey("stage")) {
			addDetail(details, "stage", "coopcraft.stage." + event.context().get("stage"));
		}
		addDetail(details, "reason", event.context().containsKey("reason")
			? "coopcraft.simulation.reason." + event.context().get("reason") : null);
		return new SettlementOverviewPayload.SimulationEventInfo(event.day(), event.type().name(), resourceType, quantity, details);
	}

	/** Historical facts only: direct export links do not describe pre-existing real inventory. */
	private static void addCraftingDetails(
		List<SettlementOverviewPayload.SimulationEventDetail> details,
		SimulationEvent event
	) {
		if (event.type() != EventType.CRAFT_COMPLETED) return;
		if ("current_attempt_direct_exports".equals(event.context().get("provenanceScope"))) {
			addDetail(details, "crafting_evidence", "coopcraft.simulation.crafting.current_attempt_direct_exports");
		}
		if ("unknown".equals(event.context().get("preexistingInventoryProvenance"))) {
			addDetail(details, "preexisting_inventory", "coopcraft.simulation.crafting.provenance_unknown");
		}
		String item = event.context().get("outputItem");
		String count = event.context().get("outputCount");
		if (item == null || item.isBlank() || count == null || !count.matches("[0-9]+")) return;
		try {
			if (Integer.parseInt(count) <= 0) return;
		} catch (NumberFormatException ignored) {
			return;
		}
		String output = item + " × " + count;
		if (output.length() <= 96) addDetail(details, "crafting_output", output);
	}

	private static List<String> positions(NormProposal proposal, NormProposal.Position position) {
		return proposal.positions().entrySet().stream()
			.filter(entry -> entry.getValue() == position)
			.map(java.util.Map.Entry::getKey)
			.sorted()
			.toList();
	}

	private static String residentNames(ServerLevel level, Iterable<String> personIds) {
		List<String> names = new ArrayList<>();
		for (String personId : personIds) {
			names.add(residentName(level, personId));
		}
		return abbreviate(String.join(", ", names));
	}

	private static String residentName(ServerLevel level, String personId) {
		if (personId.startsWith("minecraft-person:")) {
			try {
				java.util.UUID villagerId = java.util.UUID.fromString(personId.substring("minecraft-person:".length()));
				if (level != null && level.getEntity(villagerId) instanceof Villager villager
					&& villager.getCustomName() != null) {
					return villager.getCustomName().getString();
				}
				return VillagerNames.fullName(villagerId);
			} catch (IllegalArgumentException ignored) {
				return personId;
			}
		}
		return personId;
	}

	private static String abbreviate(String value) {
		return value.length() <= 96 ? value : value.substring(0, 95) + "…";
	}

	private static void addDetail(
		List<SettlementOverviewPayload.SimulationEventDetail> details,
		String label,
		String value
	) {
		if (details.size() < SettlementOverviewPayload.MAX_EVENT_DETAILS && value != null && !value.isBlank()) {
			details.add(new SettlementOverviewPayload.SimulationEventDetail("coopcraft.simulation.detail." + label, value));
		}
	}

	private static SettlementOverviewPayload.ResidentInfo toResidentInfo(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		java.util.UUID leaderId,
		Villager villager
	) {
		String customName = villager.getCustomName() == null
			? VillagerNames.nameFor(data, settlement, villager.getUUID())
			: villager.getCustomName().getString();
		VillagerPersonality personality = data.personalityOf(settlement, villager.getUUID());
		return new SettlementOverviewPayload.ResidentInfo(
			customName,
			villager.getVillagerData().getProfession().name(),
			LaborAllocator.roleOf(level, data, settlement, villager.getUUID()).id(),
			villager.isBaby(),
			villager.getBlockX(),
			villager.getBlockY(),
			villager.getBlockZ(),
			personality.diligence(),
			personality.selfishness(),
			personality.sociability(),
			data.needsOf(settlement, villager.getUUID()).satiety(),
			personality.archetype().id(),
			personality.traitIds(),
			villager.getUUID().equals(leaderId)
		);
	}
}
