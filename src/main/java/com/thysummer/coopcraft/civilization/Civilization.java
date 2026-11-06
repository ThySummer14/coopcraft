package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
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
		ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new CivilizationEras.ReloadListener());
		ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new BlueprintManager());
		UseBlockCallback.EVENT.register(Civilization::onUseBlock);
		UseEntityCallback.EVENT.register(VillagerInteraction::onUseEntity);
		ServerTickEvents.END_SERVER_TICK.register(Civilization::tick);
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
		BlockState state = level.getBlockState(hit.getBlockPos());
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
		if (SettlementMetallurgy.isSmeltingStation(state)) {
			return onUseSmeltingStation(player, level, hit);
		}
		if (state.getBlock() instanceof CampfireBlock) {
			return onUseHearth(player, level, hit);
		}
		if (state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE) || state.is(BlockTags.LOGS)
			|| SettlementMetallurgy.isOre(state)) {
			return onUseResource(player, level, hit);
		}
		return InteractionResult.PASS;
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
			if (findResidents(serverLevel, pos, FOUNDING_RADIUS).size() < MIN_FOUNDERS) {
				serverPlayer.displayClientMessage(Component.translatable("coopcraft.settlement.need_villagers"), true);
				return InteractionResult.SUCCESS;
			}
			settlement = data.getOrCreate(serverLevel, pos);
		}

		List<Villager> residents = findResidents(serverLevel, settlement.center(), RESIDENT_RADIUS);
		data.registerLoadedResidents(serverLevel, settlement, residents);
		SettlementStage stage = SettlementStages.initialize(serverLevel, data, settlement, residents);
		CivilizationEras.refresh(serverLevel, data, settlement);
		if (created) {
			serverPlayer.sendSystemMessage(Component.translatable(
				"coopcraft.settlement.created_stage", settlement.name(), Component.translatable(stage.translationKey())
			));
		}
		if (!ServerPlayNetworking.canSend(serverPlayer, SettlementOverviewPayload.TYPE)) {
			serverPlayer.displayClientMessage(Component.translatable("coopcraft.settlement.client_required"), true);
			return InteractionResult.SUCCESS;
		}

		UUID leaderId = data.leaderOf(settlement).orElse(null);
		Container stockpile = SettlementStockpile.getStockpile(serverLevel, data, settlement);
		Map<UUID, String> residentNames = new HashMap<>();
		for (Villager resident : residents) {
			residentNames.put(resident.getUUID(), displayName(data, settlement, resident));
		}
		List<SettlementOverviewPayload.ResidentInfo> summaries = residents.stream()
			.map(villager -> toResidentInfo(serverLevel, data, settlement, leaderId, residentNames, stockpile, villager))
			.sorted((first, second) -> Boolean.compare(second.leader(), first.leader()))
			.limit(SettlementOverviewPayload.MAX_RESIDENTS)
			.toList();
		List<String> publicAffairs = SettlementCoordination.publicAffairs(serverLevel, data, settlement);
		int communalResourceCount = SettlementStockpile.communalResourceCount(stockpile);
		ServerPlayNetworking.send(serverPlayer, new SettlementOverviewPayload(
			settlement.name(),
			settlement.center().asLong(),
			settlement.foundedDay(),
			residents.size(),
			data.registeredCount(settlement),
			data.housingCapacity(settlement),
			data.getHearth(settlement).map(BlockPos::asLong).orElse(-1L),
			SettlementStockpile.communalFoodPoints(stockpile),
			communalResourceCount,
			data.socialOrganizationOf(settlement),
			stage,
			summaries,
			simulationEvents(serverLevel, data, settlement),
			publicAffairs,
			nextStep(
				stockpile != null, publicAffairs, stage, data.getHearth(settlement).isPresent(),
				data.housingCapacity(settlement), data.registeredCount(settlement),
				data.getCraftingStation(settlement).isPresent(), communalResourceCount
			),
			data.eraOf(settlement).id(),
			CivilizationEras.nextMilestoneTranslationKey(data, settlement),
			data.originOf(settlement).id()
		));
		return InteractionResult.SUCCESS;
	}

	static String nextStep(
		boolean stockpilePresent,
		List<String> publicAffairs,
		SettlementStage stage,
		boolean hearthPresent,
		int housingCapacity,
		int registeredResidents,
		boolean craftingStationPresent,
		int resourceCount
	) {
		if (!stockpilePresent) {
			return "stockpile";
		}
		if (publicAffairs.contains("common_food_shortage") || publicAffairs.contains("food_emergency")) {
			return "food";
		}
		if (stage == SettlementStage.PRIMITIVE && !hearthPresent) {
			return "hearth";
		}
		if (housingCapacity < registeredResidents) {
			return "housing";
		}
		if (!craftingStationPresent) {
			return "crafting_station";
		}
		if (resourceCount < 3) {
			return "resources";
		}
		return "observe";
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
		return onUseStation(player, level, hit, "crafting");
	}

	private static InteractionResult onUseGrindingStation(Player player, Level level, BlockHitResult hit) {
		return onUseStation(player, level, hit, "grinding");
	}

	private static InteractionResult onUseSmeltingStation(Player player, Level level, BlockHitResult hit) {
		return onUseStation(player, level, hit, "smelting");
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

	private static InteractionResult onUseStation(Player player, Level level, BlockHitResult hit, String type) {
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

		switch (type) {
			case "crafting" -> data.setCraftingStation(settlement.get(), hit.getBlockPos());
			case "grinding" -> data.setGrindingStation(settlement.get(), hit.getBlockPos());
			case "smelting" -> data.setSmeltingStation(settlement.get(), hit.getBlockPos());
			default -> throw new IllegalArgumentException("Unknown station type: " + type);
		}
		serverPlayer.displayClientMessage(Component.translatable(
			"coopcraft." + type + "_station.set", settlement.get().name()
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
			for (Settlement settlement : data.all()) {
				SettlementSimulationService.tick(level, data, settlement);
				SettlementCareService.tick(level, data, settlement);
				SettlementCoordination.tick(level, data, settlement);
				SettlementNeeds.tick(level, data, settlement, level.getGameTime());
				SettlementStockpile.tick(level, data, settlement, level.getGameTime());
				SettlementStages.tick(level, data, settlement, level.getGameTime());
				CivilizationEras.tick(level, data, settlement, level.getGameTime());
				SettlementIntelligence.tick(level, data, settlement);
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
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		String campId = state == null ? "" : state.campOwner().id();
		Optional<NormProposal> proposal = state == null || !event.context().containsKey("proposalId")
			? Optional.empty() : state.world().proposal(event.context().get("proposalId"));
		if (event.type() == EventType.BIRTH && event.actors().size() == 3) {
			addDetail(details, "parents", residentNames(level, data, settlement, event.actors().subList(0, 2)));
			addDetail(details, "child", residentName(level, data, settlement, event.actors().get(2)));
		} else if (proposal.isPresent()) {
			addDetail(details, "proposer", residentName(level, data, settlement, proposal.get().proposerId()));
		} else if (!event.actors().isEmpty()) {
			addDetail(details, "actor", residentName(level, data, settlement, event.actors().getFirst()));
		}
		if (event.context().containsKey("normId")) {
			addDetail(details, "rule", "coopcraft.simulation.norm." + event.context().get("normId"));
		}
		addDetail(details, "recipe", event.context().get("recipe"));
		addDetail(details, "task", event.context().get("task"));
		if (event.context().containsKey("foodItem")) {
			String foodItem = event.context().get("foodItem");
			addDetail(details, "item", foodItem.startsWith("item.") ? foodItem : "coopcraft.simulation.resource." + foodItem);
		} else {
			addDetail(details, "item", event.context().get("item"));
		}
		addDetail(details, "meal_nutrition", event.context().get("mealNutrition"));
		addDetail(details, "food_source", event.context().containsKey("foodSource")
			? "coopcraft.simulation.food_source." + event.context().get("foodSource") : null);
		addDetail(details, "player", event.context().get("player"));
		addDetail(details, "work_type", event.context().containsKey("workType")
			? "coopcraft.simulation.worktype." + event.context().get("workType") : null);
		if (event.context().containsKey("skillBefore") && event.context().containsKey("skillAfter")) {
			addDetail(details, "skill", event.context().get("skillBefore") + " → " + event.context().get("skillAfter"));
		}
		if (event.context().containsKey("previousCap") && event.context().containsKey("newCap")) {
			addDetail(details, "cap", event.context().get("previousCap") + " → " + event.context().get("newCap"));
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
				addDetail(details, "supporters", residentNames(level, data, settlement, positions(value, NormProposal.Position.SUPPORT)));
				addDetail(details, "opponents", residentNames(level, data, settlement, positions(value, NormProposal.Position.OPPOSE)));
			});
		} else if (event.type() == EventType.NORM_ADOPTED || event.type() == EventType.NORM_REVISED
			|| event.type() == EventType.NORM_ENFORCED) {
			Optional<Norm> norm = state == null ? Optional.empty() : state.world().norm(campId, event.context().get("normId"));
			norm.ifPresent(value -> {
				addDetail(details, "supporters", residentNames(level, data, settlement, value.supporters()));
				addDetail(details, "opponents", residentNames(level, data, settlement, value.opponents()));
			});
		}
		if (event.context().containsKey("stage")) {
			addDetail(details, "stage", "coopcraft.stage." + event.context().get("stage"));
		}
		if (event.context().containsKey("era")) {
			addDetail(details, "era", CivilizationEra.translationKey(net.minecraft.resources.ResourceLocation.parse(event.context().get("era"))));
		}
		addDetail(details, "reason", event.context().containsKey("reason")
			? "coopcraft.simulation.reason." + event.context().get("reason") : null);
		return new SettlementOverviewPayload.SimulationEventInfo(event.day(), event.type().name(), resourceType, quantity, details);
	}

	private static List<String> positions(NormProposal proposal, NormProposal.Position position) {
		return proposal.positions().entrySet().stream()
			.filter(entry -> entry.getValue() == position)
			.map(java.util.Map.Entry::getKey)
			.sorted()
			.toList();
	}

	private static String residentNames(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		Iterable<String> personIds
	) {
		List<String> names = new ArrayList<>();
		for (String personId : personIds) {
			names.add(residentName(level, data, settlement, personId));
		}
		return abbreviate(String.join(", ", names));
	}

	private static String residentName(ServerLevel level, SettlementSavedData data, Settlement settlement, String personId) {
		if (personId.startsWith("minecraft-person:")) {
			try {
				java.util.UUID villagerId = java.util.UUID.fromString(personId.substring("minecraft-person:".length()));
				if (level != null && level.getEntity(villagerId) instanceof Villager villager
					&& villager.getCustomName() != null) {
					return villager.getCustomName().getString();
				}
				return VillagerNames.nameFor(data, settlement, villagerId);
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
		UUID leaderId,
		Map<UUID, String> residentNames,
		Container stockpile,
		Villager villager
	) {
		VillagerPersonality personality = data.personalityOf(settlement, villager.getUUID());
		boolean foodAvailable = SettlementSimulationService.isSurvivalEnabled(data, settlement)
			? SettlementSimulationService.hasFoodFor(data, settlement, villager.getUUID())
			: SettlementNeeds.hasFood(villager.getInventory(), stockpile);
		SettlementOverviewPayload.ResidentObservation observation = toResidentObservation(
			data, settlement, villager.getUUID(), foodAvailable, VillagerWorkGoal.statusOf(villager), residentNames,
			level.getDayTime() / Level.TICKS_PER_DAY
		);
		return new SettlementOverviewPayload.ResidentInfo(
			residentNames.get(villager.getUUID()),
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
			villager.getUUID().equals(leaderId),
			observation
		);
	}

	private static String displayName(SettlementSavedData data, Settlement settlement, Villager villager) {
		return villager.getCustomName() == null
			? VillagerNames.nameFor(data, settlement, villager.getUUID())
			: villager.getCustomName().getString();
	}

	static SettlementOverviewPayload.ResidentObservation toResidentObservation(
		SettlementSavedData data,
		Settlement settlement,
		UUID villagerId,
		boolean foodAvailable,
		VillagerWorkGoal.WorkStatus status,
		Map<UUID, String> loadedNames,
		long currentCareDay
	) {
		String blocker = status.blocker() == null ? "" : status.blocker();
		long target = status.target() == null ? -1 : status.target().asLong();
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		SettlementHouseholds.Observation household = state == null
			? null : SettlementHouseholds.observe(state, villagerId, currentCareDay).orElse(null);
		if (household == null) {
			return new SettlementOverviewPayload.ResidentObservation(
				false, List.of(), 0, List.of(), 0, false, 0, foodAvailable, status.action(), blocker, target
			);
		}

		String personId = state.residentId(villagerId).orElseThrow();
		Map<String, UUID> villagerIds = new HashMap<>();
		state.residentMappings().forEach((id, mappedPersonId) -> villagerIds.put(mappedPersonId, id));
		List<String> coResidents = new ArrayList<>();
		for (String memberId : household.residence().memberIds()) {
			UUID memberIdUuid = villagerIds.get(memberId);
			if (!memberId.equals(personId) && memberIdUuid != null) {
				coResidents.add(nameFor(data, settlement, memberIdUuid, loadedNames));
			}
		}
		List<SettlementOverviewPayload.CareObservation> care = new ArrayList<>();
		for (CareLink link : household.care()) {
			boolean providing = personId.equals(link.caregiverId());
			UUID otherId = villagerIds.get(providing ? link.dependentId() : link.caregiverId());
			if (otherId != null) {
				care.add(new SettlementOverviewPayload.CareObservation(
					nameFor(data, settlement, otherId, loadedNames), link.hours(), providing
				));
			}
		}
		long provisioningUnits = household.provisioning().stream().mapToLong(ProvisioningLink::quantity).sum();
		return new SettlementOverviewPayload.ResidentObservation(
			true,
			coResidents.stream().limit(SettlementOverviewPayload.MAX_CO_RESIDENTS).toList(),
			coResidents.size(),
			care.stream().limit(SettlementOverviewPayload.MAX_CARE_LINKS).toList(),
			care.size(),
			household.needsCare(),
			provisioningUnits,
			foodAvailable,
			status.action(),
			blocker,
			target
		);
	}

	private static String nameFor(
		SettlementSavedData data,
		Settlement settlement,
		UUID villagerId,
		Map<UUID, String> loadedNames
	) {
		return loadedNames.getOrDefault(villagerId, VillagerNames.nameFor(data, settlement, villagerId));
	}
}
