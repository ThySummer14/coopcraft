package com.thysummer.coopcraft.civilization.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Neutral, durable evidence of a positive meal interrupted by an empty common store. */
public final class CommonMealInterruption {
	private static final String REASON = "common-food-exhausted";
	private static final String BASIS = "item_units";

	private CommonMealInterruption() { }

	public static OptionalLong recordIfNeeded(SimulationWorld world, Person person,
		SurvivalSystem.MealWithEvidence evidence, int dailyAllocatedUnits, int cap) {
		Objects.requireNonNull(world, "world");
		Objects.requireNonNull(person, "person");
		Objects.requireNonNull(evidence, "evidence");
		require(person.isAlive() && world.allPeople().contains(person));
		int units = evidence.meal().units();
		require(cap > 0 && dailyAllocatedUnits >= 0 && dailyAllocatedUnits <= cap
			&& units >= 0 && dailyAllocatedUnits >= units);
		if (units == 0) return OptionalLong.empty();
		MealAllocationEvidence.validate(world.ledger(), world.day(), person.id(), person.campId(), evidence);
		if (dailyAllocatedUnits == cap || person.hunger() <= 5
			|| world.availableNutrition(StorageOwner.camp(person.campId())) != 0) return OptionalLong.empty();
		require(person.hunger() == hungerAfter(world.ledger(), evidence.meal().requestEventId(),
			evidence.allocations().getLast().consumptionEventId()));

		Map<String, String> context = Map.of(
			"reason", REASON, "allocationBasis", BASIS,
			"dailyAllocatedUnits", Integer.toString(dailyAllocatedUnits), "cap", Integer.toString(cap),
			"hunger", Integer.toString(person.hunger()), "availableNutrition", "0",
			MealAllocationEvidence.KEY, MealAllocationEvidence.VERSION,
			MealAllocationEvidence.COUNT, Integer.toString(evidence.allocations().size()),
			"allocatedUnits", Integer.toString(units));
		var causes = new ArrayList<Long>();
		causes.add(evidence.meal().requestEventId());
		causes.add(evidence.meal().allocationEventId());
		evidence.allocations().forEach(part -> causes.add(part.consumptionEventId()));
		List<Long> sortedCauses = causes.stream().sorted().toList();
		for (SimulationEvent event : world.ledger().eventsAtDay(world.day())) {
			if (event.type() == EventType.COMMON_MEAL_INTERRUPTED
				&& event.causalLinks().contains(evidence.allocations().getFirst().consumptionEventId())) {
				require(event.actors().equals(List.of(person.id())) && event.context().equals(context)
					&& event.causalLinks().equals(sortedCauses) && event.inputs().isEmpty() && event.outputs().isEmpty());
				return OptionalLong.of(event.eventId());
			}
		}
		return OptionalLong.of(world.recordSocialEvent(EventType.COMMON_MEAL_INTERRUPTED,
			List.of(person.id()), context, causes));
	}

	/** Reads only the newest matching record; malformed new evidence never revives an older record. */
	public static Optional<Snapshot> inspect(SimulationWorld world, String personId, String campId) {
		Objects.requireNonNull(world, "world");
		Objects.requireNonNull(personId, "personId");
		Objects.requireNonNull(campId, "campId");
		List<SimulationEvent> events = world.ledger().eventsAtDay(world.day());
		for (int index = events.size() - 1; index >= 0; index--) {
			SimulationEvent event = events.get(index);
			if (event.type() != EventType.COMMON_MEAL_INTERRUPTED || !event.actors().contains(personId)) continue;
			try {
				require(event.actors().equals(List.of(personId)) && event.inputs().isEmpty() && event.outputs().isEmpty()
					&& REASON.equals(event.context().get("reason")) && BASIS.equals(event.context().get("allocationBasis")));
				int total = integer(event, "dailyAllocatedUnits");
				int cap = integer(event, "cap");
				int hunger = integer(event, "hunger");
				int units = integer(event, "allocatedUnits");
				int count = integer(event, MealAllocationEvidence.COUNT);
				require(integer(event, "availableNutrition") == 0 && units > 0 && units <= total && total < cap
					&& hunger >= 6 && hunger <= 100 && count > 0 && (long) count + 2 == event.causalLinks().size());
				require(campId.equals(MealAllocationEvidence.restoredCamp(world.ledger(), event)));
				var causes = event.causalLinks().stream().map(id -> world.ledger().event(id).orElseThrow()).toList();
				long request = causes.stream().filter(cause -> cause.type() == EventType.SHARE_REQUEST).findFirst().orElseThrow().eventId();
				long consume = causes.stream().filter(cause -> cause.type() == EventType.CONSUME).mapToLong(SimulationEvent::eventId).max().orElseThrow();
				require(hunger == hungerAfter(world.ledger(), request, consume));
				return Optional.of(new Snapshot(event.eventId(), event.day(), total, cap, hunger));
			} catch (IllegalArgumentException | ArithmeticException | NullPointerException exception) {
				return Optional.empty();
			}
		}
		return Optional.empty();
	}

	private static int hungerAfter(EventLedger ledger, long requestId, long consumptionId) {
		SimulationEvent request = ledger.event(requestId).orElseThrow();
		SimulationEvent consumption = ledger.event(consumptionId).orElseThrow();
		int needed = integer(request, "neededNutrition");
		require(needed > 0 && needed <= 95);
		return (int) Math.max(0, needed + 5L - consumption.inputs().getFirst().nutrition());
	}

	private static int integer(SimulationEvent event, String key) {
		String value = event.context().get(key);
		require(value != null && value.matches("0|[1-9][0-9]*"));
		return Integer.parseInt(value);
	}

	private static void require(boolean valid) {
		if (!valid) throw new IllegalArgumentException("Invalid common meal interruption evidence");
	}

	/** allocatedUnits is the authoritative cumulative daily total, not just the interrupted meal. */
	public record Snapshot(long eventId, long coreDay, int allocatedUnits, int cap, int hunger) { }
}
