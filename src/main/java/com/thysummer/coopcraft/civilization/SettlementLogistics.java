package com.thysummer.coopcraft.civilization;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class SettlementLogistics {
	private static final int MAX_HOPPERS = 16;

	private SettlementLogistics() {
	}

	public static boolean isPublicOutput(ServerLevel level, SettlementSavedData data, Settlement settlement, BlockPos output) {
		BlockPos stockpile = data.getStockpile(settlement).orElse(null);
		if (stockpile == null) {
			return false;
		}
		Set<BlockPos> visited = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		visited.add(output);
		queue.add(output);
		while (!queue.isEmpty() && visited.size() <= MAX_HOPPERS) {
			BlockPos current = queue.removeFirst();
			if (current.equals(stockpile)) {
				return true;
			}
			BlockState state = level.getBlockState(current);
			if (!state.is(net.minecraft.world.level.block.Blocks.HOPPER)) {
				continue;
			}
			BlockPos next = current.relative(state.getValue(HopperBlock.FACING));
			if (next.equals(stockpile) || level.getBlockState(next).is(net.minecraft.world.level.block.Blocks.HOPPER) && visited.add(next)) {
				if (next.equals(stockpile)) {
					return true;
				}
				queue.addLast(next);
			}
		}
		return false;
	}
}
