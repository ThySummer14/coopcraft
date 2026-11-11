package com.thysummer.coopcraft.civilization.simulation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MealAllocationEvidenceTest {
	private SimulationWorld world(ResourceType second, int secondUnits) {
		var w = new SimulationWorld();
		w.registerCamp(new Camp("camp", "Camp"));
		w.registerCamp(new Camp("other", "Other"));
		for (int i = 1; i <= 4; i++) {
			w.registerHousehold(new Household("h" + i, "camp"));
			w.registerPerson(new Person("p" + i, "camp", "h" + i,
				i == 1 ? Person.AgeBand.CHILD : Person.AgeBand.ADULT, 100, 50));
		}
		w.importExternalBatch(ResourceType.FISH, 1, StorageOwner.camp("camp"));
		w.importExternalBatch(second, secondUnits, StorageOwner.camp("camp"));
		var supporters = Set.of("p2", "p3");
		w.registerNorm(new Norm(Norm.NEED_FIRST_COMMON_STORE, "camp", 1, supporters, Set.of(), supporters,
			50, Norm.Status.ACTIVE, List.of()));
		w.registerNorm(new Norm(Norm.CARE_FIRST_COMMON_STORE, "camp", 3, supporters, Set.of(), supporters,
			50, Norm.Status.ACTIVE, List.of()));
		return w;
	}
	private SurvivalSystem.MealWithEvidence eat(SimulationWorld w, int cap) {
		return new SurvivalSystem(w, SimulationConfig.defaults()).eatFromCommonWithEvidence(w.person("p1"), cap);
	}
	private NormSystem norms(SimulationWorld w) { return new NormSystem(w, SimulationConfig.defaults()); }

	@Test void mixedAndSameTypeMealsPreserveBothIndependentOriginsAndLegacyOutcomes() {
		for (var type : List.of(ResourceType.FISH, ResourceType.TUBERS)) {
			var w = world(type, 2);
			var old = world(type, 2);
			var receipt = eat(w, 3);
			var legacy = new SurvivalSystem(old, SimulationConfig.defaults()).eatFromCommon(old.person("p1"), 3);
			assertEquals(legacy, receipt.meal());
			norms(w).recordCommonAllocation(w.person("p1"), receipt);
			norms(old).recordCommonAllocation(old.person("p1"), legacy.units(), legacy.requestEventId(), legacy.allocationEventId());
			assertEquals(old.inventoryTotals(), w.inventoryTotals());
			assertEquals(old.person("p1").hunger(), w.person("p1").hunger());
			assertEquals(old.person("p2").socialHours(), w.person("p2").socialHours());
			assertEquals(old.ledger().events().size(), w.ledger().events().size());
			for (var e : w.ledger().events()) {
				var prior = old.ledger().event(e.eventId()).orElseThrow();
				assertEquals(prior.type(), e.type());
				if (!e.context().containsKey(MealAllocationEvidence.KEY)) { assertEquals(prior, e); continue; }
				assertEquals(List.of(1L, 2L), w.ledger().traceOrigins(e.eventId()).stream().map(SimulationEvent::eventId).toList());
				assertEquals(1, e.causalLinks().stream().map(id -> w.ledger().event(id).orElseThrow())
					.filter(c -> c.type() == EventType.SHARE).count());
			}
			for (var id : List.of(Norm.NEED_FIRST_COMMON_STORE, Norm.CARE_FIRST_COMMON_STORE))
				assertEquals(old.norm("camp", id).orElseThrow().stability(), w.norm("camp", id).orElseThrow().stability());
			w.assertConservation();
		}
	}

	@Test void singleBatchTruncationEmptyMealAndImmutableReceipts() {
		var w = world(ResourceType.TUBERS, 20);
		var single = eat(w, 1);
		assertEquals(1, single.allocations().size());
		norms(w).recordCommonAllocation(w.person("p1"), single);
		var truncated = eat(w, 2);
		assertEquals(2, truncated.meal().units());
		assertEquals(1, truncated.allocations().size());
		norms(w).recordCommonAllocation(w.person("p1"), truncated);
		assertThrows(UnsupportedOperationException.class, () -> truncated.allocations().clear());
		var mutable = new ArrayList<>(truncated.allocations());
		var copy = new SurvivalSystem.MealWithEvidence(truncated.meal(), mutable);
		mutable.clear(); assertEquals(1, copy.allocations().size());
		w.person("p1").consumeNutrition(100);
		var empty = eat(w, 2);
		assertEquals(new SurvivalSystem.MealResult(0, 0, 0, 0), empty.meal());
		assertTrue(empty.allocations().isEmpty());
		w.assertConservation();
	}

	@Test void malformedReceiptsHaveNoNormOrFeeOrAllocationSideEffects() {
		var w = world(ResourceType.TUBERS, 2);
		var good = eat(w, 3);
		var m = good.meal(); var first = good.allocations().getFirst();
		var wrongs = List.of(
			new SurvivalSystem.MealWithEvidence(m, List.of()),
			new SurvivalSystem.MealWithEvidence(m, List.of(first, first)),
			new SurvivalSystem.MealWithEvidence(new SurvivalSystem.MealResult(m.nutrition(), 2, m.requestEventId(), m.allocationEventId()), good.allocations()),
			new SurvivalSystem.MealWithEvidence(new SurvivalSystem.MealResult(1, m.units(), m.requestEventId(), m.allocationEventId()), good.allocations()),
			new SurvivalSystem.MealWithEvidence(new SurvivalSystem.MealResult(m.nutrition(), m.units(), first.requestEventId(), first.allocationEventId()), good.allocations()),
			new SurvivalSystem.MealWithEvidence(m, List.of(new SurvivalSystem.ConsumedAllocation(1, 2, 3, 3)))
		);
		var n = norms(w); var events = w.ledger().events(); var stock = w.inventoryTotals();
		for (var wrong : wrongs) assertThrows(IllegalArgumentException.class, () -> n.recordCommonAllocation(w.person("p1"), wrong));
		assertThrows(IllegalArgumentException.class, () -> n.recordCommonAllocation(w.person("p4"), good));
		w.moveHousehold("h1", "other");
		assertThrows(IllegalArgumentException.class, () -> n.recordCommonAllocation(w.person("p1"), good));
		w.moveHousehold("h1", "camp");
		assertEquals(events, w.ledger().events()); assertEquals(stock, w.inventoryTotals());
		assertEquals(0, w.person("p2").socialHours());
		assertEquals(4, n.limitCommonAllocation(w.person("p1"), 10));
		assertEquals(50, w.norm("camp", Norm.NEED_FIRST_COMMON_STORE).orElseThrow().stability());
		w.advanceDay();
		assertThrows(IllegalArgumentException.class, () -> n.recordCommonAllocation(w.person("p1"), good));
	}

	@Test void restoredEvidenceRejectsAlteredPeopleDaysCampsFlowsAndSources() {
		for (String mutation : List.of("person", "day", "camp", "batch", "type", "nutrition", "location", "quantity", "source")) {
			var w = world(ResourceType.TUBERS, 2); var receipt = eat(w, 3);
			norms(w).recordCommonAllocation(w.person("p1"), receipt);
			var changed = new ArrayList<SimulationEvent>();
			for (var e : w.ledger().events()) {
				var actors = e.actors(); var inputs = e.inputs(); var causes = e.causalLinks(); long day = e.day();
				if (mutation.equals("day") && e.eventId() >= receipt.allocations().getLast().requestEventId()) day++;
				if (e.eventId() == receipt.allocations().getFirst().consumptionEventId()) {
					if (mutation.equals("person")) actors = List.of("p4");
					var f = inputs.getFirst();
					inputs = List.of(new ResourceFlow(mutation.equals("batch") ? "wrong" : f.batchId(),
						mutation.equals("type") ? ResourceType.TUBERS : f.resourceType(),
						mutation.equals("quantity") ? 2 : f.quantity(),
						mutation.equals("nutrition") ? 999 : f.nutritionPerUnit(),
						mutation.equals("location") ? "household:h4" : f.fromLocation(), f.toLocation()));
				}
				if (e.eventId() == receipt.allocations().getFirst().allocationEventId()) {
					if (mutation.equals("camp")) actors = List.of("p1", "other");
					if (mutation.equals("source")) causes = List.of(2L, receipt.allocations().getFirst().requestEventId());
				}
				changed.add(new SimulationEvent(e.eventId(), day, e.type(), actors, inputs, e.outputs(), e.context(), causes));
			}
			var ledger = new EventLedger(); ledger.restore(changed);
			var marked = changed.stream().filter(e -> Norm.NEED_FIRST_COMMON_STORE.equals(e.context().get("normId"))).findFirst().orElseThrow();
			assertThrows(IllegalArgumentException.class, () -> MealAllocationEvidence.restoredCamp(ledger, marked), mutation);
			assertEquals(changed, ledger.events());
		}
	}

	@Test void residualCampStockKeepsProvenanceAfterEverySupportedPartialWithdrawal() {
		for (var type : List.of(EventType.EXTERNAL_EXPORT, EventType.SHARE)) {
			var w = world(ResourceType.TUBERS, 6);
			var batch = w.batchesAt(StorageOwner.camp("camp")).stream()
				.filter(b -> b.resourceType() == ResourceType.TUBERS).findFirst().orElseThrow();
			switch (type) {
				case EXTERNAL_EXPORT -> w.exportExternalBatch(StorageOwner.camp("camp"), batch.batchId(), 1);
				case SHARE -> w.allocateCommon("camp", "p4", batch.batchId(), 1,
					w.recordShareRequest("p4", "camp", 20));
				default -> throw new AssertionError(type);
			}
			var receipt = eat(w, 3);
			assertEquals(2, receipt.allocations().size(), type.toString());
			assertDoesNotThrow(() -> norms(w).recordCommonAllocation(w.person("p1"), receipt), type.toString());
			for (var event : w.ledger().events().stream()
				.filter(e -> e.context().containsKey(MealAllocationEvidence.KEY)).toList()) {
				assertEquals("camp", MealAllocationEvidence.restoredCamp(w.ledger(), event), type.toString());
				assertEquals(List.of(1L, 2L), w.ledger().traceOrigins(event.eventId()).stream()
					.map(SimulationEvent::eventId).toList(), type.toString());
			}
			w.assertConservation();
		}
	}

	@Test void violationsUseTheSameCompleteEvidenceAndRestoreCharges() {
		var w = world(ResourceType.TUBERS, 5); var receipt = eat(w, 6);
		assertTrue(receipt.meal().units() > 4);
		norms(w).recordCommonAllocation(w.person("p1"), receipt);
		var violations = w.ledger().events().stream().filter(e -> e.type() == EventType.NORM_VIOLATION).toList();
		assertEquals(2, violations.size());
		for (var e : violations) {
			assertEquals(List.of(1L, 2L), w.ledger().traceOrigins(e.eventId()).stream().map(SimulationEvent::eventId).toList());
			assertEquals("camp", MealAllocationEvidence.restoredCamp(w.ledger(), e));
		}
		var n = norms(w); n.restoreChargedSupervision();
		var batch = w.importExternalBatch(ResourceType.FISH, 1, StorageOwner.camp("camp"));
		long request = w.recordShareRequest("p4", "camp", 20);
		var share = w.allocateCommon("camp", "p4", batch.batchId(), 1, request);
		n.recordCommonAllocation(w.person("p4"), 1, request, share.eventId());
		assertEquals(1, w.person("p2").socialHours());
	}

	@Test void markedRestoreDeduplicatesChargesAndUnknownOrMalformedMarkersNeverFallBack() {
		for (String mutation : List.of("valid", "unknown", "count", "units", "missing-consume", "anchor")) {
			var w = world(ResourceType.TUBERS, 2); var receipt = eat(w, 3);
			var context = new LinkedHashMap<String, String>(Map.of("normId", Norm.NEED_FIRST_COMMON_STORE,
				"allocatedUnits", "3", MealAllocationEvidence.KEY, MealAllocationEvidence.VERSION, MealAllocationEvidence.COUNT, "2"));
			var causes = new ArrayList<Long>(List.of(receipt.meal().requestEventId(), receipt.meal().allocationEventId()));
			for (var part : receipt.allocations()) causes.add(part.consumptionEventId());
			if (mutation.equals("unknown")) context.put(MealAllocationEvidence.KEY, "future-v2");
			if (mutation.equals("count")) context.put(MealAllocationEvidence.COUNT, "1");
			if (mutation.equals("units")) context.put("allocatedUnits", "2");
			if (mutation.equals("missing-consume")) causes.remove(receipt.allocations().getFirst().consumptionEventId());
			if (mutation.equals("anchor")) { causes.set(0, receipt.allocations().getFirst().requestEventId()); causes.set(1, receipt.allocations().getFirst().allocationEventId()); }
			w.recordSocialEvent(EventType.NORM_ENFORCED, List.of("p1", "p2", "p3"), context, causes);
			w.person("p2").addSocialHours(1); w.person("p3").addSocialHours(1);
			var n = norms(w); var before = w.ledger().events();
			n.restoreChargedSupervision(); n.restoreChargedSupervision();
			assertEquals(before, w.ledger().events());
			var batch = w.importExternalBatch(ResourceType.FISH, 1, StorageOwner.camp("camp"));
			long request = w.recordShareRequest("p4", "camp", 20);
			var share = w.allocateCommon("camp", "p4", batch.batchId(), 1, request);
			n.recordCommonAllocation(w.person("p4"), 1, request, share.eventId());
			assertEquals(mutation.equals("valid") ? 1 : 2, w.person("p2").socialHours(), mutation);
		}
	}
}
