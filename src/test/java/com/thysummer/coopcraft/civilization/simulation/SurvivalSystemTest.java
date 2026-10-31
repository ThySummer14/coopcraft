package com.thysummer.coopcraft.civilization.simulation;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurvivalSystemTest {
	@Test
	void dailyHungerAndCommonMealsConserveFoodAndOrigins() {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp("camp", "Camp"));
		world.registerHousehold(new Household("h", "camp"));
		Person person = new Person("p", "camp", "h", Person.AgeBand.ADULT, 20, 50);
		world.registerPerson(person);
		ResourceBatch stock = world.importExternalBatch(ResourceType.TUBERS, 5, StorageOwner.camp("camp"));
		SurvivalSystem survival = new SurvivalSystem(world, SimulationConfig.defaults());

		survival.advanceDay(List.of(person));
		assertEquals(55, person.hunger());
		assertEquals(1, world.day());
		assertEquals(72, survival.eatFromCommon(person));
		assertEquals(0, person.hunger());
		assertEquals(2, stock.quantity());
		SimulationEvent firstMeal = world.ledger().events().getLast();
		assertEquals(EventType.CONSUME, firstMeal.type());
		assertTrue(world.ledger().traceOrigins(firstMeal.eventId()).stream()
			.anyMatch(event -> event.eventId() == stock.originEventId()));
		world.assertConservation();

		survival.advanceDay(List.of(person));
		assertEquals(48, survival.eatFromCommon(person));
		assertEquals(0, world.availableNutrition(StorageOwner.camp("camp")));
		world.assertConservation();

		survival.advanceDay(List.of(person));
		assertEquals(0, survival.eatFromCommon(person));
		assertEquals(35, person.hunger());
		assertEquals(0, world.availableNutrition(StorageOwner.camp("camp")));
		world.assertConservation();
	}
}
