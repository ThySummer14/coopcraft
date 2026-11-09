package com.thysummer.coopcraft.civilization.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static com.thysummer.coopcraft.civilization.simulation.NormProposal.Position.*;
import static com.thysummer.coopcraft.civilization.simulation.NormSystem.ProposalFactorKind.*;
import static org.junit.jupiter.api.Assertions.*;

class NormProposalInquiryTest {
	@Test
	void explicitProposalKnowledgeNeverLeaksFromAnotherProposalOrBareInformation() {
		Fixture f = fixture();
		NormProposal selected = proposal(f, "selected", 2, Map.of("source", SUPPORT));
		NormProposal other = proposal(f, "other", 3, Map.of("target", OPPOSE));
		f.target.learnInformation(information(f, selected, "bare-information", 1));
		var before = state(f);

		for (int repeat = 0; repeat < 20; repeat++) {
			var unknown = f.norms.inspectProposal(f.target, selected.proposalId()).orElseThrow();
			assertEquals(selected.proposalId(), unknown.proposalId());
			assertEquals(selected.normId(), unknown.normId());
			assertEquals(2, unknown.allocationUnitCap());
			assertEquals(0, unknown.proposedDay());
			assertFalse(unknown.known());
			assertFalse(unknown.hasReliableInformation());
			assertFalse(unknown.canIntroduce());
			assertTrue(unknown.opinion().isEmpty(), "Unknown is not an invented undecided vote");
			assertTrue(f.norms.inspectProposal(f.target, other.proposalId()).orElseThrow().known());
			assertEquals(before, state(f));
		}
		assertEquals(Set.of("source"), selected.knownBy());
		assertEquals(Map.of("target", OPPOSE), other.positions());
	}

	@Test
	void invalidIdentityAndProposalStatesHaveNoSnapshotAndNoSideEffects() {
		Fixture f = fixture();
		NormProposal valid = proposal(f, "valid", 2, Map.of("source", SUPPORT, "target", SUPPORT));
		f.world.registerCamp(new Camp("elsewhere", "Other camp"));
		f.world.registerHousehold(new Household("elsewhere-home", "elsewhere"));
		Person foreign = new Person("foreign", "elsewhere", "elsewhere-home", Person.AgeBand.ADULT, 0, 50);
		f.world.registerPerson(foreign);
		Person impostor = new Person(f.target.id(), f.target.campId(), f.target.householdId(), Person.AgeBand.ADULT, 0, 50);
		Person unregistered = new Person("unregistered", "camp", "target-home", Person.AgeBand.ADULT, 0, 50);
		for (String mutation : List.of("future", "origin-missing", "too-many-rounds", "foreign", "unknown-norm",
			"adopted", "rejected", "expired")) {
			NormProposal.Status status = switch (mutation) {
				case "adopted" -> NormProposal.Status.ADOPTED;
				case "rejected" -> NormProposal.Status.REJECTED;
				case "expired" -> NormProposal.Status.EXPIRED;
				default -> NormProposal.Status.OPEN;
			};
			f.world.registerProposal(NormProposal.restore(mutation, mutation.equals("foreign") ? "elsewhere" : "camp",
				mutation.equals("unknown-norm") ? "unrecognized-rule" : Norm.NEED_FIRST_COMMON_STORE, "source",
				mutation.equals("future") ? 1 : 0, mutation.equals("origin-missing") ? 999999 : valid.originEventId(),
				2, 0, mutation.equals("too-many-rounds") ? 5 : 0, status, Map.of("target", SUPPORT)));
			var before = state(f);
			assertTrue(f.norms.inspectProposal(f.target, mutation).isEmpty(), mutation);
			assertEquals(before, state(f), mutation);
		}
		var before = state(f);
		for (Person invalid : List.of(foreign, impostor, unregistered)) {
			assertTrue(f.norms.inspectProposal(invalid, valid.proposalId()).isEmpty());
		}
		assertTrue(f.norms.inspectProposal(null, valid.proposalId()).isEmpty());
		for (String invalid : List.of("", " ", "missing")) assertTrue(f.norms.inspectProposal(f.target, invalid).isEmpty());
		assertTrue(f.norms.inspectProposal(f.target, null).isEmpty());
		assertEquals(before, state(f));
		f.target.markDead(0);
		before = state(f);
		assertTrue(f.norms.inspectProposal(f.target, valid.proposalId()).isEmpty());
		assertEquals(before, state(f));
	}

