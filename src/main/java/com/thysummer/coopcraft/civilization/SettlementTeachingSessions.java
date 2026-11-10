package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Dimension-local ephemeral pair ownership. No world references, persistence or rewards here. */
final class SettlementTeachingSessions<T> {
	static final int LIMIT = 128, DURATION = 200;
	static final long TTL = 1200, RETRY = 200;
	private final Map<UUID, Pair<T>> residents = new LinkedHashMap<>();
	private final Map<UUID, Long> retries = new LinkedHashMap<>();
	private long lastClock = Long.MIN_VALUE;

	static final class Pair<T> {
		final UUID teacher, learner;
		final T value;
		final long created;
		boolean teacherJoined, learnerJoined, closed, completed;
		long tick = Long.MIN_VALUE, lastTogether = Long.MIN_VALUE;
		boolean teacherSeen, learnerSeen, teacherSafe, learnerSafe, counted;
		int together;
		Pair(UUID teacher, UUID learner, long created, T value) {
			this.teacher = teacher; this.learner = learner; this.created = created; this.value = value;
		}
		String phase() { return closed ? "closed" : together > 0 ? "teaching" : teacherSeen || learnerSeen ? "wait" : "travel"; }
	}

	void expire(long now) {
		if (now < lastClock) { for (Pair<T> pair : pairs()) cancel(pair); retries.clear(); }
		lastClock = now;
		for (Pair<T> pair : pairs()) if (now < pair.created || now - pair.created >= TTL) cancel(pair);
	}
	List<Pair<T>> pairs() { return new ArrayList<>(new java.util.LinkedHashSet<>(residents.values())); }
	Pair<T> find(UUID resident, long now) { expire(now); return residents.get(resident); }
	boolean attempt(UUID resident, long now) {
		expire(now);
		Long next = retries.get(resident);
		if (next != null && now < next) return false;
		if (retries.size() >= LIMIT * 2) retries.remove(retries.keySet().iterator().next());
		retries.put(resident, now + RETRY);
		return true;
	}
	Pair<T> reserve(UUID teacher, UUID learner, long now, T value) {
		expire(now);
		if (teacher == null || learner == null || teacher.equals(learner) || residents.containsKey(teacher)
			|| residents.containsKey(learner) || residents.size() >= LIMIT * 2) return null;
		Pair<T> pair = new Pair<>(teacher, learner, now, value);
		residents.put(teacher, pair); residents.put(learner, pair);
		return pair;
	}
	boolean active(Pair<T> pair, long now) {
		expire(now);
		return pair != null && !pair.closed && residents.get(pair.teacher) == pair && residents.get(pair.learner) == pair;
	}
	boolean join(Pair<T> pair, UUID resident, long now) {
		if (!active(pair, now)) return false;
		if (pair.teacher.equals(resident)) pair.teacherJoined = true;
		else if (pair.learner.equals(resident)) pair.learnerJoined = true;
		else return false;
		return true;
	}
	/** True exactly once, only after 200 consecutive ticks with two independent safe pulses. */
	boolean pulse(Pair<T> pair, UUID resident, long now, boolean safe) {
		if (!active(pair, now) || !pair.teacherJoined || !pair.learnerJoined
			|| !pair.teacher.equals(resident) && !pair.learner.equals(resident)) return false;
		if (pair.tick != now) {
			if (pair.together > 0 && pair.lastTogether != now - 1) { cancel(pair); return false; }
			pair.tick = now; pair.teacherSeen = pair.learnerSeen = pair.teacherSafe = pair.learnerSafe = pair.counted = false;
		}
		if (pair.teacher.equals(resident)) {
			pair.teacherSafe = pair.teacherSeen ? pair.teacherSafe && safe : safe; pair.teacherSeen = true;
		} else { pair.learnerSafe = pair.learnerSeen ? pair.learnerSafe && safe : safe; pair.learnerSeen = true; }
		if (!safe) {
			if (pair.together > 0) { cancel(pair); return false; }
			pair.lastTogether = Long.MIN_VALUE;
		}
		if (pair.teacherSeen && pair.learnerSeen && pair.teacherSafe && pair.learnerSafe && !pair.counted) {
			pair.counted = true; pair.lastTogether = now;
			if (++pair.together >= DURATION) { pair.completed = true; cancel(pair); return true; }
		}
		return false;
	}
	void cancel(Pair<T> pair) {
		if (pair == null) return;
		pair.closed = true; residents.remove(pair.teacher, pair); residents.remove(pair.learner, pair);
	}
	void cancelResident(UUID resident) { cancel(residents.get(resident)); }
}
