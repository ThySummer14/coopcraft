package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;

/** Read-only access to a newly enlarged allowance after a recorded, real quota refusal. */
final class SettlementFoodAccess {
	private SettlementFoodAccess() {}

	/** The production Goal's decision, including its unchanged ordinary hunger/refusal paths. */
	static boolean shouldEat(SettlementSavedData data, Settlement settlement, UUID residentId,
		long gameDay, int satiety, boolean foodAvailable) {
		return (satiety <= SettlementNeeds.HUNGRY_AT && foodAvailable)
			|| SettlementSimulationService.hasPendingFoodRefusal(data, settlement, residentId)
			|| (foodAvailable && inspect(data, settlement, residentId, gameDay).map(Snapshot::canResume).orElse(false));
	}

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
		int cap = state.dailyFoodUnitCap(personId);
		int allocated = state.allocatedFoodUnits(personId);
		int remaining = state.remainingFoodUnits(personId);
		SimulationEvent refusal = state.world().ledger().eventsAtDay(state.world().day()).stream()
			.filter(event -> provesExhaustion(state, personId, event))
			.findFirst().orElse(null);
		if (refusal == null) return Optional.empty();
		int oldCap = positiveInt(refusal.context().get("cap"));
		int oldAllocated = positiveInt(refusal.context().get("allocatedUnits"));
		Status status = !state.survivalEnabled() || !state.normsEnabled() ? Status.RULES_DISABLED
			: gameDay < 0 || !SettlementMeetings.dayReady(gameDay, state.lastSurvivalDay()) ? Status.DAY_PENDING
			: person.hunger() <= 5 ? Status.SATISFIED
			: cap <= oldCap ? Status.CAP_NOT_RAISED
			: allocated < oldAllocated ? Status.ALLOCATION_MISMATCH
			: remaining <= 0 ? Status.ALLOWANCE_SPENT
			: state.world().availableNutrition(state.campOwner()) <= 0 ? Status.NO_FOOD : Status.READY;
		return Optional.of(new Snapshot(refusal.eventId(), state.world().day(), oldAllocated, oldCap,
			positiveInt(refusal.context().get("hunger")), nonnegativeLong(refusal.context().get("availableNutrition")), status));
	}

	private static boolean provesExhaustion(SettlementSimulationState state, String personId,
		SimulationEvent event) {
		List<String> actors = List.of(personId, state.campOwner().id());
		if (event.type() != EventType.SHARE_REFUSED || event.day() != state.world().day()
			|| event.causalLinks().size() != 1
			|| !event.actors().equals(actors)
			|| !"daily-unit-cap-exhausted".equals(event.context().get("reason"))
			|| !"item_units".equals(event.context().get("allocationBasis"))) return false;
		int oldCap = positiveInt(event.context().get("cap"));
		int oldAllocated = positiveInt(event.context().get("allocatedUnits"));
		if (oldCap < 1 || oldAllocated < oldCap) return false;
		int hunger = positiveInt(event.context().get("hunger"));
		int needed = positiveInt(event.context().get("neededNutrition"));
		if (hunger <= 5 || hunger > 100 || needed != hunger - 5
			|| nonnegativeLong(event.context().get("availableNutrition")) < 0) return false;
		return event.causalLinks().stream().anyMatch(id -> state.world().ledger().event(id)
			.filter(request -> request.type() == EventType.SHARE_REQUEST && request.day() == event.day()
				&& request.actors().equals(actors)
				&& positiveInt(request.context().get("neededNutrition")) == needed).isPresent());
	}

	private static int positiveInt(String value) {
		if (value == null || value.isEmpty() || value.length() > 10
			|| !value.chars().allMatch(c -> c >= '0' && c <= '9')) return -1;
		try { return Integer.parseInt(value); } catch (NumberFormatException invalid) { return -1; }
	}

	private static long nonnegativeLong(String value) {
		if (value == null || value.isEmpty() || value.length() > 19
			|| !value.chars().allMatch(c -> c >= '0' && c <= '9')) return -1;
		try { return Long.parseLong(value); } catch (NumberFormatException invalid) { return -1; }
	}

	enum Status { READY, DAY_PENDING, RULES_DISABLED, SATISFIED, CAP_NOT_RAISED,
		ALLOCATION_MISMATCH, ALLOWANCE_SPENT, NO_FOOD }

	record Snapshot(long refusalEventId, long coreDay, int refusedAllocatedUnits, int refusedCap,
		int refusedHunger, long refusedAvailableNutrition, Status status) {
		boolean canResume() { return status == Status.READY; }
	}
}
