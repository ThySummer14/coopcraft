package com.thysummer.coopcraft.civilization.simulation;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FoodDayObservationTest {
	@Test void invalidObservationInputsCannotPartiallyRecordEarlierResidents() {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp("c", "Camp")); world.registerHousehold(new Household("h", "c"));
		Person valid = new Person("valid", "c", "h", Person.AgeBand.ADULT, 100, 50);
		Person foreign = new Person("foreign", "c", "h", Person.AgeBand.ADULT, 100, 50);
		world.registerPerson(valid);
		SurvivalSystem survival = new SurvivalSystem(world, SimulationConfig.defaults());
		assertThrows(IllegalArgumentException.class, () -> survival.observeClosingDay(List.of(valid, foreign), 1, 64));
		assertThrows(IllegalArgumentException.class, () -> survival.observeClosingDay(List.of(valid), -1, 64));
		assertThrows(IllegalArgumentException.class, () -> survival.observeClosingDay(List.of(valid), 1, -1));
		assertThrows(IllegalArgumentException.class, () -> survival.observeClosingDay(List.of(valid), 1, 101));
		assertFalse(valid.hasFoodDayHistory()); assertEquals(0, valid.hungerDays());
	}

	@Test void duplicateClosingObservationAndSocialObservationAreIdempotentBeforeAdvancement() {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp("camp", "Camp"));
		world.registerHousehold(new Household("home", "camp"));
		Person person = new Person("person", "camp", "home", Person.AgeBand.ADULT, 100, 50);
		world.registerPerson(person);
		SurvivalSystem survival = new SurvivalSystem(world, SimulationConfig.defaults());
		NormSystem norms = new NormSystem(world, SimulationConfig.defaults());
		survival.observeClosingDay(List.of(person), 1, 64);
		survival.observeClosingDay(List.of(person), 1, 64);
		assertEquals(1, person.observedFoodDays());
		survival.advanceDay(List.of(person));
		survival.observeClosingDay(List.of(person), 2, 64);
		norms.observeSocialDay(List.of(person));
		int events = world.ledger().events().size();
		for (int i = 0; i < 3; i++) {
			survival.observeClosingDay(List.of(person), 2, 64);
			norms.observeSocialDay(List.of(person));
		}
		assertEquals(events, world.ledger().events().size());
		assertEquals(2, person.hungerStreak());
		assertEquals(2, person.observedFoodDays());
		assertEquals(3, person.grievance());
		world.assertConservation();
	}

	@Test void restorationRejectsImpossibleCountsAndObservationSaturatesWithoutWrapping() {
		Person person = new Person("p", "c", "h", Person.AgeBand.ADULT, 100, 50);
		assertThrows(IllegalArgumentException.class, () -> person.restoreFoodDayHistory(3, 2, 2, 99, 0));
		assertThrows(IllegalArgumentException.class, () -> person.restoreFoodDayHistory(3, 2, 2, 0, 99));
		assertThrows(IllegalArgumentException.class, () -> person.restoreFoodDayHistory(2, 1, -1, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> person.restoreFoodDayHistory(1, 1, 0, 0, 1));
		assertFalse(person.hasFoodDayHistory());
		person.restoreFoodDayHistory(Long.MAX_VALUE - 1, Long.MAX_VALUE - 1, Integer.MAX_VALUE,
			Long.MAX_VALUE - 2, Long.MAX_VALUE - 2);
		person.observeFoodDay(Long.MAX_VALUE - 1, Long.MAX_VALUE - 1, 64);
		person.observeFoodDay(Long.MAX_VALUE, Long.MAX_VALUE, 64);
		assertEquals(Long.MAX_VALUE, person.observedFoodDays());
		assertEquals(Long.MAX_VALUE, person.hungerDays());
		assertEquals(Integer.MAX_VALUE, person.hungerStreak());
		// Once both counters saturate, a later non-hungry day can legitimately clear the streak.
		person.restoreFoodDayHistory(Long.MAX_VALUE, Long.MAX_VALUE, 0, Long.MAX_VALUE, Long.MAX_VALUE);
		assertEquals(0, person.hungerStreak());
	}

	@Test void standaloneUndatedHungerRecordingStillUsesExistingShortagePath() {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp("c", "Camp")); world.registerHousehold(new Household("h", "c"));
		Person person = new Person("p", "c", "h", Person.AgeBand.ADULT, 11, 50); world.registerPerson(person);
		person.recordHungerDay(); person.recordHungerDay();
		new NormSystem(world, SimulationConfig.defaults()).observeSocialDay(List.of(person));
		assertFalse(person.hasFoodDayHistory());
		assertEquals(1, world.ledger().events().stream().filter(e -> e.type() == EventType.SHORTAGE_CONFLICT).count());
	}
}