	@Test
	void onlyMatchingReliableProposalEventsPermitAnIntroductionAndTheActualMeetingAgrees() {
		for (String variant : List.of("valid", "threshold", "below-threshold", "wrong-kind", "wrong-object",
			"missing-event", "origin-event", "wrong-event-type", "wrong-event-proposal", "wrong-event-actor",
			"extra-event-actor", "wrong-event-day")) {
			Fixture f = fixture();
			NormProposal proposal = proposal(f, "agenda", 2, Map.of("source", SUPPORT));
			if (variant.equals("wrong-event-day")) f.world.advanceDay();
			long event = f.world.recordSocialEvent(variant.equals("wrong-event-type") ? EventType.NORM_ADOPTED : EventType.NORM_PROPOSED,
				variant.equals("wrong-event-actor") ? List.of("target")
					: variant.equals("extra-event-actor") ? List.of("source", "target") : List.of("source"),
				Map.of("proposalId", variant.equals("wrong-event-proposal") ? "different" : proposal.proposalId()),
				List.of(proposal.originEventId()));
			if (variant.equals("missing-event")) event = 999999;
			if (variant.equals("origin-event")) event = proposal.originEventId();
			double confidence = variant.equals("threshold") ? 0.5 : variant.equals("below-threshold") ? 0.499999 : 1;
			f.source.learnInformation(new SocialInformation("source-info",
				variant.equals("wrong-kind") ? SocialInformation.Kind.NORM_ADOPTION : SocialInformation.Kind.NORM_PROPOSAL,
				"source", variant.equals("wrong-object") ? "another-proposal" : proposal.proposalId(), event, f.world.day(), null, confidence));
			boolean reliable = variant.equals("valid") || variant.equals("threshold");
			var before = state(f);
			var inquiry = f.norms.inspectProposal(f.source, proposal.proposalId()).orElseThrow();
			assertTrue(inquiry.known(), variant);
			assertEquals(reliable, inquiry.hasReliableInformation(), variant);
			assertEquals(reliable, inquiry.canIntroduce(), variant);
			assertTrue(inquiry.opinion().isPresent(), "Source validity must not erase a known resident's independent opinion");
			assertEquals(before, state(f), variant);

			var result = hold(f);
			if (reliable) {
				assertEquals("held", result.status(), variant);
				assertTrue(proposal.knownBy().contains("target"), variant);
				assertEquals(1, f.source.socialHours());
				assertEquals(1, f.target.socialHours());
				assertEquals(confidence * 0.85, f.target.information("source-info").orElseThrow().confidence(), 0.000001);
			} else {
				assertEquals("insufficient_participants", result.status(), variant);
				assertEquals(before, state(f), variant);
			}
			f.world.assertConservation();
		}
	}

	@Test
	void evictedSourceInformationDoesNotEraseKnowledgeOrCurrentOpinion() {
		Fixture f = fixture();
		NormProposal proposal = proposal(f, "agenda", 2, Map.of("source", SUPPORT));
		f.source.learnInformation(information(f, proposal, "original", 1));
		assertTrue(f.norms.inspectProposal(f.source, proposal.proposalId()).orElseThrow().canIntroduce());
		for (int i = 0; i < 32; i++) {
			f.source.learnInformation(new SocialInformation("newer-" + i, SocialInformation.Kind.SHORTAGE,
				"source", "source-home", proposal.originEventId(), 0, null, 1));
		}
		assertFalse(f.source.knowsInformation("original"));
		var before = state(f);
		var inquiry = f.norms.inspectProposal(f.source, proposal.proposalId()).orElseThrow();
		assertTrue(inquiry.known());
		assertTrue(inquiry.opinion().isPresent());
		assertFalse(inquiry.hasReliableInformation());
		assertFalse(inquiry.canIntroduce());
		assertEquals("insufficient_participants", hold(f).status());
		assertEquals(before, state(f));
	}

