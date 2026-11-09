package com.thysummer.coopcraft.civilization.simulation;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NormMeetingKnowledgeTest {
	@Test
	void oldAdoptionOrRevisionNewsCannotEndorseTheCurrentRevision() {
		for (SocialInformation.Kind kind : List.of(SocialInformation.Kind.NORM_ADOPTION, SocialInformation.Kind.NORM_REVISION)) {
			Fixture f = fixture();
			long oldEvent = f.world.recordSocialEvent(kind == SocialInformation.Kind.NORM_ADOPTION
				? EventType.NORM_ADOPTED : EventType.NORM_REVISED, List.of("source"),
				Map.of("normId", Norm.NEED_FIRST_COMMON_STORE), List.of());
			long currentEvent = f.world.recordSocialEvent(EventType.NORM_REVISED, List.of("source"),
				Map.of("normId", Norm.NEED_FIRST_COMMON_STORE), List.of(oldEvent));
			Norm current = registerCurrentNorm(f, currentEvent);
			f.source.learnInformation(new SocialInformation("old-news", kind, "source", current.normId(),
				oldEvent, 0, null, 1.0));

			f.norms.propagateInformation();

			assertTrue(f.recipient.knowsInformation("old-news"), "Old news may remain as historical information");
			assertEquals(Set.of("source"), current.knownBy(), "Old information must not claim knowledge of a newer revision");
			assertEquals(Set.of("source"), current.supporters());
			assertFalse(current.opponents().contains("recipient"));
			assertFalse(f.recipient.knowsNorm(current.normId()));
		}
	}

	@Test
	void newsFromTheExactCurrentRevisionCanConveyCurrentKnowledge() {
		Fixture f = fixture();
		long currentEvent = f.world.recordSocialEvent(EventType.NORM_REVISED, List.of("source"),
			Map.of("normId", Norm.NEED_FIRST_COMMON_STORE), List.of());
		Norm current = registerCurrentNorm(f, currentEvent);
		f.source.learnInformation(new SocialInformation("current-news", SocialInformation.Kind.NORM_REVISION,
			"source", current.normId(), currentEvent, 0, null, 1.0));

		f.norms.propagateInformation();

		assertTrue(f.recipient.knowsInformation("current-news"));
		assertTrue(current.knownBy().contains("recipient"));
		assertTrue(current.supporters().contains("recipient"));
		assertTrue(f.recipient.knowsNorm(current.normId()));
	}

	private static Norm registerCurrentNorm(Fixture f, long event) {
		Norm norm = new Norm(Norm.NEED_FIRST_COMMON_STORE, "camp", 3, Set.of("source"), Set.of(), Set.of("source"),
			50, Norm.Status.ACTIVE, List.of(new Norm.Revision(0, "new-proposal", event, 2, 3, Norm.Revision.Action.REVISED)));
		f.world.registerNorm(norm);
		return norm;
	}

	private static Fixture fixture() {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp("camp", "Meeting camp"));
		world.registerHousehold(new Household("source-home", "camp"));
		world.registerHousehold(new Household("recipient-home", "camp"));
		Person source = new Person("source", "camp", "source-home", Person.AgeBand.ADULT, 100, 50);
		Person recipient = new Person("recipient", "camp", "recipient-home", Person.AgeBand.ADULT, 100, 50);
		source.setTrust("recipient", 100);
		recipient.setTrust("source", 100);
		recipient.observeNorm("neighbor-requests");
		world.registerPerson(source);
		world.registerPerson(recipient);
		return new Fixture(world, new NormSystem(world, SimulationConfig.defaults()), source, recipient);
	}

	private record Fixture(SimulationWorld world, NormSystem norms, Person source, Person recipient) {}
}
