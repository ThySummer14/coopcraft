package com.thysummer.coopcraft.civilization.simulation;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeSystemTest {
	@Test
	void successfulExchangeConservesBatchesAndChargesTravelTime() {
		SimulationWorld world = tradeWorld(true, 60);
		prepareSuccessfulExchange(world);
		ResourceBatch originalFood = batchAt(world, "ha", ResourceType.TUBERS);
		PrimitiveSocietySimulation simulation = simulation(world, TradeConfig.enabledDefaults());

		assertEquals(1, simulation.tradeSystem().executeTrades());

		assertEquals(9, quantity(world, "ha", ResourceType.TUBERS));
		assertEquals(1, quantity(world, "hb", ResourceType.TUBERS));
		assertEquals(1, quantity(world, "ha", ResourceType.STONE));
		assertEquals(3, quantity(world, "hb", ResourceType.STONE));
		assertEquals(2, world.person("a1").socialHours());
		assertEquals(1, world.person("b1").socialHours());
		assertEquals(originalFood.originEventId(), batchAt(world, "hb", ResourceType.TUBERS).originEventId());
		SimulationEvent request = onlyEvent(world, EventType.TRADE_REQUEST);
		List<SimulationEvent> completed = events(world, EventType.TRADE_COMPLETED);
		assertEquals(2, completed.size());
		for (SimulationEvent event : completed) {
			assertTrue(world.ledger().traceAncestry(event.eventId()).contains(request));
			assertFalse(world.ledger().traceOrigins(event.eventId()).isEmpty());
		}
		assertEquals(0, simulation.tradeSystem().executeTrades());
		assertEquals(2, events(world, EventType.TRADE_COMPLETED).size());
		world.assertConservation();
	}

	@Test
	void missingReportRouteOrMutualTrustPreventsRequests() {
		SimulationWorld noReport = tradeWorld(true, 60);
		addStock(noReport);
		SimulationWorld noRoute = tradeWorld(false, 60);
		prepareSuccessfulExchange(noRoute);
		SimulationWorld noTrust = tradeWorld(true, 20);
		prepareSuccessfulExchange(noTrust);

		assertEquals(0, simulation(noReport, TradeConfig.enabledDefaults()).tradeSystem().executeTrades());
		assertEquals(0, simulation(noRoute, TradeConfig.enabledDefaults()).tradeSystem().executeTrades());
		assertEquals(0, simulation(noTrust, TradeConfig.enabledDefaults()).tradeSystem().executeTrades());
		assertTrue(events(noReport, EventType.TRADE_REQUEST).isEmpty());
		assertTrue(events(noRoute, EventType.TRADE_REQUEST).isEmpty());
		assertTrue(events(noTrust, EventType.TRADE_REQUEST).isEmpty());
	}

	@Test
	void lowConfidenceReportCannotSupportTrade() {
		SimulationWorld world = tradeWorld(true, 60);
		addStock(world);
		addReport(world, "a1", "b1", 90, 0.4);
		addReport(world, "b1", "a1", 40, 0.4);

		assertEquals(0, simulation(world, TradeConfig.enabledDefaults()).tradeSystem().executeTrades());

		assertTrue(events(world, EventType.TRADE_REQUEST).isEmpty());
		assertEquals(10, quantity(world, "ha", ResourceType.TUBERS));
		assertEquals(4, quantity(world, "hb", ResourceType.STONE));
	}

	@Test
	void responderWithoutSurplusRefusesWithoutMovingAnything() {
		SimulationWorld world = tradeWorld(true, 60);
		world.createInitialBatch(ResourceType.TUBERS, 10, StorageOwner.household("ha"));
		addReport(world, "a1", "b1", 90, 1.0);
		addReport(world, "b1", "a1", 40, 1.0);

		assertEquals(0, simulation(world, TradeConfig.enabledDefaults()).tradeSystem().executeTrades());

		assertEquals("no-surplus", onlyEvent(world, EventType.TRADE_REFUSED).context().get("reason"));
		assertTrue(events(world, EventType.TRADE_COMPLETED).isEmpty());
		assertEquals(10, quantity(world, "ha", ResourceType.TUBERS));
		assertEquals(0, quantity(world, "hb", ResourceType.TUBERS));
		assertEquals(0, world.person("a1").socialHours());
		world.assertConservation();
	}

	@Test
	void responderWithoutNeedRefusesEvenWithReturnStock() {
		SimulationWorld world = tradeWorld(true, 60);
		world.createInitialBatch(ResourceType.STONE, 4, StorageOwner.household("ha"));
		world.createInitialBatch(ResourceType.STONE, 4, StorageOwner.household("hb"));
		world.createInitialBatch(ResourceType.TUBERS, 10, StorageOwner.household("hb"));
		addReport(world, "a1", "b1", 90, 1.0);
		addReport(world, "b1", "a1", 90, 1.0);

		assertEquals(0, simulation(world, TradeConfig.enabledDefaults()).tradeSystem().executeTrades());

		assertEquals("no-need", onlyEvent(world, EventType.TRADE_REFUSED).context().get("reason"));
		assertTrue(events(world, EventType.TRADE_COMPLETED).isEmpty());
		assertEquals(4, quantity(world, "ha", ResourceType.STONE));
		assertEquals(4, quantity(world, "hb", ResourceType.STONE));
		world.assertConservation();
	}

	@Test
	void disabledTradeLeavesInventoryUntouched() {
		SimulationWorld world = tradeWorld(true, 60);
		prepareSuccessfulExchange(world);

		assertEquals(0, simulation(world, TradeConfig.disabled()).tradeSystem().executeTrades());

		assertTrue(events(world, EventType.TRADE_REQUEST).isEmpty());
		assertEquals(10, quantity(world, "ha", ResourceType.TUBERS));
		assertEquals(4, quantity(world, "hb", ResourceType.STONE));
		assertEquals(0, world.person("a1").socialHours());
		world.assertConservation();
	}

	@Test
	void worldRejectsMismatchedRequestBeforeMovingInventory() {
		SimulationWorld world = tradeWorld(true, 60);
		prepareSuccessfulExchange(world);
		ResourceBatch offered = batchAt(world, "ha", ResourceType.TUBERS);
		ResourceBatch requested = batchAt(world, "hb", ResourceType.STONE);
		long shareRequest = world.recordShareRequest("a1", "b1", 24);

		assertThrows(IllegalArgumentException.class, () -> world.exchangeOneForOne(
			shareRequest, "a1", offered.batchId(), "b1", requested.batchId(), "route"
		));

		assertEquals(10, quantity(world, "ha", ResourceType.TUBERS));
		assertEquals(4, quantity(world, "hb", ResourceType.STONE));
		world.assertConservation();
	}

	@Test
	void tradeScenarioReplaysAndConservesResources() {
		PrimitiveSocietySimulation first = RiverValleyTradeScenario.create(7);
		PrimitiveSocietySimulation replay = RiverValleyTradeScenario.create(7);

		first.runDays(1000);
		replay.runDays(1000);

		assertEquals(first.world().ledger().events(), replay.world().ledger().events());
		assertEquals(first.reports(), replay.reports());
		assertEquals(first.world().inventoryTotals(), replay.world().inventoryTotals());
		assertFalse(events(first.world(), EventType.TRADE_COMPLETED).isEmpty());
		for (SimulationEvent event : first.world().ledger().events()) {
			if (event.type() == EventType.TRADE_COMPLETED) {
				assertTrue(first.world().ledger().traceAncestry(event.eventId()).stream()
					.anyMatch(cause -> cause.type() == EventType.TRADE_REQUEST));
				assertFalse(first.world().ledger().traceOrigins(event.eventId()).isEmpty());
			}
			if (event.type() == EventType.CONSUME || event.type() == EventType.FIELD_CONSUMPTION) {
				assertFalse(first.world().ledger().traceOrigins(event.eventId()).isEmpty());
			}
		}
		first.world().assertConservation();
	}

	private static SimulationWorld tradeWorld(boolean routeKnown, int trust) {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp("a", "Camp A"));
		world.registerCamp(new Camp("b", "Camp B"));
		world.registerHousehold(new Household("ha", "a"));
		world.registerHousehold(new Household("hb", "b"));
		world.registerPerson(new Person("a1", "a", "ha", Person.AgeBand.ADULT, 0, 50));
		world.registerPerson(new Person("b1", "b", "hb", Person.AgeBand.ADULT, 0, 50));
		world.registerCampLink(new CampLink("route", "a", "b", 2));
		if (routeKnown) {
			world.person("a1").learnCampRoute("route");
			world.person("b1").learnCampRoute("route");
		}
		world.person("a1").setTrust("b1", trust);
		world.person("b1").setTrust("a1", trust);
		return world;
	}

	private static PrimitiveSocietySimulation simulation(SimulationWorld world, TradeConfig config) {
		return new PrimitiveSocietySimulation(
			world, SimulationConfig.defaults(), DemographyConfig.disabled(), KnowledgeConfig.disabled(), config, 7
		);
	}

	private static void prepareSuccessfulExchange(SimulationWorld world) {
		addStock(world);
		addReport(world, "a1", "b1", 90, 1.0);
		addReport(world, "b1", "a1", 40, 1.0);
	}

	private static void addStock(SimulationWorld world) {
		world.createInitialBatch(ResourceType.TUBERS, 10, StorageOwner.household("ha"));
		world.createInitialBatch(ResourceType.STONE, 4, StorageOwner.household("hb"));
	}

	private static void addReport(SimulationWorld world, String reporterId, String recipientId, int security, double confidence) {
		Person reporter = world.person(reporterId);
		Person recipient = world.person(recipientId);
		long eventId = world.recordSocialEvent(
			EventType.INFORMATION_SHARED,
			List.of(reporterId, recipientId),
			Map.of("informationId", "report:" + reporter.campId() + ":" + recipientId),
			List.of()
		);
		recipient.learnCampKnowledge(new CampKnowledge(
			reporter.campId(), security, world.day(), world.day(), reporterId, confidence, eventId
		));
	}

	private static ResourceBatch batchAt(SimulationWorld world, String householdId, ResourceType type) {
		return world.batchesAt(StorageOwner.household(householdId)).stream()
			.filter(batch -> batch.resourceType() == type)
			.findFirst().orElseThrow();
	}

	private static long quantity(SimulationWorld world, String householdId, ResourceType type) {
		return world.batchesAt(StorageOwner.household(householdId)).stream()
			.filter(batch -> batch.resourceType() == type)
			.mapToLong(ResourceBatch::quantity)
			.sum();
	}

	private static SimulationEvent onlyEvent(SimulationWorld world, EventType type) {
		return events(world, type).stream().findFirst().orElseThrow();
	}

	private static List<SimulationEvent> events(SimulationWorld world, EventType type) {
		return world.ledger().events().stream().filter(event -> event.type() == type).toList();
	}
}
