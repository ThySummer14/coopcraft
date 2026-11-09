package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Read-only raw claims across every server dimension. Never repairs or chooses between claims. */
public final class SettlementMembershipClaims {
	private SettlementMembershipClaims() { }

	public enum Status { NONE, UNIQUE, ORPHAN, CONFLICT, UNAVAILABLE }
	public record Source(ResourceKey<Level> dimension, SettlementSavedData data) { }
	public record Claim(ResourceKey<Level> dimension, SettlementSavedData data,
			SettlementMember member, Settlement settlement) { }
	public record Resolution(Status status, List<Claim> claims) {
		public Resolution { claims = List.copyOf(claims); }
		public Optional<Claim> uniqueClaim() {
			return status == Status.UNIQUE && claims.size() == 1 ? Optional.of(claims.getFirst()) : Optional.empty();
		}
	}

	public static Resolution resolve(ServerLevel currentLevel, UUID villagerId) {
		return resolve(sources(currentLevel), currentLevel == null ? null : currentLevel.dimension(), villagerId);
	}

	/** Null denotes an incomplete read, never an empty universe. No chunks are requested and membership is never changed. */
	static List<Source> sources(ServerLevel currentLevel) {
		if (currentLevel == null) return null;
		try {
			if (currentLevel.getServer() == null) return null;
			List<Source> sources = new ArrayList<>();
			boolean foundCurrent = false;
			for (ServerLevel level : currentLevel.getServer().getAllLevels()) {
				if (level == null) return null;
				foundCurrent |= level == currentLevel;
				sources.add(new Source(level.dimension(), SettlementSavedData.get(level)));
			}
			return foundCurrent ? List.copyOf(sources) : null;
		} catch (RuntimeException exception) {
			return null;
		}
	}

	/**
	 * Explicitly missing/duplicate sources and exceptions reaching this caller fail closed.
	 * Vanilla storage may itself replace unreadable files with empty data; that is not detectable here.
	 */
	public static Resolution resolve(List<Source> sources, ResourceKey<Level> currentDimension, UUID villagerId) {
		if (sources == null || currentDimension == null || villagerId == null) return unavailable();
		try {
			var dimensions = new HashSet<ResourceKey<Level>>();
			List<Claim> claims = new ArrayList<>();
			for (Source source : sources) {
				if (source == null || source.dimension() == null || source.data() == null
						|| !dimensions.add(source.dimension())) return unavailable();
				SettlementMember member = source.data().rawMember(villagerId).orElse(null);
				if (member == null) continue;
				List<Settlement> settlements = source.data().all().stream()
					.filter(settlement -> settlement.id().equals(member.settlementId())).toList();
				if (settlements.size() > 1) return unavailable();
				claims.add(new Claim(source.dimension(), source.data(), member,
					settlements.isEmpty() ? null : settlements.getFirst()));
			}
			if (!dimensions.contains(currentDimension)) return unavailable();
			claims.sort(Comparator.comparing(claim -> claim.dimension().location().toString()));
			Status status = claims.isEmpty() ? Status.NONE : claims.size() > 1 ? Status.CONFLICT
				: claims.getFirst().settlement() == null ? Status.ORPHAN : Status.UNIQUE;
			return new Resolution(status, claims);
		} catch (RuntimeException exception) {
			return unavailable();
		}
	}

	static boolean belongsTo(Resolution resolution, ResourceKey<Level> dimension,
			SettlementSavedData data, Settlement settlement) {
		return resolution.uniqueClaim().filter(claim -> claim.dimension().equals(dimension)
			&& claim.data() == data && claim.settlement().equals(settlement)).isPresent();
	}

	private static Resolution unavailable() { return new Resolution(Status.UNAVAILABLE, List.of()); }
}
