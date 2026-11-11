package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Bounded, dimension-local delivery ownership; never persists or mutates inventory. */
final class SettlementFoodDeliveryReservations<T> {
	static final int LIMIT = 128;
	static final long TTL = 160, RETRY = 200;
	private final Map<UUID, Lease<T>> residents = new LinkedHashMap<>();
	private final Map<UUID, Long> retries = new LinkedHashMap<>();
	private long lastClock = Long.MIN_VALUE;

	/** Identity token; callers may inspect the parties and payload, never manufacture ownership. */
	static final class Lease<T> {
		final UUID giver, recipient;
		final T value;
		private final long created;
		private boolean closed, transferring;
		private Lease(UUID giver, UUID recipient, long created, T value) {
			this.giver = giver; this.recipient = recipient; this.created = created; this.value = value;
		}
	}

	synchronized Lease<T> reserve(UUID giver, UUID recipient, long now, T value) {
		expire(now);
		if (giver == null || recipient == null || giver.equals(recipient) || !validTime(now)
			|| residents.containsKey(giver) || residents.containsKey(recipient)
			|| retries.containsKey(giver) || retries.containsKey(recipient) || retries.size() > LIMIT * 2 - 2) return null;
		Lease<T> lease = new Lease<>(giver, recipient, now, value);
		residents.put(giver, lease); residents.put(recipient, lease);
		retries.put(giver, deadline(now, RETRY)); retries.put(recipient, deadline(now, RETRY));
		return lease;
	}

	synchronized Lease<T> find(UUID resident, long now) { expire(now); return residents.get(resident); }
	synchronized boolean active(Lease<T> lease, long now) { expire(now); return now >= 0 && owns(lease); }

	/** Read-only readiness. Failed searches remain the caller's responsibility. */
	synchronized boolean attempt(UUID resident, long now) {
		expire(now);
		return resident != null && validTime(now) && !residents.containsKey(resident)
			&& !retries.containsKey(resident) && retries.size() <= LIMIT * 2 - 2;
	}

	/** Promote to a sharing session while both parties are still owned. Consume even on failure. */
	synchronized boolean transfer(Lease<T> lease, long now, BooleanSupplier promote) {
		if (!active(lease, now) || lease.transferring) return false;
		lease.transferring = true;
		try {
			return promote.getAsBoolean() && owns(lease);
		} finally {
			cancel(lease);
		}
	}

	synchronized void cancel(Lease<T> lease) {
		if (!owns(lease)) return;
		lease.closed = true;
		residents.remove(lease.giver); residents.remove(lease.recipient);
		long retryAt = deadline(Math.max(0, lastClock), RETRY);
		retries.put(lease.giver, retryAt); retries.put(lease.recipient, retryAt);
	}
	synchronized void cancelResident(UUID resident) { cancel(residents.get(resident)); }

	private boolean owns(Lease<T> lease) {
		return lease != null && !lease.closed
			&& residents.get(lease.giver) == lease && residents.get(lease.recipient) == lease;
	}
	private void expire(long now) {
		long clock = Math.max(0, now);
		if (now < 0 || clock < lastClock) {
			lastClock = clock;
			for (Lease<T> lease : new ArrayList<>(new LinkedHashSet<>(residents.values()))) cancel(lease);
			// A clock rewind must not turn cancellation into an immediate retry.
			retries.replaceAll((resident, retryAt) -> deadline(clock, RETRY));
		}
		lastClock = clock;
		retries.entrySet().removeIf(entry -> clock >= entry.getValue() && !residents.containsKey(entry.getKey()));
		for (Lease<T> lease : new ArrayList<>(new LinkedHashSet<>(residents.values()))) {
			if (clock >= deadline(lease.created, TTL)) cancel(lease);
		}
	}
	private static boolean validTime(long now) { return now >= 0 && now <= Long.MAX_VALUE - TTL - RETRY; }
	private static long deadline(long now, long duration) {
		return now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
	}
}
