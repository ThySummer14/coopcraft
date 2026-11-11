package com.thysummer.coopcraft.civilization.simulation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.thysummer.coopcraft.civilization.simulation.NormSystem.ProposalFactorKind.*;

class NormGenerosityOpinionTest {
	@Test void defaultIsNeutralAndMutualAidMustAlreadyBeKnown() {
		Fixture f = fixture();
		NormProposal p = proposal(f, Norm.NEED_FIRST_COMMON_STORE, 4);
		var neutral = opinion(f, p);
		assertEquals(0, factor(neutral, GENEROSITY));
		f.norms.setOpinionGenerosityProvider(person -> false);
		assertEquals(neutral, opinion(f, p));
		f.norms.setOpinionGenerosityProvider(person -> true);
		assertEquals(3, factor(opinion(f, p), GENEROSITY));
		Fixture unknown = fixture(false);
		unknown.norms.setOpinionGenerosityProvider(person -> true);
		assertEquals(0, factor(opinion(unknown, proposal(unknown, Norm.NEED_FIRST_COMMON_STORE, 4)), GENEROSITY));
		assertFalse(unknown.target.knowsNorm("neighbor-requests"));
	}

	@Test void needUsesDefaultActiveAndDormantAllowanceDirection() {
		for (int proposed : List.of(2, 3, 4)) {
			Fixture f = fixture();
			f.norms.setOpinionGenerosityProvider(person -> true);
			assertEquals(3 * Integer.compare(proposed, 3), factor(opinion(f,
				proposal(f, Norm.NEED_FIRST_COMMON_STORE, proposed)), GENEROSITY));
			for (Norm.Status status : Norm.Status.values()) {
				for (int existing : List.of(2, 3, 4)) {
					f.world.registerNorm(norm(Norm.NEED_FIRST_COMMON_STORE, existing, status));
					assertEquals(3 * Integer.compare(proposed, status == Norm.Status.ACTIVE ? existing : 3),
						factor(opinion(f, f.world.proposal("p").orElseThrow()), GENEROSITY));
				}
			}
		}
	}

	@Test void careRequiresHungryDependentAndUsesZeroWhenAbsentOrDormant() {
		Fixture f = fixture();
		f.norms.setOpinionGenerosityProvider(person -> true);
		NormProposal p = proposal(f, Norm.CARE_FIRST_COMMON_STORE, 1);
		assertEquals(0, factor(opinion(f, p), GENEROSITY), "Hungry adult is unaffected by care allowance");
		f.source.initializeAge(Person.AgeBand.ELDER, 0);
		assertEquals(3, factor(opinion(f, p), GENEROSITY));
		for (Norm.Status status : Norm.Status.values()) {
			for (int cap : List.of(1, 2, 4)) {
				f.world.registerNorm(norm(Norm.CARE_FIRST_COMMON_STORE, cap, status));
				assertEquals(3 * Integer.compare(1, status == Norm.Status.ACTIVE ? cap : 0),
					factor(opinion(f, p), GENEROSITY));
			}
		}
		f.source.setHunger(SimulationConfig.defaults().foodAccessThreshold());
		assertEquals(0, factor(opinion(f, p), GENEROSITY), "Threshold is strict");
	}

	@Test void excludesSelfOwnHouseholdDeadForeignAndUnregisteredBeneficiaries() {
		for (String excluded : List.of("self", "own-household", "dead", "foreign", "unregistered", "not-hungry")) {
			Fixture f = fixture();
			f.norms.setOpinionGenerosityProvider(person -> true);
			f.source.setHunger(0);
			Person candidate = new Person("candidate", "camp", "source-home", Person.AgeBand.CHILD, 100, 50);
			switch (excluded) {
				case "self" -> f.target.setHunger(100);
				case "own-household" -> f.world.registerPerson(new Person("child", "camp", "target-home", Person.AgeBand.CHILD, 100, 50));
				case "dead" -> { candidate.markDead(0); f.world.registerPerson(candidate); }
				case "foreign" -> {
					f.world.registerCamp(new Camp("foreign", "Foreign"));
					f.world.registerHousehold(new Household("foreign-home", "foreign"));
					f.world.registerPerson(new Person("foreign-child", "foreign", "foreign-home", Person.AgeBand.CHILD, 100, 50));
				}
				case "unregistered" -> { /* Constructed candidate is deliberately outside world.people(). */ }
				case "not-hungry" -> f.source.setHunger(SimulationConfig.defaults().foodAccessThreshold());
				default -> throw new AssertionError(excluded);
			}
			assertEquals(0, factor(opinion(f, proposal(f, Norm.NEED_FIRST_COMMON_STORE, 4)), GENEROSITY), excluded);
		}
	}

