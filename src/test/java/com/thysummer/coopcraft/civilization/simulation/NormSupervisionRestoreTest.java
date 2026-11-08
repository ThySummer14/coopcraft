package com.thysummer.coopcraft.civilization.simulation;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NormSupervisionRestoreTest {
	private SimulationWorld world;
	private NormSystem norms;

	@BeforeEach
	void setUp() {
		world = new SimulationWorld();
		for (String camp : List.of("camp", "other")) {
			world.registerCamp(new Camp(camp, camp));
			world.createInitialBatch(ResourceType.FISH, 50, StorageOwner.camp(camp));
		}
		for (int i = 1; i <= 4; i++) {
			world.registerHousehold(new Household("h" + i, "camp"));
			world.registerPerson(new Person("p" + i, "camp", "h" + i, Person.AgeBand.ADULT, 50, 50));
		}
		registerNorm("camp", Norm.NEED_FIRST_COMMON_STORE, Set.of("p2", "p3"));
		norms = new NormSystem(world, SimulationConfig.defaults());
	}

	@Test
	void repeatedRestoreDoesNotChargeAgainForSameOrDifferentRequester() {
		allocate("p1", 1, true);
		var events = world.ledger().events();
		var stock = world.inventoryTotals();
		reload();
		norms.restoreChargedSupervision();
		assertEquals(events, world.ledger().events());
		assertEquals(stock, world.inventoryTotals());
		assertHours(1);
		allocate("p1", 1, true);
		allocate("p4", 1, true);
		assertHours(1);
		world.assertConservation();
	}

	@Test
	void violationAlsoProvesAnActualCharge() {
		allocate("p1", 4, true);
		assertEquals(EventType.NORM_VIOLATION, world.ledger().lastEvent().orElseThrow().type());
		reload();
		allocate("p4", 1, true);
		assertHours(1);
	}

	@Test
	void requesterWhoAlsoSupervisesIsRestoredByActorPosition() {
		registerNorm("camp", Norm.NEED_FIRST_COMMON_STORE, Set.of("p1", "p2"));
		allocate("p1", 1, true);
		reload();
		allocate("p4", 1, true);
		assertEquals(1, world.person("p1").socialHours());
		assertEquals(1, world.person("p2").socialHours());
	}

	@Test
	void careEventsDoNotProveSupervisionCharges() {
		world.person("p1").initializeAge(Person.AgeBand.CHILD, 10);
		registerNorm("camp", Norm.CARE_FIRST_COMMON_STORE, Set.of("p2", "p3"));
		registerNorm("camp", Norm.NEED_FIRST_COMMON_STORE, Set.of());
		allocate("p1", 4, true);
		assertEquals(Norm.CARE_FIRST_COMMON_STORE, world.ledger().lastEvent().orElseThrow().context().get("normId"));
		assertHours(0);
		registerNorm("camp", Norm.NEED_FIRST_COMMON_STORE, Set.of("p2", "p3"));
		reload();
		allocate("p4", 1, true);
		assertHours(1);
	}

	@Test
	void historicalCampComesFromAllocationEvenAfterSupervisorsMove() {
		allocate("p1", 1, true);
		world.moveHousehold("h2", "other");
		world.moveHousehold("h3", "other");
		world.moveHousehold("h4", "other");
		registerNorm("other", Norm.NEED_FIRST_COMMON_STORE, Set.of("p2", "p3"));
		reload();
		allocate("p4", 1, true);
		assertHours(2);
		world.moveHousehold("h2", "camp");
		world.moveHousehold("h3", "camp");
		allocate("p1", 1, true);
		assertHours(2);
	}

	@Test
	void yesterdayDoesNotWaiveTodaysFirstCharge() {
		allocate("p1", 1, true);
		world.advanceDay();
		reload();
		int before = world.person("p2").socialHours();
		allocate("p4", 1, true);
		assertEquals(before + 1, world.person("p2").socialHours());
		allocate("p1", 1, true);
		assertEquals(before + 1, world.person("p2").socialHours());
	}

	@Test
	void plainShareDoesNotWaiveAChargeAndNewSupervisorsStillPay() {
		allocate("p1", 1, false);
		reload();
		allocate("p1", 1, true);
		assertHours(1);
		registerNorm("camp", Norm.NEED_FIRST_COMMON_STORE, Set.of("p1", "p4"));
		reload();
		allocate("p1", 1, true);
		assertEquals(1, world.person("p1").socialHours());
		assertEquals(1, world.person("p4").socialHours());
	}

	@Test
	void mismatchedOrMissingAllocationEvidenceDoesNotWaiveCharges() {
		long share = allocate("p4", 1, false);
		world.recordSocialEvent(EventType.NORM_ENFORCED, List.of("p1", "p2", "p3"),
			Map.of("normId", Norm.NEED_FIRST_COMMON_STORE), List.of(share));
		world.recordSocialEvent(EventType.NORM_ENFORCED, List.of("p1", "p2", "p3"),
			Map.of("normId", Norm.NEED_FIRST_COMMON_STORE), List.of());
		reload();
		allocate("p1", 1, true);
		assertHours(1);
	}

	@Test
	void ambiguousMultipleAllocationsDoNotWaiveCharges() {
		long first = allocate("p1", 1, false);
		long second = allocate("p1", 1, false);
		world.recordSocialEvent(EventType.NORM_ENFORCED, List.of("p1", "p2", "p3"),
			Map.of("normId", Norm.NEED_FIRST_COMMON_STORE), List.of(first, second));
		reload();
		allocate("p1", 1, true);
		assertHours(1);
	}

	@Test
	void shareLabelWithoutRealInventoryTransferDoesNotWaiveCharges() {
		long share = world.recordSocialEvent(EventType.SHARE, List.of("p1", "camp"),
			Map.of("allocation", "camp-storage"), List.of());
		world.recordSocialEvent(EventType.NORM_ENFORCED, List.of("p1", "p2", "p3"),
			Map.of("normId", Norm.NEED_FIRST_COMMON_STORE), List.of(share));
		reload();
		allocate("p1", 1, true);
		assertHours(1);
	}

	private void reload() {
		norms = new NormSystem(world, SimulationConfig.defaults());
		norms.restoreChargedSupervision();
	}

	private void assertHours(int hours) {
		assertEquals(hours, world.person("p2").socialHours());
		assertEquals(hours, world.person("p3").socialHours());
	}

	private void registerNorm(String camp, String normId, Set<String> supporters) {
		world.registerNorm(new Norm(normId, camp, 3, supporters, Set.of(), supporters,
			50, Norm.Status.ACTIVE, List.of()));
	}

	private long allocate(String personId, int units, boolean supervised) {
		Person person = world.person(personId);
		long request = world.recordShareRequest(personId, person.campId(), 60);
		long allocation = world.allocateCommon(person.campId(), personId,
			world.batchesAt(StorageOwner.camp(person.campId())).getFirst().batchId(), units, request).eventId();
		if (supervised) {
			norms.recordCommonAllocation(person, units, request, allocation);
		}
		return allocation;
	}
}
