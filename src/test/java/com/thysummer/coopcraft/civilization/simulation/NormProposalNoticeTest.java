package com.thysummer.coopcraft.civilization.simulation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NormProposalNoticeTest {
	@Test
	void sharesExactlyOneKnownNoticeWithOriginalProvenanceAndCurrentOpinion() {
		Fixture f = fixture();
		SocialInformation first = notice(f, "first", 0.8);
		SocialInformation second = notice(f, "second", 1);
		f.source.learnCampRoute("unrelated-route");
		f.source.learnCampKnowledge(new CampKnowledge("remote", 90, 0, 0, "source", 1, first.sourceEventId()));
		f.world.registerCamp(new Camp("remote", "Remote camp"));
		f.world.registerHousehold(new Household("remote-home", "remote"));
		Person remote = new Person("remote-person", "remote", "remote-home", Person.AgeBand.ADULT, 0, 50);
		f.world.registerPerson(remote);
		Person bystander = new Person("bystander", "camp", "target-home", Person.AgeBand.ADULT, 0, 50);
		f.world.registerPerson(bystander);
		f.source.addSocialHours(5);
		f.target.addSocialHours(5);
		var plan = f.norms.prepareProposalNotice(f.source, f.target).orElseThrow();
		var duplicate = f.norms.prepareProposalNotice(f.source, f.target).orElseThrow();
		var before = state(f);
		assertTrue(f.norms.validProposalNotice(plan));
		assertEquals(before, state(f), "Preparing and validating are read-only");
		f.target.setHunger(100); // Initial opinion must be computed at commit, not frozen during preparation.
		int events = f.world.ledger().events().size();
		var inventory = f.world.inventoryTotals();
		var result = f.norms.shareProposalNotice(plan, Map.of("conversation", "onsite", "proposalId", "forged",
			"receivedConfidence", "1", "hoursPerParticipant", "0", "costTiming", "free"));

		assertTrue(result.shared());
		assertEquals(first.informationId(), result.informationId());
		assertEquals(f.proposal.proposalId(), result.proposalId());
		assertEquals(first.sourceEventId(), result.sourceEventId());
		assertEquals(first.propagate(0, "source"), f.target.information(first.informationId()).orElseThrow());
		assertFalse(f.target.knowsInformation(second.informationId()));
		assertEquals(List.of(first.propagate(0, "source")), f.target.information());
		assertEquals(Set.of("source", "target"), f.proposal.knownBy());
		assertEquals(NormProposal.Position.SUPPORT, f.proposal.positions().get("target"));
		assertEquals(NormProposal.Position.OPPOSE, f.proposal.positions().get("source"));
		assertEquals(0, f.proposal.rounds());
		assertEquals(NormProposal.Status.OPEN, f.proposal.status());
		assertEquals(6, f.source.socialHours());
		assertEquals(6, f.target.socialHours());
		assertEquals(events + 1, f.world.ledger().events().size());
		SimulationEvent shared = f.world.ledger().event(result.sharedEventId()).orElseThrow();
		assertEquals(EventType.INFORMATION_SHARED, shared.type());
		assertEquals(List.of("source", "target"), shared.actors());
		assertEquals(List.of(first.sourceEventId()), shared.causalLinks());
		assertEquals("agenda", shared.context().get("proposalId"));
		assertEquals("onsite", shared.context().get("conversation"));
		assertEquals(Double.toString(first.confidence()), shared.context().get("confidence"));
		assertEquals(Double.toString(first.propagate(0, "source").confidence()), shared.context().get("receivedConfidence"));
		assertEquals("1", shared.context().get("hoursPerParticipant"));
		assertEquals("next_settled_day", shared.context().get("costTiming"));
		assertTrue(shared.inputs().isEmpty());
		assertTrue(shared.outputs().isEmpty());
		assertEquals(inventory, f.world.inventoryTotals());
		assertTrue(f.target.knownCampRoutes().isEmpty());
		assertTrue(f.target.campKnowledge().isEmpty());
		assertTrue(f.target.observedNorms().isEmpty());
		assertEquals(0, f.target.trustIn("source"));
		assertTrue(remote.information().isEmpty());
		assertTrue(bystander.information().isEmpty());
		assertEquals(Long.MIN_VALUE, f.norms.lastMeetingDay("camp"));
		before = state(f);
		assertFalse(f.norms.validProposalNotice(plan));
		assertFalse(f.norms.shareProposalNotice(plan, Map.of()).shared());
		assertFalse(f.norms.shareProposalNotice(duplicate, Map.of()).shared());
		assertEquals(before, state(f));
		f.world.assertConservation();
	}

	@Test
	void rejectsForgedIncompleteFutureAndUnrelatedSourceProof() {
		for (String variant : List.of("kind", "subject", "object", "missing-source", "origin-as-source", "event-type",
			"event-actor", "extra-actor", "event-day", "proposalId", "normId", "cap", "revision", "missing-field",
			"missing-cause", "wrong-cause", "extra-cause", "future-learning", "confidence")) {
			Fixture f = fixture();
			Map<String, String> context = new LinkedHashMap<>(facts(f));
			if (List.of("proposalId", "normId", "cap", "revision").contains(variant)) context.put(variant, "wrong");
			if (variant.equals("missing-field")) context.remove("revision");
			long other = f.world.recordSocialEvent(EventType.SHORTAGE_CONFLICT, List.of("target"), Map.of(), List.of());
			List<Long> causes = switch (variant) {
				case "missing-cause" -> List.of();
				case "wrong-cause" -> List.of(other);
				case "extra-cause" -> List.of(f.proposal.originEventId(), other);
				default -> List.of(f.proposal.originEventId());
			};
			if (variant.equals("event-day")) f.world.advanceDay();
			long source = f.world.recordSocialEvent(variant.equals("event-type") ? EventType.NORM_ADOPTED : EventType.NORM_PROPOSED,
				variant.equals("event-actor") ? List.of("target") : variant.equals("extra-actor") ? List.of("source", "target") : List.of("source"),
				context, causes);
			if (variant.equals("missing-source")) source = 999999;
			if (variant.equals("origin-as-source")) source = f.proposal.originEventId();
			f.source.learnInformation(new SocialInformation("notice", variant.equals("kind") ? SocialInformation.Kind.NORM_REVISION : SocialInformation.Kind.NORM_PROPOSAL,
				variant.equals("subject") ? "target" : "source", variant.equals("object") ? "absent" : "agenda", source,
				variant.equals("future-learning") ? 1 : 0, null, variant.equals("confidence") ? 0.49999 : 1));
			var before = state(f);
			assertTrue(f.norms.prepareProposalNotice(f.source, f.target).isEmpty(), variant);
			assertEquals(before, state(f), variant);
		}
	}

	@Test
	void revalidatesOwnerDayParticipantsProposalAndSourceBeforeCommit() {
		for (String mutation : List.of("day", "dead-source", "dead-target", "child", "health", "camp", "trust",
			"source-hours", "target-hours", "concluded", "round", "known-proposal", "known-notice", "known-other-notice",
			"evicted", "replaced")) {
			Fixture f = fixture();
			SocialInformation information = notice(f, "notice", 0.5);
			var plan = f.norms.prepareProposalNotice(f.source, f.target).orElseThrow();
			switch (mutation) {
				case "day" -> f.world.advanceDay();
				case "dead-source" -> f.source.markDead(0);
				case "dead-target" -> f.target.markDead(0);
				case "child" -> f.target.initializeAge(Person.AgeBand.CHILD, 0);
				case "health" -> f.source.adjustHealth(-80);
				case "camp" -> {
					f.world.registerCamp(new Camp("elsewhere", "Elsewhere"));
					f.world.moveHousehold("target-home", "elsewhere");
				}
				case "trust" -> f.source.setTrust("target", 19);
				case "source-hours" -> f.source.addSocialHours(6);
				case "target-hours" -> f.target.addSocialHours(6);
				case "concluded" -> f.proposal.conclude(NormProposal.Status.REJECTED);
				case "round" -> f.proposal.advanceRound();
				case "known-proposal" -> f.proposal.learn("target", NormProposal.Position.UNDECIDED);
				case "known-notice" -> f.target.learnInformation(information);
				case "known-other-notice" -> f.target.learnInformation(new SocialInformation("other-notice", information.kind(),
					information.subjectId(), information.objectId(), information.sourceEventId(), 0, null, 1));
				case "evicted" -> {
					for (int i = 0; i < 32; i++) f.source.learnInformation(new SocialInformation("filler-" + i,
						SocialInformation.Kind.SHORTAGE, "source", "source-home", f.proposal.originEventId(), 0, null, 1));
				}
				case "replaced" -> f.source.learnInformation(new SocialInformation("notice", information.kind(),
					information.subjectId(), information.objectId(), information.sourceEventId(), 0, null, 1));
				default -> throw new AssertionError(mutation);
			}
			var before = state(f);
			assertFalse(f.norms.validProposalNotice(plan), mutation);
			assertFalse(f.norms.shareProposalNotice(plan, Map.of()).shared(), mutation);
			assertEquals(before, state(f), mutation);
			// A failed commit consumes its own stale plan, even if eligibility is restored.
			if (mutation.equals("trust")) {
				f.source.setTrust("target", 20);
				assertFalse(f.norms.validProposalNotice(plan));
			}
		}
		Fixture f = fixture();
		notice(f, "notice", 1);
		var plan = f.norms.prepareProposalNotice(f.source, f.target).orElseThrow();
		NormSystem foreign = new NormSystem(f.world, SimulationConfig.defaults());
		var before = state(f);
		assertFalse(foreign.validProposalNotice(plan));
		assertFalse(foreign.shareProposalNotice(plan, Map.of()).shared());
		assertEquals(before, state(f));
		assertTrue(f.norms.validProposalNotice(plan), "Another owner must not consume the real owner's plan");
		Person impostor = new Person("source", "camp", "source-home", Person.AgeBand.ADULT, 0, 50);
		assertTrue(f.norms.prepareProposalNotice(impostor, f.target).isEmpty());
		assertTrue(f.norms.prepareProposalNotice(f.source, impostor).isEmpty());
		assertTrue(f.norms.prepareProposalNotice(f.source, f.source).isEmpty());
		assertTrue(f.norms.prepareProposalNotice(null, f.target).isEmpty());
		assertFalse(f.norms.validProposalNotice(null));
		assertFalse(f.norms.shareProposalNotice(null, Map.of()).shared());
	}

	@Test
	void trustAndDecisionThresholdsApplyInEitherDirectionForAdultsAndElders() {
		for (Person.AgeBand age : Person.AgeBand.values()) {
			for (int trust : List.of(19, 20)) {
				for (int health : List.of(20, 21)) {
					Fixture f = fixture();
					notice(f, "notice", 0.5);
					f.source.setTrust("target", 0);
					f.target.setTrust("source", trust);
					f.target.initializeAge(age, 0);
					f.target.adjustHealth(health - 100);
					boolean valid = trust == 20 && health == 21 && (age == Person.AgeBand.ADULT || age == Person.AgeBand.ELDER);
					assertEquals(valid, f.norms.prepareProposalNotice(f.source, f.target).isPresent());
				}
			}
		}
	}

	@Test
	void bareSourceInformationAndClosedOrFutureProposalsCannotCreateKnowledge() {
		for (String variant : List.of("bare", "future", "closed", "round-limit", "missing-origin")) {
			Fixture f = fixture(false);
			NormProposal original = f.proposal;
			NormProposal altered = NormProposal.restore("agenda", "camp", original.normId(), "source",
				variant.equals("future") ? 1 : 0, variant.equals("missing-origin") ? 99999 : original.originEventId(),
				2, 0, variant.equals("round-limit") ? 5 : 0,
				variant.equals("closed") ? NormProposal.Status.ADOPTED : NormProposal.Status.OPEN,
				variant.equals("bare") ? Map.of() : Map.of("source", NormProposal.Position.OPPOSE));
			f.world.registerProposal(altered);
			notice(f, "notice", 1);
			assertTrue(f.norms.prepareProposalNotice(f.source, f.target).isEmpty(), variant);
		}
	}

	private static Fixture fixture() { return fixture(true); }

	private static Fixture fixture(boolean registerProposal) {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp("camp", "Camp"));
		world.registerHousehold(new Household("source-home", "camp"));
		world.registerHousehold(new Household("target-home", "camp"));
		Person source = new Person("source", "camp", "source-home", Person.AgeBand.ADULT, 0, 50);
		Person target = new Person("target", "camp", "target-home", Person.AgeBand.ADULT, 0, 50);
		source.setTrust("target", 20);
		world.registerPerson(source);
		world.registerPerson(target);
		long origin = world.recordSocialEvent(EventType.SHORTAGE_CONFLICT, List.of("source"), Map.of(), List.of());
		NormProposal proposal = NormProposal.restore("agenda", "camp", Norm.NEED_FIRST_COMMON_STORE, "source", 0,
			origin, 2, 0, 0, NormProposal.Status.OPEN, Map.of("source", NormProposal.Position.OPPOSE));
		if (registerProposal) world.registerProposal(proposal);
		return new Fixture(world, new NormSystem(world, SimulationConfig.defaults()), source, target, proposal);
	}

	private static Map<String, String> facts(Fixture f) {
		return Map.of("proposalId", "agenda", "normId", f.proposal.normId(), "cap", "2", "revision", "0");
	}

	private static SocialInformation notice(Fixture f, String id, double confidence) {
		long event = f.world.recordSocialEvent(EventType.NORM_PROPOSED, List.of("source"), facts(f), List.of(f.proposal.originEventId()));
		SocialInformation information = new SocialInformation(id, SocialInformation.Kind.NORM_PROPOSAL,
			"source", "agenda", event, 0, null, confidence);
		f.source.learnInformation(information);
		return information;
	}

	private static List<Object> state(Fixture f) {
		return List.of(f.world.ledger().events(), f.world.inventoryTotals(), f.world.allPeople().stream().map(person ->
			List.of(person.id(), person.campId(), person.isAlive(), person.ageBand(), person.health(), person.hunger(),
				person.information(), person.socialHours(), person.trustedPeople(), person.knownCampRoutes(), person.campKnowledge())).toList(),
			f.proposal.knownBy(), f.proposal.positions(), f.proposal.status(), f.proposal.rounds());
	}

	private record Fixture(SimulationWorld world, NormSystem norms, Person source, Person target, NormProposal proposal) {}
}
