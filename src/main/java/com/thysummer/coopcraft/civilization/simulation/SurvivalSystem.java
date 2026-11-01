package com.thysummer.coopcraft.civilization.simulation;

import java.util.Collection;

public final class SurvivalSystem {
	private static final int TARGET_HUNGER = 5;

	private final SimulationWorld world;
	private final int dailyHunger;

	public SurvivalSystem(SimulationWorld world, SimulationConfig config) {
		this.world = world;
		this.dailyHunger = config.dailyHunger();
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
		if (!person.isAlive() || !world.allPeople().contains(person)) {
			throw new IllegalArgumentException("Only living registered residents can eat");
		}
		if (maximumUnits <= 0) {
			throw new IllegalArgumentException("Meal unit limit must be positive");
		}
		long consumed = 0;
		int units = 0;
		long lastRequestEventId = 0;
		long lastAllocationEventId = 0;
		for (ResourceBatch batch : world.batchesAt(StorageOwner.camp(person.campId()))) {
			if (units >= maximumUnits) {
				break;
			}
			if (person.hunger() <= TARGET_HUNGER || !batch.resourceType().isFood()) {
				continue;
			}
			long shortfall = person.hunger() - TARGET_HUNGER;
			int quantity = (int) Math.min(
				maximumUnits - units, Math.min(batch.quantity(), unitsFor(shortfall, batch.nutritionPerUnit()))
			);
			lastRequestEventId = world.recordShareRequest(person.id(), person.campId(), shortfall);
			SimulationWorld.TransferResult allocation = world.allocateCommon(
				person.campId(), person.id(), batch.batchId(), quantity, lastRequestEventId
			);
			lastAllocationEventId = allocation.eventId();
			consumed += world.consume(person.id(), allocation.batchId(), quantity, EventType.CONSUME);
			units += quantity;
		}
		return new MealResult(consumed, units, lastRequestEventId, lastAllocationEventId);
	}

	public record MealResult(long nutrition, int units, long requestEventId, long allocationEventId) {
	}

	private static long unitsFor(long nutrition, long nutritionPerUnit) {
		return Math.max(1, (nutrition + nutritionPerUnit - 1) / nutritionPerUnit);
	}
}
