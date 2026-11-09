package com.thysummer.coopcraft.civilization;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Server-thread-only failed-survey timing, owned by one SavedData instance, never serialized. */
final class ConstructionSiteRetries {
	static final int RETRY_TICKS = 20;
	static final int MAX_ENTRIES = 1024;
	private final Map<Key, Failure> failures = new LinkedHashMap<>();

	boolean shouldWait(Settlement settlement, ConstructionPlan plan, long gameTime) {
		Key key = new Key(settlement.id(), plan.id());
		Failure failure = failures.get(key);
		if (failure == null) return false;
		// Reject rollback and signed subtraction overflow; both must allow a fresh retry.
		long elapsed = gameTime - failure.gameTime();
		if (failure.center().equals(settlement.center()) && failure.fingerprint().equals(plan.fingerprint())
			&& gameTime >= failure.gameTime() && elapsed >= 0 && elapsed < RETRY_TICKS) return true;
		failures.remove(key);
		return false;
	}

	void failed(Settlement settlement, ConstructionPlan plan, long gameTime) {
		Key key = new Key(settlement.id(), plan.id());
		failures.remove(key);
		if (failures.size() >= MAX_ENTRIES) failures.remove(failures.keySet().iterator().next());
		failures.put(key, new Failure(gameTime, settlement.center().immutable(), plan.fingerprint()));
	}

	void succeeded(Settlement settlement, ConstructionPlan plan) {
		failures.remove(new Key(settlement.id(), plan.id()));
	}

	int size() { return failures.size(); }

	private record Key(UUID settlementId, ResourceLocation blueprintId) {}
	private record Failure(long gameTime, BlockPos center, String fingerprint) {}
}
