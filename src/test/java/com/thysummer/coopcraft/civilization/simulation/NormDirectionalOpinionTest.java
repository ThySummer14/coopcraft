package com.thysummer.coopcraft.civilization.simulation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.thysummer.coopcraft.civilization.simulation.NormSystem.ProposalFactorKind.*;

class NormDirectionalOpinionTest {
	@Test void defaultThreeAndActiveOrDormantNormsUseEffectiveChange() {
		for (Norm.Status status : Norm.Status.values()) {
			for (int existing : List.of(2, 3, 4)) {
				for (int proposed : List.of(2, 3, 4)) {
					Fixture f = fixture();
					f.world.registerNorm(norm(Norm.NEED_FIRST_COMMON_STORE, existing, status));
					var opinion = opinion(f, proposal(f, Norm.NEED_FIRST_COMMON_STORE, proposed), f.target);
					int direction = Integer.compare(proposed, status == Norm.Status.ACTIVE ? existing : 3);
					assertEquals(15 * direction, factor(opinion, PERSONAL_HUNGER));
					assertEquals(10 * direction, factor(opinion, HOUSEHOLD_HUNGER));
					assertEquals(-10 * direction, factor(opinion, COMMUNITY_RESERVE));
				}
			}
		}
		for (int proposed : List.of(2, 3, 4)) {
			Fixture f = fixture();
			assertEquals(15 * Integer.compare(proposed, 3), factor(opinion(f, proposal(f, Norm.NEED_FIRST_COMMON_STORE, proposed), f.target), PERSONAL_HUNGER));
		}
	}

	@Test void careDistinguishesOwnDependentHouseholdAndUnrelatedAdults() {
		Fixture f = fixture();
		NormProposal care = proposal(f, Norm.CARE_FIRST_COMMON_STORE, 1);
		f.norms.setOpinionSelfishnessProvider(person -> 100);
		var outsider = opinion(f, care, f.target);
		assertEquals(0, factor(outsider, PERSONAL_HUNGER));
		assertEquals(0, factor(outsider, HOUSEHOLD_HUNGER));
		assertEquals(0, factor(outsider, SELFISHNESS));
		assertEquals(0, factor(outsider, COMMUNITY_RESERVE), "No eligible beneficiaries in camp");
		Person child = new Person("child", "camp", "target-home", Person.AgeBand.CHILD, 100, 50);
		f.world.registerPerson(child);
		var parent = opinion(f, care, f.target);
		assertEquals(0, factor(parent, PERSONAL_HUNGER));
		assertEquals(10, factor(parent, HOUSEHOLD_HUNGER));
		assertEquals(2, factor(parent, SELFISHNESS));
		assertEquals(0, factor(opinion(f, care, f.source), HOUSEHOLD_HUNGER));
		f.target.initializeAge(Person.AgeBand.ELDER, 0);
		assertTrue(f.target.needsCare());
		assertEquals(15, factor(opinion(f, care, f.target), PERSONAL_HUNGER));
		f.world.registerNorm(norm(Norm.CARE_FIRST_COMMON_STORE, 2, Norm.Status.ACTIVE));
		assertEquals(-15, factor(opinion(f, care, f.target), PERSONAL_HUNGER));
		f.world.registerNorm(norm(Norm.CARE_FIRST_COMMON_STORE, 1, Norm.Status.ACTIVE));
		assertEquals(0, factor(opinion(f, care, f.target), SELFISHNESS));
		f.world.registerNorm(norm(Norm.CARE_FIRST_COMMON_STORE, 2, Norm.Status.DORMANT));
		assertEquals(15, factor(opinion(f, care, f.target), PERSONAL_HUNGER));
		child.markDead(0);
		f.target.initializeAge(Person.AgeBand.ADULT, 0);
		assertEquals(0, factor(opinion(f, care, f.target), HOUSEHOLD_HUNGER));
	}