	@Test
	void decisionAgeHealthAndRemainingBudgetGateIntroductionWithoutErasingKnowledge() {
		for (Person.AgeBand age : Person.AgeBand.values()) {
			for (int health : List.of(20, 21, 100)) {
				for (int hours : List.of(0, 5, 6)) {
					Fixture f = fixture();
					NormProposal proposal = proposal(f, "agenda", 2, Map.of("source", SUPPORT));
					f.source.learnInformation(information(f, proposal, "source-info", 1));
					f.source.initializeAge(age, 0);
					f.source.adjustHealth(health - 100);
					f.source.restoreSocialState(7, hours, 4);
					boolean capable = (age == Person.AgeBand.ADULT || age == Person.AgeBand.ELDER) && health > 20;
					var before = state(f);
					var inquiry = f.norms.inspectProposal(f.source, proposal.proposalId()).orElseThrow();
					String context = age + "/health=" + health + "/hours=" + hours;
					assertTrue(inquiry.known(), context);
					assertTrue(inquiry.hasReliableInformation(), context);
					assertEquals(capable, inquiry.decisionCapable(), context);
					assertEquals(capable, inquiry.opinion().isPresent(), context);
					assertEquals(6 - hours, inquiry.remainingSocialHours(), context);
					assertEquals(capable && hours < 6, inquiry.canIntroduce(), context);
					assertEquals(before, state(f), context);
				}
			}
		}
	}

	@Test
	void exactLegacyScoreBoundariesAndFactorCombinationsMatchActualMeetingPositions() {
		// Expected scores are fixed regression values, not a second implementation of the formula.
		for (Scenario scenario : List.of(
			new Scenario("baseline", false, 0, false, 0, 0, 0, 2, 35, OPPOSE),
			new Scenario("below-40", false, 0, false, 0, 33, 0, 2, 39.95, OPPOSE),
			new Scenario("exactly-40", false, 0, false, 1, 20, 0, 2, 40, UNDECIDED),
			new Scenario("below-55", true, 0, false, 0, 33, 0, 2, 54.95, UNDECIDED),
			new Scenario("exactly-55", true, 0, false, 1, 20, 0, 2, 55, SUPPORT),
			new Scenario("hunger-threshold", false, 10, false, 0, 0, 0, 2, 35, OPPOSE),
			new Scenario("self-also-counts-in-household", false, 11, false, 0, 0, 0, 2, 60, SUPPORT),
			new Scenario("hungry-housemate-only", false, 0, true, 0, 0, 0, 2, 45, UNDECIDED),
			new Scenario("grievance-below-cap", false, 0, false, 7, 0, 0, 2, 49, UNDECIDED),
			new Scenario("grievance-cap", false, 0, false, 8, 0, 0, 2, 50, UNDECIDED),
			new Scenario("grievance-stays-capped", false, 0, false, 100, 0, 0, 2, 50, UNDECIDED),
			new Scenario("trust", false, 0, false, 0, 100, 0, 2, 50, UNDECIDED),
			new Scenario("household-surplus", false, 0, false, 0, 0, 6, 2, 15, OPPOSE),
			new Scenario("large-allocation-without-surplus", false, 0, false, 0, 0, 0, 3, 35, OPPOSE),
			new Scenario("large-allocation-and-surplus", false, 0, false, 0, 0, 6, 3, 5, OPPOSE),
			new Scenario("all-factors", true, 100, true, 100, 100, 11, 3, 75, SUPPORT))) {
			Fixture f = fixture();
			if (scenario.neighbors) f.target.observeNorm("neighbor-requests");
			f.target.setHunger(scenario.hunger);
			f.target.setTrust("source", scenario.trust);
			f.target.restoreSocialState(scenario.grievance, 0, 0);
			if (scenario.hungryHousemate) {
				f.world.registerPerson(new Person("dependent", "camp", "target-home", Person.AgeBand.CHILD, 11, 50));
			}
			if (scenario.fish > 0) f.world.importExternalBatch(ResourceType.FISH, scenario.fish, StorageOwner.household("target-home"));
			NormProposal proposal = proposal(f, "agenda", scenario.cap, Map.of("source", SUPPORT, "target", UNDECIDED));
			var before = state(f);
			var inquiry = f.norms.inspectProposal(f.target, proposal.proposalId()).orElseThrow();
			var opinion = inquiry.opinion().orElseThrow();
			assertEquals(scenario.score, opinion.score(), 0.000001, scenario.name);
			assertEquals(scenario.position, opinion.position(), scenario.name);
			assertEquals(opinion.score(), 35 + opinion.factors().stream().mapToDouble(NormSystem.ProposalFactor::contribution).sum(), 0.000001);
			assertEquals(before, state(f), scenario.name);
			if (scenario.name.equals("all-factors")) {
				assertEquals(List.of(factor(NEIGHBOR_REQUESTS, 15), factor(PERSONAL_HUNGER, 15), factor(HOUSEHOLD_HUNGER, 10),
					factor(GRIEVANCE, 15), factor(PROPOSER_TRUST, 15), factor(HOUSEHOLD_SURPLUS, -20), factor(LARGE_ALLOCATION, -10)), opinion.factors());
			}
			assertEquals("held", hold(f).status(), scenario.name);
			assertEquals(scenario.position, proposal.positions().get("target"), scenario.name);
			assertEquals(1, f.target.socialHours(), scenario.name);
			assertEquals(1, f.source.socialHours(), scenario.name);
			f.world.assertConservation();
		}
	}

