package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Deliberately conservative: unload only from a clear, same-height face neighbor. */
final class SettlementCargoApproach {
	static final int MAX_PATH_ATTEMPTS = 2;
	static final int RETRY_TICKS = 20;
	static final int BACKOFF_TICKS = 100;
	static final int MAX_TRAVEL_TICKS = 200;
	private SettlementCargoApproach() { }

	static List<BlockPos> candidates(BlockPos store, Vec3 from) {
		List<BlockPos> result = new ArrayList<>();
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			try {
				result.add(new BlockPos(Math.addExact(store.getX(), direction.getStepX()), store.getY(),
					Math.addExact(store.getZ(), direction.getStepZ())));
			} catch (ArithmeticException ignored) { /* Do not wrap at integer boundaries. */ }
		}
		result.sort(Comparator.comparingDouble(pos -> ConstructionApproach.feet(pos).distanceToSqr(from)));
		return List.copyOf(result);
	}

	static boolean safe(BlockPos store, BlockPos feet, ConstructionApproach.Probe probe) {
		if (store == null || feet == null || store.getY() != feet.getY()
			|| Math.abs((long) store.getX() - feet.getX()) + Math.abs((long) store.getZ() - feet.getZ()) != 1
			|| feet.getY() == Integer.MIN_VALUE || feet.getY() == Integer.MAX_VALUE) return false;
		// Include store before any state reads; the binding separately checks its physical identity.
		for (BlockPos cell : List.of(store, feet, feet.above(), feet.below())) {
			if (!probe.inBounds(cell) || !probe.loaded(cell) || !probe.ticking(cell)) return false;
		}
		return probe.state(feet).isAir() && probe.state(feet.above()).isAir()
			&& ConstructionSite.safeSupport(probe.state(feet.below()));
	}

	static boolean arrived(BlockPos store, Vec3 actual, ConstructionApproach.Probe probe) {
		if (store == null || !Double.isFinite(actual.x) || !Double.isFinite(actual.y) || !Double.isFinite(actual.z)
			|| actual.distanceToSqr(store.getCenter()) > 9) return false;
		BlockPos feet = BlockPos.containing(actual);
		// The entity must be standing, not jumping/falling past the safe floor. Keeping its center
		// inside this face neighbor makes the entire short hand-to-store segment clear air.
		return Math.abs(actual.y - feet.getY()) <= 0.125
			&& Math.abs(actual.x - (feet.getX() + 0.5)) <= 0.49
			&& Math.abs(actual.z - (feet.getZ() + 0.5)) <= 0.49 && safe(store, feet, probe);
	}

	/** Relative elapsed time is safe across clock wrap; reversal permits recovery. */
	static final class Cooldown {
		private boolean active;
		private long since;
		void defer(long now) { active = true; since = now; }
		boolean ready(long now) {
			long elapsed = now - since;
			if (active && elapsed >= 0 && elapsed < BACKOFF_TICKS) return false;
			active = false;
			return true;
		}
	}

	enum Outcome { WAITING, MOVING, BLOCKED }

	/** Retained across stop/start; contains positions and clocks, never inventory contents. */
	static final class Search {
		private BlockPos store;
		private List<BlockPos> positions = List.of();
		private int cursor;
		private int examined;
		private boolean attempted;
		private long lastAttempt;

		Outcome attempt(BlockPos requested, Vec3 from, long now, ConstructionApproach.Probe probe,
			BooleanSupplier regionLoaded, Predicate<BlockPos> pathAttempt) {
			if (!requested.equals(store)) {
				store = requested.immutable();
				positions = candidates(store, from);
				cursor = 0;
				examined = 0;
			}
			long elapsed = now - lastAttempt;
			if (attempted && elapsed >= 0 && elapsed < RETRY_TICKS) return Outcome.WAITING;
			attempted = true;
			lastAttempt = now;
			if (!regionLoaded.getAsBoolean() || examined >= positions.size()) {
				examined = 0;
				return Outcome.BLOCKED;
			}
			int attempts = 0;
			while (examined < positions.size()) {
				examined++;
				BlockPos candidate = positions.get(Math.floorMod(cursor++, positions.size()));
				if (!safe(store, candidate, probe)) continue;
				attempts++;
				if (pathAttempt.test(candidate)) return Outcome.MOVING;
				if (attempts == MAX_PATH_ATTEMPTS) return Outcome.WAITING;
			}
			examined = 0;
			return Outcome.BLOCKED;
		}
	}
}
