package com.thysummer.coopcraft.civilization.simulation;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NormAllocationTotalsTest {
	@Test
	void authoritativeTotalsGiveIdenticalCarePracticeWithOrWithoutRebuiltCache() {
		var uninterrupted = fixture();
		var restored = fixture();
		var first = new NormSystem(uninterrupted, SimulationConfig.defaults());
		var second = new NormSystem(restored, SimulationConfig.defaults());
		first.recordCommonAllocation(uninterrupted.person("child"), meal(uninterrupted, 2), 2);
		second.recordCommonAllocation(restored.person("child"), meal(restored, 2), 2);
		second = new NormSystem(restored, SimulationConfig.defaults());
		second.restoreChargedSupervision();
		first.recordCommonAllocation(uninterrupted.person("child"), meal(uninterrupted, 1), 3);
		second.recordCommonAllocation(restored.person("child"), meal(restored, 1), 3);
		assertEquals(uninterrupted.ledger().events(), restored.ledger().events());
		assertEquals(1, careEvents(restored));
		assertEquals(51, restored.norm("camp", Norm.CARE_FIRST_COMMON_STORE).orElseThrow().stability());
		assertEquals(0, second.limitCommonAllocation(restored.person("child"), 9));
		assertEquals(1, restored.person("s1").socialHours());
		uninterrupted.assertConservation();
		restored.assertConservation();
	}

	@Test
	void earlierUnobservedMealsCountWithoutInventingEarlierNormPractices() {
		var w = fixture();
		meal(w, 2); // Actual food receipt, while norm observation was disabled.
		var n = new NormSystem(w, SimulationConfig.defaults());
		n.recordCommonAllocation(w.person("child"), meal(w, 1), 3);
		assertEquals(1, careEvents(w));
		assertEquals(2, w.ledger().events().stream().filter(e -> e.type() == EventType.NORM_ENFORCED).count());
		assertEquals(0, n.limitCommonAllocation(w.person("child"), 1));
		w.assertConservation();
	}

	@Test
	void invalidTotalsCannotMutateNormsFeesOrCachedCounts() {
		var w = fixture();
		var n = new NormSystem(w, SimulationConfig.defaults());
		n.recordCommonAllocation(w.person("child"), meal(w, 2), 2);
		var receipt = meal(w, 1);
		var events = w.ledger().events();
		for (int total : List.of(-1, 0, 1, 2)) {
			assertThrows(IllegalArgumentException.class, () -> n.recordCommonAllocation(w.person("child"), receipt, total));
			assertEquals(events, w.ledger().events());
			assertEquals(1, n.limitCommonAllocation(w.person("child"), 9));
			assertEquals(50, w.norm("camp", Norm.CARE_FIRST_COMMON_STORE).orElseThrow().stability());
			assertEquals(1, w.person("s1").socialHours());
		}
		n.recordCommonAllocation(w.person("child"), receipt, 3);
		assertEquals(1, careEvents(w));
	}

	@Test
	void newDayAcceptsItsOwnSmallerTotalAndDoesNotCarryCarePracticeForward() {
		var w = fixture();
		var n = new NormSystem(w, SimulationConfig.defaults());
		n.recordCommonAllocation(w.person("child"), meal(w, 3), 3);
		w.advanceDay();
		w.person("child").setHunger(100);
		n.recordCommonAllocation(w.person("child"), meal(w, 1), 1);
		assertEquals(1, careEvents(w));
		assertEquals(2, n.limitCommonAllocation(w.person("child"), 9));
		assertEquals(2, w.person("s1").socialHours());
		w.assertConservation();
	}

	@Test
	void authoritativeTotalsAreScopedToTheRequesterAndKeepViolationJudgment() {
		var w = fixture();
		meal(w, 3);
		var n = new NormSystem(w, SimulationConfig.defaults());
		n.recordCommonAllocation(w.person("child"), meal(w, 1), 4);
		assertEquals(2, w.ledger().events().stream().filter(e -> e.type() == EventType.NORM_VIOLATION).count());
		assertEquals(42, w.norm("camp", Norm.CARE_FIRST_COMMON_STORE).orElseThrow().stability());
		assertEquals(2, n.limitCommonAllocation(w.person("s1"), 9));
		w.assertConservation();
	}

	@Test
	void originalReceiptApiStillAccumulatesForUnpersistedSimulations() {
		var w = fixture();
		var n = new NormSystem(w, SimulationConfig.defaults());
		n.recordCommonAllocation(w.person("child"), meal(w, 2));
		n.recordCommonAllocation(w.person("child"), meal(w, 1));
		assertEquals(1, careEvents(w));
		assertEquals(0, n.limitCommonAllocation(w.person("child"), 9));
		w.assertConservation();
	}

	private static SimulationWorld fixture() {
		var w = new SimulationWorld();
		w.registerCamp(new Camp("camp", "Camp"));
		for (String id : List.of("child", "s1", "s2")) {
			w.registerHousehold(new Household("h-" + id, "camp"));
			w.registerPerson(new Person(id, "camp", "h-" + id,
				id.equals("child") ? Person.AgeBand.CHILD : Person.AgeBand.ADULT, 100, 50));
		}
		var supporters = Set.of("s1", "s2");
		for (String id : List.of(Norm.NEED_FIRST_COMMON_STORE, Norm.CARE_FIRST_COMMON_STORE)) {
			w.registerNorm(new Norm(id, "camp", id.equals(Norm.NEED_FIRST_COMMON_STORE) ? 2 : 1,
				supporters, Set.of(), supporters, 50, Norm.Status.ACTIVE, List.of()));
		}
		return w;
	}

	private static SurvivalSystem.MealWithEvidence meal(SimulationWorld w, int units) {
		w.importExternalBatch(ResourceType.FISH, units, StorageOwner.camp("camp"));
		return new SurvivalSystem(w, SimulationConfig.defaults()).eatFromCommonWithEvidence(w.person("child"), units);
	}

	private static long careEvents(SimulationWorld w) {
		return w.ledger().events().stream().filter(e -> Norm.CARE_FIRST_COMMON_STORE.equals(e.context().get("normId"))).count();
	}
}
