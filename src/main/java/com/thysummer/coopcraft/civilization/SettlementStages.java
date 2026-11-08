package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.thysummer.coopcraft.civilization.simulation.ResourceBatch;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

public final class SettlementStages {
	static final int SCAN_RADIUS = 32;
	static final int SCAN_DOWN = 16;
	static final int SCAN_UP = 24;
	private static final int PROGRESS_INTERVAL_TICKS = 600;
	private static final int AGRICULTURAL_FOOD = 16;
	private static final int TOWN_FOOD = 32;
	private static final int TOWN_RESOURCES = 32;
	private static final Set<Block> WORKSTATIONS = Set.of(
		Blocks.CRAFTING_TABLE,
		Blocks.FURNACE,
		Blocks.BLAST_FURNACE,
		Blocks.SMOKER,
		Blocks.SMITHING_TABLE,
		Blocks.CARTOGRAPHY_TABLE,
		Blocks.FLETCHING_TABLE,
		Blocks.LECTERN,
		Blocks.STONECUTTER,
		Blocks.LOOM,
		Blocks.BARREL,
		Blocks.COMPOSTER
	);

	private SettlementStages() {
	}

	public static SettlementStage initialize(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		List<Villager> residents
	) {
		return initialize(level, data, settlement, residents, SCAN_RADIUS);
	}

	static SettlementStage initialize(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		List<Villager> residents,
		int radius
	) {
		Survey survey = survey(level, settlement.center(), residents.size(), radius);
		if (survey.stage().ordinal() > data.stageOf(settlement).ordinal()) {
			data.setStage(settlement, survey.stage());
		}
		for (BlockPos bed : survey.bedPositions()) {
			SettlementHousing.registerBed(level, data, settlement, bed);
		}
		if (data.getCraftingStation(settlement).isEmpty()) {
			survey.craftingTable().ifPresent(pos -> data.setCraftingStation(settlement, pos));
		}
		if (data.getGrindingStation(settlement).isEmpty()) {
			survey.stonecutter().ifPresent(pos -> data.setGrindingStation(settlement, pos));
		}
		if (data.getHearth(settlement).isEmpty()) {
			survey.campfire().ifPresent(pos -> data.setHearth(settlement, pos));
		}
		return data.stageOf(settlement);
	}

	public static void tick(ServerLevel level, SettlementSavedData data, Settlement settlement, long gameTime) {
		if (Math.floorMod(gameTime + settlement.id().getLeastSignificantBits(), PROGRESS_INTERVAL_TICKS) != 0) {
			return;
		}
		SettlementHousing.reconcile(level, data, settlement);
		SettlementStage achieved = progressionStage(level, data, settlement);
		if (achieved.ordinal() <= data.stageOf(settlement).ordinal()) {
			return;
		}
		data.setStage(settlement, achieved);
		data.getOrCreateSimulation(settlement).world().recordStageAdvanced(settlement.id().toString(), achieved.id());
		data.setDirty();
	}

	static SettlementStage classify(
		int beds,
		int farmland,
		int workstationTypes,
		int villagers,
		boolean villageStructure
	) {
		boolean settled = villageStructure || (beds >= 3 && workstationTypes >= 2) || farmland >= 8;
		if (!settled) {
			return SettlementStage.PRIMITIVE;
		}
		if (beds >= 8 && workstationTypes >= 5 && farmland >= 8 && villagers >= 6) {
			return SettlementStage.TOWN;
		}
		return SettlementStage.AGRICULTURAL;
	}

