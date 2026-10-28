package com.thysummer.coopcraft.civilization.simulation;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeSystemTest {
	@Test
	void acquiredTechniqueCanBeUsedAndTeachingReducesTheSameDaysProduction() {
		SimulationWorld world = twoPersonWorld(false);
		PrimitiveSocietySimulation simulation = simulation(world, config(1));

		simulation.simulateDay();

		Person teacher = world.person("teacher");
		Person learner = world.person("learner");
		assertTrue(learner.knowsTechnique(WorkType.GATHER));
		assertEquals(1, teacher.learningHours());
		assertEquals(1, learner.learningHours());
		SimulationEvent lesson = onlyLesson(world);
		assertEquals("true", lesson.context().get("techniqueAcquired"));
		List<SimulationEvent> harvests = harvests(world);
		assertEquals(2, harvests.size());
		assertTrue(harvests.stream().allMatch(event -> event.context().get("effortHours").equals("5")));
		world.assertConservation();
	}

	@Test
	void unknownTechniqueCannotProduceWhenLearningFails() {
		SimulationWorld world = twoPersonWorld(false);
		PrimitiveSocietySimulation simulation = simulation(world, config(0));

		simulation.simulateDay();

		assertFalse(world.person("learner").knowsTechnique(WorkType.GATHER));
		assertEquals("false", onlyLesson(world).context().get("techniqueAcquired"));
		assertEquals(1, harvests(world).size());
		assertEquals("teacher", harvests(world).getFirst().actors().getFirst());
		world.assertConservation();
	}

	@Test
	void knownSkillImprovesOnlyWhenPropagationIsEnabled() {
		SimulationWorld enabledWorld = twoPersonWorld(true);
		PrimitiveSocietySimulation enabled = simulation(enabledWorld, config(1));
		SimulationWorld disabledWorld = twoPersonWorld(true);
		PrimitiveSocietySimulation disabled = new PrimitiveSocietySimulation(
			disabledWorld, SimulationConfig.defaults(), DemographyConfig.disabled(), KnowledgeConfig.disabled(), 7
		);

		enabled.simulateDay();
		disabled.simulateDay();

		assertEquals(1.5, enabledWorld.person("learner").skill(WorkType.GATHER));
		assertEquals(1, enabledWorld.person("learner").learningHours());
		assertEquals(1.0, disabledWorld.person("learner").skill(WorkType.GATHER));
		assertEquals(0, disabledWorld.person("learner").learningHours());
		assertTrue(harvests(disabledWorld).size() > 0);
		assertTrue(disabledWorld.ledger().events().stream().noneMatch(event -> event.type() == EventType.TEACHING_SESSION));
	}

	@Test
	void childrenLearnThroughRelationshipsButInfantsAndStrangersDoNot() {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp("camp", "Camp"));
		world.registerHousehold(new Household("h1", "camp"));
		world.registerHousehold(new Household("h2", "camp"));
		Person teacher = new Person("teacher", "camp", "h1", Person.AgeBand.ADULT, 0, 50);
		teacher.setSkill(WorkType.GATHER, 1.5);
		teacher.learnTechnique(WorkType.GATHER);
		world.registerPerson(teacher);
		world.registerPerson(new Person("child", "camp", "h1", Person.AgeBand.CHILD, 0, 50));
		world.registerPerson(new Person("infant", "camp", "h1", Person.AgeBand.INFANT, 0, 50));
		world.registerPerson(new Person("stranger", "camp", "h2", Person.AgeBand.ADULT, 0, 50));
		PrimitiveSocietySimulation simulation = simulation(world, config(1));

		simulation.simulateDay();

		assertTrue(world.person("child").knowsTechnique(WorkType.GATHER));
		assertFalse(world.person("infant").knowsTechnique(WorkType.GATHER));
		assertFalse(world.person("stranger").knowsTechnique(WorkType.GATHER));
		assertEquals(1, world.person("child").learningHours());
		assertEquals(0, world.person("infant").learningHours());
		assertEquals(0, world.person("stranger").learningHours());
		assertEquals(List.of("teacher", "child"), onlyLesson(world).actors());
	}

	@Test
	void learningCannotCreateExtraProductionTime() {
		SimulationWorld world = twoPersonWorld(false);
		world.person("teacher").addSocialHours(6);
		world.person("learner").addSocialHours(6);
		PrimitiveSocietySimulation simulation = simulation(world, config(1));

		simulation.simulateDay();

		assertEquals(6, world.person("teacher").previousSocialHours());
		assertEquals(1, world.person("teacher").learningHours());
		assertTrue(harvests(world).isEmpty());
		assertTrue(simulation.workReservations().isEmpty());
		world.assertConservation();
	}

	@Test
	void repeatedSchedulingCannotDoubleBookTheSameDay() {
		SimulationWorld world = twoPersonWorld(false);
		KnowledgeSystem knowledge = new KnowledgeSystem(world, config(1));

		knowledge.organizeLearning(new Random(1));
		knowledge.organizeLearning(new Random(1));

		assertEquals(1, world.person("teacher").learningHours());
		assertEquals(1, world.person("learner").learningHours());
		assertEquals(1, world.ledger().events().stream().filter(event -> event.type() == EventType.TEACHING_SESSION).count());
	}

	@Test
	void deadTeachersLeaveTheLivingKnowledgeNetwork() {
		SimulationWorld world = twoPersonWorld(false);
		Person teacher = world.person("teacher");
		KnowledgeSystem knowledge = new KnowledgeSystem(world, config(1));
		assertEquals(1, knowledge.techniqueHolders("camp", WorkType.GATHER));

		teacher.markDead(0);
		knowledge.organizeLearning(new Random(1));

		assertEquals(0, knowledge.techniqueHolders("camp", WorkType.GATHER));
		assertFalse(world.person("learner").knowsTechnique(WorkType.GATHER));
		assertTrue(world.ledger().events().stream().noneMatch(event -> event.type() == EventType.TEACHING_SESSION));
	}

	@Test
	void knowledgeScenarioReplaysAndConservesResources() {
		PrimitiveSocietySimulation first = RiverValleyKnowledgeScenario.create(7);
		PrimitiveSocietySimulation replay = RiverValleyKnowledgeScenario.create(7);

		first.runDays(1000);
		replay.runDays(1000);

		assertEquals(first.world().ledger().events(), replay.world().ledger().events());
		assertEquals(first.reports(), replay.reports());
		assertTrue(first.world().ledger().events().stream().anyMatch(event -> event.type() == EventType.TEACHING_SESSION
			&& event.context().get("techniqueAcquired").equals("true")));
		assertTrue(first.world().ledger().events().stream().anyMatch(event -> event.type() == EventType.TEACHING_SESSION
			&& Double.parseDouble(event.context().get("skillAfter")) > Double.parseDouble(event.context().get("skillBefore"))));
		for (SimulationEvent event : first.world().ledger().events()) {
			if (event.type() == EventType.CONSUME || event.type() == EventType.FIELD_CONSUMPTION) {
				assertFalse(first.world().ledger().traceOrigins(event.eventId()).isEmpty());
			}
		}
		first.world().assertConservation();
	}

	private static SimulationWorld twoPersonWorld(boolean learnerKnows) {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp("camp", "Camp"));
		world.registerHousehold(new Household("h", "camp"));
		Person teacher = new Person("teacher", "camp", "h", Person.AgeBand.ADULT, 0, 50);
		teacher.setSkill(WorkType.GATHER, 1.5);
		teacher.learnTechnique(WorkType.GATHER);
		Person learner = new Person("learner", "camp", "h", Person.AgeBand.ADULT, 0, 50);
		learner.setSkill(WorkType.GATHER, 1.0);
		if (learnerKnows) {
			learner.learnTechnique(WorkType.GATHER);
		}
		teacher.setTrust("learner", 100);
		learner.setTrust("teacher", 100);
		world.registerPerson(teacher);
		world.registerPerson(learner);
		world.registerPatch(new ResourcePatch(
			"fruit", "camp", new ResourcePatch.Position(0, 64, 0), "test", ResourceType.WILD_FRUIT, 100, 100, 0
		));
		return world;
	}

	private static PrimitiveSocietySimulation simulation(SimulationWorld world, KnowledgeConfig config) {
		return new PrimitiveSocietySimulation(world, SimulationConfig.defaults(), DemographyConfig.disabled(), config, 7);
	}

	private static KnowledgeConfig config(double techniqueLearningChance) {
		return new KnowledgeConfig(true, 1.1, 0.1, 1.0, techniqueLearningChance, 2.0);
	}

	private static SimulationEvent onlyLesson(SimulationWorld world) {
		return world.ledger().events().stream()
			.filter(event -> event.type() == EventType.TEACHING_SESSION)
			.findFirst().orElseThrow();
	}

	private static List<SimulationEvent> harvests(SimulationWorld world) {
		return world.ledger().events().stream().filter(event -> event.type() == EventType.HARVEST).toList();
	}
}
