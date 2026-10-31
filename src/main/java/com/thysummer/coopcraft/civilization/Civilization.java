package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.thysummer.coopcraft.civilization.simulation.ResourceFlow;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.mixin.MobAccessor;
import com.thysummer.coopcraft.net.SettlementOverviewPayload;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
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
		ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new BlueprintManager());
		UseBlockCallback.EVENT.register(Civilization::onUseBlock);
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
		if (state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE) || state.is(BlockTags.LOGS)) {
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
		Settlement settlement;
		if (existing.isPresent()) {
			settlement = existing.get();
		} else {
			if (findResidents(serverLevel, pos, FOUNDING_RADIUS).size() < MIN_FOUNDERS) {
				serverPlayer.displayClientMessage(Component.translatable("coopcraft.settlement.need_villagers"), true);
				return InteractionResult.SUCCESS;
			}
			settlement = data.getOrCreate(serverLevel, pos);
			serverPlayer.sendSystemMessage(Component.translatable("coopcraft.settlement.created", settlement.name()));
		}

		List<Villager> residents = findResidents(serverLevel, settlement.center(), RESIDENT_RADIUS);
		data.registerLoadedResidents(serverLevel, settlement, residents);
		if (!ServerPlayNetworking.canSend(serverPlayer, SettlementOverviewPayload.TYPE)) {
			serverPlayer.displayClientMessage(Component.translatable("coopcraft.settlement.client_required"), true);
			return InteractionResult.SUCCESS;
		}

		java.util.UUID leaderId = data.leaderOf(settlement).orElse(null);
		List<SettlementOverviewPayload.ResidentInfo> summaries = residents.stream()
			.map(villager -> toResidentInfo(data, settlement, leaderId, villager))
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
			SettlementStockpile.communalFoodPoints(stockpile),
			SettlementStockpile.communalResourceCount(stockpile),
			data.socialOrganizationOf(settlement),
			summaries,
			simulationEvents(data, settlement)
		));
		return InteractionResult.SUCCESS;
	}

	private static InteractionResult onUseStockpile(Player player, Level level, BlockHitResult hit) {
		if (!player.isShiftKeyDown()) {
			return InteractionResult.PASS;
		}
		if (level.isClientSide) {
			return InteractionResult.SUCCESS;
		}
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}

		SettlementSavedData data = SettlementSavedData.get(serverLevel);
		Optional<Settlement> settlement = data.find(hit.getBlockPos());
		if (settlement.isEmpty()) {
			serverPlayer.displayClientMessage(Component.translatable("coopcraft.settlement.no_settlement"), true);
			return InteractionResult.SUCCESS;
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

	private static InteractionResult onUseStation(Player player, Level level, BlockHitResult hit, boolean grinding) {
		if (!player.isShiftKeyDown()) {
			return InteractionResult.PASS;
		}
		if (level.isClientSide) {
			return InteractionResult.SUCCESS;
		}
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}

		SettlementSavedData data = SettlementSavedData.get(serverLevel);
		Optional<Settlement> settlement = data.find(hit.getBlockPos());
		if (settlement.isEmpty()) {
			serverPlayer.displayClientMessage(Component.translatable("coopcraft.settlement.no_settlement"), true);
			return InteractionResult.SUCCESS;
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
			return InteractionResult.SUCCESS;
		}
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}

		SettlementSavedData data = SettlementSavedData.get(serverLevel);
		Optional<Settlement> settlement = data.find(hit.getBlockPos());
		if (settlement.isEmpty()) {
			serverPlayer.displayClientMessage(Component.translatable("coopcraft.settlement.no_settlement"), true);
			return InteractionResult.SUCCESS;
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
				SettlementNeeds.tick(level, data, settlement, level.getGameTime());
				SettlementStockpile.tick(level, data, settlement, level.getGameTime());
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
		SettlementSavedData data,
		Settlement settlement
	) {
		return SettlementSimulationService.recentEvents(data, settlement, SettlementOverviewPayload.MAX_SIMULATION_EVENTS)
			.stream()
			.map(Civilization::toSimulationEventInfo)
			.toList();
	}

	private static SettlementOverviewPayload.SimulationEventInfo toSimulationEventInfo(SimulationEvent event) {
		List<ResourceFlow> flows = event.outputs().isEmpty() ? event.inputs() : event.outputs();
		String resourceType = flows.stream().findFirst().map(flow -> flow.resourceType().name()).orElse("NONE");
		long quantity = flows.stream().mapToLong(ResourceFlow::quantity).sum();
		return new SettlementOverviewPayload.SimulationEventInfo(event.day(), event.type().name(), resourceType, quantity);
	}

	private static SettlementOverviewPayload.ResidentInfo toResidentInfo(
		SettlementSavedData data,
		Settlement settlement,
		java.util.UUID leaderId,
		Villager villager
	) {
		String customName = villager.getCustomName() == null ? "" : villager.getCustomName().getString();
		VillagerPersonality personality = data.personalityOf(settlement, villager.getUUID());
		return new SettlementOverviewPayload.ResidentInfo(
			customName,
			villager.getVillagerData().getProfession().name(),
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
