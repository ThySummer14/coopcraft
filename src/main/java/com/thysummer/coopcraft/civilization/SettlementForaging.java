package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class SettlementForaging {
	private static final int SEARCH_RADIUS = 12;
	private static final int SEARCH_VERTICAL_RADIUS = 4;
	private static final int RIPE_AGE = 2;

	private SettlementForaging() {
	}

	public static boolean canForage(SettlementSavedData data, Settlement settlement) {
		return !SettlementSimulationService.isSurvivalEnabled(data, settlement)
			|| SettlementSimulationService.isProductionEnabled(data, settlement);
	}

	static boolean needsForage(boolean hasFood, long gameTime, long cooldownUntil) {
		return !hasFood && gameTime >= cooldownUntil;
	}

	static boolean isRipe(BlockState state) {
		return state.is(Blocks.SWEET_BERRY_BUSH) && state.getValue(SweetBerryBushBlock.AGE) >= RIPE_AGE;
	}

	public static Optional<BlockPos> findRipeBush(ServerLevel level, BlockPos origin) {
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
					if (isRipe(level.getBlockState(cursor))) {
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

	public static int harvest(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		Villager villager,
		BlockPos position
	) {
		BlockState state = level.getBlockState(position);
		if (!isRipe(state)) {
			return 0;
		}
		List<ItemStack> drops = Block.getDrops(state, level, position, level.getBlockEntity(position));
		level.setBlock(position, state.setValue(SweetBerryBushBlock.AGE, 1), Block.UPDATE_ALL);
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
					villager, data, settlement, drop.getItem(), inserted, "forage"
				);
				harvested += inserted;
			}
		}
		depositForagedFood(level, data, settlement, villager);
		return harvested;
	}

	private static void depositForagedFood(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		Villager villager
	) {
		Container stockpile = SettlementStockpile.getStockpile(level, data, settlement);
		if (stockpile != null) {
			SettlementStockpile.depositItem(stockpile, villager.getInventory(), Items.SWEET_BERRIES);
		}
	}
}
