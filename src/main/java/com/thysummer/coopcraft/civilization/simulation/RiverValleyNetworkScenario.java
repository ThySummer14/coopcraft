package com.thysummer.coopcraft.civilization.simulation;

public final class RiverValleyNetworkScenario {
	public static final String RIDGE_CAMP_ID = "ridge-camp";
	public static final String RIVER_RIDGE_ROUTE = "river-ridge-trail";

	private RiverValleyNetworkScenario() {
	}

	public static PrimitiveSocietySimulation create(long seed) {
		return create(seed, SimulationConfig.defaults());
	}

	public static PrimitiveSocietySimulation create(long seed, SimulationConfig config) {
		PrimitiveSocietySimulation base = RiverValleyScenario.create(seed, config);
		SimulationWorld world = base.world();
		world.registerCamp(new Camp(RIDGE_CAMP_ID, "Stone Ridge"));
		for (int household = 0; household < 3; household++) {
			world.registerHousehold(new Household("ridge-household-" + household, RIDGE_CAMP_ID));
		}
		for (int index = 0; index < 12; index++) {
			int household = index / 4;
			int slot = index % 4;
			int adults = household % 2 == 0 ? 3 : 2;
			Person.AgeBand ageBand = slot < adults ? Person.AgeBand.ADULT : Person.AgeBand.CHILD;
			Person person = new Person(
				"ridge-person-" + String.format("%02d", index), RIDGE_CAMP_ID, "ridge-household-" + household,
				ageBand, 45 + index * 5 % 35, index * 29 % 101
			);
			WorkType preferred = WorkType.values()[index % WorkType.values().length];
			for (WorkType workType : WorkType.values()) {
				person.setSkill(workType, workType == preferred ? 1.2 + index % 3 * 0.1 : 0.7 + index % 4 * 0.05);
			}
			person.observeNorm("neighbor-requests");
			person.observeNorm("family-priority");
			person.observeNorm("camp-storage");
			world.registerPerson(person);
		}
		for (Person first : world.peopleInCamp(RIDGE_CAMP_ID)) {
			for (Person second : world.peopleInCamp(RIDGE_CAMP_ID)) {
				if (!first.id().equals(second.id())) {
					first.setTrust(second.id(), 25 + Math.floorMod(first.id().hashCode() * 11 + second.id().hashCode() * 7, 45));
				}
			}
		}
		registerRidgePatches(world);
		for (int household = 0; household < 3; household++) {
			world.createInitialBatch(ResourceType.TUBERS, 1, StorageOwner.household("ridge-household-" + household));
		}
		if (config.publicStorageEnabled()) {
			world.createInitialBatch(ResourceType.FISH, 2, StorageOwner.camp(RIDGE_CAMP_ID));
		}
		world.registerCampLink(new CampLink(RIVER_RIDGE_ROUTE, RiverValleyScenario.CAMP_ID, RIDGE_CAMP_ID, 2));
		connect(world, "person-00", "ridge-person-00", 60);
		connect(world, "person-05", "ridge-person-04", 55);
		connect(world, "person-12", "ridge-person-08", 50);
		return new PrimitiveSocietySimulation(world, config, seed);
	}

	private static void connect(SimulationWorld world, String riverPersonId, String ridgePersonId, int trust) {
		Person river = world.person(riverPersonId);
		Person ridge = world.person(ridgePersonId);
		river.setTrust(ridgePersonId, trust);
		ridge.setTrust(riverPersonId, trust);
		river.learnCampRoute(RIVER_RIDGE_ROUTE);
		ridge.learnCampRoute(RIVER_RIDGE_ROUTE);
	}

	private static void registerRidgePatches(SimulationWorld world) {
		world.registerPatch(new ResourcePatch(
			"ridge-fruit", RIDGE_CAMP_ID, new ResourcePatch.Position(80, 70, -10), "ridge_forest",
			ResourceType.WILD_FRUIT, 600, 1000, 0.06
		));
		world.registerPatch(new ResourcePatch(
			"ridge-tubers", RIDGE_CAMP_ID, new ResourcePatch.Position(76, 69, 12), "ridge_meadow",
			ResourceType.TUBERS, 700, 1000, 0.05
		));
		world.registerPatch(new ResourcePatch(
			"ridge-game", RIDGE_CAMP_ID, new ResourcePatch.Position(90, 72, 4), "ridge_hills",
			ResourceType.GAME, 500, 800, 0.04
		));
		world.registerPatch(new ResourcePatch(
			"ridge-wood", RIDGE_CAMP_ID, new ResourcePatch.Position(84, 70, 16), "ridge_forest",
			ResourceType.WOOD, 1000, 2000, 0.08
		));
		world.registerPatch(new ResourcePatch(
			"ridge-stone", RIDGE_CAMP_ID, new ResourcePatch.Position(88, 73, 16), "ridge_hills",
			ResourceType.STONE, 2400, 3000, 0
		));
	}
}
