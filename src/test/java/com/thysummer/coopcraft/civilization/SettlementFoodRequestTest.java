package com.thysummer.coopcraft.civilization;

import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementFoodRequestTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void exhaustedAllowanceAllowsOneReadOnlyPendingRequestPerDayAcrossReloads() {
		Fixture fixture = fixture();
		assertFalse(pending(fixture));
		assertEquals(60, SettlementSimulationService.eat(fixture.data(), fixture.settlement(), fixture.resident()));
		int eventsAfterMeal = fixture.state().world().ledger().events().size();
		for (int i = 0; i < 100; i++) {
			assertTrue(pending(fixture));
		}
		assertEquals(eventsAfterMeal, fixture.state().world().ledger().events().size());

		assertEquals(0, SettlementSimulationService.eat(fixture.data(), fixture.settlement(), fixture.resident()));
		assertFalse(pending(fixture));
		assertEquals(1, refusals(fixture.state()));
		int eventsAfterRefusal = fixture.state().world().ledger().events().size();
		SettlementSimulationService.eat(fixture.data(), fixture.settlement(), fixture.resident());
		assertEquals(eventsAfterRefusal, fixture.state().world().ledger().events().size());

		SettlementSavedData restored = SettlementSavedData.load(fixture.data().save(new CompoundTag(), null), null);
		Settlement settlement = restored.find(fixture.settlement().center()).orElseThrow();
		SettlementSimulationState state = restored.simulationOf(settlement).orElseThrow();
		Fixture reloaded = new Fixture(restored, settlement, state, fixture.resident());
		assertFalse(pending(reloaded));
		SettlementSimulationService.eat(restored, settlement, fixture.resident());
		assertEquals(eventsAfterRefusal, state.world().ledger().events().size());

		state.world().advanceDay();
		state.world().person(state.residentId(fixture.resident()).orElseThrow()).setHunger(100);
		assertFalse(pending(reloaded));
		assertEquals(60, SettlementSimulationService.eat(restored, settlement, fixture.resident()));
		assertTrue(pending(reloaded));
		SettlementSimulationService.eat(restored, settlement, fixture.resident());
		assertFalse(pending(reloaded));
		assertEquals(2, refusals(state));
		state.world().assertConservation();
	}

	@Test
	void disabledFeaturesUnknownResidentsAndSatisfiedResidentsCannotRequestRefusal() {
		Fixture fixture = fixture();
		String personId = fixture.state().residentId(fixture.resident()).orElseThrow();
		fixture.state().recordFoodAllocation(personId, 3);
		assertTrue(pending(fixture));
		assertFalse(SettlementSimulationService.hasPendingFoodRefusal(fixture.data(), fixture.settlement(), UUID.randomUUID()));
		fixture.state().setNormsEnabled(false);
		assertFalse(pending(fixture));
		fixture.state().setNormsEnabled(true);
		fixture.state().setSurvivalEnabled(false, 0);
		assertFalse(pending(fixture));
		fixture.state().setSurvivalEnabled(true, 0);
		fixture.state().world().person(personId).setHunger(5);
		assertFalse(pending(fixture));
		assertEquals(0, refusals(fixture.state()));
	}

	@Test
	void queryingWithoutSimulationDoesNotCreateOne() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "No simulation", 0);
		assertFalse(SettlementSimulationService.hasPendingFoodRefusal(data, settlement, UUID.randomUUID()));
		assertTrue(data.simulationOf(settlement).isEmpty());
	}

	private static Fixture fixture() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Food requests", 0);
		UUID resident = new UUID(1, 2);
		data.registerMember(settlement, resident, 0);
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		state.mapResident(resident, false, 0);
		state.setSurvivalEnabled(true, 0);
		state.setNormsEnabled(true);
		state.world().importExternalBatch(ResourceType.FISH, 20, state.campOwner());
		return new Fixture(data, settlement, state, resident);
	}

	private static boolean pending(Fixture fixture) {
		return SettlementSimulationService.hasPendingFoodRefusal(fixture.data(), fixture.settlement(), fixture.resident());
	}

	private static long refusals(SettlementSimulationState state) {
		return state.world().ledger().events().stream().filter(event -> event.type() == EventType.SHARE_REFUSED).count();
	}

	private record Fixture(SettlementSavedData data, Settlement settlement, SettlementSimulationState state, UUID resident) {
	}
}
