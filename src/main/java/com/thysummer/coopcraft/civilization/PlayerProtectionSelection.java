package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Bounded, server-thread-confined selection state. No world access or persistence before confirm. */
public final class PlayerProtectionSelection {
	public static final int MAX_SESSIONS = 256;
	public static final long TTL_TICKS = 5 * 60 * 20;
	public static final long CLOSED_GESTURE_TICKS = 20;
	private final Map<UUID, Draft> sessions = new HashMap<>();
	// Active drafts plus short-lived close barriers share the same hard capacity.
	private final Map<UUID, Long> closedGestures = new HashMap<>();

	public enum Status {
		STARTED, FIRST_CORNER, PREVIEW, GUIDANCE, NO_SELECTION, CANCELLED, EXPIRED,
		DIMENSION_CHANGED, PLAYER_UNAVAILABLE, SESSION_LIMIT, INVALID_NAME, OUT_OF_BOUNDS,
		TOO_LARGE, NEED_CORNERS, CONFIRMED, SAVE_REJECTED, CLOSED_GESTURE
	}

	/** eligible means the same online, alive, non-spectating player at the time of this operation. */
	public record Actor(UUID id, String dimension, boolean eligible) {}
	public record Point(int x, int z) {}
	public record Bounds(int minX, int minZ, int maxX, int maxZ) {
		public int width() { return maxX - minX + 1; }
		public int depth() { return maxZ - minZ + 1; }
	}
	public record Draft(String dimension, String name, Point first, Point second, long createdTick) {
		public Bounds bounds() {
			return first == null || second == null ? null : new Bounds(
				Math.min(first.x(), second.x()), Math.min(first.z(), second.z()),
				Math.max(first.x(), second.x()), Math.max(first.z(), second.z()));
		}
	}
	public record Result(Status status, Draft draft) {
		/** Every click that began with a selection is consumed, including invalid or expired clicks. */
		public boolean consumesClick() { return status != Status.NO_SELECTION; }
	}
	public record Confirmation(Result selection, PlayerProtectedAreas.AddResult saved) {}
	public record Closed(UUID playerId, Status reason) {}

	@FunctionalInterface
	public interface Boundary { boolean contains(int x, int z); }
	@FunctionalInterface
	public interface Writer { PlayerProtectedAreas.AddResult add(UUID owner, String name, Bounds bounds); }

	public Result start(Actor actor, String name, long now) {
		pruneClosedGestures(now);
		pruneExpired(now);
		if (!eligible(actor)) {
			if (actor != null) cancel(actor.id(), now);
			return new Result(Status.PLAYER_UNAVAILABLE, null);
		}
		String normalized = PlayerProtectedAreas.normalizeName(name);
		if (!PlayerProtectedAreas.validName(normalized)) return new Result(Status.INVALID_NAME, null);
		if (!sessions.containsKey(actor.id()) && !closedGestures.containsKey(actor.id()) && trackedCount() >= MAX_SESSIONS) {
			return new Result(Status.SESSION_LIMIT, null);
		}
		var draft = new Draft(actor.dimension(), normalized, null, null, now);
		closedGestures.remove(actor.id());
		sessions.put(actor.id(), draft);
		return new Result(Status.STARTED, draft);
	}

	public Result click(Actor actor, long now, boolean mainHand, boolean sneaking,
		boolean emptyMainHand, int x, int z, Boundary boundary) {
		pruneClosedGestures(now);
		Result checked = inspect(actor, now);
		if (checked.status() == Status.NO_SELECTION && actor != null && closedGestures.containsKey(actor.id())) {
			return new Result(Status.CLOSED_GESTURE, null);
		}
		if (checked.status() != Status.PREVIEW && checked.status() != Status.NEED_CORNERS) return checked;
		Draft draft = checked.draft();
		if (!mainHand || !sneaking || !emptyMainHand) return new Result(Status.GUIDANCE, draft);
		if (!PlayerProtectedAreas.validBounds(x, z, x, z) || !boundary.contains(x, z)) {
			return new Result(Status.OUT_OF_BOUNDS, draft);
		}
		if (draft.second() != null) return new Result(Status.PREVIEW, draft);
		var point = new Point(x, z);
		if (draft.first() == null) {
			draft = new Draft(draft.dimension(), draft.name(), point, null, draft.createdTick());
			sessions.put(actor.id(), draft);
			return new Result(Status.FIRST_CORNER, draft);
		}
		if (!PlayerProtectedAreas.validBounds(draft.first().x(), draft.first().z(), x, z)) {
			return new Result(Status.TOO_LARGE, draft);
		}
		Draft completed = new Draft(draft.dimension(), draft.name(), draft.first(), point, draft.createdTick());
		if (!within(completed.bounds(), boundary)) return new Result(Status.OUT_OF_BOUNDS, draft);
		sessions.put(actor.id(), completed);
		return new Result(Status.PREVIEW, completed);
	}

