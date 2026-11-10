package com.thysummer.coopcraft.civilization;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Dimension-local coordination of givers only; never reserves a child's eating action or food. */
public final class SettlementChildCareClaims {
	public static final int LIMIT = 128;
	public static final long TTL = 400;
	public static final long RETRY = 100;
	// Both maps together contain at most 2 * LIMIT UUIDs. Active claims reserve their cooldown slots.
	private final Map<UUID, Lease> residents = new HashMap<>();
	private final Map<UUID, Long> cooldowns = new HashMap<>();
	private long lastClock = -1;

	/** Identity token: a released token can never release a later claim for the same pair. */
	public static final class Lease {
		private final UUID giver, recipient;
		private final long expiresAt;
		private Lease(UUID giver, UUID recipient, long expiresAt) {
			this.giver = giver; this.recipient = recipient; this.expiresAt = expiresAt;
		}
		public UUID giver() { return giver; }
		public UUID recipient() { return recipient; }
	}

	public Lease claim(UUID giver, UUID recipient, long now) {
		if (!advance(now) || giver == null || recipient == null || giver.equals(recipient)
			|| !free(giver) || !free(recipient) || residents.size() + cooldowns.size() > 2 * LIMIT - 2) return null;
		Lease lease = new Lease(giver, recipient, now + TTL);
		residents.put(giver, lease);
		residents.put(recipient, lease);
		return lease;
	}

	public boolean active(Lease lease, long now) {
		return advance(now) && current(lease);
	}

	public boolean available(UUID resident, long now) {
		return advance(now) && resident != null && free(resident);
	}

	public void release(Lease lease, long now) {
		if (advance(now) && current(lease)) retire(lease, now + RETRY);
	}

	/** Membership removal has no clock argument; use the most recently observed valid tick. */
	public void cancelResident(UUID resident) {
		Lease lease = residents.get(resident);
		if (lease != null) retire(lease, lastClock + RETRY);
	}

	private boolean free(UUID resident) {
		return !residents.containsKey(resident) && !cooldowns.containsKey(resident);
	}

	private boolean current(Lease lease) {
		return lease != null && residents.get(lease.giver) == lease && residents.get(lease.recipient) == lease;
	}

	private void retire(Lease lease, long retryAt) {
		residents.remove(lease.giver);
		residents.remove(lease.recipient);
		cooldowns.put(lease.giver, retryAt);
		cooldowns.put(lease.recipient, retryAt);
	}

	private boolean advance(long now) {
		if (now < 0 || now > Long.MAX_VALUE - TTL - RETRY || now < lastClock) {
			residents.clear();
			cooldowns.clear();
			lastClock = now >= 0 && now <= Long.MAX_VALUE - TTL - RETRY ? now : -1;
			return false;
		}
		lastClock = now;
		// Snapshot avoids modifying the map while iterating; bounded to 256 entries.
		for (Lease lease : residents.values().toArray(Lease[]::new)) {
			if (now >= lease.expiresAt && current(lease)) retire(lease, lease.expiresAt + RETRY);
		}
		cooldowns.values().removeIf(retryAt -> now >= retryAt);
		return true;
	}
}
