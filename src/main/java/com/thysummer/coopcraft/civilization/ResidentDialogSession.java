package com.thysummer.coopcraft.civilization;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Server-thread-only ephemeral protocol state. No inventories, invitations or persistent state are changed here. */
final class ResidentDialogSession<B> {
	static final long IDLE_TICKS = 2400;
	static final int MAX_SESSIONS = 256;
	enum Request { NEXT, DUPLICATE, GAP, CLOSED }
	final UUID token = UUID.randomUUID();
	final B binding;
	final long createdTick;
	private long lastActivityTick, lastRequest, revision;
	private boolean active = true;
	private Object invitation;

	ResidentDialogSession(B binding, long tick) {
		this.binding = Objects.requireNonNull(binding);
		if (tick < 0) throw new IllegalArgumentException("Negative tick");
		createdTick = lastActivityTick = tick;
	}

	boolean alive(long tick) {
		return active && tick >= createdTick && tick >= lastActivityTick && tick - lastActivityTick < IDLE_TICKS;
	}

	/** Claim before invoking effects. An exception can never make the same request executable again. */
	Request claim(long request, long tick) {
		if (!alive(tick)) return Request.CLOSED;
		if (request <= 0) return Request.GAP;
		if (request <= lastRequest) return Request.DUPLICATE;
		if (lastRequest == Long.MAX_VALUE || request != lastRequest + 1) return Request.GAP;
		lastRequest = request;
		lastActivityTick = tick;
		return Request.NEXT;
	}

	boolean matchesToken(UUID candidate) { return active && token.equals(candidate); }
	long acknowledgedRequest() { return lastRequest; }
	long revision() { return revision; }
	void nextRevision() {
		if (revision == Long.MAX_VALUE) throw new IllegalStateException("Dialog revision exhausted");
		revision++;
	}
	void observeInvitation(Object ticket) { invitation = ticket; }
	Object observedInvitation() { return invitation; }
	void close() { active = false; invitation = null; }

	/** Removal is identity-conditional so callbacks cannot remove a replacement session. */
	static final class Registry<B> {
		private final Map<UUID, ResidentDialogSession<B>> sessions = new HashMap<>();
		ResidentDialogSession<B> get(UUID player) { return sessions.get(player); }
		boolean add(UUID player, ResidentDialogSession<B> session) {
			if (sessions.size() >= MAX_SESSIONS || sessions.containsKey(player)) return false;
			sessions.put(player, session);
			return true;
		}
		boolean remove(UUID player, ResidentDialogSession<B> session) {
			if (!sessions.remove(player, session)) return false;
			session.close();
			return true;
		}
		List<ResidentDialogSession<B>> all() { return List.copyOf(sessions.values()); }
	}
}
