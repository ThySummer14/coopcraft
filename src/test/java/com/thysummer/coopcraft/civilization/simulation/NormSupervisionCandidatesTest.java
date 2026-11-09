package com.thysummer.coopcraft.civilization.simulation;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NormSupervisionCandidatesTest {
	@Test
	void defaultPopulationAndExplicitPopulationHaveIdenticalResults() {
		var a = fixture();
		var b = fixture();
		var legacy = new NormSystem(a, SimulationConfig.defaults());
		var scoped = new NormSystem(b, SimulationConfig.defaults());
		assertEquals(legacy.isNeedFirstEnforced("camp"), scoped.isNeedFirstEnforced("camp", b.people()));
		assertEquals(ids(legacy.prioritizeFoodRequests(a.people())), ids(scoped.prioritizeFoodRequests(b.people(), b.people())));
		legacy.recordCommonAllocation(a.person("child"), meal(a, 3), 3);
		scoped.recordCommonAllocation(b.person("child"), meal(b, 3), 3, b.people());
		assertEquals(a.ledger().events(), b.ledger().events());
		assertEquals(a.inventoryTotals(), b.inventoryTotals());
		assertEquals(legacy.limitCommonAllocation(a.person("child"), 9), scoped.limitCommonAllocation(b.person("child"), 9));
		a.assertConservation();
		b.assertConservation();
	}

	@Test
	void absentSupportersDoNotSortOrExecuteAndDoNotEraseAllocationCounts() {
		var w = fixture();
		var n = new NormSystem(w, SimulationConfig.defaults());
		w.person("s1").setHunger(10);
		w.person("s2").setHunger(80);
		var requests = new ArrayList<>(List.of(w.person("s1"), w.person("s2")));
		var absent = List.of(w.person("s1"));
		assertEquals(List.of("s2", "s1"), ids(n.prioritizeFoodRequests(requests)));
		assertEquals(List.of("s1", "s2"), ids(n.prioritizeFoodRequests(requests, absent)));
		assertEquals(List.of("s1", "s2"), ids(requests));
		n.recordCommonAllocation(w.person("child"), meal(w, 2), 2, absent);
		assertTrue(normEvents(w, Norm.NEED_FIRST_COMMON_STORE).isEmpty());
		assertEquals(50, w.norm("camp", Norm.NEED_FIRST_COMMON_STORE).orElseThrow().stability());
		assertEquals(0, w.person("s1").socialHours());
		assertEquals(1, n.limitCommonAllocation(w.person("child"), 9));
		w.assertConservation();
	}

	@Test
	void invalidIdentitiesDuplicatesNullMovedDeadAndExhaustedCandidatesCannotExecute() {
		var w = fixture();
		var n = new NormSystem(w, SimulationConfig.defaults());
		var s1 = w.person("s1");
		var s2 = w.person("s2");
		var impostor = new Person("s2", "camp", "h-s2", Person.AgeBand.ADULT, 100, 50);
		assertFalse(n.isNeedFirstEnforced("camp", List.of()));
		assertFalse(n.isNeedFirstEnforced("camp", null));
		assertFalse(n.isNeedFirstEnforced("camp", Arrays.asList(s1, s1, null, impostor)));
		var foreign = fixture();
		assertFalse(n.isNeedFirstEnforced("camp", List.of(s1, foreign.person("s2"))));
		var candidates = List.of(s1, s2);
		assertTrue(n.isNeedFirstEnforced("camp", candidates));
		w.moveHousehold("h-s2", "other");
		assertFalse(n.isNeedFirstEnforced("camp", candidates));
		w.moveHousehold("h-s2", "camp");
		s2.initializeAge(Person.AgeBand.CHILD, 10);
		assertFalse(n.isNeedFirstEnforced("camp", candidates));
		s2.initializeAge(Person.AgeBand.ADULT, 30);
		s2.addSocialHours(6);
		assertFalse(n.isNeedFirstEnforced("camp", candidates));
		s2.startDay();
		assertTrue(n.isNeedFirstEnforced("camp", candidates));
		var receipt = meal(w, 1);
		s2.markDead(w.day());
		assertFalse(n.isNeedFirstEnforced("camp", candidates));
		assertDoesNotThrow(() -> n.recordCommonAllocation(w.person("child"), receipt, 1, candidates));
		assertTrue(normEvents(w, Norm.NEED_FIRST_COMMON_STORE).isEmpty());
		assertEquals(0, s1.socialHours());
		w.assertConservation();
	}

	@Test
	void requestWindowReadsTheCandidateCollectionOnlyOnceAndDoesNotMutateIt() {
		var w = fixture();
		var n = new NormSystem(w, SimulationConfig.defaults());
		var candidates = new AbstractCollection<Person>() {
			private int reads;
			@Override public int size() { return 2; }
			@Override public Iterator<Person> iterator() {
				assertEquals(1, ++reads);
				return List.of(w.person("s2"), w.person("s1")).iterator();
			}
		};
		w.person("s1").setHunger(10);
		w.person("s2").setHunger(80);
		assertEquals(List.of("child", "s2", "s1"), ids(n.prioritizeFoodRequests(w.people().subList(0, 3), candidates)));
	}

	@Test
	void selectedSupervisorsAreSortedLimitedChargedAndNamedInNeedAndCareEvidence() {
		var w = fixture();
		var n = new NormSystem(w, SimulationConfig.defaults());
		var candidates = List.of(w.person("s3"), w.person("s2"), w.person("s2"));
		n.recordCommonAllocation(w.person("child"), meal(w, 3), 3, candidates);
		var need = normEvents(w, Norm.NEED_FIRST_COMMON_STORE).getFirst();
		assertEquals(EventType.NORM_ENFORCED, need.type());
		assertEquals(List.of("child", "s2", "s3"), need.actors());
		assertEquals(List.of("child", "s2", "s3"), normEvents(w, Norm.CARE_FIRST_COMMON_STORE).getFirst().actors());
		assertEquals(0, w.person("s1").socialHours());
		assertEquals(1, w.person("s2").socialHours());
		assertEquals(1, w.person("s3").socialHours());
		assertEquals(0, n.limitCommonAllocation(w.person("child"), 9));
		n.recordCommonAllocation(w.person("child"), meal(w, 1), 4, candidates);
		assertEquals(EventType.NORM_VIOLATION, normEvents(w, Norm.NEED_FIRST_COMMON_STORE).getLast().type());
		assertEquals(1, w.person("s2").socialHours());
		w.assertConservation();
	}

	@Test
	void atMostTwoCanonicalSupportersAreChosenInIdOrder() {
		var w = fixture();
		var n = new NormSystem(w, SimulationConfig.defaults());
		var candidates = List.of(w.person("s3"), w.person("s1"), w.person("child"), w.person("s2"));
		n.recordCommonAllocation(w.person("child"), meal(w, 1), 1, candidates);
		assertEquals(List.of("child", "s1", "s2"), normEvents(w, Norm.NEED_FIRST_COMMON_STORE).getFirst().actors());
		assertEquals(0, w.person("s3").socialHours());
		assertEquals(List.of("s3", "s1", "child", "s2"), ids(candidates));
		w.assertConservation();
	}

	@Test
	void carePracticeAndBonusDoNotRequireAnySupervisorButActorsAreScoped() {
		for (boolean onePresent : List.of(false, true)) {
			var w = fixture();
			var n = new NormSystem(w, SimulationConfig.defaults());
			var candidates = onePresent ? List.of(w.person("s2")) : List.<Person>of();
			n.recordCommonAllocation(w.person("child"), meal(w, 3), 3, candidates);
			assertTrue(normEvents(w, Norm.NEED_FIRST_COMMON_STORE).isEmpty());
			var care = normEvents(w, Norm.CARE_FIRST_COMMON_STORE).getFirst();
			assertEquals(onePresent ? List.of("child", "s2") : List.of("child"), care.actors());
			assertEquals(EventType.NORM_ENFORCED, care.type());
			assertEquals("3", care.context().get("cap"));
			assertEquals(51, w.norm("camp", Norm.CARE_FIRST_COMMON_STORE).orElseThrow().stability());
			assertEquals(0, w.person("s2").socialHours());
			w.assertConservation();
		}
	}

	@Test
	void returningSupervisorsPayOnceEvenAfterLedgerChargeRestoration() {
		var w = fixture();
		var n = new NormSystem(w, SimulationConfig.defaults());
		var candidates = List.of(w.person("s1"), w.person("s2"));
		n.recordCommonAllocation(w.person("child"), meal(w, 1), 1, candidates);
		n.recordCommonAllocation(w.person("child"), meal(w, 1), 2, List.of());
		var savedEvents = w.ledger().events();
		var restored = new NormSystem(w, SimulationConfig.defaults());
		restored.restoreChargedSupervision();
		restored.restoreChargedSupervision();
		assertEquals(savedEvents, w.ledger().events());
		restored.recordCommonAllocation(w.person("child"), meal(w, 1), 3, candidates);
		assertEquals(2, normEvents(w, Norm.NEED_FIRST_COMMON_STORE).size());
		assertEquals(1, w.person("s1").socialHours());
		assertEquals(1, w.person("s2").socialHours());
		assertEquals(0, restored.limitCommonAllocation(w.person("child"), 9));
		w.assertConservation();
	}

	private static SimulationWorld fixture() {
		var w = new SimulationWorld();
		w.registerCamp(new Camp("camp", "Camp"));
		w.registerCamp(new Camp("other", "Other"));
		for (String id : List.of("child", "s1", "s2", "s3")) {
			w.registerHousehold(new Household("h-" + id, "camp"));
			w.registerPerson(new Person(id, "camp", "h-" + id,
				id.equals("child") ? Person.AgeBand.CHILD : Person.AgeBand.ADULT, 100, 50));
		}
		var supporters = Set.of("s1", "s2", "s3");
		for (String norm : List.of(Norm.NEED_FIRST_COMMON_STORE, Norm.CARE_FIRST_COMMON_STORE)) {
			w.registerNorm(new Norm(norm, "camp", norm.equals(Norm.NEED_FIRST_COMMON_STORE) ? 2 : 1,
				supporters, Set.of(), supporters, 50, Norm.Status.ACTIVE, List.of()));
		}
		return w;
	}

	private static SurvivalSystem.MealWithEvidence meal(SimulationWorld w, int units) {
		w.person("child").setHunger(100);
		w.importExternalBatch(ResourceType.FISH, units, StorageOwner.camp("camp"));
		return new SurvivalSystem(w, SimulationConfig.defaults()).eatFromCommonWithEvidence(w.person("child"), units);
	}

	private static List<String> ids(List<Person> people) {
		return people.stream().map(Person::id).toList();
	}

	private static List<SimulationEvent> normEvents(SimulationWorld w, String norm) {
		return w.ledger().events().stream().filter(e -> norm.equals(e.context().get("normId"))).toList();
	}
}
