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
		if (!person.isAlive() || !world.allPeople().contains(person)) {
			throw new IllegalArgumentException("Only living registered residents can eat");
		}
		long consumed = 0;
		for (ResourceBatch batch : world.batchesAt(StorageOwner.camp(person.campId()))) {
			if (person.hunger() <= TARGET_HUNGER || !batch.resourceType().isFood()) {
				continue;
			}
			long shortfall = person.hunger() - TARGET_HUNGER;
			int quantity = (int) Math.min(batch.quantity(), unitsFor(shortfall, batch.nutritionPerUnit()));
			long requestEventId = world.recordShareRequest(person.id(), person.campId(), shortfall);
			SimulationWorld.TransferResult allocation = world.allocateCommon(
				person.campId(), person.id(), batch.batchId(), quantity, requestEventId
			);
			consumed += world.consume(person.id(), allocation.batchId(), quantity, EventType.CONSUME);
		}
		return consumed;
	}

	private static long unitsFor(long nutrition, long nutritionPerUnit) {
		return Math.max(1, (nutrition + nutritionPerUnit - 1) / nutritionPerUnit);
	}
}
