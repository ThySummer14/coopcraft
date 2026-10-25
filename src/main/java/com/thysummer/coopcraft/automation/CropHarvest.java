package com.thysummer.coopcraft.automation;

import java.util.Iterator;
import java.util.List;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;

final class CropHarvest {
	private CropHarvest() {
	}

	static boolean isMature(BlockState state) {
		boolean supported = state.is(Blocks.WHEAT)
			|| state.is(Blocks.CARROTS)
			|| state.is(Blocks.POTATOES)
			|| state.is(Blocks.BEETROOTS)
			|| state.is(Blocks.NETHER_WART);
		if (!supported) {
			return false;
		}
		if (state.getBlock() instanceof CropBlock crop) {
			return crop.isMaxAge(state);
		}
		return state.getValue(NetherWartBlock.AGE) == NetherWartBlock.MAX_AGE;
	}

	static BlockState getReplantState(BlockState state) {
		if (state.getBlock() instanceof CropBlock crop) {
			return crop.getStateForAge(0);
		}
		return state.setValue(NetherWartBlock.AGE, 0);
	}

	static boolean removeOneSeed(List<ItemStack> drops, ItemStack seed) {
		if (seed.isEmpty()) {
			return false;
		}

		for (Iterator<ItemStack> iterator = drops.iterator(); iterator.hasNext();) {
			ItemStack drop = iterator.next();
			if (!ItemStack.isSameItemSameComponents(drop, seed)) {
				continue;
			}

			drop.shrink(1);
			if (drop.isEmpty()) {
				iterator.remove();
			}
			return true;
		}
		return false;
	}

	static Result harvest(ServerLevel level, BlockPos cropPos, BlockState cropState, Storage<ItemVariant> storage) {
		List<ItemStack> drops = Block.getDrops(cropState, level, cropPos, level.getBlockEntity(cropPos));
		ItemStack seed = cropState.getBlock().getCloneItemStack(level, cropPos, cropState);
		if (!removeOneSeed(drops, seed)) {
			return Result.MISSING_SEED;
		}

		try (Transaction transaction = Transaction.openOuter()) {
			for (ItemStack drop : drops) {
				long inserted = storage.insert(ItemVariant.of(drop), drop.getCount(), transaction);
				if (inserted != drop.getCount()) {
					return Result.OUTPUT_FULL;
				}
			}
			transaction.commit();
		}

		level.setBlock(cropPos, getReplantState(cropState), Block.UPDATE_ALL);
		level.levelEvent(null, 2001, cropPos, Block.getId(cropState));
		return Result.HARVESTED;
	}

	enum Result {
		HARVESTED,
		OUTPUT_FULL,
		MISSING_SEED
	}
}
