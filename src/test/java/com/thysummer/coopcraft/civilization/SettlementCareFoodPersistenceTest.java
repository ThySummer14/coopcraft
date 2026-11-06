package com.thysummer.coopcraft.civilization;

import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.EventType;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementCareFoodPersistenceTest {
	@Test
	void careFoodRequestIsDeduplicatedAndSurvivesTwoReloads() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(new BlockPos(8, 64, 12), "Carehaven", 0);
		UUID child = UUID.randomUUID();
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		state.mapResident(child, true, 80);

		long request = state.ensureCareFoodRequest(child, 7, 15);
		assertEquals(request, state.ensureCareFoodRequest(child, 7, 15));
		assertEquals(1, state.world().ledger().events().size());
		assertEquals("care_food", state.world().ledger().event(request).orElseThrow().context().get("reason"));

		SettlementSavedData restored = data;
		for (int reload = 0; reload < 2; reload++) {
			CompoundTag saved = restored.save(new CompoundTag(), null);
			assertEquals(26, saved.getInt("DataVersion"));
			restored = SettlementSavedData.load(saved, null);
			Settlement restoredSettlement = restored.find(settlement.center()).orElseThrow();
			SettlementSimulationState restoredState = restored.simulationOf(restoredSettlement).orElseThrow();
			assertEquals(request, restoredState.careFoodRequest(child, 7).orElseThrow());
			assertEquals(request, restoredState.ensureCareFoodRequest(child, 7, 15));
			assertEquals(1, restoredState.world().ledger().events().size());
		}

		SettlementSimulationState restoredState = restored.simulationOf(settlement).orElseThrow();
		long nextDay = restoredState.ensureCareFoodRequest(child, 8, 20);
		assertNotEquals(request, nextDay);
		assertTrue(restoredState.careFoodRequest(child, 7).isEmpty());
		assertEquals(nextDay, restoredState.careFoodRequest(child, 8).orElseThrow());
		assertEquals(2, restoredState.world().ledger().events().size());
		assertEquals(EventType.SHARE_REQUEST, restoredState.world().ledger().event(nextDay).orElseThrow().type());
	}

	@Test
	void versionTwentySaveStartsWithoutCareFoodRequests() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Carehaven", 0);
		UUID child = UUID.randomUUID();
		data.getOrCreateSimulation(settlement).mapResident(child, true, 80);
		CompoundTag saved = data.save(new CompoundTag(), null);
		saved.putInt("DataVersion", 20);
		CompoundTag simulation = saved.getList("Simulations", net.minecraft.nbt.ListTag.TAG_COMPOUND).getCompound(0);
		simulation.remove("CareFoodRequestDay");
		simulation.remove("CareFoodRequests");

		SettlementSimulationState restored = SettlementSavedData.load(saved, null).simulationOf(settlement).orElseThrow();

		assertTrue(restored.careFoodRequest(child, 0).isEmpty());
		assertTrue(restored.world().ledger().events().isEmpty());
	}
}