	static Survey survey(ServerLevel level, BlockPos center, int villagers, int radius) {
		List<BlockPos> beds = new ArrayList<>();
		int farmland = 0;
		Set<Block> workstationTypes = new HashSet<>();
		BlockPos craftingTable = null;
		BlockPos stonecutter = null;
		BlockPos campfire = null;
		double craftingDistance = Double.MAX_VALUE;
		double stonecutterDistance = Double.MAX_VALUE;
		double campfireDistance = Double.MAX_VALUE;
		int radiusSquared = radius * radius;
		int minY = center.getY() - SCAN_DOWN;
		int maxY = center.getY() + SCAN_UP;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (dx * dx + dz * dz > radiusSquared) {
					continue;
				}
				for (int y = minY; y <= maxY; y++) {
					BlockPos pos = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
					if (!level.hasChunkAt(pos)) {
						continue;
					}
					BlockState state = level.getBlockState(pos);
					if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.FOOT
						&& SettlementHousing.inspectBed(level, pos).status() == SettlementHousing.BedStatus.VALID) {
						beds.add(pos.immutable());
					} else if (state.is(Blocks.FARMLAND)) {
						farmland++;
					}
					Block block = state.getBlock();
					if (WORKSTATIONS.contains(block)) {
						workstationTypes.add(block);
						double distance = center.distSqr(pos);
						if (block == Blocks.CRAFTING_TABLE && distance < craftingDistance) {
							craftingTable = pos.immutable();
							craftingDistance = distance;
						} else if (block == Blocks.STONECUTTER && distance < stonecutterDistance) {
							stonecutter = pos.immutable();
							stonecutterDistance = distance;
						}
					}
					if (state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)) {
						double distance = center.distSqr(pos);
						if (distance < campfireDistance) {
							campfire = pos.immutable();
							campfireDistance = distance;
						}
					}
				}
			}
		}
		boolean villageStructure = level.structureManager()
			.getStructureWithPieceAt(center, StructureTags.VILLAGE)
			.isValid();
		SettlementStage stage = classify(beds.size(), farmland, workstationTypes.size(), villagers, villageStructure);
		return new Survey(
			stage,
			beds.size(),
			farmland,
			workstationTypes.size(),
			villagers,
			villageStructure,
			List.copyOf(beds),
			Optional.ofNullable(craftingTable),
			Optional.ofNullable(stonecutter),
			Optional.ofNullable(campfire)
		);
	}

	private static SettlementStage progressionStage(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement
	) {
		int members = data.registeredCount(settlement);
		int housing = data.housingCapacity(settlement);
		int food = foodUnits(level, data, settlement);
		int resources = resourceUnits(level, data, settlement);
		long day = level.getDayTime() / Level.TICKS_PER_DAY;
		if (members >= 8 && housing >= 4 && food >= TOWN_FOOD && resources >= TOWN_RESOURCES
			&& data.getCraftingStation(settlement).isPresent() && data.getGrindingStation(settlement).isPresent()
			&& day >= settlement.foundedDay() + 3) {
			return SettlementStage.TOWN;
		}
		if (members >= 4 && housing >= 2 && food >= AGRICULTURAL_FOOD
			&& data.getHearth(settlement).isPresent() && data.getStockpile(settlement).isPresent()) {
			return SettlementStage.AGRICULTURAL;
		}
		return SettlementStage.PRIMITIVE;
	}

	private static int foodUnits(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state != null && state.survivalEnabled()) {
			return clampToInt(state.world().batchesAt(state.campOwner()).stream()
				.filter(batch -> batch.resourceType().isFood())
				.mapToLong(ResourceBatch::quantity)
				.sum());
		}
		Container stockpile = SettlementStockpile.getStockpile(level, data, settlement);
		return SettlementStockpile.communalFoodPoints(stockpile);
	}

	private static int resourceUnits(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state != null && state.survivalEnabled()) {
			return clampToInt(state.world().batchesAt(state.campOwner()).stream()
				.filter(batch -> !batch.resourceType().isFood())
				.mapToLong(ResourceBatch::quantity)
				.sum());
		}
		return SettlementStockpile.communalResourceCount(SettlementStockpile.getStockpile(level, data, settlement));
	}

	private static int clampToInt(long value) {
		return (int) Math.min(Integer.MAX_VALUE, value);
	}

	record Survey(
		SettlementStage stage,
		int beds,
		int farmland,
		int workstationTypes,
		int villagers,
		boolean villageStructure,
		List<BlockPos> bedPositions,
		Optional<BlockPos> craftingTable,
		Optional<BlockPos> stonecutter,
		Optional<BlockPos> campfire
	) {
		Survey {
			Objects.requireNonNull(stage, "stage");
			bedPositions = List.copyOf(bedPositions);
			Objects.requireNonNull(craftingTable, "craftingTable");
			Objects.requireNonNull(stonecutter, "stonecutter");
			Objects.requireNonNull(campfire, "campfire");
		}
	}
}
