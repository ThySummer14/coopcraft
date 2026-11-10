package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.civilization.simulation.StorageOwner;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementFoodRefusalEvidenceTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void emptyCommonStockDoesNotInventARefusalBeforeAllowanceIsExhausted() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Empty common stock", 0);
		UUID resident = new UUID(1, 2);
		data.registerMember(settlement, resident, 0);
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		state.mapResident(resident, false, 0);
		state.setSurvivalEnabled(true, 0);
		state.setNormsEnabled(true);
		String personId = state.residentId(resident).orElseThrow();
		assertFalse(SettlementSimulationService.hasPendingFoodRefusal(data, settlement, resident));
		assertEquals(0, SettlementSimulationService.eat(data, settlement, resident));
		assertEquals(0, state.world().ledger().events().stream()
			.filter(event -> event.type() == EventType.SHARE_REFUSED).count());

		// Existing exhausted-cap behavior also applies with zero stock; it is still a cap refusal.
		state.recordFoodAllocation(personId, 3);
		assertTrue(SettlementSimulationService.hasPendingFoodRefusal(data, settlement, resident));
		SettlementSimulationService.eat(data, settlement, resident);
		SimulationEvent refusal = state.world().ledger().events().getLast();
		assertEquals(EventType.SHARE_REFUSED, refusal.type());
		assertEquals("daily-unit-cap-exhausted", refusal.context().get("reason"));
		assertEquals("0", refusal.context().get("availableNutrition"));
	}

	@Test
	void refusalSnapshotsAllowanceAndFoodAndSurvivesReloadWithoutDuplication() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Evidence", 0);
		UUID resident = new UUID(1, 2);
		data.registerMember(settlement, resident, 0);
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		state.mapResident(resident, false, 0);
		state.setSurvivalEnabled(true, 0);
		state.setNormsEnabled(true);
		String personId = state.residentId(resident).orElseThrow();
		String campId = state.campOwner().id();
		state.world().person(personId).setTrust(campId, 50);
		state.world().person(personId).setHunger(100);
		state.world().importExternalBatch(ResourceType.FISH, 20, state.campOwner());
		// Household food and non-food common stock are not available common food nutrition.
		state.world().importExternalBatch(ResourceType.WOOD, 10, state.campOwner());
		state.world().importExternalBatch(ResourceType.TUBERS, 10,
			StorageOwner.household(state.world().person(personId).householdId()));

		assertEquals(60, SettlementSimulationService.eat(data, settlement, resident));
		int countAfterMeal = state.world().ledger().events().size();
		int trustBefore = state.world().person(personId).trustIn(campId);
		assertTrue(SettlementSimulationService.hasPendingFoodRefusal(data, settlement, resident));
		assertEquals(countAfterMeal, state.world().ledger().events().size());
		assertEquals(0, SettlementSimulationService.eat(data, settlement, resident));
		assertEquals(countAfterMeal + 2, state.world().ledger().events().size());
		SimulationEvent refusal = state.world().ledger().events().getLast();
		Map<String, String> expected = Map.of(
			"score", "0.0", "reason", "daily-unit-cap-exhausted", "allocationBasis", "item_units",
			"allocatedUnits", "3", "cap", "3", "hunger", "40", "neededNutrition", "35",
			"availableNutrition", "340");
		assertEquals(EventType.SHARE_REFUSED, refusal.type());
		assertEquals(expected, refusal.context());
		assertEquals(List.of(personId, campId), refusal.actors());
		SimulationEvent request = state.world().ledger().event(refusal.causalLinks().getFirst()).orElseThrow();
		assertEquals(EventType.SHARE_REQUEST, request.type());
		assertEquals(refusal.actors(), request.actors());
		assertEquals("35", request.context().get("neededNutrition"));
		assertEquals(trustBefore - 2, state.world().person(personId).trustIn(campId));

		// Later stock, hunger and day changes must never rewrite the refusal evidence.
		state.world().importExternalBatch(ResourceType.FISH, 1, state.campOwner());
		state.world().person(personId).setHunger(80);
		int count = state.world().ledger().events().size();
		SettlementSimulationService.eat(data, settlement, resident);
		assertEquals(count, state.world().ledger().events().size());
		CompoundTag saved = data.save(new CompoundTag(), null);
		assertEquals(21, saved.getInt("DataVersion"));
		SettlementSavedData restored = SettlementSavedData.load(saved, null);
		Settlement restoredSettlement = restored.find(settlement.center()).orElseThrow();
		SettlementSimulationState restoredState = restored.simulationOf(restoredSettlement).orElseThrow();
		assertEquals(refusal, restoredState.world().ledger().event(refusal.eventId()).orElseThrow());
		assertFalse(SettlementSimulationService.hasPendingFoodRefusal(restored, restoredSettlement, resident));
		SettlementSimulationService.eat(restored, restoredSettlement, resident);
		assertEquals(count, restoredState.world().ledger().events().size());
		assertEquals(trustBefore - 2, restoredState.world().person(personId).trustIn(campId));
		restoredState.world().advanceDay();
		assertEquals(expected, restoredState.world().ledger().event(refusal.eventId()).orElseThrow().context());
		SettlementSavedData twiceRestored = SettlementSavedData.load(restored.save(new CompoundTag(), null), null);
		Settlement twiceRestoredSettlement = twiceRestored.find(settlement.center()).orElseThrow();
		SettlementSimulationState twiceRestoredState = twiceRestored.simulationOf(twiceRestoredSettlement).orElseThrow();
		SimulationEvent twiceRestoredRefusal = twiceRestoredState.world().ledger().event(refusal.eventId()).orElseThrow();
		assertEquals(expected, twiceRestoredRefusal.context());
		assertEquals(refusal.actors(), twiceRestoredRefusal.actors());
		assertEquals(refusal.causalLinks(), twiceRestoredRefusal.causalLinks());
		assertEquals(refusal, twiceRestoredRefusal);
		assertEquals(count, twiceRestoredState.world().ledger().events().size());
		assertEquals(trustBefore - 2, twiceRestoredState.world().person(personId).trustIn(campId));
		twiceRestoredState.world().assertConservation();
	}
}
