package com.thysummer.coopcraft.civilization.simulation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShareRefusalEvidenceTest {
	@Test
	void legacyApiRetainsScoreOnlyEventAndTrustChange() {
		SimulationWorld world = world();
		world.person("p").setTrust("camp", 50);
		long request = world.recordShareRequest("p", "camp", 40);
		long refusal = world.recordShareRefusal(request, "p", "camp", 20);
		SimulationEvent event = world.ledger().event(refusal).orElseThrow();

		assertEquals(Map.of("score", "20.0"), event.context());
		assertEquals(EventType.SHARE_REFUSED, event.type());
		assertEquals(List.of("p", "camp"), event.actors());
		assertEquals(List.of(request), event.causalLinks());
		assertEquals(List.of(), event.inputs());
		assertEquals(List.of(), event.outputs());
		assertEquals(2, world.ledger().events().size());
		assertEquals(48, world.person("p").trustIn("camp"));
	}

	@Test
	void contextIsAnImmutableSnapshotAndCannotOverrideScore() {
		SimulationWorld world = world();
		world.person("p").setTrust("camp", 50);
		long request = world.recordShareRequest("p", "camp", 40);
		Map<String, String> context = new HashMap<>(Map.of(
			"reason", "daily-unit-cap-exhausted", "score", "999", "availableNutrition", "340"));
		long refusal = world.recordShareRefusal(request, "p", "camp", 0, context);
		context.put("availableNutrition", "0");
		context.clear();
		SimulationEvent event = world.ledger().event(refusal).orElseThrow();

		assertEquals(Map.of("reason", "daily-unit-cap-exhausted", "score", "0.0",
			"availableNutrition", "340"), event.context());
		assertThrows(UnsupportedOperationException.class, () -> event.context().put("reason", "changed"));
		assertEquals(48, world.person("p").trustIn("camp"));

		SimulationWorld restored = world();
		restored.restoreState(world.day(), world.nextBatchId(), List.of(), world.ledger().events());
		assertEquals(event, restored.ledger().event(refusal).orElseThrow());
	}

	@Test
	void invalidCausesActorsAndContextHaveNoSideEffects() {
		SimulationWorld world = world();
		long request = world.recordShareRequest("p", "camp", 40);
		long wrongType = world.importExternalBatch(ResourceType.FISH, 1, StorageOwner.camp("camp")).originEventId();
		List<SimulationEvent> before = world.ledger().events();
		Map<String, String> invalidContext = new HashMap<>();
		invalidContext.put("reason", null);

		assertThrows(NoSuchElementException.class, () -> world.recordShareRefusal(999, "p", "camp", 0));
		assertThrows(IllegalArgumentException.class, () -> world.recordShareRefusal(wrongType, "p", "camp", 0));
		assertThrows(IllegalArgumentException.class, () -> world.recordShareRefusal(request, "p", "other", 0));
		assertThrows(IllegalArgumentException.class, () -> world.recordShareRefusal(request, "q", "camp", 0));
		assertThrows(IllegalArgumentException.class, () -> world.recordShareRefusal(request, "camp", "p", 0));
		assertThrows(NullPointerException.class, () -> world.recordShareRefusal(request, "p", "camp", 0, null));
		assertThrows(NullPointerException.class, () -> world.recordShareRefusal(request, "p", "camp", 0, invalidContext));
		assertEquals(before, world.ledger().events());
		assertEquals(Map.of(), world.person("p").trustedPeople());
		assertEquals(Map.of(), world.person("q").trustedPeople());
		assertEquals(wrongType + 1, world.recordShareRefusal(request, "p", "camp", 0));
		world.assertConservation();
	}

	private static SimulationWorld world() {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp("camp", "Test Camp"));
		world.registerHousehold(new Household("h", "camp"));
		world.registerPerson(new Person("p", "camp", "h", Person.AgeBand.ADULT, 60, 50));
		world.registerPerson(new Person("q", "camp", "h", Person.AgeBand.ADULT, 60, 50));
		return world;
	}
}
