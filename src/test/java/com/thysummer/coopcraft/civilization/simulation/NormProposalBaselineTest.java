package com.thysummer.coopcraft.civilization.simulation;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NormProposalBaselineTest {
	@Test void actualBaselineCanIncreaseOrDecreaseWithHouseholdReserve() {
		assertEquals(4, proposal(true, null, false, 100).allocationUnitCap());
		assertEquals(2, proposal(true, null, true, 100).allocationUnitCap());
		assertEquals(3, proposal(true, norm(2, Norm.Status.ACTIVE), false, 100).allocationUnitCap());
		assertEquals(4, proposal(true, norm(1, Norm.Status.DORMANT), false, 100).allocationUnitCap());
		assertEquals(3, proposal(true, norm(4, Norm.Status.ACTIVE), true, 100).allocationUnitCap());
	}

	@Test void dormantHistoricalFourCanProposeFourAgainWithoutRewritingHistory() {
		Fixture f = fixture(true, 100);
		var history = List.of(new Norm.Revision(0, "historical", 1, 3, 4, Norm.Revision.Action.REVISED));
		Norm dormant = new Norm(Norm.NEED_FIRST_COMMON_STORE, "camp", 4,
			Set.of(), Set.of(), Set.of(), 20, Norm.Status.DORMANT, history);
		f.world.registerNorm(dormant);
		refusal(f); f.norms.observeSocialDay(f.world.people());
		NormProposal proposal = f.world.proposalsForCamp("camp").getFirst();
		assertEquals(4, proposal.allocationUnitCap());
		assertEquals(1, proposal.revision());
		assertEquals(history, dormant.revisionHistory());
		assertEquals(Norm.Status.DORMANT, dormant.status());
	}

	@Test void boundsDoNotCreateZeroOrFiveOrRedundantActiveProposal() {
		for (boolean rich : List.of(false, true)) {
			Fixture f = fixture(true, 100);
			f.world.registerNorm(norm(rich ? 1 : 4, Norm.Status.ACTIVE));
			if (rich) f.world.createInitialBatch(ResourceType.FISH, 100, StorageOwner.household("h"));
			refusal(f);
			f.norms.observeSocialDay(f.world.people());
			assertTrue(f.world.proposalsForCamp("camp").isEmpty());
		}
	}

	@Test void legacyConstructorRetainsHungrySatiatedAndDormantSemantics() {
		assertEquals(2, proposal(false, null, false, 100).allocationUnitCap());
		assertEquals(1, proposal(false, null, false, 0).allocationUnitCap());
		assertEquals(2, proposal(false, norm(1, Norm.Status.DORMANT), false, 100).allocationUnitCap());
		Fixture f = fixture(false, 100);
		assertEquals(20, f.norms.limitCommonAllocation(f.person, 20));
	}

	@Test void careRemainsAnIndependentOneUnitProposalAndBonusIsNotBase() {
		Fixture f = fixture(true, 100);
		f.world.registerPerson(new Person("child", "camp", "h", Person.AgeBand.CHILD, 100, 50));
		refusal(f); f.norms.observeSocialDay(f.world.people());
		NormProposal care = f.world.proposalsForCamp("camp").getFirst();
		assertEquals(Norm.CARE_FIRST_COMMON_STORE, care.normId());
		assertEquals(1, care.allocationUnitCap());
		Fixture withCare = fixture(true, 100);
		withCare.world.registerPerson(new Person("child", "camp", "h", Person.AgeBand.CHILD, 100, 50));
		withCare.world.registerNorm(new Norm(Norm.CARE_FIRST_COMMON_STORE, "camp", 1,
			Set.of(), Set.of(), Set.of(), 50, Norm.Status.ACTIVE, List.of()));
		withCare.world.registerNorm(norm(2, Norm.Status.ACTIVE));
		refusal(withCare); withCare.norms.observeSocialDay(withCare.world.people());
		NormProposal need = withCare.world.proposalsForCamp("camp").getFirst();
		assertEquals(Norm.NEED_FIRST_COMMON_STORE, need.normId());
		assertEquals(3, need.allocationUnitCap());
	}

	@Test void explicitBaselineMustFitProposalBounds() {
		SimulationWorld world = new SimulationWorld();
		assertThrows(IllegalArgumentException.class, () -> new NormSystem(world, SimulationConfig.defaults(), 0));
		assertThrows(IllegalArgumentException.class, () -> new NormSystem(world, SimulationConfig.defaults(), 5));
	}

	private static NormProposal proposal(boolean adapter, Norm existing, boolean rich, int hunger) {
		Fixture f = fixture(adapter, hunger);
		if (existing != null) f.world.registerNorm(existing);
		if (rich) f.world.createInitialBatch(ResourceType.FISH, 100, StorageOwner.household("h"));
		var stock = f.world.inventoryTotals();
		refusal(f); f.norms.observeSocialDay(f.world.people());
		assertEquals(stock, f.world.inventoryTotals());
		f.world.assertConservation();
		return f.world.proposalsForCamp("camp").getFirst();
	}
	private static Norm norm(int cap, Norm.Status status) {
		return new Norm(Norm.NEED_FIRST_COMMON_STORE, "camp", cap, Set.of(), Set.of(), Set.of(), 50, status, List.of());
	}
	private static Fixture fixture(boolean adapter, int hunger) {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp("camp", "Camp"));
		world.registerHousehold(new Household("h", "camp"));
		Person person = new Person("p", "camp", "h", Person.AgeBand.ADULT, hunger, 50);
		world.registerPerson(person);
		return new Fixture(world, person, adapter ? new NormSystem(world, SimulationConfig.defaults(), 3)
			: new NormSystem(world, SimulationConfig.defaults()));
	}
	private static void refusal(Fixture f) {
		long request = f.world.recordShareRequest("p", "camp", 40);
		f.world.recordShareRefusal(request, "p", "camp", 20);
		// Core fixtures isolate proposal selection, including the satiated legacy branch.
		f.person.increaseGrievance(4);
	}
	private record Fixture(SimulationWorld world, Person person, NormSystem norms) {}
}
