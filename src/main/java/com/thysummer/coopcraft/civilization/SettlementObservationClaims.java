package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Bounded, per-tick raw membership snapshot. Does not scan settlements or repair conflicting claims. */
final class SettlementObservationClaims {
	static final int MAX_DIMENSIONS = 32;
	private record Stamp(SettlementMembershipClaims.Source source, long revision) {}
	private final List<Stamp> stamps;
	private final SettlementSavedData local;
	private final Map<UUID, Boolean> unique = new HashMap<>();

	private SettlementObservationClaims(List<Stamp> stamps, SettlementSavedData local) {
		this.stamps = stamps;
		this.local = local;
	}

	static SettlementObservationClaims capture(ServerLevel level, SettlementSavedData data) {
		List<SettlementMembershipClaims.Source> sources = new ArrayList<>();
		try {
			for (ServerLevel source : level.getServer().getAllLevels()) {
				if (source == null || sources.size() >= MAX_DIMENSIONS) return unavailable();
				sources.add(new SettlementMembershipClaims.Source(source.dimension(), SettlementSavedData.get(source)));
			}
			return capture(sources, level.dimension(), data);
		} catch (RuntimeException exception) {
			return unavailable();
		}
	}

	static SettlementObservationClaims capture(List<SettlementMembershipClaims.Source> sources,
		ResourceKey<Level> dimension, SettlementSavedData data) {
		if (sources == null || sources.isEmpty() || sources.size() > MAX_DIMENSIONS || dimension == null || data == null) return unavailable();
		var dimensions = new HashSet<ResourceKey<Level>>();
		var stores = new HashSet<SettlementSavedData>();
		List<Stamp> stamps = new ArrayList<>();
		boolean local = false;
		for (var source : sources) {
			if (source == null || source.dimension() == null || source.data() == null
				|| !dimensions.add(source.dimension()) || !stores.add(source.data())) return unavailable();
			if (source.dimension().equals(dimension)) {
				if (source.data() != data) return unavailable();
				local = true;
			}
			stamps.add(new Stamp(source, source.data().membershipRevision()));
		}
		return local ? new SettlementObservationClaims(List.copyOf(stamps), data) : unavailable();
	}

	boolean unique(SettlementSavedData data, Settlement camp, SettlementMember actor) {
		if (stamps == null || data != local || camp == null || actor == null
			|| !data.canonicalSettlement(camp) || !actor.settlementId().equals(camp.id())
			|| data.memberIdentity(actor.villagerId()) != actor) return false;
		// Any membership mutation invalidates this tick's cached decisions, including revoke/rejoin.
		for (var stamp : stamps) if (stamp.source().data().membershipRevision() != stamp.revision()) return false;
		return unique.computeIfAbsent(actor.villagerId(), id -> {
			for (var stamp : stamps) {
				if (stamp.source().data() != local && stamp.source().data().rawMember(id).isPresent()) return false;
			}
			return data.isMember(camp, id);
		});
	}

	private static SettlementObservationClaims unavailable() { return new SettlementObservationClaims(null, null); }
}
