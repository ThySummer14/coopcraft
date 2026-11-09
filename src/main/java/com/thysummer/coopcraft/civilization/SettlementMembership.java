package com.thysummer.coopcraft.civilization;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

/** Join-only membership within one dimension's saved data; never relocates existing claims. */
final class SettlementMembership {
	static final double MEMBER_RADIUS_SQUARED = 64.0 * 64.0;
	static final double FOUNDING_RADIUS_SQUARED = 32.0 * 32.0;

	private SettlementMembership() {
	}

	/** Observed identity eligibility, deliberately independent of health/work availability. */
	record Candidate(UUID villagerId, boolean alive, boolean removed, boolean sameLevel,
			boolean loaded, double distanceSquared) { }

	static Candidate observe(ServerLevel level, BlockPos center, Villager villager) {
		if (level == null || center == null || villager == null) return null;
		boolean sameLevel = villager.level() == level;
		boolean loaded = sameLevel && level.hasChunkAt(villager.blockPosition())
			&& level.getEntity(villager.getUUID()) == villager;
		return new Candidate(villager.getUUID(), villager.isAlive(), villager.isRemoved(), sameLevel,
			loaded, villager.distanceToSqr(center.getCenter()));
	}

	static boolean eligible(Candidate candidate, double radiusSquared) {
		return candidate != null && candidate.villagerId() != null && candidate.alive()
			&& !candidate.removed() && candidate.sameLevel() && candidate.loaded()
			&& Double.isFinite(candidate.distanceSquared()) && candidate.distanceSquared() >= 0
			&& candidate.distanceSquared() <= radiusSquared;
	}

	/** Immutable accepted identities only. Out-of-range or absent members keep their records. */
	static List<UUID> registerCandidates(SettlementSavedData data, Settlement settlement,
			List<Candidate> candidates, long joinedDay) {
		if (data == null || candidates == null) return List.of();
		var accepted = new LinkedHashSet<UUID>();
		for (Candidate candidate : candidates) {
			if (eligible(candidate, MEMBER_RADIUS_SQUARED) && !accepted.contains(candidate.villagerId())
					&& data.registerMember(settlement, candidate.villagerId(), joinedDay).accepted()) {
				accepted.add(candidate.villagerId());
			}
		}
		return List.copyOf(accepted);
	}

	/** Raw claims include orphan records, which cannot be adopted by founding another camp. */
	static List<UUID> unclaimedFounders(SettlementSavedData data, List<Candidate> candidates) {
		if (data == null || candidates == null) return List.of();
		var founders = new LinkedHashSet<UUID>();
		for (Candidate candidate : candidates) {
			if (eligible(candidate, FOUNDING_RADIUS_SQUARED) && !data.isRegistered(candidate.villagerId())) {
				founders.add(candidate.villagerId());
			}
		}
		return List.copyOf(founders);
	}
}
