package com.thysummer.coopcraft.civilization.simulation;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SurvivalSystem {
	private static final int TARGET_HUNGER = 5;

	private final SimulationWorld world;
	private final int dailyHunger;

	public SurvivalSystem(SimulationWorld world, SimulationConfig config) {
		this.world = world;
		this.dailyHunger = config.dailyHunger();
	}

	public void observeClosingDay(Collection<Person> residents, long gameDay, int foodAccessThreshold) {
		if (gameDay < 0 || foodAccessThreshold < 0 || foodAccessThreshold > 100) {
			throw new IllegalArgumentException("Invalid food observation boundary or threshold");
		}
		List<Person> observed = List.copyOf(residents);
		for (Person person : observed) {
			if (!person.isAlive() || !world.allPeople().contains(person)) {
				throw new IllegalArgumentException("Survival residents must be alive and registered");
			}
		}
		for (Person person : observed) {
			person.observeFoodDay(world.day(), gameDay, foodAccessThreshold);
		}
	}

	public void advanceDay(Collection<Person> residents) {
		for (Person person : residents) {
			if (!person.isAlive() || !world.allPeople().contains(person)) {
				throw new IllegalArgumentException("Survival residents must be alive and registered");
			}
			person.increaseHunger(dailyHunger);
		}
		world.advanceDay();
	}

	public long availableFood(String campId) {
		return world.availableNutrition(StorageOwner.camp(campId));
	}

	public long eatFromCommon(Person person) {
		return eatFromCommon(person, Integer.MAX_VALUE).nutrition();
	}

	public MealResult eatFromCommon(Person person, int maximumUnits) {
		return eatFromCommonWithEvidence(person, maximumUnits, 0).meal();
	}

	public MealResult eatFromCommon(Person person, int maximumUnits, long existingRequestEventId) {
		return eatFromCommonWithEvidence(person, maximumUnits, existingRequestEventId).meal();
	}

	public MealWithEvidence eatFromCommonWithEvidence(Person person, int maximumUnits) {
		return eatFromCommonWithEvidence(person, maximumUnits, 0);
	}

	public MealWithEvidence eatFromCommonWithEvidence(Person person, int maximumUnits, long existingRequestEventId) {
		if (!person.isAlive() || !world.allPeople().contains(person)) {
			throw new IllegalArgumentException("Only living registered residents can eat");
		}
		if (maximumUnits <= 0) {
			throw new IllegalArgumentException("Meal unit limit must be positive");
		}
		if (existingRequestEventId > 0) {
			world.ledger().event(existingRequestEventId)
				.filter(event -> event.type() == EventType.SHARE_REQUEST)
				.filter(event -> event.actors().equals(List.of(person.id(), person.campId())))
				.orElseThrow(() -> new IllegalArgumentException("Meal request does not match the resident and camp"));
		}
		List<ConsumedAllocation> allocations = new ArrayList<>();
		long consumed = 0;
		int units = 0;
		long lastRequestEventId = 0;
		long lastAllocationEventId = 0;
		for (ResourceBatch batch : world.batchesAt(StorageOwner.camp(person.campId()))) {
			if (units >= maximumUnits) {
				break;
			}
			if (person.hunger() <= TARGET_HUNGER || !batch.resourceType().isFood() || world.day() > batch.expiryDay()) {
				continue;
			}
			long shortfall = person.hunger() - TARGET_HUNGER;
			int quantity = (int) Math.min(
				maximumUnits - units, Math.min(batch.quantity(), unitsFor(shortfall, batch.nutritionPerUnit()))
			);
			lastRequestEventId = existingRequestEventId > 0
				? existingRequestEventId : world.recordShareRequest(person.id(), person.campId(), shortfall);
			SimulationWorld.TransferResult allocation = world.allocateCommon(
				person.campId(), person.id(), batch.batchId(), quantity, lastRequestEventId
			);
			lastAllocationEventId = allocation.eventId();
			consumed += world.consume(person.id(), allocation.batchId(), quantity, EventType.CONSUME);
			allocations.add(new ConsumedAllocation(lastRequestEventId, lastAllocationEventId,
				world.ledger().lastEvent().orElseThrow().eventId(), quantity));
			units += quantity;
		}
		return new MealWithEvidence(new MealResult(consumed, units, lastRequestEventId, lastAllocationEventId), allocations);
	}

	public record MealResult(long nutrition, int units, long requestEventId, long allocationEventId) {
	}

	public record ConsumedAllocation(long requestEventId, long allocationEventId, long consumptionEventId, int units) {
	}

	public record MealWithEvidence(MealResult meal, List<ConsumedAllocation> allocations) {
		public MealWithEvidence {
			Objects.requireNonNull(meal, "meal");
			allocations = List.copyOf(allocations);
		}
	}

	private static long unitsFor(long nutrition, long nutritionPerUnit) {
		return Math.max(1, (nutrition + nutritionPerUnit - 1) / nutritionPerUnit);
	}
}