	@Test void severalBeneficiariesDoNotStackAndUnchangedCapHasNoPersonalityEffect() {
		Fixture f = fixture();
		f.norms.setOpinionGenerosityProvider(person -> true);
		NormProposal p = proposal(f, Norm.NEED_FIRST_COMMON_STORE, 4);
		for (int i = 0; i < 20; i++) {
			f.world.registerPerson(new Person("other-" + i, "camp", "source-home", Person.AgeBand.CHILD, 100, 50));
			assertEquals(3, factor(opinion(f, p), GENEROSITY));
		}
		f.world.registerNorm(norm(Norm.NEED_FIRST_COMMON_STORE, 4, Norm.Status.ACTIVE));
		f.target.setHunger(100);
		f.norms.setOpinionSelfishnessProvider(person -> { throw new AssertionError("No change requires no lookup"); });
		f.norms.setOpinionGenerosityProvider(person -> { throw new AssertionError("No change requires no lookup"); });
		assertEquals(0, factor(opinion(f, p), SELFISHNESS));
		assertEquals(0, factor(opinion(f, p), GENEROSITY));
	}

	@Test void sharedAbsoluteBudgetScalesBothIncludingOppositeSigns() {
		for (int cap : List.of(2, 4)) {
			for (int selfishness : List.of(Integer.MIN_VALUE, 0, 25, 50, 75, 100, Integer.MAX_VALUE)) {
				Fixture f = fixture();
				f.target.setHunger(100);
				f.norms.setOpinionSelfishnessProvider(person -> selfishness);
				NormProposal p = proposal(f, Norm.NEED_FIRST_COMMON_STORE, cap);
				var neutral = opinion(f, p);
				f.norms.setOpinionGenerosityProvider(person -> true);
				var result = opinion(f, p);
				double rawSelfish = (Math.max(0, Math.min(100, selfishness)) - 50) / 250.0 * 25 * Integer.compare(cap, 3);
				double rawGenerous = 3 * Integer.compare(cap, 3);
				double scale = Math.min(1, 5 / (Math.abs(rawSelfish) + Math.abs(rawGenerous)));
				assertEquals(rawSelfish, factor(neutral, SELFISHNESS), 1e-9);
				assertEquals(rawSelfish * scale, factor(result, SELFISHNESS), 1e-9);
				assertEquals(rawGenerous * scale, factor(result, GENEROSITY), 1e-9);
				assertTrue(Math.abs(factor(result, SELFISHNESS)) + Math.abs(factor(result, GENEROSITY)) <= 5 + 1e-9);
				assertEquals(neutral.score() - rawSelfish + (rawSelfish + rawGenerous) * scale, result.score(), 1e-9);
				assertEquals(neutral.factors().stream().filter(factor -> factor.kind() != SELFISHNESS).toList(),
					result.factors().stream().filter(factor -> factor.kind() != SELFISHNESS && factor.kind() != GENEROSITY).toList());
			}
		}
	}

	@Test void readOnlyInquiryDoesNotLearnSpendOrRewriteAnything() {
		Fixture f = fixture();
		Norm original = norm(Norm.NEED_FIRST_COMMON_STORE, 2, Norm.Status.DORMANT);
		f.world.registerNorm(original);
		f.norms.setOpinionGenerosityProvider(person -> true);
		NormProposal p = proposal(f, Norm.NEED_FIRST_COMMON_STORE, 4);
		var positions = Map.copyOf(p.positions());
		var events = List.copyOf(f.world.ledger().events());
		var inventory = f.world.inventoryTotals();
		var history = List.copyOf(original.revisionHistory());
		var information = List.copyOf(f.target.information());
		int hunger = f.target.hunger();
		int grievance = f.target.grievance();
		for (int i = 0; i < 20; i++) {
			assertEquals(3, factor(opinion(f, p), GENEROSITY));
			assertEquals(positions, p.positions());
			assertEquals(events, f.world.ledger().events());
			assertEquals(inventory, f.world.inventoryTotals());
			assertEquals(history, original.revisionHistory());
			assertEquals(Norm.Status.DORMANT, original.status());
			assertEquals(information, f.target.information());
			assertEquals(hunger, f.target.hunger());
			assertEquals(grievance, f.target.grievance());
			assertEquals(0, f.target.socialHours());
			assertEquals(0, f.source.socialHours());
			assertEquals(0, p.rounds());
		}
		f.world.assertConservation();
	}

