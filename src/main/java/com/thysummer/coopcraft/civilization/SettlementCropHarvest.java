package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Explicitly marked potato plots only. All mutations run synchronously on the server thread. */
public final class SettlementCropHarvest {
	private SettlementCropHarvest() {}

	static boolean isRipe(BlockState state) {
		return state.is(Blocks.POTATOES) && ((CropBlock) Blocks.POTATOES).isMaxAge(state);
	}

	static boolean ordinaryPotato(ItemStack stack) {
		return !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, new ItemStack(Items.POTATO));
	}

	static boolean validTarget(ServerLevel level, SettlementSavedData data, Settlement settlement, BlockPos pos) {
		return validTarget(data, settlement, pos, () -> level.isLoaded(pos),
			() -> level.isPositionEntityTicking(pos), () -> level.getBlockState(pos));
	}

	/** Lazy world reads let the loaded/authority gates be tested without starting a server. */
	static boolean validTarget(SettlementSavedData data, Settlement settlement, BlockPos pos,
		BooleanSupplier loaded, BooleanSupplier entityTicking, Supplier<BlockState> block) {
		return data.resourcesOf(settlement).contains(pos)
			&& data.find(pos).filter(settlement::equals).isPresent()
			&& SettlementForaging.canForage(data, settlement)
			&& loaded.getAsBoolean() && entityTicking.getAsBoolean() && isRipe(block.get());
	}

	static Optional<BlockPos> findRipe(ServerLevel level, SettlementSavedData data, Settlement settlement, BlockPos origin) {
		return data.resourcesOf(settlement).stream()
			.filter(pos -> Math.abs(pos.getY() - origin.getY()) <= 4)
			.filter(pos -> { long dx = pos.getX() - origin.getX(), dz = pos.getZ() - origin.getZ(); return dx * dx + dz * dz <= 144; })
			.filter(pos -> validTarget(level, data, settlement, pos))
			.min(java.util.Comparator.comparingDouble(origin::distSqr));
	}

	/** Copies loot and reserves exactly one ordinary seed; byproducts/components remain untouched. */
	static Optional<List<ItemStack>> outputAfterSeed(List<ItemStack> loot) {
		List<ItemStack> output = new ArrayList<>();
		boolean reserved = false;
		for (ItemStack original : loot) {
			ItemStack stack = original.copy();
			if (!reserved && ordinaryPotato(stack)) {
				stack.shrink(1);
				reserved = true;
			}
			if (!stack.isEmpty()) output.add(stack);
		}
		return reserved ? Optional.of(output) : Optional.empty();
	}

	static int harvest(ServerLevel level, SettlementSavedData data, Settlement settlement, Villager villager, BlockPos pos) {
		if (villager.isBaby() || !validTarget(level, data, settlement, pos)) return 0;
		if (SettlementSimulationService.isSurvivalEnabled(data, settlement)
			&& data.simulationOf(settlement).flatMap(state -> state.residentId(villager.getUUID())).isEmpty()) return 0;
		BlockState mature = level.getBlockState(pos);
		var output = outputAfterSeed(Block.getDrops(mature, level, pos, level.getBlockEntity(pos)));
		if (output.isEmpty()) return 0;
		if (!SettlementHarvestCommit.commit(villager.getInventory(), output.get(), () -> validTarget(level, data, settlement, pos)
			&& level.getBlockState(pos).equals(mature)
			&& level.setBlock(pos, ((CropBlock) Blocks.POTATOES).getStateForAge(0), Block.UPDATE_ALL))) return 0;
		villager.swing(InteractionHand.MAIN_HAND);
		int potatoes = output.get().stream().filter(SettlementCropHarvest::ordinaryPotato).mapToInt(ItemStack::getCount).sum();
		if (potatoes > 0) SettlementSimulationService.recordProduction(villager, data, settlement, Items.POTATO, potatoes, "harvest_crop");
		if (SettlementSimulationService.isSurvivalEnabled(data, settlement)) {
			var stockpile = SettlementStockpile.getStockpile(level, data, settlement);
			if (stockpile != null) SettlementStockpile.depositItem(stockpile, villager.getInventory(), Items.POTATO);
		}
		return potatoes;
	}
}
