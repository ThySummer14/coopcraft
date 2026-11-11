package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Bounded, read-only standing positions. A candidate is not evidence of a reachable path. */
final class ConstructionApproach {
	static final int HORIZONTAL_RADIUS = 5;
	static final int MIN_VERTICAL = -5;
	static final int MAX_VERTICAL = 1;
	static final int MAX_PATH_ATTEMPTS = 8;
	static final int RETRY_TICKS = 20;
	static final double REACH_SQUARED = 36;

	private ConstructionApproach() {}

	interface Probe {
		boolean inBounds(BlockPos position);
		boolean loaded(BlockPos position);
		boolean ticking(BlockPos position);
		BlockState state(BlockPos position);
	}

	static Vec3 feet(BlockPos position) {
		return new Vec3(position.getX() + 0.5, position.getY(), position.getZ() + 0.5);
	}

	static List<BlockPos> candidates(BlockPos target, Vec3 from) {
		List<BlockPos> result = new ArrayList<>();
		for (int x = -HORIZONTAL_RADIUS; x <= HORIZONTAL_RADIUS; x++) {
			for (int y = MIN_VERTICAL; y <= MAX_VERTICAL; y++) {
				for (int z = -HORIZONTAL_RADIUS; z <= HORIZONTAL_RADIUS; z++) {
					try {
						BlockPos position = new BlockPos(Math.addExact(target.getX(), x),
							Math.addExact(target.getY(), y), Math.addExact(target.getZ(), z));
						if (withinReach(target, position) && !occupiesTarget(target, position)) result.add(position);
					} catch (ArithmeticException ignored) { /* Never wrap synthetic boundary coordinates. */ }
				}
			}
		}
		result.sort(Comparator.<BlockPos>comparingDouble(position -> feet(position).distanceToSqr(from))
			.thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ));
		return List.copyOf(result);
	}

	static boolean withinReach(BlockPos target, BlockPos position) {
		return feet(position).distanceToSqr(target.getCenter()) <= REACH_SQUARED;
	}

	private static boolean occupiesTarget(BlockPos target, BlockPos position) {
		return target.getX() == position.getX() && target.getZ() == position.getZ()
			&& (target.getY() == position.getY() || (long) target.getY() == (long) position.getY() + 1);
	}

	static boolean safe(BlockPos target, BlockPos position, Probe probe) {
		if (!withinReach(target, position) || occupiesTarget(target, position)
			|| position.getY() == Integer.MIN_VALUE || position.getY() == Integer.MAX_VALUE) return false;
		BlockPos head = position.above();
		BlockPos support = position.below();
		// Preflight the whole envelope before ANY state read, even when only head/support is unknown.
		for (BlockPos cell : List.of(position, head, support)) {
			if (!probe.inBounds(cell) || !probe.loaded(cell) || !probe.ticking(cell)) return false;
		}
		return probe.state(position).isAir() && probe.state(head).isAir()
			&& ConstructionSite.safeSupport(probe.state(support));
	}

	/** Survives Goal.stop/start; only a different build position resets the rotation.
	 * Order is fixed from the first approach, so movement cannot repeatedly reorder the first batch. */
	static final class Search {
		private BlockPos target;
		private List<BlockPos> candidates = List.of();
		private int cursor;
		private boolean attempted;
		private long lastAttempt;

		int cursor() { return cursor; }

		boolean attempt(BlockPos requested, Vec3 from, long now, Probe probe,
			BooleanSupplier regionLoaded, Predicate<BlockPos> pathAttempt) {
			if (!requested.equals(target)) {
				target = requested.immutable();
				candidates = candidates(target, from);
				cursor = 0;
				attempted = false;
			}
			// Difference arithmetic handles long clock wrap without overflowing a future deadline.
			long elapsed = now - lastAttempt;
			if (attempted && elapsed >= 0 && elapsed < RETRY_TICKS) return false;
			attempted = true;
			lastAttempt = now;
			if (!regionLoaded.getAsBoolean()) return false;
			int attempts = 0;
			for (int inspected = 0; inspected < candidates.size(); inspected++) {
				cursor = Math.floorMod(cursor, candidates.size());
				BlockPos candidate = candidates.get(cursor);
				cursor = (cursor + 1) % candidates.size();
				if (!safe(target, candidate, probe)) continue;
				attempts++;
				if (pathAttempt.test(candidate)) return true;
				if (attempts >= MAX_PATH_ATTEMPTS) return false;
			}
			return false;
		}
	}
}
