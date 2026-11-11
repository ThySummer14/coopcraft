package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bounded dimension-local ownership and timing only: no persistence or inventory mutation. */
final class SettlementFoodSharingSessions<T> {
	static final int LIMIT = 128, DURATION = 40;
	static final long TTL = 200, RETRY = 200;
	private final Map<UUID, Pair<T>> residents = new LinkedHashMap<>();
	private final Map<UUID, Long> retries = new LinkedHashMap<>();
	private long lastClock = Long.MIN_VALUE;

	/** Opaque identity token. Only this registry can create or advance its state. */
	static final class Pair<T> {
		final UUID speaker, listener;
		final T value;
		private final long created;
		private boolean speakerJoined, listenerJoined, closed, completed;
		private long joinedAt, tick;
		private boolean speakerSeen, listenerSeen;
		private int together;
		private Pair(UUID speaker, UUID listener, long created, T value) {
			this.speaker = speaker; this.listener = listener; this.created = created; this.value = value;
		}
		boolean completed() { return completed; }
		boolean joined(UUID resident) {
			return speaker.equals(resident) ? speakerJoined : listener.equals(resident) && listenerJoined;
		}
		String phase() { return closed ? "closed" : together > 0 ? "discussing" : "wait"; }
	}

	void expire(long now) {
		if (now < 0) {
			for (Pair<T> pair : pairs()) cancel(pair);
			return;
		}
		if (now < lastClock) {
			lastClock = now;
			for (Pair<T> pair : pairs()) cancel(pair);
			// Rewinds invalidate leases, but cannot bypass the failure cooldown.
			retries.replaceAll((resident, retryAt) -> deadline(now, RETRY));
		}
		lastClock = now;
		retries.entrySet().removeIf(entry -> now >= entry.getValue() && !residents.containsKey(entry.getKey()));
		for (Pair<T> pair : pairs()) {
			if (now < pair.created || now >= deadline(pair.created, TTL)
				|| pair.speakerJoined && pair.listenerJoined && now > pair.joinedAt
					&& (now > pair.tick + 1 || now > pair.tick && pair.tick > pair.joinedAt
						&& !(pair.speakerSeen && pair.listenerSeen))) cancel(pair);
		}
	}
	List<Pair<T>> pairs() { return new ArrayList<>(new LinkedHashSet<>(residents.values())); }
	Pair<T> find(UUID resident, long now) { expire(now); return residents.get(resident); }

	/** Read-only readiness; callers own failed-search backoff. Cooldown covers both identities after release. */
	boolean attempt(UUID resident, long now) {
		expire(now);
		return resident != null && now >= 0 && now <= Long.MAX_VALUE - TTL - RETRY && !residents.containsKey(resident) && !retries.containsKey(resident)
			&& retries.size() < LIMIT * 2;
	}
	Pair<T> reserve(UUID speaker, UUID listener, long now, T value) {
		expire(now);
		if (speaker == null || listener == null || speaker.equals(listener) || now < 0 || now > Long.MAX_VALUE - TTL - RETRY
			|| residents.containsKey(speaker) || residents.containsKey(listener)
			|| retries.containsKey(speaker) || retries.containsKey(listener) || retries.size() > LIMIT * 2 - 2) return null;
		Pair<T> pair = new Pair<>(speaker, listener, now, value);
		residents.put(speaker, pair); residents.put(listener, pair);
		retries.put(speaker, now + RETRY); retries.put(listener, now + RETRY);
		return pair;
	}
	boolean active(Pair<T> pair, long now) {
		expire(now);
		return now >= 0 && owns(pair);
	}
	private boolean owns(Pair<T> pair) {
		return pair != null && !pair.closed && residents.get(pair.speaker) == pair && residents.get(pair.listener) == pair;
	}
	boolean join(Pair<T> pair, UUID resident, long now) {
		if (!active(pair, now)) return false;
		boolean joined = pair.speakerJoined && pair.listenerJoined;
		if (pair.speaker.equals(resident)) pair.speakerJoined = true;
		else if (pair.listener.equals(resident)) pair.listenerJoined = true;
		else return false;
		if (!joined && pair.speakerJoined && pair.listenerJoined) {
			pair.joinedAt = now; pair.tick = now;
		}
		return true;
	}
	/** Count the 40 complete ticks AFTER the final join; consume ownership before returning true once. */
	boolean pulse(Pair<T> pair, UUID resident, long now, boolean safe) {
		if (!active(pair, now) || !pair.speaker.equals(resident) && !pair.listener.equals(resident)) return false;
		if (!safe) { cancel(pair); return false; }
		if (!pair.speakerJoined || !pair.listenerJoined || now <= pair.joinedAt) return false;
		if (pair.tick != now) {
			pair.tick = now; pair.speakerSeen = pair.listenerSeen = false;
		}
		boolean complete = pair.speakerSeen && pair.listenerSeen;
		if (pair.speaker.equals(resident)) pair.speakerSeen = true;
		else pair.listenerSeen = true;
		if (!complete && pair.speakerSeen && pair.listenerSeen && ++pair.together == DURATION) {
			pair.completed = true;
			cancel(pair);
			return true;
		}
		return false;
	}
	void cancel(Pair<T> pair) {
		if (!owns(pair)) return;
		pair.closed = true;
		residents.remove(pair.speaker); residents.remove(pair.listener);
		long retryAt = deadline(Math.max(0, lastClock), RETRY);
		retries.put(pair.speaker, retryAt); retries.put(pair.listener, retryAt);
	}
	private static long deadline(long now, long duration) {
		return now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
	}
	void cancelResident(UUID resident) { cancel(residents.get(resident)); }
}
