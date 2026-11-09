package com.thysummer.coopcraft.civilization;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.state.BlockState;

/** Passive, bounded resident observation. Does not navigate, harvest, assign work or load chunks. */
final class SettlementResourceDiscovery {
	static final int MEMBER_BUDGET = 4;
	static final int POSITION_BUDGET = 16;
	static final int RADIUS = 8;
	static final int VERTICAL_RADIUS = 2;
	static final int WIDTH = RADIUS * 2 + 1;
	static final int HEIGHT = VERTICAL_RADIUS * 2 + 1;
	static final int VOLUME = WIDTH * WIDTH * HEIGHT;
	static final double CAMP_RADIUS_SQUARED = 64.0 * 64.0;

	record Observer(BlockPos position, boolean alive, boolean removed, boolean adult,
		boolean sameLevel, boolean sleeping, boolean noAi) {
		Observer { position = position == null ? null : position.immutable(); }
		boolean eligible() { return position != null && alive && !removed && adult && sameLevel && !sleeping && !noAi; }
	}

	/** All world reads used by the production scanner go through this same boundary. */
	interface Probe {
		SettlementObservationClaims membership();
		Observer observer(UUID id);
		boolean inBounds(BlockPos position);
		boolean loaded(BlockPos position);
		boolean entityTicking(BlockPos position);
		BlockState blockState(BlockPos position);
	}

	private SettlementMember actor;
	private BlockPos origin;
	private int cursor;
	private long lastTick = Long.MIN_VALUE;

	static void tick(ServerLevel level, SettlementSavedData data) {
		if (level == null || data == null || SettlementSavedData.get(level) != data) return;
		data.resourceDiscovery().tick(data, level.getGameTime(), new Probe() {
			@Override public SettlementObservationClaims membership() { return SettlementObservationClaims.capture(level, data); }
			@Override public Observer observer(UUID id) {
				if (!(level.getEntity(id) instanceof Villager villager)) return null;
				return new Observer(villager.blockPosition(), villager.isAlive(), villager.isRemoved(), !villager.isBaby(),
					villager.level() == level, villager.isSleeping(), villager.isNoAi());
			}
			@Override public boolean inBounds(BlockPos pos) {
				return !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos);
			}
			@Override public boolean loaded(BlockPos pos) { return level.isLoaded(pos); }
			@Override public boolean entityTicking(BlockPos pos) { return level.isPositionEntityTicking(pos); }
			@Override public BlockState blockState(BlockPos pos) { return level.getBlockState(pos); }
		});
	}

	/** At most four roster selections and sixteen coordinate attempts for the entire dimension/tick. */
	void tick(SettlementSavedData data, long time, Probe probe) {
		if (time == lastTick) return;
		lastTick = time;
		if (data.resourceObservationsBlocked() || !data.hasDiscoveryMembers()) return;
		SettlementObservationClaims claims = probe.membership();
		if (claims == null) return;
		if (actor == null) {
			for (int attempt = 0; attempt < MEMBER_BUDGET; attempt++) {
				SettlementMember next = data.nextDiscoveryMember();
				if (next == null) break;
				Settlement camp = data.settlementById(next.settlementId());
				Observer observed = probe.observer(next.villagerId());
				if (!eligible(data, camp, next, observed, probe, claims)) continue;
				actor = next;
				origin = observed.position();
				cursor = 0;
				break;
			}
		}
		if (actor == null) return;
		Settlement camp = data.settlementById(actor.settlementId());
		for (int attempt = 0; attempt < POSITION_BUDGET && cursor < VOLUME; attempt++) {
			Observer observed = probe.observer(actor.villagerId());
			if (!eligible(data, camp, actor, observed, probe, claims)) { actor = null; return; }
			BlockPos pos = position(origin, cursor++);
			// Check distance/build/world bounds before any chunk or block-state query for the target.
			if (!near(observed.position(), pos) || camp.center().distSqr(pos) > CAMP_RADIUS_SQUARED
				|| !probe.inBounds(pos) || data.resourceObservationSuppressed(camp, pos)
				|| !probe.loaded(pos) || !probe.entityTicking(pos)) continue;
			BlockState block = probe.blockState(pos);
			// Defensive recheck after the read, also preventing stale member callbacks from recording.
			Observer current = probe.observer(actor.villagerId());
			if (!eligible(data, camp, actor, current, probe, claims) || !near(current.position(), pos)) { actor = null; return; }
			data.recordResourceObservation(camp, pos, SettlementForaging.isRipe(block));
		}
		if (cursor >= VOLUME) actor = null;
	}

	private static boolean eligible(SettlementSavedData data, Settlement camp, SettlementMember actor,
		Observer observer, Probe probe, SettlementObservationClaims claims) {
		return claims.unique(data, camp, actor) && data.canonicalSettlement(camp) && data.memberIdentity(actor.villagerId()) == actor
			&& data.isMember(camp, actor.villagerId()) && observer != null && observer.eligible()
			&& camp.center().distSqr(observer.position()) <= CAMP_RADIUS_SQUARED
			&& probe.inBounds(observer.position()) && probe.loaded(observer.position()) && probe.entityTicking(observer.position());
	}

	static boolean near(BlockPos origin, BlockPos target) {
		long dx = (long) target.getX() - origin.getX(), dz = (long) target.getZ() - origin.getZ();
		return Math.abs(dx) <= RADIUS && Math.abs(dz) <= RADIUS && dx * dx + dz * dz <= RADIUS * RADIUS
			&& Math.abs((long) target.getY() - origin.getY()) <= VERTICAL_RADIUS;
	}

	static BlockPos position(BlockPos origin, int cursor) {
		return origin.offset(cursor / (WIDTH * HEIGHT) - RADIUS,
			cursor % HEIGHT - VERTICAL_RADIUS, cursor / HEIGHT % WIDTH - RADIUS);
	}
}
