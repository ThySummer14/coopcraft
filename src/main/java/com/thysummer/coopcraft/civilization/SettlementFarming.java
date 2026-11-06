package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import com.thysummer.coopcraft.automation.CropHarvest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class SettlementFarming {
	private static final int SEARCH_RADIUS = 12;
	private static final int SEARCH_VERTICAL_RADIUS = 4;

	private SettlementFarming() {
	}

	public static boolean canFarm(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		return level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
			&& supportsStage(data.stageOf(settlement)) && SettlementForaging.canForage(data, settlement);
	}

	static boolean supportsStage(SettlementStage stage) {
		return stage != SettlementStage.PRIMITIVE;
	}

	static boolean isMature(BlockState state) {
		return (state.is(Blocks.WHEAT) || state.is(Blocks.CARROTS) || state.is(Blocks.POTATOES) || state.is(Blocks.BEETROOTS))
			&& CropHarvest.isMature(state);
	}

	static boolean isPlantingSite(BlockState ground, BlockState above) {
		return ground.is(Blocks.FARMLAND) && above.isAir();
	}

	public static Optional<BlockPos> findMatureCrop(ServerLevel level, BlockPos origin) {
		return findNearest(level, origin, position -> isMature(level.getBlockState(position)));
	}

	public static Optional<BlockPos> findPlantingSite(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		BlockPos origin
	) {
		if (!canFarm(level, data, settlement)) {
			return Optional.empty();
		}
		return findNearest(level, origin, position -> isPlantingSite(
			level.getBlockState(position), level.getBlockState(position.above())
		));
	}

	private static Optional<BlockPos> findNearest(ServerLevel level, BlockPos origin, Predicate<BlockPos> matcher) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
			for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
				if (dx * dx + dz * dz > SEARCH_RADIUS * SEARCH_RADIUS) {
					continue;
				}
				for (int dy = -SEARCH_VERTICAL_RADIUS; dy <= SEARCH_VERTICAL_RADIUS; dy++) {
					cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					if (!level.isLoaded(cursor) || !level.isPositionEntityTicking(cursor)) {
						continue;
					}
					if (matcher.test(cursor)) {
						double distance = origin.distSqr(cursor);
						if (distance < bestDistance) {
							bestDistance = distance;
							best = cursor.immutable();
						}
					}
				}
			}
		}
		return Optional.ofNullable(best);
	}

	public static boolean plant(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		Villager villager,
		BlockPos position,
		Item item
	) {
		if (!canFarm(level, data, settlement)
			|| !isPlantingSite(level.getBlockState(position), level.getBlockState(position.above()))) {
			return false;
		}
		BlockState crop;
		if (item == Items.POTATO) {
			crop = Blocks.POTATOES.defaultBlockState();
		} else if (item == Items.WHEAT_SEEDS) {
			crop = Blocks.WHEAT.defaultBlockState();
		} else if (item == Items.CARROT) {
			crop = Blocks.CARROTS.defaultBlockState();
		} else if (item == Items.BEETROOT_SEEDS) {
			crop = Blocks.BEETROOTS.defaultBlockState();
		} else {
			throw new IllegalArgumentException("Unsupported crop item: " + item);
		}
		if (!level.setBlock(position.above(), crop, Block.UPDATE_ALL)) {
			return false;
		}
		villager.swing(InteractionHand.MAIN_HAND);
		return true;
	}

	public static int harvest(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		Villager villager,
		BlockPos position
	) {
		BlockState state = level.getBlockState(position);
		if (!canFarm(level, data, settlement) || !isMature(state)) {
			return 0;
		}
		List<ItemStack> drops = Block.getDrops(state, level, position, level.getBlockEntity(position));
		ItemStack seed = state.getBlock().getCloneItemStack(level, position, state);
		if (!CropHarvest.removeOneSeed(drops, seed)) {
			return 0;
		}

		level.setBlock(position, CropHarvest.getReplantState(state), Block.UPDATE_ALL);
		level.levelEvent(null, 2001, position, Block.getId(state));
		villager.swing(InteractionHand.MAIN_HAND);
		int harvested = 0;
		for (ItemStack drop : drops) {
			int produced = drop.getCount();
			ItemStack remainder = villager.getInventory().addItem(drop);
			int inserted = produced - remainder.getCount();
			if (!remainder.isEmpty()) {
				villager.spawnAtLocation(remainder);
			}
			if (inserted > 0) {
				SettlementSimulationService.recordProduction(
					villager, data, settlement, drop.getItem(), inserted, "farm"
				);
				harvested += inserted;
			}
		}
		depositHarvestedFood(level, data, settlement, villager);
		if (harvested > 0) {
			SettlementCoordination.requestReplan(data, settlement);
		}
		return harvested;
	}

	private static void depositHarvestedFood(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		Villager villager
	) {
		Container stockpile = SettlementStockpile.getStockpile(level, data, settlement);
		if (stockpile == null) {
			return;
		}
		for (var item : List.of(Items.CARROT, Items.POTATO, Items.BEETROOT, Items.WHEAT, Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS)) {
			SettlementStockpile.depositItem(stockpile, villager.getInventory(), item);
		}
	}
}
