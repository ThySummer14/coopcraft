package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettlementCraftingTraceTest {
	@Test
	void completionClosesPreviousInputsWithoutOptionalSkillEvents() {
		List<SimulationEvent> events = List.of(
			event(1, EventType.EXTERNAL_EXPORT, "alice", "sticks", "craft"),
			event(2, EventType.CRAFT_COMPLETED, "alice", "sticks", "craft"),
			event(3, EventType.EXTERNAL_EXPORT, "alice", "sticks", "craft"));
		assertEquals(List.of(3L), SettlementSimulationService.craftingMaterialCauses(events, "alice", "sticks"));
	}

	@Test
	void unrelatedPracticeAndRecipesDoNotDiscardPendingInputs() {
		List<SimulationEvent> events = List.of(
			event(1, EventType.EXTERNAL_EXPORT, "alice", "sticks", "craft"),
			event(2, EventType.CRAFT_COMPLETED, "alice", "door", "craft"),
			event(3, EventType.SKILL_PRACTICED, "alice", "door", "craft"),
			event(4, EventType.EXTERNAL_EXPORT, "bob", "sticks", "craft"),
			event(5, EventType.EXTERNAL_EXPORT, "alice", "sticks", "build"));
		assertEquals(List.of(1L), SettlementSimulationService.craftingMaterialCauses(events, "alice", "sticks"));
		assertEquals(List.of(4L), SettlementSimulationService.craftingMaterialCauses(events, "bob", "sticks"));
	}

	private static SimulationEvent event(long id, EventType type, String actor, String recipe, String activity) {
		return new SimulationEvent(id, 0, type, List.of(actor), List.of(), List.of(),
			Map.of("recipe", recipe, "activity", activity), List.of());
	}
}
