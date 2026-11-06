package com.thysummer.coopcraft.civilization;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import com.thysummer.coopcraft.automation.DriveShaftBlock;
import com.thysummer.coopcraft.automation.WaterwheelBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

public final class SettlementIndustry {
	private static final int MAX_SHAFT_SEGMENTS = 16;
	private static final int MAX_NETWORK_BLOCKS = 18;

	private SettlementIndustry() {
	}

	public static boolean isPowered(ServerLevel level, BlockPos machine) {
		return powerSource(level, machine).isPresent();
	}

	public static Optional<BlockPos> powerSource(ServerLevel level, BlockPos machine) {
		return powerSource(level, machine, new HashSet<>(), MAX_SHAFT_SEGMENTS);
	}

	private static Optional<BlockPos> powerSource(ServerLevel level, BlockPos endpoint, Set<BlockPos> visited, int remaining) {
		if (!visited.add(endpoint) || visited.size() > MAX_NETWORK_BLOCKS) {
			return Optional.empty();
		}
		for (Direction direction : Direction.values()) {
			BlockPos wheel = endpoint.relative(direction);
			BlockState state = level.getBlockState(wheel);
			if (state.getBlock() instanceof WaterwheelBlock
				&& state.getValue(WaterwheelBlock.FACING) == direction.getOpposite()
				&& WaterwheelBlock.isDriven(level, wheel, state)) {
				return Optional.of(wheel);
			}
		}
		if (remaining == 0) {
			return Optional.empty();
		}
		BlockState endpointState = level.getBlockState(endpoint);
		Direction.Axis endpointAxis = endpointState.getBlock() instanceof DriveShaftBlock
			? endpointState.getValue(DriveShaftBlock.AXIS) : null;
		for (Direction direction : Direction.values()) {
			BlockPos next = endpoint.relative(direction);
			BlockState state = level.getBlockState(next);
			if (state.getBlock() instanceof DriveShaftBlock
				&& (endpointAxis == null || endpointAxis == direction.getAxis() || state.getValue(DriveShaftBlock.AXIS) == direction.getAxis())) {
				Optional<BlockPos> source = powerSource(level, next, visited, remaining - 1);
				if (source.isPresent()) {
					return source;
				}
			}
		}
		return Optional.empty();
	}

	public static void recordPoweredHarvest(ServerLevel level, BlockPos machine, BlockPos output) {
		SettlementSavedData data = SettlementSavedData.get(level);
		data.find(machine)
			.filter(settlement -> SettlementLogistics.isPublicOutput(level, data, settlement, output))
			.ifPresent(settlement -> {
				data.completeEraMilestone(settlement, CivilizationEras.MECHANICAL_POWER);
				powerSource(level, machine).ifPresent(source -> data.recordPoweredProducer(settlement, machine, source));
				data.recordProductionCycle(settlement, SettlementIntelligence.POWERED_HARVEST, level.getGameTime());
				CivilizationEras.refresh(level, data, settlement);
			});
	}
}
