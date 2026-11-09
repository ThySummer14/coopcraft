package com.thysummer.coopcraft.civilization.simulation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommonMealInterruptionTest {
	@Test
	void realSingleAndMultipleBatchMealsProduceNeutralIdempotentEvidence() {
		for (int batches : List.of(1, 3)) {
			var world = fixture();
			var person = world.person("p");
			var evidence = meal(world, batches);
			int grievance = person.grievance();
			int trust = person.trustIn("other");
			long id = CommonMealInterruption.recordIfNeeded(world, person, evidence, batches + 1, 8).orElseThrow();
			var events = world.ledger().events();
			assertEquals(id, CommonMealInterruption.recordIfNeeded(world, person, evidence, batches + 1, 8).orElseThrow());
			assertEquals(events, world.ledger().events());
			var event = world.ledger().event(id).orElseThrow();
			assertEquals(batches + 2, event.causalLinks().size());
			assertTrue(event.inputs().isEmpty());
			assertTrue(event.outputs().isEmpty());
			assertEquals(grievance, person.grievance());
			assertEquals(trust, person.trustIn("other"));
			assertEquals(batches + 1, CommonMealInterruption.inspect(world, "p", "camp").orElseThrow().allocatedUnits());
			world.assertConservation();
		}
	}

	@Test
	void zeroMealsFullQuotaSatisfiedHungerAndRemainingFoodDoNotWrite() {
		var world = fixture();
		var person = world.person("p");
		var zero = new SurvivalSystem(world, SimulationConfig.defaults()).eatFromCommonWithEvidence(person, 4);
		assertTrue(CommonMealInterruption.recordIfNeeded(world, person, zero, 0, 4).isEmpty());
		var receipt = meal(world, 1);
		var events = world.ledger().events();
		assertTrue(CommonMealInterruption.recordIfNeeded(world, person, receipt, 4, 4).isEmpty());
		person.setHunger(5);
		assertTrue(CommonMealInterruption.recordIfNeeded(world, person, receipt, 1, 4).isEmpty());
		assertEquals(events, world.ledger().events());
		person.setHunger(90);
		world.importExternalBatch(ResourceType.FISH, 1, StorageOwner.camp("camp"));
		events = world.ledger().events();
		assertTrue(CommonMealInterruption.recordIfNeeded(world, person, receipt, 1, 4).isEmpty());
		assertEquals(events, world.ledger().events());
		assertEquals(0, person.grievance());
	}

	@Test
	void invalidTotalsCapsMissingMixedPersonAndMixedDayEvidenceAreRejectedWithoutWrites() {
		var world = fixture();
		var person = world.person("p");
		var receipt = meal(world, 2);
		var events = world.ledger().events();
		for (int[] args : List.of(new int[]{-1, 4}, new int[]{1, 4}, new int[]{5, 4}, new int[]{2, 0})) {
			assertThrows(IllegalArgumentException.class,
				() -> CommonMealInterruption.recordIfNeeded(world, person, receipt, args[0], args[1]));
		}
		assertThrows(IllegalArgumentException.class, () -> CommonMealInterruption.recordIfNeeded(world,
			person, new SurvivalSystem.MealWithEvidence(receipt.meal(), List.of()), 2, 4));
		assertThrows(IllegalArgumentException.class,
			() -> CommonMealInterruption.recordIfNeeded(world, world.person("other"), receipt, 2, 4));
		assertEquals(events, world.ledger().events());
		world.advanceDay();
		assertThrows(IllegalArgumentException.class,
			() -> CommonMealInterruption.recordIfNeeded(world, person, receipt, 2, 4));
		assertEquals(events, world.ledger().events());
	}

	@Test
	void unregisteredCopyAndDeadResidentCannotWriteEvenWithRealMealEvidence() {
		var world = fixture();
		var person = world.person("p");
		var receipt = meal(world, 2);
		var events = world.ledger().events();
		var copy = new Person("p", "camp", "h-p", Person.AgeBand.ADULT, 60, 50);
		assertThrows(IllegalArgumentException.class,
			() -> CommonMealInterruption.recordIfNeeded(world, copy, receipt, 2, 3));
		FoodAccessTestPeople.markDead(person, 0);
		assertThrows(IllegalArgumentException.class,
			() -> CommonMealInterruption.recordIfNeeded(world, person, receipt, 2, 3));
		assertEquals(events, world.ledger().events());
	}

	@Test
	void restoredEvidenceIsReadOnlyAndDoesNotDependOnCurrentFoodOrHunger() {
		var original = recorded();
		var restored = restore(original.ledger().events(), original.day());
		var before = restored.ledger().events();
		var expected = CommonMealInterruption.inspect(original, "p", "camp");
		assertEquals(expected, CommonMealInterruption.inspect(restored, "p", "camp"));
		assertEquals(before, restored.ledger().events());
		restored.person("p").setHunger(0);
		restored.importExternalBatch(ResourceType.FISH, 4, StorageOwner.camp("camp"));
		before = restored.ledger().events();
		assertEquals(expected, CommonMealInterruption.inspect(restored, "p", "camp"));
		assertEquals(before, restored.ledger().events());
		assertTrue(CommonMealInterruption.inspect(restored, "p", "wrong-camp").isEmpty());
		restored.advanceDay();
		assertTrue(CommonMealInterruption.inspect(restored, "p", "camp").isEmpty());
	}

	@Test
	void malformedNewestRecordNeverFallsBackToEarlierEvidence() {
		var source = recorded();
		var good = source.ledger().lastEvent().orElseThrow();
		for (Map.Entry<String, String> mutation : List.of(
			Map.entry("reason", "refused"), Map.entry("allocationBasis", "nutrition"),
			Map.entry("dailyAllocatedUnits", "2147483648"), Map.entry("cap", "2"),
			Map.entry("allocatedUnits", "0"), Map.entry("hunger", "5"), Map.entry("availableNutrition", "1"),
			Map.entry(MealAllocationEvidence.KEY, "unknown"), Map.entry(MealAllocationEvidence.COUNT, "1"),
			Map.entry("cap", "-1"), Map.entry("hunger", "101"), Map.entry("hunger", "90"), Map.entry("cap", "+8"))) {
			var context = new HashMap<>(good.context());
			context.put(mutation.getKey(), mutation.getValue());
			assertInvalidNewest(source, context, good.actors(), good.causalLinks(), List.of());
		}
		for (String key : good.context().keySet()) {
			var context = new HashMap<>(good.context());
			context.remove(key);
			assertInvalidNewest(source, context, good.actors(), good.causalLinks(), List.of());
		}
		assertInvalidNewest(source, good.context(), List.of("p", "other"), good.causalLinks(), List.of());
		var extra = new ArrayList<>(good.causalLinks());
		extra.add(1L); // An unrelated import is not part of the meal receipt.
		assertInvalidNewest(source, good.context(), good.actors(), extra, List.of());
		var missing = new ArrayList<>(good.causalLinks());
		missing.removeLast();
		assertInvalidNewest(source, good.context(), good.actors(), missing, List.of());
		var consume = source.ledger().event(good.causalLinks().getLast()).orElseThrow();
		assertInvalidNewest(source, good.context(), good.actors(), good.causalLinks(), consume.inputs());
	}

	@Test
	void restoredMixedPersonAndDayReceiptsCannotBeUsed() {
		var source = recorded();
		var good = source.ledger().lastEvent().orElseThrow();
		var events = new ArrayList<>(source.ledger().events());
		events.set(events.size() - 1, new SimulationEvent(good.eventId(), good.day(), good.type(), List.of("other"),
			List.of(), List.of(), good.context(), good.causalLinks()));
		assertTrue(CommonMealInterruption.inspect(restore(events, 0), "other", "camp").isEmpty());
		events.set(events.size() - 1, new SimulationEvent(good.eventId(), 1, good.type(), good.actors(),
			List.of(), List.of(), good.context(), good.causalLinks()));
		assertTrue(CommonMealInterruption.inspect(restore(events, 1), "p", "camp").isEmpty());
	}

	private static void assertInvalidNewest(SimulationWorld source, Map<String, String> context,
		List<String> actors, List<Long> causes, List<ResourceFlow> inputs) {
		var events = new ArrayList<>(source.ledger().events());
		events.add(new SimulationEvent(events.getLast().eventId() + 1, source.day(), EventType.COMMON_MEAL_INTERRUPTED,
			actors, inputs, List.of(), context, causes));
		var restored = restore(source.ledger().events(), source.day());
		restored.ledger().append(events.getLast());
		assertTrue(CommonMealInterruption.inspect(restored, "p", "camp").isEmpty(), context.toString());
		assertEquals(events, restored.ledger().events());
	}

	private static SimulationWorld restore(List<SimulationEvent> events, long day) {
		var world = fixture();
		world.restoreState(day, 100, List.of(), events);
		return world;
	}

	private static SimulationWorld recorded() {
		var world = fixture();
		var receipt = meal(world, 2);
		CommonMealInterruption.recordIfNeeded(world, world.person("p"), receipt, 2, 8).orElseThrow();
		return world;
	}

	private static SurvivalSystem.MealWithEvidence meal(SimulationWorld world, int batches) {
		for (int i = 0; i < batches; i++) world.importExternalBatch(ResourceType.FISH, 1, StorageOwner.camp("camp"));
		return new SurvivalSystem(world, SimulationConfig.defaults()).eatFromCommonWithEvidence(world.person("p"), 8);
	}

	private static SimulationWorld fixture() {
		var world = new SimulationWorld();
		world.registerCamp(new Camp("camp", "Camp"));
		for (String id : List.of("p", "other")) {
			world.registerHousehold(new Household("h-" + id, "camp"));
			world.registerPerson(new Person(id, "camp", "h-" + id, Person.AgeBand.ADULT, 100, 50));
		}
		return world;
	}
}
