package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.Person;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

public final class CarePlanner {
	private static final double CARE_RADIUS_SQUARED = 24.0 * 24.0;
	private static final int TRUST_THRESHOLD = 30;
	private static final int SOCIABILITY_THRESHOLD = 60;

	private CarePlanner() {
	}

	public static List<CareAssignment> plan(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		long currentDay,
		List<CareAssignment> existing
	) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null) {
			return List.of();
		}
		List<CareAssignment> planned = new ArrayList<>();
		Set<UUID> caregivers = new HashSet<>();
		Set<UUID> dependents = new HashSet<>();
		for (CareAssignment assignment : existing) {
			if (assignment.day() != currentDay) {
				continue;
			}
			if (assignment.completed() || canContinue(assignment, level, data, settlement, state)) {
				planned.add(assignment);
				caregivers.add(assignment.caregiverId());
				dependents.add(assignment.dependentId());
			}
		}

		List<Villager> residents = loadedResidents(level, data, settlement);
		List<Villager> needingCare = residents.stream()
			.filter(villager -> !dependents.contains(villager.getUUID()))
			.filter(villager -> person(state, villager).needsCare())
			.sorted(Comparator.comparing(villager -> villager.getUUID().toString()))
			.toList();
		for (Villager dependent : needingCare) {
			Villager caregiver = residents.stream()
				.filter(candidate -> !caregivers.contains(candidate.getUUID()))
				.filter(candidate -> !candidate.getUUID().equals(dependent.getUUID()))
				.filter(candidate -> person(state, candidate).canProvideCare())
				.filter(candidate -> data.needsOf(settlement, candidate.getUUID()).satiety() > 20)
				.filter(candidate -> candidate.distanceToSqr(dependent) <= CARE_RADIUS_SQUARED)
				.filter(candidate -> willing(data, settlement, state, candidate, dependent))
				.max(Comparator.comparingInt((Villager candidate) -> score(data, settlement, state, candidate, dependent))
					.thenComparing(candidate -> candidate.getUUID().toString(), Comparator.reverseOrder()))
				.orElse(null);
			if (caregiver != null) {
				planned.add(CareAssignment.start(currentDay, caregiver.getUUID(), dependent.getUUID()));
				caregivers.add(caregiver.getUUID());
				dependents.add(dependent.getUUID());
			}
		}
		planned.sort(Comparator.comparing(assignment -> assignment.dependentId().toString()));
		return List.copyOf(planned);
	}

	private static boolean canContinue(
		CareAssignment assignment,
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		SettlementSimulationState state
	) {
		if (!data.isMember(settlement, assignment.caregiverId()) || !data.isMember(settlement, assignment.dependentId())) {
			return false;
		}
		Person caregiver = state.world().person(state.residentId(assignment.caregiverId()).orElseThrow());
		Person dependent = state.world().person(state.residentId(assignment.dependentId()).orElseThrow());
		return caregiver.canProvideCare() && dependent.needsCare()
			&& aliveIfLoaded(level, assignment.caregiverId()) && aliveIfLoaded(level, assignment.dependentId());
	}

	private static boolean aliveIfLoaded(ServerLevel level, UUID villagerId) {
		return !(level.getEntity(villagerId) instanceof Villager villager) || villager.isAlive() && !villager.isRemoved();
	}

	private static List<Villager> loadedResidents(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		return data.membersOf(settlement).stream()
			.map(member -> level.getEntity(member.villagerId()))
			.filter(entity -> entity instanceof Villager)
			.map(entity -> (Villager) entity)
			.filter(villager -> villager.isAlive() && !villager.isRemoved())
			.toList();
	}

	private static Person person(SettlementSimulationState state, Villager villager) {
		String personId = state.residentId(villager.getUUID()).orElseThrow();
		return state.world().person(personId);
	}

	private static boolean willing(
		SettlementSavedData data,
		Settlement settlement,
		SettlementSimulationState state,
		Villager caregiver,
		Villager dependent
	) {
		Person provider = person(state, caregiver);
		Person recipient = person(state, dependent);
		return provider.householdId().equals(recipient.householdId())
			|| provider.trustIn(recipient.id()) >= TRUST_THRESHOLD || recipient.trustIn(provider.id()) >= TRUST_THRESHOLD
			|| data.personalityOf(settlement, caregiver.getUUID()).sociability() >= SOCIABILITY_THRESHOLD;
	}

	private static int score(
		SettlementSavedData data,
		Settlement settlement,
		SettlementSimulationState state,
		Villager caregiver,
		Villager dependent
	) {
		Person provider = person(state, caregiver);
		Person recipient = person(state, dependent);
		int household = provider.householdId().equals(recipient.householdId()) ? 100 : 0;
		int trust = Math.max(provider.trustIn(recipient.id()), recipient.trustIn(provider.id()));
		return household + trust + data.personalityOf(settlement, caregiver.getUUID()).sociability();
	}
}
