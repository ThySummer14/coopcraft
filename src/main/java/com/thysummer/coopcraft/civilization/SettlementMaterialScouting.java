package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Ephemeral, read-only travel. Arrival is not evidence of discovery or depletion. */
final class SettlementMaterialScouting {
	static final int STEP = 6, MAX_PATH_ATTEMPTS = 12, MAX_TICKS = 600, OBSERVE_TICKS = 120, RETRY_TICKS = 400;
	private SettlementMaterialScouting() { }

	interface Probe extends ConstructionApproach.Probe {
		boolean blocked(BlockPos position);
	}

	/** All accessibility checks precede state reads. A clear shoulder excludes diagonal corner hazards/doors. */
	static boolean safe(BlockPos feet, Probe probe) {
		if (feet == null || feet.getY() <= Integer.MIN_VALUE + 1 || feet.getY() >= Integer.MAX_VALUE - 1
			|| Math.abs((long) feet.getX()) > 29_999_980 || Math.abs((long) feet.getZ()) > 29_999_980) return false;
		for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) for (int y = -1; y <= 1; y++) {
			BlockPos cell = feet.offset(x, y, z);
			if (!probe.inBounds(cell) || !probe.loaded(cell) || !probe.ticking(cell) || probe.blocked(cell)) return false;
		}
		for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
			BlockPos cell = feet.offset(x, 0, z);
			BlockState support = probe.state(cell.below());
			if (!probe.state(cell).isAir() || !probe.state(cell.above()).isAir()
				|| support.is(Blocks.FARMLAND) || !ConstructionSite.safeSupport(support)) return false;
		}
		return true;
	}

	/** Same-height, axis-aligned short legs. No terrain search and no underground/full-height exploration. */
	static BlockPos waypoint(BlockPos from, SettlementGatheringAreas.Area area) {
		int margin = 3; // Center remains within the existing eight-block observation radius, including diagonals.
		long dx = (long) area.centerX() - from.getX(), dz = (long) area.centerZ() - from.getZ();
		dx = Math.abs(dx) <= margin ? 0 : dx - Long.signum(dx) * margin;
		dz = Math.abs(dz) <= margin ? 0 : dz - Long.signum(dz) * margin;
		if (dx != 0) return from.offset((int) Math.max(-STEP, Math.min(STEP, dx)), 0, 0);
		return from.offset(0, 0, (int) Math.max(-STEP, Math.min(STEP, dz)));
	}

	static boolean withinCamp(BlockPos feet, Settlement camp) {
		long dx = (long) feet.getX() - camp.center().getX(), dz = (long) feet.getZ() - camp.center().getZ();
		return Math.abs(dx) <= 64 && Math.abs(dz) <= 64 && dx * dx + dz * dz <= 64L * 64L;
	}

	static boolean onLeg(BlockPos feet, BlockPos from, BlockPos to) {
		return feet.getY() == from.getY() && feet.getX() >= Math.min(from.getX(), to.getX())
			&& feet.getX() <= Math.max(from.getX(), to.getX()) && feet.getZ() >= Math.min(from.getZ(), to.getZ())
			&& feet.getZ() <= Math.max(from.getZ(), to.getZ());
	}

	static boolean pathSafe(List<BlockPos> nodes, BlockPos from, BlockPos to, Probe probe) {
		if (nodes.isEmpty() || nodes.size() > STEP + 2 || !nodes.getLast().equals(to)) return false;
		BlockPos previous = from;
		for (BlockPos node : nodes) {
			if (node.getY() != from.getY() || node.getX() < Math.min(from.getX(), to.getX())
				|| node.getX() > Math.max(from.getX(), to.getX()) || node.getZ() < Math.min(from.getZ(), to.getZ())
				|| node.getZ() > Math.max(from.getZ(), to.getZ())
				|| Math.abs((long) node.getX() - previous.getX()) + Math.abs((long) node.getZ() - previous.getZ()) > 1
				|| !safe(node, probe)) return false;
			previous = node;
		}
		return true;
	}

	static final class Cooldown {
		private boolean deferred;
		private long since;
		void defer(long now) { deferred = true; since = now; }
		boolean ready(long now) { return !deferred || now - since < 0 || now - since >= RETRY_TICKS; }
	}

	static final class Session {
		final SettlementSavedData data;
		final Settlement camp;
		final UUID actor;
		final Object member;
		final SettlementGatheringAreas.Area area;
		final long started;
		final int height;
		private int paths;
		private long observationStarted;
		private boolean observing, cancelled;
		Session(SettlementSavedData data, Settlement camp, UUID actor, BlockPos start, long now) {
			this.data = data; this.camp = camp; this.actor = actor; started = now; height = start.getY();
			member = data.memberIdentity(actor); area = data.gatheringAreaOf(camp).orElse(null);
		}
		boolean valid(SettlementSavedData current, boolean bodyReady, long now) {
			boolean valid = !cancelled && bodyReady && current == data && member != null
				&& data.canonicalSettlement(camp) && data.memberIdentity(actor) == member && data.isMember(camp, actor)
				&& area != null && data.gatheringAreaOf(camp).orElse(null) == area
				&& SettlementGatheringAreas.withinCamp(camp, area.centerX(), area.centerZ(), area.radius())
				&& data.canObserveResource(camp) && now - started >= 0 && now - started < MAX_TICKS
				&& (!observing || now - observationStarted >= 0 && now - observationStarted < OBSERVE_TICKS);
			if (!valid) cancelled = true;
			return valid;
		}
		boolean attemptPath() { if (cancelled || observing || paths >= MAX_PATH_ATTEMPTS) return false; paths++; return true; }
		void observe(long now) { if (!observing) { observing = true; observationStarted = now; } }
		void cancel() { cancelled = true; }
	}
}
