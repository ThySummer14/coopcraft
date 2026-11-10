package com.thysummer.coopcraft.civilization;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/** Dimension-local, transient coordination shared by manual and automatic Goal gatherers. Not authority. */
final class SettlementMaterialLeases {
	static final int MAX_LEASES = 128;
	// Two minutes: accommodates slow personality, hunger and 12x skill/social duration plus travel.
	static final long TTL_TICKS = 2400;
	private final Map<BlockPos, Lease> leases = new LinkedHashMap<>();
	private long lastClock = -1;
	static final class Lease {
		final SettlementMaterialLeases owner;
		final SettlementSavedData data;
		final Settlement camp;
		final UUID actor;
		final Object member;
		final BlockPos position;
		final long acquiredAt, expiresAt;
		Lease(SettlementMaterialLeases owner, SettlementSavedData data, Settlement camp, UUID actor, BlockPos pos, long now) {
			this.owner = owner; this.data = data; this.camp = camp; this.actor = actor;
			member = data.memberIdentity(actor); position = pos.immutable(); acquiredAt = now; expiresAt = now + TTL_TICKS;
		}
		boolean current(long now) {
			return now >= acquiredAt && now < expiresAt && data.materialLeases() == owner
				&& data.canonicalSettlement(camp) && member != null && data.memberIdentity(actor) == member && data.isMember(camp, actor);
		}
	}
	private boolean clock(long now) {
		if (now < 0 || now > Long.MAX_VALUE - TTL_TICKS) {
			leases.clear();
			return false;
		}
		if (now < lastClock) {
			leases.clear(); // Cancel old work, but permit fresh claims on the new timeline.
			lastClock = now;
			return false;
		}
		lastClock = now;
		return true;
	}
	boolean available(BlockPos pos, UUID actor, long now) {
		if (!clock(now)) return false;
		Lease existing = leases.get(pos);
		if (existing != null && !existing.current(now)) { release(existing); existing = null; }
		return existing == null || existing.actor.equals(actor);
	}
	Lease claim(SettlementSavedData data, Settlement camp, UUID actor, BlockPos pos, long now) {
		if (!clock(now)) return null;
		leases.values().removeIf(lease -> !lease.current(now));
		if (now < 0 || now > Long.MAX_VALUE - TTL_TICKS || data == null || data.materialLeases() != this
			|| camp == null || actor == null || pos == null || !data.canonicalSettlement(camp) || !data.isMember(camp, actor)) return null;
		Lease previous = leases.get(pos);
		if (previous != null) return valid(previous, data, camp, actor, pos, now) ? previous : null;
		if (leases.size() >= MAX_LEASES || leases.values().stream().anyMatch(lease -> lease.actor.equals(actor))) return null;
		Lease lease = new Lease(this, data, camp, actor, pos, now);
		leases.put(lease.position, lease);
		return lease;
	}
	boolean valid(Lease lease, SettlementSavedData data, Settlement camp, UUID actor, BlockPos pos, long now) {
		if (!clock(now)) return false;
		if (lease != null && lease.owner == this && !lease.current(now)) release(lease);
		return lease != null && lease.owner == this && lease.data == data && lease.camp == camp
			&& lease.actor.equals(actor) && lease.position.equals(pos) && leases.get(pos) == lease;
	}
	void release(Lease lease) { if (lease != null && lease.owner == this) leases.remove(lease.position, lease); }
	int size(long now) { if (!clock(now)) return 0; leases.values().removeIf(lease -> !lease.current(now)); return leases.size(); }
}
