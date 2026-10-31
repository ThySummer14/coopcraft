package com.thysummer.coopcraft.civilization.simulation;

public final class RiverValleyDemographyScenario {
	private RiverValleyDemographyScenario() {
	}

	public static PrimitiveSocietySimulation create(long seed) {
		return create(seed, SimulationConfig.defaults(), DemographyConfig.enabledDefaults());
	}

	public static PrimitiveSocietySimulation create(long seed, SimulationConfig config, DemographyConfig demographyConfig) {
		PrimitiveSocietySimulation base = RiverValleyScenario.create(seed, config);
		SimulationWorld world = base.world();
		for (Person person : world.people()) {
			int index = Integer.parseInt(person.id().substring("person-".length()));
			if (person.ageBand() == Person.AgeBand.CHILD) {
				switch (index % 3) {
					case 0 -> person.initializeAge(Person.AgeBand.INFANT, 360);
					case 1 -> person.initializeAge(Person.AgeBand.ADOLESCENT, 15 * 360);
					case 2 -> person.initializeAge(Person.AgeBand.CHILD, 8 * 360);
					default -> throw new IllegalStateException("Unexpected age variant");
				}
			} else if (index == 0 || index == 6 || index == 12) {
				person.initializeAge(Person.AgeBand.ELDER, 65 * 360);
			}
		}
		return new PrimitiveSocietySimulation(world, config, demographyConfig, seed);
	}
}
