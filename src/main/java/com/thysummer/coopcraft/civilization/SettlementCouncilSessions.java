package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Bounded dimension-local ownership and clock gate. Never persisted, never awards anything. */
final class SettlementCouncilSessions<T> {
	static final int LIMIT = 128, DURATION = 200;
	static final long TTL = 600, RETRY = 200;
	private final Map<UUID, Group<T>> camps = new LinkedHashMap<>();
	private final Map<UUID, Group<T>> actors = new LinkedHashMap<>();
	private final Map<UUID, Long> retries = new LinkedHashMap<>();
	private long clock = Long.MIN_VALUE;

	static final class Group<T> {
		final UUID camp;
		final List<UUID> participants;
		final T value;
		final long created;
		final Set<UUID> joined = new HashSet<>(), seen = new HashSet<>();
		long tick = Long.MIN_VALUE, joinedAt = Long.MIN_VALUE;
		int together;
		boolean counted, closed, completed;
		Group(UUID camp, List<UUID> participants, long created, T value) {
			this.camp = camp; this.participants = List.copyOf(participants); this.created = created; this.value = value;
		}
		String phase() { return closed ? "closed" : together > 0 ? "discussing" : "wait"; }
	}
	static boolean quorum(int participants, int adults) {
		return participants >= 2 && participants <= 4 && adults >= participants && participants * 100L >= adults * 35L;
	}
	List<Group<T>> groups() { return new ArrayList<>(camps.values()); }
	boolean claimed(UUID actor) { return actors.containsKey(actor); }
	void expire(long now) {
		if (now < clock) { for (var group : groups()) cancel(group); retries.clear(); }
		clock = now;
		for (var group : groups()) if (now < group.created || now - group.created >= TTL) cancel(group);
	}
	Group<T> find(UUID actor, long now) { expire(now); return actors.get(actor); }
	boolean attempt(UUID camp, long now) {
		expire(now);
		if (now < 0 || now > Long.MAX_VALUE - TTL || camps.containsKey(camp)) return false;
		Long next = retries.get(camp);
		if (next != null && now < next) return false;
		if (retries.size() >= LIMIT) retries.remove(retries.keySet().iterator().next());
		retries.put(camp, now + RETRY);
		return true;
	}
	Group<T> reserve(UUID camp, List<UUID> participants, int adults, long now, T value) {
		expire(now);
		if (camp == null || now < 0 || now > Long.MAX_VALUE - TTL || !quorum(participants.size(), adults)
			|| participants.stream().anyMatch(id -> id == null || actors.containsKey(id))
			|| new HashSet<>(participants).size() != participants.size() || camps.containsKey(camp) || camps.size() >= LIMIT) return null;
		var group = new Group<>(camp, participants, now, value);
		camps.put(camp, group); participants.forEach(id -> actors.put(id, group));
		return group;
	}
	boolean active(Group<T> group, long now) {
		expire(now);
		return group != null && !group.closed && camps.get(group.camp) == group
			&& group.participants.stream().allMatch(id -> actors.get(id) == group);
	}
	boolean join(Group<T> group, UUID actor, long now) {
		if (!active(group, now) || !group.participants.contains(actor)) return false;
		group.joined.add(actor);
		if (group.joined.size() == group.participants.size() && group.joinedAt == Long.MIN_VALUE) group.joinedAt = now;
		return true;
	}
	/** Consumes ownership before returning true, exactly once, after all actors independently pulse. */
	boolean pulse(Group<T> group, UUID actor, long now, boolean safe) {
		if (!active(group, now) || !group.participants.contains(actor)) return false;
		if (!safe) { cancel(group); return false; }
		// Begin on the next tick: the final join may occur after earlier actors already ticked.
		if (group.joined.size() != group.participants.size() || !group.joined.contains(actor) || now <= group.joinedAt) return false;
		if (group.tick == Long.MIN_VALUE && now != group.joinedAt + 1) { cancel(group); return false; }
		if (group.tick != now) {
			if (group.tick != Long.MIN_VALUE && (now != group.tick + 1 || !group.counted)) { cancel(group); return false; }
			group.tick = now; group.seen.clear(); group.counted = false;
		}
		group.seen.add(actor);
		if (!group.counted && group.seen.size() == group.participants.size()) {
			group.counted = true;
			if (++group.together == DURATION) { group.completed = true; cancel(group); return true; }
		}
		return false;
	}
	void cancel(Group<T> group) {
		if (group == null) return;
		group.closed = true; camps.remove(group.camp, group);
		group.participants.forEach(id -> actors.remove(id, group));
	}
	void cancelResident(UUID actor) { cancel(actors.get(actor)); }
}
