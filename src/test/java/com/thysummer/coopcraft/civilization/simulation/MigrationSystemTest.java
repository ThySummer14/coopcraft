package com.thysummer.coopcraft.civilization.simulation;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationSystemTest {
	private SimulationWorld world;
	private MigrationSystem migrations;

	@BeforeEach
	void setUp() {
		world = new SimulationWorld();
		world.registerCamp(new Camp("good", "Good Camp"));
		world.registerCamp(new Camp("bad", "Bad Camp"));
		world.registerHousehold(new Household("good-h", "good"));
		world.registerHousehold(new Household("bad-h1", "bad"));
		world.registerHousehold(new Household("bad-h2", "bad"));
		world.registerPerson(new Person("good1", "good", "good-h", Person.AgeBand.ADULT, 0, 50));
		world.registerPerson(new Person("mover1", "bad", "bad-h1", Person.AgeBand.ADULT, 90, 50));
		world.registerPerson(new Person("mover2", "bad", "bad-h1", Person.AgeBand.ADULT, 90, 50));
		world.registerPerson(new Person("child", "bad", "bad-h1", Person.AgeBand.CHILD, 90, 50));
		world.registerPerson(new Person("stayer", "bad", "bad-h2", Person.AgeBand.ADULT, 90, 50));
		world.registerCampLink(new CampLink("route", "good", "bad", 2));
		for (String id : new String[] {"good1", "mover1", "mover2"}) {
			world.person(id).learnCampRoute("route");
		}
		world.person("mover1").setTrust("good1", 50);
		world.person("mover2").setTrust("good1", 50);
		world.createInitialBatch(ResourceType.TUBERS, 5, StorageOwner.household("bad-h1"));
		world.registerNorm(new Norm(
			Norm.NEED_FIRST_COMMON_STORE, "bad", 1, Set.of("mover1", "mover2"), Set.of(),
			Set.of("mover1", "mover2", "stayer"), 40, Norm.Status.ACTIVE, List.of()
		));
		migrations = new MigrationSystem(world, SimulationConfig.defaults());
	}

	@Test
	void householdMovesWithItsInventoryAndLosesRemoteEnforcement() {
		long reportEvent = world.recordSocialEvent(
			EventType.INFORMATION_SHARED, List.of("good1", "mover1"), java.util.Map.of("informationId", "report"), List.of()
		);
		for (String id : new String[] {"mover1", "mover2"}) {
			world.person(id).learnCampKnowledge(new CampKnowledge("good", 100, 0, 0, "good1", 1.0, reportEvent));
		}
		for (int day = 0; day < 5; day++) {
			migrations.evaluateMigration();
			if (day < 4) {
				world.advanceDay();
			}
		}

		assertEquals("good", world.household("bad-h1").campId());
		assertEquals("good", world.person("mover1").campId());
		assertEquals("good", world.person("child").campId());
		assertEquals("bad", world.household("bad-h2").campId());
		assertEquals(120, world.availableNutrition(StorageOwner.household("bad-h1")));
		assertEquals(2, world.person("mover1").socialHours());
		assertEquals(0, world.person("child").socialHours());
		assertEquals(4, world.lastMigrationDay("bad-h1"));
		assertEquals(3, new NormSystem(world, SimulationConfig.defaults()).limitCommonAllocation(world.person("stayer"), 3));

		SimulationEvent migrated = lastEvent(EventType.MIGRATED);
		assertEquals("bad-h1", migrated.context().get("householdId"));
		assertTrue(world.ledger().traceAncestry(migrated.eventId()).stream()
			.anyMatch(event -> event.type() == EventType.MIGRATION_DECIDED));
		assertTrue(world.ledger().traceAncestry(migrated.eventId()).stream().anyMatch(event -> event.eventId() == reportEvent));
		world.assertConservation();
	}

	private SimulationEvent lastEvent(EventType type) {
		return world.ledger().events().stream().filter(event -> event.type() == type).reduce((first, second) -> second)
			.orElseThrow();
	}
}