	/** The writer is invoked at most once per draft, even if it throws after changing data. */
	public Confirmation confirm(Actor actor, long now, Boundary boundary, Writer writer) {
		Result checked = inspect(actor, now);
		if (checked.status() != Status.PREVIEW) return new Confirmation(checked, null);
		Draft draft = checked.draft();
		Bounds bounds = draft.bounds();
		if (!PlayerProtectedAreas.validName(draft.name())
			|| !PlayerProtectedAreas.validBounds(bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ())) {
			close(actor.id(), now);
			return new Confirmation(new Result(Status.TOO_LARGE, null), null);
		}
		if (!within(bounds, boundary)) {
			close(actor.id(), now);
			return new Confirmation(new Result(Status.OUT_OF_BOUNDS, null), null);
		}
		// Consume before the effect. Failure is explicit and requires a fresh selection; no blind replay.
		close(actor.id(), now);
		PlayerProtectedAreas.AddResult saved = writer.add(actor.id(), draft.name(), bounds);
		return new Confirmation(new Result(saved.status() == PlayerProtectedAreas.Status.ADDED
			? Status.CONFIRMED : Status.SAVE_REJECTED, draft), saved);
	}

	/** Revalidates identity, role, dimension and the fixed (not sliding) tick deadline. */
	public Result inspect(Actor actor, long now) {
		Draft draft = actor == null || actor.id() == null ? null : sessions.get(actor.id());
		if (draft == null) return new Result(Status.NO_SELECTION, null);
		Status invalid = invalidReason(actor, draft, now);
		if (invalid != null) {
			close(actor.id(), now);
			return new Result(invalid, null);
		}
		return new Result(draft.second() == null ? Status.NEED_CORNERS : Status.PREVIEW, draft);
	}

	public Result cancel(UUID id, long now) {
		pruneClosedGestures(now);
		return new Result(close(id, now) ? Status.CANCELLED : Status.NO_SELECTION, null);
	}

	/** Disconnected connections cannot deliver another interaction; no tombstone is needed. */
	public void forget(UUID id) {
		sessions.remove(id);
		closedGestures.remove(id);
	}

	/** Resolver must return null for offline players. Never loads blocks, chunks or players. */
	public List<Closed> prune(long now, Function<UUID, Actor> onlinePlayer) {
		pruneClosedGestures(now);
		var closed = new ArrayList<Closed>();
		var iterator = sessions.entrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			Actor actor = onlinePlayer.apply(entry.getKey());
			Status invalid = actor == null || !entry.getKey().equals(actor.id()) ? Status.PLAYER_UNAVAILABLE
				: invalidReason(actor, entry.getValue(), now);
			if (invalid != null) {
				closed.add(new Closed(entry.getKey(), invalid));
				iterator.remove();
				if (actor != null) closedGestures.put(entry.getKey(), now);
			}
		}
		return List.copyOf(closed);
	}

	public void clear() { sessions.clear(); closedGestures.clear(); }
	public int size() { return sessions.size(); }
	public int trackedCount() { return sessions.size() + closedGestures.size(); }

	private boolean close(UUID id, long now) {
		if (id == null || sessions.remove(id) == null) return false;
		closedGestures.put(id, now);
		return true;
	}

	private void pruneClosedGestures(long now) {
		closedGestures.values().removeIf(closed -> now < closed || now - closed < 0
			|| now - closed >= CLOSED_GESTURE_TICKS);
	}

	private void pruneExpired(long now) {
		var iterator = sessions.entrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			if (!alive(entry.getValue(), now)) {
				closedGestures.put(entry.getKey(), now);
				iterator.remove();
			}
		}
	}

	private static Status invalidReason(Actor actor, Draft draft, long now) {
		if (!eligible(actor)) return Status.PLAYER_UNAVAILABLE;
		if (!draft.dimension().equals(actor.dimension())) return Status.DIMENSION_CHANGED;
		return alive(draft, now) ? null : Status.EXPIRED;
	}

	private static boolean eligible(Actor actor) {
		return actor != null && actor.id() != null && actor.dimension() != null && actor.eligible();
	}

	private static boolean alive(Draft draft, long now) {
		// Subtraction is safe for a monotonic nonnegative world tick clock, including its upper boundary.
		return now >= draft.createdTick() && now - draft.createdTick() >= 0
			&& now - draft.createdTick() < TTL_TICKS;
	}

	private static boolean within(Bounds bounds, Boundary boundary) {
		return boundary.contains(bounds.minX(), bounds.minZ()) && boundary.contains(bounds.maxX(), bounds.maxZ());
	}
}
