package com.thysummer.coopcraft.civilization.simulation;

import java.util.List;

public final class RiverValleyScenario {
	public static final String CAMP_ID = "river-camp";

	private RiverValleyScenario() {
	}

	public static PrimitiveSocietySimulation create(long seed) {
		return create(seed, SimulationConfig.defaults());
	}

	public static PrimitiveSocietySimulation create(long seed, SimulationConfig config) {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp(CAMP_ID, "River Valley"));
		for (int household = 0; household < 6; household++) {
			world.registerHousehold(new Household("household-" + household, CAMP_ID));
		}
		for (int index = 0; index < 24; index++) {
			int household = index / 4;
			int slot = index % 4;
			int adults = household % 2 == 0 ? 3 : 2;
			Person.AgeBand ageBand = slot < adults ? Person.AgeBand.ADULT : Person.AgeBand.CHILD;
			Person person = new Person(
				"person-" + String.format("%02d", index), CAMP_ID, "household-" + household, ageBand,
				15 + index * 7 % 20, index * 37 % 101
			);
			WorkType preferred = WorkType.values()[index % WorkType.values().length];
			for (WorkType workType : WorkType.values()) {
				person.setSkill(workType, workType == preferred ? 1.25 + index % 3 * 0.1 : 0.75 + index % 4 * 0.05);
			}
			person.observeNorm("neighbor-requests");
			person.observeNorm("family-priority");
			person.observeNorm("camp-storage");
			world.registerPerson(person);
		}
		List<Person> people = world.people();
		for (Person first : people) {
			for (Person second : people) {
				if (!first.id().equals(second.id())) {
					first.setTrust(second.id(), 25 + Math.floorMod(first.id().hashCode() * 17 + second.id().hashCode() * 13, 45));
				}
			}
		}
		registerPatches(world);
		for (int household = 0; household < 6; household++) {
			world.createInitialBatch(ResourceType.TUBERS, 2, StorageOwner.household("household-" + household));
		}
		if (config.publicStorageEnabled()) {
			world.createInitialBatch(ResourceType.FISH, 6, StorageOwner.camp(CAMP_ID));
		}
		return new PrimitiveSocietySimulation(world, config, seed);
	}

	private static void registerPatches(SimulationWorld world) {
		world.registerPatch(new ResourcePatch(
			"fruit-grove", CAMP_ID, new ResourcePatch.Position(12, 64, -8), "river_forest",
			ResourceType.WILD_FRUIT, 1600, 2400, 0.12
		));
		world.registerPatch(new ResourcePatch(
			"tuber-meadow", CAMP_ID, new ResourcePatch.Position(-16, 63, 6), "river_meadow",
			ResourceType.TUBERS, 2200, 3200, 0.08
		));
		world.registerPatch(new ResourcePatch(
			"fish-shallows", CAMP_ID, new ResourcePatch.Position(2, 62, 18), "river_water",
			ResourceType.FISH, 1800, 3000, 0.10
		));
		world.registerPatch(new ResourcePatch(
			"game-woodland", CAMP_ID, new ResourcePatch.Position(-22, 65, -14), "river_forest",
			ResourceType.GAME, 1200, 2200, 0.06
		));
		world.registerPatch(new ResourcePatch(
			"wood-stand", CAMP_ID, new ResourcePatch.Position(20, 64, 4), "river_forest",
			ResourceType.WOOD, 1800, 2400, 0.08
		));
		world.registerPatch(new ResourcePatch(
			"stone-outcrop", CAMP_ID, new ResourcePatch.Position(24, 66, 4), "river_hills",
			ResourceType.STONE, 2000, 2000, 0
		));
	}
}
