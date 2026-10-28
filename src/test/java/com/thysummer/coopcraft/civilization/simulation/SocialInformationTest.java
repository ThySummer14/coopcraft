package com.thysummer.coopcraft.civilization.simulation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocialInformationTest {
	private SimulationWorld world;
	private NormSystem norms;

	@BeforeEach
	void setUp() {
		world = new SimulationWorld();
		world.registerCamp(new Camp("camp", "Test Camp"));
		world.registerHousehold(new Household("household", "camp"));
		for (String id : new String[] {"a", "b", "c", "d", "e"}) {
			world.registerPerson(new Person(id, "camp", "household", Person.AgeBand.ADULT, 0, 50));
		}
		norms = new NormSystem(world, SimulationConfig.defaults().withPublicStorage(false));
	}

	@Test
	void onlyParticipantsLearnDirectlyAndInformationTravelsOneHopPerDay() {
		long request = world.recordShareRequest("b", "a", 40);
		long refusal = world.recordShareRefusal(request, "b", "a", 20);
		norms.processSocialPhase();
		String informationId = "refusal:" + refusal;

		assertTrue(world.person("a").knowsInformation(informationId));
		assertTrue(world.person("b").knowsInformation(informationId));
		assertFalse(world.person("c").knowsInformation(informationId));
		assertFalse(world.person("d").knowsInformation(informationId));
		assertFalse(world.person("e").knowsInformation(informationId));

		world.person("a").setTrust("c", 50);
		world.person("c").setTrust("a", 50);
		world.person("c").setTrust("d", 50);
		world.person("d").setTrust("c", 50);
		norms.propagateInformation();

		assertTrue(world.person("c").knowsInformation(informationId));
		assertEquals("a", world.person("c").information(informationId).orElseThrow().learnedFrom());
		assertEquals(0.85, world.person("c").information(informationId).orElseThrow().confidence());
		assertFalse(world.person("d").knowsInformation(informationId));

		norms.propagateInformation();
		assertFalse(world.person("d").knowsInformation(informationId), "Same-day reticks cannot create a gossip chain");
		world.advanceDay();
		norms.propagateInformation();

		assertTrue(world.person("d").knowsInformation(informationId));
		assertEquals("c", world.person("d").information(informationId).orElseThrow().learnedFrom());
		assertEquals(0.7225, world.person("d").information(informationId).orElseThrow().confidence(), 0.0001);
		assertFalse(world.person("e").knowsInformation(informationId), "Isolated residents cannot learn the dispute");
		assertEquals(2, world.ledger().events().stream().filter(event -> event.type() == EventType.INFORMATION_SHARED).count());
		assertTrue(world.ledger().events().stream()
			.filter(event -> event.type() == EventType.INFORMATION_SHARED)
			.allMatch(event -> event.causalLinks().contains(refusal)));
	}
}
