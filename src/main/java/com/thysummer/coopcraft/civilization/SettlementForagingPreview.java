package com.thysummer.coopcraft.civilization;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;

/** Bounded, transient server-thread state. No block reads or persistent changes before confirmation. */
final class SettlementForagingPreview {
	static final int MAX_PREVIEWS = 256;
	static final long TTL_TICKS = 6000;
	private final Map<UUID, Draft> drafts = new HashMap<>();

	enum Status {
		PREVIEW, NO_PREVIEW, CANCELLED, EXPIRED, PLAYER_UNAVAILABLE, DIMENSION_CHANGED,
		CAMP_CHANGED, AREA_CHANGED, INVALID, OUT_OF_BOUNDS, SESSION_LIMIT, CONFIRMED
	}

	/** identity is the actual online ServerPlayer, compared by reference after reconnects/respawns. */
	record Actor(UUID id, Object identity, String dimension, boolean eligible) {}
	record Draft(Object playerIdentity, String dimension, Settlement settlement, BlockPos center, int radius,
		SettlementForagingAreas.Area previousArea, long createdTick) {}
	record Result(Status status, Draft draft) {}
	record Confirmation(Result preview, SettlementForagingAreas.PermitResult saved) {}
	@FunctionalInterface interface Boundary { boolean contains(int x, int z); }
	@FunctionalInterface interface Writer { SettlementForagingAreas.PermitResult save(Draft draft); }

	Result preview(Actor actor, Settlement settlement, BlockPos center, int radius,
		SettlementForagingAreas.Area previousArea, long now, Boundary border) {
		drafts.values().removeIf(draft -> !alive(draft, now));
		if (!eligible(actor)) {
			if (actor != null) forget(actor.id());
			return new Result(Status.PLAYER_UNAVAILABLE, null);
		}
		Draft previous = drafts.get(actor.id());
		// A failed replacement must not leave an earlier, differently sized preview confirmable.
		forget(actor.id());
		if (!SettlementForagingAreas.validRange(settlement, center, radius)) return new Result(Status.INVALID, null);
		if (!within(center, radius, border)) return new Result(Status.OUT_OF_BOUNDS, null);
		if (drafts.size() >= MAX_PREVIEWS) return new Result(Status.SESSION_LIMIT, null);
		long created = previous != null && invalid(actor, previous, now) == null ? previous.createdTick() : now;
		var draft = new Draft(actor.identity(), actor.dimension(), settlement, center.immutable(), radius, previousArea, created);
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

	/** Every confirmation consumes its preview before a possible write, including a rejected/throwing write. */
	Confirmation confirm(Actor actor, long now, Settlement currentSettlement,
		SettlementForagingAreas.Area currentArea, Boundary border, Writer writer) {
		Result checked = inspect(actor, now);
		if (checked.status() != Status.PREVIEW) return new Confirmation(checked, null);
		Draft draft = checked.draft();
		forget(actor.id());
		if (currentSettlement != draft.settlement()) return rejected(Status.CAMP_CHANGED);
		if (currentArea != draft.previousArea()) return rejected(Status.AREA_CHANGED);
		if (!SettlementForagingAreas.validRange(currentSettlement, draft.center(), draft.radius())) return rejected(Status.INVALID);
		if (!within(draft.center(), draft.radius(), border)) return rejected(Status.OUT_OF_BOUNDS);
		return new Confirmation(new Result(Status.CONFIRMED, draft), writer.save(draft));
	}

	Result cancel(UUID playerId) {
		return new Result(drafts.remove(playerId) == null ? Status.NO_PREVIEW : Status.CANCELLED, null);
	}

	void forget(UUID playerId) { drafts.remove(playerId); }
	void forgetSettlement(String dimension, UUID settlementId) {
		// Prevent a create/revoke round trip from restoring an old "no area" preview (ABA).
		drafts.values().removeIf(draft -> draft.dimension().equals(dimension) && draft.settlement().id().equals(settlementId));
	}
	void clear() { drafts.clear(); }
	int size() { return drafts.size(); }

	private static Confirmation rejected(Status status) { return new Confirmation(new Result(status, null), null); }
	private static boolean eligible(Actor actor) {
		return actor != null && actor.id() != null && actor.identity() != null && actor.dimension() != null && actor.eligible();
	}
	private static Status invalid(Actor actor, Draft draft, long now) {
		if (!eligible(actor) || actor.identity() != draft.playerIdentity()) return Status.PLAYER_UNAVAILABLE;
		if (!actor.dimension().equals(draft.dimension())) return Status.DIMENSION_CHANGED;
		return alive(draft, now) ? null : Status.EXPIRED;
	}
	private static boolean alive(Draft draft, long now) {
		return now >= draft.createdTick() && now - draft.createdTick() >= 0 && now - draft.createdTick() < TTL_TICKS;
	}
	private static boolean within(BlockPos center, int radius, Boundary border) {
		int minX = center.getX() - radius, maxX = center.getX() + radius;
		int minZ = center.getZ() - radius, maxZ = center.getZ() + radius;
		return border.contains(minX, minZ) && border.contains(minX, maxZ)
			&& border.contains(maxX, minZ) && border.contains(maxX, maxZ);
	}
}