	@Test void selfishnessIsBoundedClampedAndNeverChangesBenefitDirection() {
		for (int cap : List.of(2, 3, 4)) {
			Fixture f = fixture();
			NormProposal p = proposal(f, Norm.NEED_FIRST_COMMON_STORE, cap);
			double neutral = opinion(f, p, f.target).score();
			for (int value : List.of(Integer.MIN_VALUE, 0, 25, 50, 75, 100, Integer.MAX_VALUE)) {
				f.norms.setOpinionSelfishnessProvider(person -> value);
				var result = opinion(f, p, f.target);
				double expected = (Math.max(0, Math.min(100, value)) - 50) / 250.0 * 25 * Integer.compare(cap, 3);
				assertEquals(expected, factor(result, SELFISHNESS), 1e-9);
				assertEquals(neutral + expected, result.score(), 1e-9);
				assertTrue(Math.abs(expected) <= 5);
			}
		}
		Fixture f = fixture();
		f.target.setHunger(0);
		f.norms.setOpinionSelfishnessProvider(person -> { throw new AssertionError("Unaffected residents need no personality lookup"); });
		assertEquals(0, factor(opinion(f, proposal(f, Norm.NEED_FIRST_COMMON_STORE, 4), f.target), SELFISHNESS));
	}

	@Test void reservesUseNutritionAndStrictThreeDayThresholdsInBothDirections() {
		// Configured 20 nutrition/day; fish supplies 20 nutrition/unit.
		for (int cap : List.of(2, 3, 4)) {
			Fixture f = fixture(new SimulationConfig(20, 6, 8, 0.5, 75, 20, 10, 40, true, true, 1));
			NormProposal p = proposal(f, Norm.NEED_FIRST_COMMON_STORE, cap);
			f.world.importExternalBatch(ResourceType.FISH, 3, StorageOwner.household("target-home"));
			f.world.importExternalBatch(ResourceType.FISH, 6, StorageOwner.camp("camp"));
			assertEquals(0, factor(opinion(f, p, f.target), HOUSEHOLD_SURPLUS));
			assertEquals(0, factor(opinion(f, p, f.target), COMMUNITY_RESERVE));
			f.world.importExternalBatch(ResourceType.FISH, 1, StorageOwner.household("target-home"));
			assertEquals(-20 * Integer.compare(cap, 3), factor(opinion(f, p, f.target), HOUSEHOLD_SURPLUS));
		}
	}

	@Test void queriesDoNotRewriteHistoryAndMeetingsUseExactlyTheCurrentOpinion() {
		Fixture f = fixture();
		Norm original = norm(Norm.NEED_FIRST_COMMON_STORE, 4, Norm.Status.DORMANT);
		f.world.registerNorm(original);
		NormProposal p = proposal(f, Norm.NEED_FIRST_COMMON_STORE, 2);
		f.norms.setOpinionSelfishnessProvider(person -> 100);
		var positions = Map.copyOf(p.positions());
		var events = List.copyOf(f.world.ledger().events());
		var inventory = f.world.inventoryTotals();
		var history = List.copyOf(original.revisionHistory());
		var information = f.target.information();
		for (int i = 0; i < 20; i++) {
			assertEquals(NormProposal.Position.OPPOSE, opinion(f, p, f.target).position());
			assertEquals(positions, p.positions());
			assertEquals(events, f.world.ledger().events());
			assertEquals(inventory, f.world.inventoryTotals());
			assertEquals(history, original.revisionHistory());
			assertEquals(information, f.target.information());
			assertEquals(0, f.target.socialHours());
			assertEquals(0, p.rounds());
		}
		var expected = opinion(f, p, f.target).position();
		assertTrue(f.norms.holdCommonStoreMeeting(f.world.people(), f.world.people(), "camp", "0,64,0").held());
		assertEquals(expected, p.positions().get(f.target.id()));
		assertEquals(history, original.revisionHistory());
		assertEquals(Norm.Status.DORMANT, original.status());
		f.world.assertConservation();
	}