	@Test
	void householdSurplusUsesStrictThreeDayNutritionReserveAndConfiguredHungerThreshold() {
		SimulationConfig config = new SimulationConfig(20, 6, 8, 0.5, 75, 20, 30, 40, true, true, 1);
		for (int fish : List.of(3, 4)) {
			Fixture f = fixture(config);
			f.target.setHunger(30);
			f.world.importExternalBatch(ResourceType.FISH, fish, StorageOwner.household("target-home"));
			NormProposal proposal = proposal(f, "agenda", 3, Map.of("source", SUPPORT, "target", SUPPORT));
			var opinion = f.norms.inspectProposal(f.target, proposal.proposalId()).orElseThrow().opinion().orElseThrow();
			assertEquals(fish == 3 ? 35 : 5, opinion.score());
			assertEquals(fish == 4, opinion.factors().stream().anyMatch(factor -> factor.kind() == HOUSEHOLD_SURPLUS));
			f.target.setHunger(31);
			assertEquals(fish == 3 ? 60 : 30, f.norms.inspectProposal(f.target, proposal.proposalId()).orElseThrow().opinion().orElseThrow().score());
		}
	}

	@Test
	void careProposalsUseTheSameIndependentFormulaAndSnapshotsNeverAliasMutableFactorsOrPeople() {
		Fixture f = fixture();
		NormProposal need = proposal(f, "need", 1, Map.of("source", SUPPORT, "target", SUPPORT));
		NormProposal care = NormProposal.restore("care", "camp", Norm.CARE_FIRST_COMMON_STORE, "source", 0,
			need.originEventId(), 1, 0, 0, NormProposal.Status.OPEN, Map.of("source", SUPPORT, "target", SUPPORT));
		f.world.registerProposal(care);
		f.target.setHunger(100);
		var snapshot = f.norms.inspectProposal(f.target, care.proposalId()).orElseThrow();
		var opinion = snapshot.opinion().orElseThrow();
		assertEquals(f.norms.inspectProposal(f.target, need.proposalId()).orElseThrow().opinion(), snapshot.opinion());
		assertEquals(60, opinion.score());
		assertEquals(SUPPORT, opinion.position());
		assertThrows(UnsupportedOperationException.class, () -> opinion.factors().add(factor(GRIEVANCE, 1)));
		var mutable = new ArrayList<>(opinion.factors());
		var copied = new NormSystem.ProposalOpinion(opinion.position(), opinion.score(), mutable);
		mutable.clear();
		assertEquals(opinion.factors(), copied.factors());
		f.target.setHunger(0);
		f.target.restoreSocialState(0, 6, 0);
		assertEquals(60, opinion.score());
		assertEquals(6, snapshot.remainingSocialHours());
		assertEquals(35, f.norms.inspectProposal(f.target, care.proposalId()).orElseThrow().opinion().orElseThrow().score());
		assertEquals(SUPPORT, care.positions().get("target"), "A read must not overwrite the stored position");
	}