	@Test void actualMeetingsUseTheSameOpinionForEveryDirectionAndPersonality() {
		for (int cap : List.of(2, 3, 4)) {
			for (int selfishness : List.of(0, 50, 100)) {
				Fixture f = fixture();
				f.target.setHunger(100);
				f.norms.setOpinionSelfishnessProvider(person -> selfishness);
				f.norms.setOpinionGenerosityProvider(person -> true);
				NormProposal p = proposal(f, Norm.NEED_FIRST_COMMON_STORE, cap);
				var expected = opinion(f, p).position();
				assertTrue(f.norms.holdCommonStoreMeeting(f.world.people(), f.world.people(), "camp", "0,64,0").held());
				assertEquals(expected, p.positions().get(f.target.id()));
				assertEquals(1, p.rounds());
				assertEquals(1, f.target.socialHours());
			}
		}
	}

	@Test void legacyIgnoresBothProvidersAndProviderRejectsNull() {
		Fixture f = fixture();
		NormProposal p = proposal(f, Norm.NEED_FIRST_COMMON_STORE, 4);
		NormSystem legacy = new NormSystem(f.world, SimulationConfig.defaults());
		var expected = legacy.inspectProposal(f.target, p.proposalId()).orElseThrow();
		legacy.setOpinionSelfishnessProvider(person -> { throw new AssertionError("Legacy selfishness lookup"); });
		legacy.setOpinionGenerosityProvider(person -> { throw new AssertionError("Legacy generosity lookup"); });
		assertEquals(expected, legacy.inspectProposal(f.target, p.proposalId()).orElseThrow());
		assertThrows(NullPointerException.class, () -> f.norms.setOpinionGenerosityProvider(null));
	}

	private static double factor(NormSystem.ProposalOpinion opinion, NormSystem.ProposalFactorKind kind) {
		return opinion.factors().stream().filter(f -> f.kind() == kind).mapToDouble(NormSystem.ProposalFactor::contribution).sum();
	}
	private static NormSystem.ProposalOpinion opinion(Fixture f, NormProposal p) {
		var result = f.norms.inspectProposal(f.target, p.proposalId()).orElseThrow().opinion().orElseThrow();
		assertEquals(result.score(), 35 + result.factors().stream().mapToDouble(NormSystem.ProposalFactor::contribution).sum(), 1e-9);
		return result;
	}
	private static Norm norm(String id, int cap, Norm.Status status) {
		return new Norm(id, "camp", cap, Set.of(), Set.of(), Set.of(), 50, status,
			List.of(new Norm.Revision(0, "historic", 1, 3, cap, Norm.Revision.Action.REVISED)));
	}
	private static NormProposal proposal(Fixture f, String norm, int cap) {
		long origin = f.world.recordShareRequest("source", "camp", 40);
		NormProposal p = NormProposal.restore("p", "camp", norm, "source", 0, origin, cap, 0, 0,
			NormProposal.Status.OPEN, Map.of("source", NormProposal.Position.SUPPORT, "target", NormProposal.Position.SUPPORT));
		f.world.registerProposal(p);
		long event = f.world.recordSocialEvent(EventType.NORM_PROPOSED, List.of("source"), Map.of("proposalId", "p"), List.of(origin));
		f.source.learnInformation(new SocialInformation("info", SocialInformation.Kind.NORM_PROPOSAL, "source", "p", event, 0, null, 1));
		return p;
	}
	private static Fixture fixture() { return fixture(true); }
	private static Fixture fixture(boolean mutualAid) {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp("camp", "Camp"));
		world.registerHousehold(new Household("source-home", "camp"));
		world.registerHousehold(new Household("target-home", "camp"));
		Person source = new Person("source", "camp", "source-home", Person.AgeBand.ADULT, 100, 50);
		Person target = new Person("target", "camp", "target-home", Person.AgeBand.ADULT, 0, 50);
		world.registerPerson(source); world.registerPerson(target);
		if (mutualAid) target.observeNorm("neighbor-requests");
		return new Fixture(world, new NormSystem(world, SimulationConfig.defaults(), 3), source, target);
	}
	private record Fixture(SimulationWorld world, NormSystem norms, Person source, Person target) {}
}
