package com.thysummer.coopcraft.civilization.simulation;

import java.util.Map;

public final class RiverValleyTradeScenario {
	private RiverValleyTradeScenario() {
	}

	public static PrimitiveSocietySimulation create(long seed) {
		return create(
			seed, SimulationConfig.defaults(), DemographyConfig.disabled(), KnowledgeConfig.disabled(), TradeConfig.enabledDefaults()
		);
	}

	public static PrimitiveSocietySimulation create(
		long seed,
		SimulationConfig config,
		DemographyConfig demographyConfig,
		KnowledgeConfig knowledgeConfig,
		TradeConfig tradeConfig
	) {
		PrimitiveSocietySimulation base = RiverValleyNetworkScenario.create(seed, config);
		SimulationWorld world = base.world();
		world.createInitialBatch(ResourceType.TUBERS, 12, StorageOwner.household("household-0"));
		world.createInitialBatch(ResourceType.STONE, 4, StorageOwner.household("ridge-household-0"));
		shareInitialReport(world, "person-00", "ridge-person-00");
		shareInitialReport(world, "ridge-person-00", "person-00");
		return new PrimitiveSocietySimulation(world, config, demographyConfig, knowledgeConfig, tradeConfig, seed);
	}

	private static void shareInitialReport(SimulationWorld world, String reporterId, String recipientId) {
		Person reporter = world.person(reporterId);
		Person recipient = world.person(recipientId);
		long eventId = world.recordSocialEvent(
			EventType.INFORMATION_SHARED,
			java.util.List.of(reporterId, recipientId),
			Map.of("informationId", "initial-report:" + reporter.campId(), "reportType", "initial-camp-report"),
			java.util.List.of()
		);
		recipient.learnCampKnowledge(new CampKnowledge(
			reporter.campId(), world.foodSecurity(reporter.campId()), world.day(), world.day(), reporterId, 1.0, eventId
		));
	}
}