	private static Fixture fixture() { return fixture(SimulationConfig.defaults()); }

	private static Fixture fixture(SimulationConfig config) {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp("camp", "Inquiry camp"));
		world.registerHousehold(new Household("source-home", "camp"));
		world.registerHousehold(new Household("target-home", "camp"));
		Person source = new Person("source", "camp", "source-home", Person.AgeBand.ADULT, 0, 50);
		Person target = new Person("target", "camp", "target-home", Person.AgeBand.ADULT, 0, 50);
		world.registerPerson(source);
		world.registerPerson(target);
		return new Fixture(world, new NormSystem(world, config), source, target);
	}

	private static NormProposal proposal(Fixture f, String id, int cap, Map<String, NormProposal.Position> positions) {
		long request = f.world.recordShareRequest("source", "camp", 40);
		long origin = f.world.recordShareRefusal(request, "source", "camp", 20);
		NormProposal proposal = NormProposal.restore(id, "camp", Norm.NEED_FIRST_COMMON_STORE, "source", f.world.day(),
			origin, cap, 0, 0, NormProposal.Status.OPEN, positions);
		f.world.registerProposal(proposal);
		return proposal;
	}

	private static SocialInformation information(Fixture f, NormProposal proposal, String id, double confidence) {
		long event = f.world.recordSocialEvent(EventType.NORM_PROPOSED, List.of("source"),
			Map.of("proposalId", proposal.proposalId()), List.of(proposal.originEventId()));
		return new SocialInformation(id, SocialInformation.Kind.NORM_PROPOSAL, "source", proposal.proposalId(), event, f.world.day(), null, confidence);
	}

	private static NormSystem.MeetingResult hold(Fixture f) {
		return f.norms.holdCommonStoreMeeting(f.world.people(), List.of(f.source, f.target), "camp", "0, 64, 0");
	}

	private static NormSystem.ProposalFactor factor(NormSystem.ProposalFactorKind kind, double contribution) {
		return new NormSystem.ProposalFactor(kind, contribution);
	}

	private static List<Object> state(Fixture f) {
		return List.of(f.world.day(), f.world.nextBatchId(), f.world.inventoryTotals(), f.world.ledger().events(),
			f.norms.lastMeetingDay("camp"), f.world.allPeople().stream().map(person -> List.of(person.id(), person.campId(),
				person.ageBand(), person.isAlive(), person.health(), person.hunger(), person.grievance(), person.socialHours(),
				person.previousSocialHours(), person.information(), Set.copyOf(person.observedNorms()), person.trustedPeople())).toList(),
			f.world.proposalsForCamp("camp").stream().map(proposal -> List.of(proposal.proposalId(), proposal.knownBy(),
				proposal.positions(), proposal.rounds(), proposal.status())).toList());
	}

	private record Scenario(String name, boolean neighbors, int hunger, boolean hungryHousemate, int grievance,
		int trust, int fish, int cap, double score, NormProposal.Position position) {}

	private record Fixture(SimulationWorld world, NormSystem norms, Person source, Person target) {}
}