	@Test void mutualAidAndGrievanceCannotMechanicallyEndorseCutsOrNoChange() {
		for (int cap : List.of(2, 3, 4)) {
			Fixture f = fixture();
			f.target.observeNorm("neighbor-requests");
			f.target.increaseGrievance(100);
			var result = opinion(f, proposal(f, Norm.NEED_FIRST_COMMON_STORE, cap), f.target);
			assertEquals(15 * Integer.compare(cap, 3), factor(result, NEIGHBOR_REQUESTS));
			assertEquals(15 * Integer.compare(cap, 3), factor(result, GRIEVANCE));
		}
		Fixture f = fixture();
		f.target.observeNorm("neighbor-requests");
		f.target.increaseGrievance(100);
		NormProposal care = proposal(f, Norm.CARE_FIRST_COMMON_STORE, 1);
		assertEquals(0, factor(opinion(f, care, f.target), GRIEVANCE));
		assertEquals(0, factor(opinion(f, care, f.target), NEIGHBOR_REQUESTS));
		f.world.registerPerson(new Person("outsider-child", "camp", "source-home", Person.AgeBand.CHILD, 100, 50));
		assertEquals(15, factor(opinion(f, care, f.target), NEIGHBOR_REQUESTS));
		assertEquals(0, factor(opinion(f, care, f.target), GRIEVANCE));
	}

	@Test void everyDirectionAndPersonalityUsesTheSameActualMeetingFormula() {
		for (int cap : List.of(2, 3, 4)) {
			for (int selfishness : List.of(0, 50, 100)) {
				Fixture f = fixture();
				NormProposal p = proposal(f, Norm.NEED_FIRST_COMMON_STORE, cap);
				f.norms.setOpinionSelfishnessProvider(person -> selfishness);
				var expected = opinion(f, p, f.target).position();
				assertTrue(f.norms.holdCommonStoreMeeting(f.world.people(), f.world.people(), "camp", "0,64,0").held());
				assertEquals(expected, p.positions().get("target"));
				assertEquals(1, p.rounds());
				assertEquals(1, f.target.socialHours());
			}
		}
	}

	@Test void legacyFormulaIgnoresPersonalityProvider() {
		Fixture f = fixture();
		NormProposal p = proposal(f, Norm.NEED_FIRST_COMMON_STORE, 2);
		NormSystem legacy = new NormSystem(f.world, SimulationConfig.defaults());
		legacy.setOpinionSelfishnessProvider(person -> { throw new AssertionError("Legacy formula must be unchanged"); });
		assertEquals(60, legacy.inspectProposal(f.target, p.proposalId()).orElseThrow().opinion().orElseThrow().score());
	}

	private static double factor(NormSystem.ProposalOpinion opinion, NormSystem.ProposalFactorKind kind) {
		return opinion.factors().stream().filter(f -> f.kind() == kind).mapToDouble(NormSystem.ProposalFactor::contribution).sum();
	}
	private static NormSystem.ProposalOpinion opinion(Fixture f, NormProposal p, Person person) {
		var result = f.norms.inspectProposal(person, p.proposalId()).orElseThrow().opinion().orElseThrow();
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
	private static Fixture fixture() { return fixture(SimulationConfig.defaults()); }
	private static Fixture fixture(SimulationConfig config) {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp("camp", "Camp"));
		world.registerHousehold(new Household("source-home", "camp"));
		world.registerHousehold(new Household("target-home", "camp"));
		Person source = new Person("source", "camp", "source-home", Person.AgeBand.ADULT, 0, 50);
		Person target = new Person("target", "camp", "target-home", Person.AgeBand.ADULT, 100, 50);
		world.registerPerson(source); world.registerPerson(target);
		return new Fixture(world, new NormSystem(world, config, 3), source, target);
	}
	private record Fixture(SimulationWorld world, NormSystem norms, Person source, Person target) {}
}
