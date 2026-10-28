package com.thysummer.coopcraft.civilization.simulation;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NormSystemTest {
	private SimulationWorld world;

	@BeforeEach
	void setUp() {
		world = new SimulationWorld();
		world.registerCamp(new Camp("camp", "Test Camp"));
		for (int index = 1; index <= 4; index++) {
			world.registerHousehold(new Household("h" + index, "camp"));
		}
		world.registerPerson(new Person("p1", "camp", "h1", Person.AgeBand.ADULT, 0, 50));
		world.registerPerson(new Person("p2", "camp", "h2", Person.AgeBand.ADULT, 90, 50));
		world.registerPerson(new Person("p3", "camp", "h3", Person.AgeBand.ADULT, 20, 50));
		world.registerPerson(new Person("p4", "camp", "h4", Person.AgeBand.ADULT, 20, 50));
	}

	@Test
	void dailyAllocationCapIsCumulativeAndSupervisionCostsTime() {
		for (Person person : world.people()) {
			person.observeNorm("neighbor-requests");
		}
		world.createInitialBatch(ResourceType.FISH, 10, StorageOwner.camp("camp"));
		world.registerNorm(new Norm(
			Norm.NEED_FIRST_COMMON_STORE, "camp", 2, Set.of("p2", "p3"), Set.of(),
			Set.of("p1", "p2", "p3", "p4"), 50, Norm.Status.ACTIVE, List.of()
		));
		NormSystem norms = new NormSystem(world, SimulationConfig.defaults());
		Person requester = world.person("p1");

		assertEquals(2, norms.limitCommonAllocation(requester, 4));
		long request = world.recordShareRequest("p1", "camp", 60);
		SimulationWorld.TransferResult allocation = world.allocateCommon(
			"camp", "p1", world.batchesAt(StorageOwner.camp("camp")).getFirst().batchId(), 2, request
		);
		norms.recordCommonAllocation(requester, 2, request, allocation.eventId());

		assertEquals(0, norms.limitCommonAllocation(requester, 1));
		assertEquals(1, world.person("p2").socialHours());
		assertEquals(1, world.person("p3").socialHours());
		assertEquals(51, world.norm("camp", Norm.NEED_FIRST_COMMON_STORE).orElseThrow().stability());
		world.advanceDay();
		assertEquals(1, norms.limitCommonAllocation(requester, 1));
		world.assertConservation();
	}

	@Test
	void insufficientParticipationRejectsInsteadOfAdopting() {
		long origin = refusal();
		NormProposal proposal = proposal(origin, 2);
		proposal.learn("p1", NormProposal.Position.SUPPORT);
		NormSystem norms = new NormSystem(world, SimulationConfig.defaults().withPublicStorage(false));

		runSocialDays(norms, 5);

		assertEquals(NormProposal.Status.REJECTED, proposal.status());
		SimulationEvent rejection = lastEvent(EventType.NORM_REJECTED);
		assertEquals("insufficient-participation", rejection.context().get("reason"));
		assertTrue(world.norm("camp", Norm.NEED_FIRST_COMMON_STORE).isEmpty());
	}

	@Test
	void supportWithoutSupervisorsCannotBecomeAnEnforcedRule() {
		long origin = refusal();
		NormProposal proposal = proposal(origin, 2);
		for (Person person : world.people()) {
			person.observeNorm("neighbor-requests");
			person.setTrust("p1", 100);
			person.addSocialHours(6);
			proposal.learn(person.id(), NormProposal.Position.SUPPORT);
		}
		NormSystem norms = new NormSystem(world, SimulationConfig.defaults().withPublicStorage(false));

		runSocialDays(norms, 5);

		assertEquals(NormProposal.Status.REJECTED, proposal.status());
		assertEquals("insufficient-supervision", lastEvent(EventType.NORM_REJECTED).context().get("reason"));
	}

	@Test
	void residentsTakeDifferentPositionsFromTheirMaterialConditions() {
		world.createInitialBatch(ResourceType.TUBERS, 100, StorageOwner.household("h1"));
		NormProposal proposal = proposal(refusal(), 3);
		for (Person person : world.people()) {
			person.observeNorm("neighbor-requests");
			person.setTrust("p1", 100);
			proposal.learn(person.id(), NormProposal.Position.UNDECIDED);
		}
		NormSystem norms = new NormSystem(world, SimulationConfig.defaults().withPublicStorage(false));

		norms.processSocialPhase();

		assertEquals(NormProposal.Position.OPPOSE, proposal.positions().get("p1"));
		assertEquals(NormProposal.Position.SUPPORT, proposal.positions().get("p2"));
		assertNotEquals(proposal.positions().get("p1"), proposal.positions().get("p2"));
	}

	@Test
	void completeScenarioProducesTraceableNormHistoryAndRealLaborCosts() {
		PrimitiveSocietySimulation simulation = RiverValleyScenario.create(7);

		simulation.runDays(250);

		List<SimulationEvent> events = simulation.world().ledger().events();
		SimulationEvent firstProposal = events.stream().filter(event -> event.type() == EventType.NORM_PROPOSED)
			.findFirst().orElseThrow();
		assertEquals(1, firstProposal.actors().size());
		assertTrue(events.stream().anyMatch(event -> event.type() == EventType.NORM_ADOPTED));
		assertTrue(events.stream().anyMatch(event -> event.type() == EventType.NORM_REVISED));
		assertTrue(events.stream().anyMatch(event -> event.type() == EventType.NORM_REJECTED));
		assertTrue(events.stream().filter(event -> event.type() == EventType.NORM_ENFORCED)
			.allMatch(event -> Integer.parseInt(event.context().get("allocatedUnits"))
				<= Integer.parseInt(event.context().get("cap"))));
		assertTrue(events.stream().filter(event -> event.type() == EventType.NORM_ADOPTED
				|| event.type() == EventType.NORM_REVISED)
			.allMatch(event -> simulation.world().ledger().traceAncestry(event.eventId()).stream()
				.anyMatch(cause -> cause.type() == EventType.SHARE_REFUSED || cause.type() == EventType.SHORTAGE_CONFLICT)));
		assertTrue(events.stream().anyMatch(event -> event.type() == EventType.HARVEST
			&& Integer.parseInt(event.context().get("effortHours")) < SimulationConfig.defaults().effortHours()));
		assertTrue(simulation.world().normsForCamp(RiverValleyScenario.CAMP_ID).stream()
			.anyMatch(norm -> norm.revisionHistory().size() >= 2));
		simulation.world().assertConservation();
	}

	private long refusal() {
		long request = world.recordShareRequest("p2", "p1", 40);
		return world.recordShareRefusal(request, "p2", "p1", 20);
	}

	private NormProposal proposal(long origin, int cap) {
		NormProposal proposal = new NormProposal(
			"proposal-test", "camp", Norm.NEED_FIRST_COMMON_STORE, "p1", world.day(), origin, cap, 0
		);
		world.registerProposal(proposal);
		return proposal;
	}

	private void runSocialDays(NormSystem norms, int days) {
		for (int day = 0; day < days; day++) {
			norms.processSocialPhase();
			if (day + 1 < days) {
				world.advanceDay();
			}
		}
	}

	private SimulationEvent lastEvent(EventType type) {
		return world.ledger().events().stream().filter(event -> event.type() == type).reduce((first, second) -> second)
			.orElseThrow();
	}
}
