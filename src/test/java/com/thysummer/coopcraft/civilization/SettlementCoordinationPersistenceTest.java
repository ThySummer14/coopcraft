package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementCoordinationPersistenceTest {
	@Test
	void preservesNeedsCommitmentsAndConstructionProgressThroughTwoReloads() {
		SettlementSavedData data = new SettlementSavedData();
		BlockPos center = new BlockPos(5, 64, 7);
		Settlement settlement = data.getOrCreate(center, "Testhaven", 0);
		UUID actor = UUID.randomUUID();
		data.registerMember(settlement, actor, 0);
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		state.mapResident(actor, false, 80);
		NeedSignal food = NeedSignal.observed(
			settlement.id(), NeedSignal.Type.COMMON_FOOD_SHORTAGE, NeedSignal.Severity.URGENT,
			4, center.south(), 1, Set.of(actor)
		);
		NeedSignal shelter = NeedSignal.observed(
			settlement.id(), NeedSignal.Type.SHELTER_GAP, NeedSignal.Severity.WATCH,
			4, center.north(6), 1, Set.of(actor)
		);
		WorkReservation paused = WorkReservation.commit(4, actor, shelter).activate().pause("food_shortage");
		ConstructionJob job = new ConstructionJob(ConstructionPlanner.STONE_COTTAGE, center.north(6), 17);
		data.setConstructionJob(settlement, job);
		state.replaceCoordinationPlan(new CoordinationPlan(4, List.of(food, shelter), List.of(paused)));

		SettlementSavedData restored = data;
		for (int reload = 0; reload < 2; reload++) {
			CompoundTag saved = restored.save(new CompoundTag(), null);
			assertEquals(26, saved.getInt("DataVersion"));
			restored = SettlementSavedData.load(saved, null);
			Settlement restoredSettlement = restored.find(center).orElseThrow();
			assertEquals(job, restored.getConstructionJob(restoredSettlement).orElseThrow());
			assertEquals(paused, restored.simulationOf(restoredSettlement).orElseThrow()
				.coordinationPlan().reservationFor(actor).orElseThrow());
		}
	}

	@Test
	void oldSaveStartsWithAnEmptyCoordinationPlan() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Testhaven", 0);
		data.getOrCreateSimulation(settlement);
		CompoundTag tag = data.save(new CompoundTag(), null);
		CompoundTag simulation = tag.getList("Simulations", net.minecraft.nbt.ListTag.TAG_COMPOUND).getCompound(0);
		simulation.remove("CoordinationDay");
		simulation.remove("NeedSignals");
		simulation.remove("WorkReservations");

		SettlementSimulationState restored = SettlementSavedData.load(tag, null).simulationOf(settlement).orElseThrow();

		assertEquals(-1, restored.coordinationPlan().day());
		assertTrue(restored.coordinationPlan().signals().isEmpty());
		assertTrue(restored.coordinationPlan().reservations().isEmpty());
	}
}
