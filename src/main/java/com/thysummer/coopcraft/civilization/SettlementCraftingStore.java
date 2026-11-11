package com.thysummer.coopcraft.civilization;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Transient, server-thread-only identity check. Never loads chunks or retains an inventory. */
final class SettlementCraftingStore {
	private SettlementCraftingStore() { }

	interface Probe {
		/** Bounds, loaded and entity-ticking checks only; must not load a chunk. */
		boolean ready(BlockPos position);
		BlockState state(BlockPos position);
		/** Null for absent, removed or wrong-level block entities. */
		Object identity(BlockPos position);
	}

	private record Support(BlockPos position, BlockState state, Object identity) { }

	static final class Binding {
		private final Probe probe;
		private final List<Support> supports;

		private Binding(Probe probe, List<Support> supports) {
			this.probe = probe;
			this.supports = List.copyOf(supports);
		}

		boolean valid() {
			// Preflight the entire known support set before reading any state or entity.
			for (Support support : supports) {
				if (!probe.ready(support.position())) return false;
			}
			for (Support support : supports) {
				BlockState current = probe.state(support.position());
				if (current == null || !SettlementFoodRequests.sameStoreState(support.state(), current)
					|| probe.identity(support.position()) != support.identity()) return false;
			}
			return true;
		}
	}

	static Binding capture(ServerLevel level, BlockPos store) {
		return capture(store, new Probe() {
			@Override
			public boolean ready(BlockPos position) {
				return !level.isOutsideBuildHeight(position) && level.getWorldBorder().isWithinBounds(position)
					&& level.isLoaded(position) && level.isPositionEntityTicking(position);
			}
			@Override
			public BlockState state(BlockPos position) { return level.getBlockState(position); }
			@Override
			public Object identity(BlockPos position) {
				BlockEntity entity = level.getBlockEntity(position);
				return entity != null && !entity.isRemoved() && entity.getLevel() == level ? entity : null;
			}
		});
	}

	/** Returns null when the complete physical store cannot safely be bound. */
	static Binding capture(BlockPos store, Probe probe) {
		if (store == null) return null;
		BlockPos position = store.immutable();
		if (!probe.ready(position)) return null;
		// The main state is the sole discovery read before the full support set is known.
		BlockState state = probe.state(position);
		if (state == null || (!state.is(Blocks.CHEST) && !state.is(Blocks.BARREL))) return null;
		BlockPos partner = null;
		if (state.is(Blocks.CHEST) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			partner = connected(position, state);
			if (partner == null) return null;
		}
		// Recheck all support readiness before reading any block entity (or partner state).
		if (!probe.ready(position) || (partner != null && !probe.ready(partner))) return null;
		BlockState partnerState = partner == null ? null : probe.state(partner);
		if (partner != null && !paired(position, state, partner, partnerState)) return null;
		Object identity = probe.identity(position);
		if (identity == null) return null;
		Support main = new Support(position, state, identity);
		if (partner == null) return new Binding(probe, List.of(main));
		Object partnerIdentity = probe.identity(partner);
		if (partnerIdentity == null) return null;
		return new Binding(probe, List.of(main, new Support(partner, partnerState, partnerIdentity)));
	}

	private static boolean paired(BlockPos position, BlockState state, BlockPos partner, BlockState other) {
		if (other == null || !other.is(Blocks.CHEST)
			|| other.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING)) return false;
		ChestType expected = state.getValue(ChestBlock.TYPE) == ChestType.LEFT ? ChestType.RIGHT : ChestType.LEFT;
		return other.getValue(ChestBlock.TYPE) == expected && position.equals(connected(partner, other));
	}

	private static BlockPos connected(BlockPos position, BlockState state) {
		Direction direction = ChestBlock.getConnectedDirection(state);
		try {
			return new BlockPos(Math.addExact(position.getX(), direction.getStepX()),
				Math.addExact(position.getY(), direction.getStepY()),
				Math.addExact(position.getZ(), direction.getStepZ()));
		} catch (ArithmeticException overflow) {
			return null;
		}
	}
}
