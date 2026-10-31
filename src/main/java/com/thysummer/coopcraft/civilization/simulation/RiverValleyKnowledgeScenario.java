package com.thysummer.coopcraft.civilization.simulation;

public final class RiverValleyKnowledgeScenario {
	private RiverValleyKnowledgeScenario() {
	}

	public static PrimitiveSocietySimulation create(long seed) {
		return create(seed, SimulationConfig.defaults(), DemographyConfig.enabledDefaults(), KnowledgeConfig.enabledDefaults());
	}

	public static PrimitiveSocietySimulation create(
		long seed,
		SimulationConfig config,
		DemographyConfig demographyConfig,
		KnowledgeConfig knowledgeConfig
	) {
		PrimitiveSocietySimulation base = RiverValleyDemographyScenario.create(seed, config, demographyConfig);
		SimulationWorld world = base.world();
		for (Person person : world.people()) {
			if (person.ageBand() == Person.AgeBand.INFANT || person.ageBand() == Person.AgeBand.CHILD) {
				continue;
			}
			int index = Integer.parseInt(person.id().substring("person-".length()));
			person.learnTechnique(WorkType.values()[index % WorkType.values().length]);
		}
		return new PrimitiveSocietySimulation(world, config, demographyConfig, knowledgeConfig, seed);
	}
}
