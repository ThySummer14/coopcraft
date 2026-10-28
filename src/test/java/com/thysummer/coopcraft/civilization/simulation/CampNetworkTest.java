package com.thysummer.coopcraft.civilization.simulation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampNetworkTest {
	private SimulationWorld world;
	private NormSystem norms;

	@BeforeEach
	void setUp() {
		world = new SimulationWorld();
		world.registerCamp(new Camp("a", "Camp A"));
		world.registerCamp(new Camp("b", "Camp B"));
		world.registerHousehold(new Household("ha", "a"));
		world.registerHousehold(new Household("hb", "b"));
		world.registerPerson(new Person("a1", "a", "ha", Person.AgeBand.ADULT, 0, 50));
		world.registerPerson(new Person("a2", "a", "ha", Person.AgeBand.ADULT, 0, 50));
		world.registerPerson(new Person("b1", "b", "hb", Person.AgeBand.ADULT, 0, 50));
		world.registerPerson(new Person("b2", "b", "hb", Person.AgeBand.ADULT, 0, 50));
		world.registerCampLink(new CampLink("route", "a", "b", 2));
		world.person("a1").learnCampRoute("route");
		world.person("b1").learnCampRoute("route");
		world.person("a1").setTrust("b1", 50);
		world.person("b1").setTrust("a1", 50);
		world.person("b1").setTrust("b2", 50);
		norms = new NormSystem(world, SimulationConfig.defaults().withPublicStorage(false));
	}

	@Test
	void crossCampInformationUsesKnownRoutesAndCarriesLimitedReports() {
		long request = world.recordShareRequest("a2", "a1", 40);
		long refusal = world.recordShareRefusal(request, "a2", "a1", 20);
		norms.processSocialPhase();
		norms.propagateInformation();
		String informationId = "refusal:" + refusal;

		assertTrue(world.person("b1").knowsInformation(informationId));
		assertFalse(world.person("b2").knowsInformation(informationId));
		assertEquals(2, world.person("a1").socialHours());
		assertEquals(1, world.person("b1").socialHours());
		CampKnowledge report = world.person("b1").campKnowledge("a").orElseThrow();
		assertEquals(100, report.foodSecurity());
		assertEquals("a1", report.learnedFrom());
		assertTrue(world.ledger().events().stream()
			.anyMatch(event -> event.type() == EventType.INFORMATION_SHARED && "route".equals(event.context().get("routeId"))
				&& "2".equals(event.context().get("travelHours"))));

		world.advanceDay();
		norms.propagateInformation();

		assertTrue(world.person("b2").knowsInformation(informationId));
		assertTrue(world.person("b2").knowsCampRoute("route"));
		CampKnowledge forwarded = world.person("b2").campKnowledge("a").orElseThrow();
		assertEquals("b1", forwarded.learnedFrom());
		assertEquals(0.85, forwarded.confidence());
		assertTrue(world.person("b2").campKnowledge("a").isPresent());
	}
}
