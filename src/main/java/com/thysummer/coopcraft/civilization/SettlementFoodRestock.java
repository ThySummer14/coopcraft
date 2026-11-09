package com.thysummer.coopcraft.civilization;

import java.util.Optional;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.CommonMealInterruption;
import com.thysummer.coopcraft.civilization.simulation.Person;

/** Read-only continuation of a real meal interrupted while its original allowance still had room. */
final class SettlementFoodRestock {
	private SettlementFoodRestock() {}

	static Optional<Snapshot> inspect(SettlementSavedData data, Settlement settlement, UUID residentId, long gameDay) {
		if (data == null || settlement == null || residentId == null
			|| data.all().stream().noneMatch(current -> current == settlement)
			|| !data.isMember(settlement, residentId)) return Optional.empty();
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null) return Optional.empty();
		String personId = state.residentId(residentId).orElse(null);
		Person person = state.world().people().stream()
			.filter(candidate -> candidate.id().equals(personId) && candidate.campId().equals(state.campOwner().id()))
			.findFirst().orElse(null);
		if (person == null || !person.isAlive()) return Optional.empty();
		return CommonMealInterruption.inspect(state.world(), personId, state.campOwner().id()).map(evidence -> {
			Status status = !state.survivalEnabled() ? Status.SURVIVAL_DISABLED
				: gameDay < 0 || !SettlementMeetings.dayReady(gameDay, state.lastSurvivalDay()) ? Status.DAY_PENDING
				: person.hunger() <= 5 ? Status.SATISFIED
				: state.allocatedFoodUnits(personId) != evidence.allocatedUnits() ? Status.ALLOCATION_CHANGED
				: state.remainingFoodUnits(personId) <= 0 ? Status.ALLOWANCE_SPENT
				: state.world().availableNutrition(state.campOwner()) <= 0 ? Status.NO_FOOD : Status.READY;
			return new Snapshot(evidence, status);
		});
	}

	enum Status { READY, DAY_PENDING, SURVIVAL_DISABLED, SATISFIED, ALLOCATION_CHANGED, ALLOWANCE_SPENT, NO_FOOD }
	record Snapshot(CommonMealInterruption.Snapshot evidence, Status status) {
		boolean canResume() { return status == Status.READY; }
	}
}
