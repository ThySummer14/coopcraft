package com.thysummer.coopcraft.civilization;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Transient UUID coordination only. A claim never grants permission to shear. */
final class SettlementShearingLeases {
	static final int MAX_LEASES = 128;
	static final long TTL_TICKS = 2400;
	private final Map<UUID, Lease> leases = new LinkedHashMap<>();
	private long lastClock = -1;

	static final class Lease {
		final SettlementShearingLeases owner;
		final SettlementSavedData data;
		final Settlement camp;
		final UUID actor, sheep;
		final Object member, actorIdentity, sheepIdentity;
		final long acquiredAt, expiresAt;

		private Lease(SettlementShearingLeases owner, SettlementSavedData data, Settlement camp,
			UUID actor, Object actorIdentity, UUID sheep, Object sheepIdentity, long now) {
			this.owner = owner; this.data = data; this.camp = camp; this.actor = actor; this.sheep = sheep;
			this.actorIdentity = actorIdentity; this.sheepIdentity = sheepIdentity;
			member = data.memberIdentity(actor); acquiredAt = now; expiresAt = now + TTL_TICKS;
		}

		private boolean current(long now) {
			return now >= acquiredAt && now < expiresAt && data.shearingLeases() == owner
				&& data.canonicalSettlement(camp) && member != null
				&& data.memberIdentity(actor) == member && data.isMember(camp, actor);
		}
	}

	private boolean clock(long now) {
		if (now < 0 || now > Long.MAX_VALUE - TTL_TICKS) { leases.clear(); return false; }
		if (now < lastClock) { leases.clear(); lastClock = now; return false; }
		lastClock = now;
		return true;
	}

	boolean available(UUID sheep, UUID actor, long now) {
		if (!clock(now) || sheep == null || actor == null) return false;
		Lease existing = leases.get(sheep);
		if (existing != null && !existing.current(now)) { release(existing); existing = null; }
		return existing == null || existing.actor.equals(actor);
	}

	Lease claim(SettlementSavedData data, Settlement camp, UUID actor, Object actorIdentity,
		UUID sheep, Object sheepIdentity, long now) {
		if (!clock(now)) return null;
		leases.values().removeIf(lease -> !lease.current(now));
		if (data == null || data.shearingLeases() != this || camp == null || actor == null || sheep == null
			|| actorIdentity == null || sheepIdentity == null || !data.canonicalSettlement(camp) || !data.isMember(camp, actor)) return null;
		Lease previous = leases.get(sheep);
		if (previous != null) return valid(previous, data, camp, actor, actorIdentity, sheep, sheepIdentity, now) ? previous : null;
		if (leases.size() >= MAX_LEASES || leases.values().stream().anyMatch(lease -> lease.actor.equals(actor))) return null;
		Lease lease = new Lease(this, data, camp, actor, actorIdentity, sheep, sheepIdentity, now);
		leases.put(sheep, lease);
		return lease;
	}

	boolean valid(Lease lease, SettlementSavedData data, Settlement camp, UUID actor, Object actorIdentity,
		UUID sheep, Object sheepIdentity, long now) {
		if (!clock(now)) return false;
		if (lease != null && lease.owner == this && !lease.current(now)) release(lease);
		return lease != null && lease.owner == this && lease.data == data && lease.camp == camp
			&& lease.actor.equals(actor) && lease.actorIdentity == actorIdentity
			&& lease.sheep.equals(sheep) && lease.sheepIdentity == sheepIdentity && leases.get(sheep) == lease;
	}

	void release(Lease lease) { if (lease != null && lease.owner == this) leases.remove(lease.sheep, lease); }
	int size(long now) { if (!clock(now)) return 0; leases.values().removeIf(lease -> !lease.current(now)); return leases.size(); }
}
