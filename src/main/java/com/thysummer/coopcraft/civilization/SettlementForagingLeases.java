package com.thysummer.coopcraft.civilization;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;

/** Bounded, dimension-local coordination only. Never saved and never an authority to harvest. */
final class SettlementForagingLeases {
	static final int MAX_LEASES = 128;
	static final long TTL_TICKS = 200;
	private final Map<BlockPos, Lease> leases = new LinkedHashMap<>();

	static final class Lease {
		private final SettlementForagingLeases owner;
		private final SettlementSavedData data;
		private final Settlement settlement;
		private final UUID actor;
		private final Object member;
		private final SettlementForagingAreas.Area area;
		private final BlockPos position;
		private final long acquiredAt, expiresAt;

		private Lease(SettlementForagingLeases owner, SettlementSavedData data, Settlement settlement,
			UUID actor, SettlementForagingAreas.Area area, BlockPos position, long now) {
			this.owner = owner; this.data = data; this.settlement = settlement; this.actor = actor;
			this.member = data.memberIdentity(actor); this.area = area; this.position = position.immutable();
			this.acquiredAt = now; this.expiresAt = now + TTL_TICKS;
		}

		private boolean current(long now) {
			return now >= acquiredAt && now < expiresAt && data.foragingLeases() == owner
				&& member != null && data.memberIdentity(actor) == member && data.isMember(settlement, actor)
				&& data.foragingAreaOf(settlement).orElse(null) == area && data.canHarvestBerries(settlement, position);
		}
	}

	private void expire(long now) { leases.values().removeIf(lease -> !lease.current(now)); }

	boolean available(BlockPos position, UUID actor, long now) {
		Lease lease = leases.get(position);
		if (lease != null && !lease.current(now)) { release(lease); lease = null; }
		return lease == null || lease.actor.equals(actor);
	}

	Lease claim(SettlementSavedData data, Settlement settlement, UUID actor, BlockPos position, long now) {
		expire(now);
		if (now < 0 || now > Long.MAX_VALUE - TTL_TICKS || data == null || data.foragingLeases() != this
			|| settlement == null || actor == null || position == null || !data.isMember(settlement, actor) || !data.canHarvestBerries(settlement, position)) return null;
		SettlementForagingAreas.Area area = data.foragingAreaOf(settlement).orElse(null);
		if (area == null) return null;
		Lease existing = leases.get(position);
		if (existing != null) return valid(existing, data, settlement, actor, position, now) ? existing : null;
		if (leases.size() >= MAX_LEASES || leases.values().stream().anyMatch(lease -> lease.actor.equals(actor))) return null;
		Lease lease = new Lease(this, data, settlement, actor, area, position, now);
		leases.put(lease.position, lease);
		return lease;
	}

	boolean valid(Lease lease, SettlementSavedData data, Settlement settlement, UUID actor, BlockPos position, long now) {
		if (lease != null && lease.owner == this && !lease.current(now)) release(lease);
		return lease != null && lease.owner == this && lease.data == data && lease.settlement == settlement
			&& lease.actor.equals(actor) && lease.position.equals(position) && leases.get(position) == lease;
	}

	void release(Lease lease) {
		if (lease != null && lease.owner == this) leases.remove(lease.position, lease);
	}

	int size(long now) { expire(now); return leases.size(); }
}
