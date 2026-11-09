package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
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

	static boolean needsForage(boolean hasFood, boolean reserveLow, long gameTime, long cooldownUntil) {
		return (!hasFood || reserveLow) && gameTime >= cooldownUntil;
	}

	static boolean needsForage(boolean hasFood, WorkRole role, BooleanSupplier reserveLow, long gameTime, long cooldownUntil) {
		return needsForage(hasFood, role == WorkRole.FORAGER && reserveLow.getAsBoolean(), gameTime, cooldownUntil);
	}

	static boolean reserveLow(SettlementSavedData data, Settlement settlement, Container stockpile) {
		SettlementReserves.Snapshot snapshot = SettlementReserves.inspect(data, settlement, stockpile);
		// Preserve the existing preventive-work behavior when a physical store cannot be resolved.
		// Reporting keeps it unknown; this is not an assertion that its actual stock is zero.
		return snapshot.current().orElse(0) < snapshot.goal();
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

	/** Same lazy eligibility boundary used by execution and Goal continuation. */
	static boolean validTarget(SettlementSavedData data, Settlement settlement, UUID actor,
		BooleanSupplier adult, BooleanSupplier loaded, BooleanSupplier ticking, Supplier<BlockState> block) {
		return adult.getAsBoolean() && canForage(data, settlement)
			&& (!SettlementSimulationService.isSurvivalEnabled(data, settlement)
				|| data.simulationOf(settlement).flatMap(state -> state.residentId(actor)).isPresent())
			&& loaded.getAsBoolean() && ticking.getAsBoolean() && isRipe(block.get());
	}

	static boolean validTarget(ServerLevel level, SettlementSavedData data, Settlement settlement,
		Villager villager, BlockPos pos) {
		return validTarget(data, settlement, villager.getUUID(), () -> !villager.isBaby(),
			() -> level.isLoaded(pos), () -> level.isPositionEntityTicking(pos), () -> level.getBlockState(pos));
	}

	/** Production entry shared by world harvesting and server-free regression tests.
	 * Loot and world mutation stay lazy; every eligibility condition is rechecked after planning. */
	static int harvest(SettlementSavedData data, Settlement settlement, UUID actor, SimpleContainer inventory,
		BooleanSupplier adult, BooleanSupplier loaded, BooleanSupplier ticking, Supplier<BlockState> block,
		Supplier<List<ItemStack>> loot, Predicate<BlockState> regrow) {
		if (!validTarget(data, settlement, actor, adult, loaded, ticking, block)) return 0;
		BlockState mature = block.get();
		List<ItemStack> output = loot.get().stream().map(ItemStack::copy).toList();
		if (!SettlementHarvestCommit.commit(inventory, output,
			() -> validTarget(data, settlement, actor, adult, loaded, ticking, block)
				&& block.get().equals(mature) && regrow.test(mature))) return 0;
		int berries = output.stream().filter(stack -> stack.is(Items.SWEET_BERRIES)
			&& stack.getComponentsPatch().isEmpty()).mapToInt(ItemStack::getCount).sum();
		if (berries > 0) SettlementSimulationService.recordProduction(
			data, settlement, actor, inventory, Items.SWEET_BERRIES, berries, "forage");
		return output.stream().mapToInt(ItemStack::getCount).sum();
	}

	public static int harvest(ServerLevel level, SettlementSavedData data, Settlement settlement,
		Villager villager, BlockPos position) {
		int harvested = harvest(data, settlement, villager.getUUID(), villager.getInventory(),
			() -> !villager.isBaby(), () -> level.isLoaded(position),
			() -> level.isPositionEntityTicking(position), () -> level.getBlockState(position),
			() -> Block.getDrops(level.getBlockState(position), level, position, level.getBlockEntity(position)),
			mature -> level.setBlock(position, mature.setValue(SweetBerryBushBlock.AGE, 1), Block.UPDATE_ALL));
		if (harvested > 0) {
			villager.swing(InteractionHand.MAIN_HAND);
			if (SettlementSimulationService.isSurvivalEnabled(data, settlement)) {
				depositForagedFood(level, data, settlement, villager);
			}
		}
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
