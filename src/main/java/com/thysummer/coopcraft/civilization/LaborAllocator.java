package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

public final class LaborAllocator {
	private static final int MIN_DIVIDED_ADULTS = 3;

	private LaborAllocator() {
	}

	public static WorkRole roleOf(ServerLevel level, SettlementSavedData data, Settlement settlement, UUID villagerId) {
		if (level.getEntity(villagerId) instanceof Villager villager && villager.isBaby()) {
			return WorkRole.DEPENDENT;
		}
		List<Worker> adults = new ArrayList<>();
		for (SettlementMember member : data.membersOf(settlement)) {
			if (level.getEntity(member.villagerId()) instanceof Villager loaded && !loaded.isBaby()) {
				adults.add(new Worker(member.villagerId(), member.personality()));
			}
		}
		return allocate(adults).getOrDefault(villagerId, WorkRole.GENERALIST);
	}

	static Map<UUID, WorkRole> allocate(List<Worker> workers) {
		Map<UUID, WorkRole> roles = new HashMap<>();
		if (workers.size() < MIN_DIVIDED_ADULTS) {
			for (Worker worker : workers) {
				roles.put(worker.id(), WorkRole.GENERALIST);
			}
			return Map.copyOf(roles);
		}
		List<Worker> remaining = new ArrayList<>(workers);
		remaining.sort(Comparator.comparing(worker -> worker.id().toString()));
		for (WorkRole role : quota(workers.size())) {
			Worker best = null;
			for (Worker candidate : remaining) {
				if (best == null || better(candidate, best, role)) {
					best = candidate;
				}
			}
			roles.put(best.id(), role);
			remaining.remove(best);
		}
		for (Worker worker : remaining) {
			roles.put(worker.id(), WorkRole.GENERALIST);
		}
		return Map.copyOf(roles);
	}

	static List<WorkRole> quota(int adults) {
		if (adults < MIN_DIVIDED_ADULTS) {
			return List.of();
		}
		List<WorkRole> roles = new ArrayList<>(List.of(WorkRole.FORAGER, WorkRole.MINER, WorkRole.BUILDER));
		if (adults >= 4) {
			roles.add(WorkRole.WOODCUTTER);
		}
		if (adults >= 5) {
			roles.add(WorkRole.CRAFTER);
		}
		return List.copyOf(roles);
	}

	private static boolean better(Worker candidate, Worker current, WorkRole role) {
		int candidateScore = affinity(role, candidate.personality());
		int currentScore = affinity(role, current.personality());
		return candidateScore > currentScore
			|| (candidateScore == currentScore
				&& candidate.id().toString().compareTo(current.id().toString()) < 0);
	}

	static int affinity(WorkRole role, VillagerPersonality personality) {
		return switch (role) {
			case FORAGER -> personality.sociability() * 2
				+ (100 - personality.selfishness()) + personality.diligence() / 2;
			case MINER -> personality.diligence() * 2
				+ personality.selfishness() / 2 + (100 - personality.sociability()) / 3;
			case WOODCUTTER -> personality.diligence() * 2 + (100 - personality.selfishness()) / 2;
			case BUILDER -> personality.diligence() * 2
				+ (100 - personality.selfishness()) + personality.sociability() / 2;
			case CRAFTER -> personality.diligence()
				+ (100 - personality.selfishness()) + personality.sociability() / 2;
			case GENERALIST, DEPENDENT -> 0;
		};
	}

	record Worker(UUID id, VillagerPersonality personality) {
		Worker {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(personality, "personality");
		}
	}
}
