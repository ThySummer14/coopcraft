package com.thysummer.coopcraft.civilization;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

import com.thysummer.coopcraft.automation.ComputerCoreBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

public final class SettlementIntelligence {
	public static final String POWERED_HARVEST = "powered_harvest";
	private static final long PLAN_INTERVAL_TICKS = 100;
	private static final int BREAD_RESERVE = 6;

	private SettlementIntelligence() {
	}

	public static void tick(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		if (Math.floorMod(level.getGameTime() + settlement.id().getLeastSignificantBits(), PLAN_INTERVAL_TICKS) != 0
			|| !data.eraOf(settlement).id().equals(CivilizationEras.INFORMATION)
			|| data.stageOf(settlement) == SettlementStage.PRIMITIVE || data.activePlanOf(settlement).isPresent()
			|| !hasInformationInfrastructure(level, data, settlement)) {
			return;
		}
		Container stockpile = SettlementStockpile.getStockpile(level, data, settlement);
		if (stockpile == null) {
			return;
		}
		long bread = stockpile.countItem(Items.BREAD)
			+ SettlementSimulationService.craftingResourceCount(data, settlement, Items.BREAD);
		if (stockpile.countItem(Items.WHEAT) < 3 || bread >= BREAD_RESERVE
			|| data.getCraftingStation(settlement).map(level::getBlockState).filter(state -> state.is(Blocks.CRAFTING_TABLE)).isEmpty()) {
			return;
		}
		data.membersOf(settlement).stream()
			.map(member -> level.getEntity(member.villagerId()))
			.filter(Villager.class::isInstance)
			.map(Villager.class::cast)
			.filter(villager -> villager.isAlive() && !villager.isBaby()
				&& villager.distanceToSqr(settlement.center().getCenter()) <= 64.0 * 64.0)
			.min(Comparator.comparing((Villager villager) -> LaborAllocator.roleOf(level, data, settlement, villager.getUUID()) != WorkRole.CRAFTER)
				.thenComparing(villager -> villager.getUUID().toString()))
			.ifPresent(villager -> data.startPlan(
				settlement, SettlementPlan.Kind.BAKE_BREAD, villager.getUUID(), level.getDayTime() / Level.TICKS_PER_DAY
			));
	}

	private static boolean hasInformationInfrastructure(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		for (BlockPos corePos : data.planningCoresOf(settlement)) {
			if (level.getBlockEntity(corePos) instanceof ComputerCoreBlockEntity core && core.connectedArchives() >= 1) {
				return true;
			}
		}
		return false;
	}

	public static void recordCrafted(ServerLevel level, SettlementSavedData data, Settlement settlement, Villager worker, Item item, int inserted) {
		if (inserted > 0) {
			data.recordPlanOutput(
				settlement, worker.getUUID(), BuiltInRegistries.ITEM.getKey(item).toString(), inserted,
				level.getDayTime() / Level.TICKS_PER_DAY
			);
		}
	}

	public static int workInterval(ServerLevel level, BlockPos machine) {
		SettlementSavedData data = SettlementSavedData.get(level);
		return data.find(machine)
			.map(settlement -> data.productionIntervalOf(settlement, POWERED_HARVEST))
			.orElse(ProductionStats.DEFAULT_INTERVAL);
	}

	public static Component statusOf(ServerLevel level, BlockPos corePos) {
		SettlementSavedData data = SettlementSavedData.get(level);
		Optional<Settlement> settlement = data.find(corePos);
		if (settlement.isEmpty()) {
			return Component.translatable("coopcraft.intelligence.status.idle");
		}
		Settlement current = settlement.get();
		Component plan = data.latestPlanOf(current)
			.map(value -> Component.translatable(
				"coopcraft.intelligence.status.plan." + value.status().name().toLowerCase(Locale.ROOT),
				Component.translatable(value.kind().translationKey()), VillagerNames.nameFor(data, current, value.workerId())
			))
			.orElse(Component.translatable("coopcraft.intelligence.status.no_plan"));
		ProductionStats stats = data.productionStatsOf(current, POWERED_HARVEST);
		if (stats.cycles() == 0) {
			return plan;
		}
		return Component.translatable(
			"coopcraft.intelligence.status.learning", plan, stats.recommendedIntervalTicks(),
			stats.baselineCycleTicks(), stats.improvedCycleTicks(), stats.cycles()
		);
	}
}
