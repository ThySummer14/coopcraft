package com.thysummer.coopcraft.civilization.simulation;

import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemographySystemTest {
	@Test
	void ageThresholdsAndCapabilitiesAreIndependent() {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp("camp", "Camp"));
		world.registerHousehold(new Household("h", "camp"));
		Person person = new Person("p", "camp", "h", Person.AgeBand.INFANT, 0, 50);
		person.initializeAge(Person.AgeBand.INFANT, 3 * 360 - 1);
		world.registerPerson(person);
		DemographySystem demography = new DemographySystem(world, config(0, 0));

		assertFalse(person.canWork());
		assertFalse(person.canMakeDecisions());
		assertFalse(person.canProvideCare());
		demography.advancePopulation(new Random(1));

		assertEquals(Person.AgeBand.CHILD, person.ageBand());
		assertEquals(3 * 360, person.ageDays());
		assertFalse(person.canWork());
		assertFalse(person.canMakeDecisions());
		assertEquals(EventType.LIFE_STAGE_CHANGED, world.ledger().events().getLast().type());

		Person adolescent = new Person("a", "camp", "h", Person.AgeBand.ADOLESCENT, 0, 50);
		Person elder = new Person("e", "camp", "h", Person.AgeBand.ELDER, 0, 50);
		assertTrue(adolescent.canWork());
		assertFalse(adolescent.canMakeDecisions());
		assertTrue(adolescent.canProvideCare());
		assertTrue(elder.canMakeDecisions());
		assertTrue(elder.canProvideCare());
	}

	@Test
	void crossHouseholdCareReducesTheSameDaysProductionTime() {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp("camp", "Camp"));
		world.registerHousehold(new Household("h1", "camp"));
		world.registerHousehold(new Household("h2", "camp"));
		Person infant = new Person("infant", "camp", "h1", Person.AgeBand.INFANT, 0, 50);
		Person caregiver = new Person("caregiver", "camp", "h2", Person.AgeBand.ADULT, 0, 50);
		infant.setTrust("caregiver", 40);
		caregiver.setTrust("infant", 40);
		world.registerPerson(infant);
		world.registerPerson(caregiver);
		world.registerPatch(new ResourcePatch(
			"fruit", "camp", new ResourcePatch.Position(0, 64, 0), "test", ResourceType.WILD_FRUIT, 100, 100, 0
		));
		PrimitiveSocietySimulation simulation = new PrimitiveSocietySimulation(
			world, SimulationConfig.defaults(), config(0, 0), 7
		);

		simulation.simulateDay();

		assertEquals(4, caregiver.careHours());
		assertEquals(4, simulation.demographySystem().careHoursFor("infant"));
		SimulationEvent care = world.ledger().events().stream().filter(event -> event.type() == EventType.CARE_PROVIDED)
			.findFirst().orElseThrow();
		assertEquals("caregiver", care.actors().getFirst());
		assertEquals("false", care.context().get("sameHousehold"));
		SimulationEvent harvest = world.ledger().events().stream().filter(event -> event.type() == EventType.HARVEST)
			.findFirst().orElseThrow();
		assertEquals("2", harvest.context().get("effortHours"));
		world.assertConservation();
	}

	@Test
	void missingCareHarmsInfantsAndDeathClosesCurrentCapabilities() {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp("camp", "Camp"));
		world.registerHousehold(new Household("h", "camp"));
		Person infant = new Person("infant", "camp", "h", Person.AgeBand.INFANT, 90, 50);
		Person adult = new Person("adult", "camp", "h", Person.AgeBand.ADULT, 0, 50);
		world.registerPerson(infant);
		world.registerPerson(adult);
		world.registerNorm(new Norm(
			Norm.NEED_FIRST_COMMON_STORE, "camp", 1, Set.of("infant"), Set.of(), Set.of("infant", "adult"),
			40, Norm.Status.ACTIVE, List.of()
		));
		ResourceBatch personal = world.createInitialBatch(ResourceType.TUBERS, 1, StorageOwner.person("infant"));
		infant.adjustHealth(-97);
		DemographySystem demography = new DemographySystem(world, config(0, 0));

		demography.advancePopulation(new Random(1));

		assertEquals(0, infant.health());
		assertFalse(infant.isAlive());
		assertFalse(infant.canWork());
		assertFalse(infant.canMakeDecisions());
		assertFalse(world.people().contains(infant));
		assertTrue(world.allPeople().contains(infant));
		assertThrows(IllegalArgumentException.class, () -> world.consume("infant", personal.batchId(), 1, EventType.CONSUME));
		assertEquals(3, new NormSystem(world, SimulationConfig.defaults()).limitCommonAllocation(adult, 3));
		assertTrue(world.ledger().events().stream().anyMatch(event -> event.type() == EventType.DEATH));
		world.assertConservation();
	}

	@Test
	void birthNeedsFoodAndCareAndDoesNotCreateStockOrKnowledge() {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp("camp", "Camp"));
		world.registerHousehold(new Household("h", "camp"));
		world.registerPerson(new Person("a", "camp", "h", Person.AgeBand.ADULT, 0, 50));
		world.registerPerson(new Person("b", "camp", "h", Person.AgeBand.ADULT, 0, 50));
		world.createInitialBatch(ResourceType.TUBERS, 5, StorageOwner.household("h"));
		long nutritionBefore = world.nutritionTotals().get(ResourceType.TUBERS);
		DemographySystem demography = new DemographySystem(world, config(1, 0));

		demography.advancePopulation(new Random(2));

		assertEquals(3, world.people().size());
		Person child = world.people().stream().filter(person -> person.ageBand() == Person.AgeBand.INFANT).findFirst().orElseThrow();
		assertTrue(child.observedNorms().isEmpty());
		assertTrue(child.campKnowledge().isEmpty());
		assertTrue(child.knownTechniques().isEmpty());
		assertFalse(child.canWork());
		assertEquals(nutritionBefore, world.nutritionTotals().get(ResourceType.TUBERS));
		assertTrue(world.ledger().events().stream().anyMatch(event -> event.type() == EventType.BIRTH));

		SimulationWorld hungry = new SimulationWorld();
		hungry.registerCamp(new Camp("camp", "Camp"));
		hungry.registerHousehold(new Household("h", "camp"));
		hungry.registerPerson(new Person("a", "camp", "h", Person.AgeBand.ADULT, 90, 50));
		hungry.registerPerson(new Person("b", "camp", "h", Person.AgeBand.ADULT, 90, 50));
		new DemographySystem(hungry, config(1, 0)).advancePopulation(new Random(2));
		assertEquals(2, hungry.people().size());
		world.assertConservation();
		hungry.assertConservation();
	}

	@Test
	void demographyScenarioReplaysAndConservesResources() {
		PrimitiveSocietySimulation first = RiverValleyDemographyScenario.create(7);
		PrimitiveSocietySimulation replay = RiverValleyDemographyScenario.create(7);

		first.runDays(1000);
		replay.runDays(1000);

		assertEquals(first.world().ledger().events(), replay.world().ledger().events());
		assertEquals(first.reports(), replay.reports());
		assertEquals(first.world().people().size(), replay.world().people().size());
		assertTrue(first.world().ledger().events().stream().anyMatch(event -> event.type() == EventType.CARE_PROVIDED));
		assertTrue(first.world().ledger().events().stream().anyMatch(event -> event.type() == EventType.BIRTH));
		assertTrue(first.world().ledger().events().stream().anyMatch(event -> event.type() == EventType.DEATH));
		assertTrue(first.workReservations().stream().allMatch(id -> first.world().person(id).canWork()));
		for (SimulationEvent event : first.world().ledger().events()) {
			if (event.type() == EventType.CONSUME || event.type() == EventType.FIELD_CONSUMPTION) {
				assertFalse(first.world().ledger().traceOrigins(event.eventId()).isEmpty());
			}
		}
		first.world().assertConservation();
	}

	private static DemographyConfig config(double birthChance, double elderMortality) {
		return new DemographyConfig(true, 360, 3, 12, 18, 60, 4, 2, 1, 4, 3, 40, 75, birthChance, elderMortality);
	}
}
