package com.thysummer.coopcraft.civilization;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Transient, bounded consent sessions. Object identities deliberately survive no reconnect or reload. */
final class SettlementShearingPreview {
	static final int MAX_PREVIEWS = 256;
	static final long TTL_TICKS = 6000;
	private final Map<UUID, Draft> drafts = new HashMap<>();
	enum Status { PREVIEW, NO_PREVIEW, CANCELLED, EXPIRED, PLAYER_UNAVAILABLE, DIMENSION_CHANGED,
		CAMP_CHANGED, DATA_CHANGED, TARGET_CHANGED, INVALID_TARGET, SESSION_LIMIT, CONFIRMED }
	record Actor(UUID id, Object identity, String dimension, boolean eligible) {}
	record Target(UUID id, Object identity, boolean eligible) {}
	record Draft(Object playerIdentity, String dimension, Object data, Settlement camp, Object revision,
		UUID sheepId, Object sheepIdentity, long createdTick) {}
	record Result(Status status, Draft draft) {}
	record Confirmation(Result preview, SettlementShearingPermits.PermitResult saved) {}

	Result preview(Actor actor, Object data, Settlement camp, Object revision, Target target, long now) {
		drafts.values().removeIf(draft -> !alive(draft, now));
		Draft previous = actor == null ? null : drafts.remove(actor.id());
		if (!eligible(actor)) return new Result(Status.PLAYER_UNAVAILABLE, null);
		if (data == null || camp == null || revision == null || !eligible(target)) return new Result(Status.INVALID_TARGET, null);
		if (drafts.size() >= MAX_PREVIEWS) return new Result(Status.SESSION_LIMIT, null);
		long created = previous != null && invalid(actor, previous, now) == null ? previous.createdTick() : now;
		Draft draft = new Draft(actor.identity(), actor.dimension(), data, camp, revision, target.id(), target.identity(), created);
		drafts.put(actor.id(), draft);
		return new Result(Status.PREVIEW, draft);
	}

	Result inspect(Actor actor, long now) {
		Draft draft = actor == null ? null : drafts.get(actor.id());
		if (draft == null) return new Result(Status.NO_PREVIEW, null);
		Status invalid = invalid(actor, draft, now);
		if (invalid == null) return new Result(Status.PREVIEW, draft);
		forget(actor.id());
		return new Result(invalid, null);
	}

	/** Consume before resolving the current entity or calling a writer, including exceptions. */
	Confirmation confirm(Actor actor, Object data, Settlement camp, Object revision, long now,
		Function<UUID, Target> resolver, Function<Draft, SettlementShearingPermits.PermitResult> writer) {
		Result checked = inspect(actor, now);
		if (checked.status() != Status.PREVIEW) return new Confirmation(checked, null);
		Draft draft = checked.draft();
		forget(actor.id());
		if (data != draft.data() || revision != draft.revision()) return rejected(Status.DATA_CHANGED);
		if (camp != draft.camp()) return rejected(Status.CAMP_CHANGED);
		Target current = resolver.apply(draft.sheepId());
		if (current == null || current.identity() != draft.sheepIdentity() || !draft.sheepId().equals(current.id()))
			return rejected(Status.TARGET_CHANGED);
		if (!eligible(current)) return rejected(Status.INVALID_TARGET);
		return new Confirmation(new Result(Status.CONFIRMED, draft), writer.apply(draft));
	}
	Result cancel(UUID id) { return new Result(drafts.remove(id) == null ? Status.NO_PREVIEW : Status.CANCELLED, null); }
	void forget(UUID id) { drafts.remove(id); }
	void clear() { drafts.clear(); }
	int size() { return drafts.size(); }
	private static Confirmation rejected(Status status) { return new Confirmation(new Result(status, null), null); }
	private static boolean eligible(Actor actor) { return actor != null && actor.id() != null && actor.identity() != null && actor.dimension() != null && actor.eligible(); }
	private static boolean eligible(Target target) { return target != null && target.id() != null && target.identity() != null && target.eligible(); }
	private static boolean alive(Draft draft, long now) { return now >= draft.createdTick() && now - draft.createdTick() >= 0 && now - draft.createdTick() < TTL_TICKS; }
	private static Status invalid(Actor actor, Draft draft, long now) {
		if (!eligible(actor) || actor.identity() != draft.playerIdentity()) return Status.PLAYER_UNAVAILABLE;
		if (!actor.dimension().equals(draft.dimension())) return Status.DIMENSION_CHANGED;
		return alive(draft, now) ? null : Status.EXPIRED;
	}
}
