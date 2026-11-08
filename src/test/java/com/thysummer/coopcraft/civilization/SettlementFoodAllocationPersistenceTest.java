package com.thysummer.coopcraft.civilization;

import java.util.OptionalLong;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.ResourceType;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SettlementFoodAllocationPersistenceTest {
	private static final UUID RESIDENT = new UUID(1, 2);
	private static final UUID OTHER_RESIDENT = new UUID(1, 3);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void sameDayExhaustionSurvivesRepeatedLoadsWithoutResettingEitherResident() {
		Fixture f = fixture();
		f.state().recordFoodAllocation(f.otherPerson(), 2);
		for (int i = 0; i < 3; i++) {
			f = reloadUnchanged(f);
			assertEquals(3, f.state().allocatedFoodUnits(f.person()));
			assertEquals(0, f.state().remainingFoodUnits(f.person()));
			assertEquals(2, f.state().allocatedFoodUnits(f.otherPerson()));
			assertEquals(1, f.state().remainingFoodUnits(f.otherPerson()));
			assertEquals(OptionalLong.of(54), snapshot(f).foodNutritionReceived());
			Fixture loaded = f;
			assertThrows(IllegalArgumentException.class, () -> loaded.state().recordFoodAllocation(loaded.person(), 1));
		}
	}

	@Test
	void yesterdayRemainsYesterdayAcrossTwoLoadsBeforeTodaysFirstMeal() {
		Fixture f = fixture();
		f.state().recordFoodAllocation(f.otherPerson(), 2);
		f.state().world().advanceDay();
		CompoundTag allocations = f.state().save();
		for (int i = 0; i < 2; i++) {
			f = reloadUnchanged(f);
			assertEquals(1, f.state().world().day());
			assertEquals(0, f.state().allocatedFoodUnits(f.person()));
			assertEquals(3, f.state().remainingFoodUnits(f.person()));
			assertEquals(0, f.state().allocatedFoodUnits(f.otherPerson()));
			assertEquals(OptionalLong.of(0), snapshot(f).foodNutritionReceived());
			assertEquals(0, f.state().save().getLong("FoodAllocationDay"));
			assertEquals(allocations.get("FoodAllocations"), f.state().save().get("FoodAllocations"));
		}
	}

	@Test
	void firstRealMealOnNewDayClearsOnlyOldAllowancesAndChargesTodaysFoodOnce() {
		Fixture f = fixture();
		f.state().recordFoodAllocation(f.otherPerson(), 2);
		f.state().world().advanceDay();
		f.state().world().person(f.person()).setHunger(100);
		f = reloadUnchanged(f);
		long stockBefore = f.state().world().inventoryTotals().get(ResourceType.WILD_FRUIT);
		int eventsBefore = f.state().world().ledger().events().size();
		assertEquals(54, SettlementSimulationService.eat(f.data(), f.settlement(), RESIDENT));
		assertEquals(stockBefore - 3, f.state().world().inventoryTotals().get(ResourceType.WILD_FRUIT).longValue());
		assertEquals(eventsBefore + 3, f.state().world().ledger().events().size());
		assertEquals(3, f.state().allocatedFoodUnits(f.person()));
		assertEquals(0, f.state().remainingFoodUnits(f.person()));
		assertEquals(0, f.state().allocatedFoodUnits(f.otherPerson()));
		assertEquals(1, f.state().save().getLong("FoodAllocationDay"));
		assertEquals(1, f.state().save().getList("FoodAllocations", ListTag.TAG_COMPOUND).size());
		assertEquals(OptionalLong.of(54), snapshot(f).foodNutritionReceived());
		assertEquals(46, snapshot(f).hunger());
		assertEquals(0, SettlementSimulationService.eat(f.data(), f.settlement(), RESIDENT));
		assertEquals(eventsBefore + 3, f.state().world().ledger().events().size());
		f.state().world().assertConservation();
		f = reloadUnchanged(f);
		assertEquals(0, f.state().remainingFoodUnits(f.person()));
	}

	@Test
	void firstExplicitAllocationRollsLedgerForwardWithoutReplayingStockOrEvents() {
		Fixture f = fixture();
		f.state().recordFoodAllocation(f.otherPerson(), 2);
		f.state().world().advanceDay();
		f = reloadUnchanged(f);
		CompoundTag before = f.state().save();
		f.state().recordFoodAllocation(f.otherPerson(), 1);
		assertEquals(1, f.state().save().getLong("FoodAllocationDay"));
		assertEquals(0, f.state().allocatedFoodUnits(f.person()));
		assertEquals(1, f.state().allocatedFoodUnits(f.otherPerson()));
		assertEquals(2, f.state().remainingFoodUnits(f.otherPerson()));
		assertEquals(before.get("Batches"), f.state().save().get("Batches"));
		assertEquals(before.get("Events"), f.state().save().get("Events"));
		f = reloadUnchanged(f);
		assertEquals(1, f.state().allocatedFoodUnits(f.otherPerson()));
		f.state().recordFoodAllocation(f.otherPerson(), 2);
		assertEquals(0, f.state().remainingFoodUnits(f.otherPerson()));
	}

	@Test
	void missingDateIsUnknownRatherThanInventedTodayAndOriginalCountsAreRetained() {
		Fixture f = fixture();
		CompoundTag tag = f.data().save(new CompoundTag(), null);
		CompoundTag simulation = savedSimulation(tag);
		simulation.remove("FoodAllocationDay");
		CompoundTag originalSimulation = simulation.copy();
		f = load(tag);
		assertEquals(Long.MIN_VALUE, f.state().save().getLong("FoodAllocationDay"));
		assertEquals(originalSimulation.get("FoodAllocations"), f.state().save().get("FoodAllocations"));
		assertEquals(originalSimulation.get("Events"), f.state().save().get("Events"));
		assertEquals(originalSimulation.get("Batches"), f.state().save().get("Batches"));
		assertEquals(0, f.state().allocatedFoodUnits(f.person()));
		assertEquals(3, f.state().remainingFoodUnits(f.person()));
		// Today's real events cannot be reconciled with an undated quota ledger.
		assertEquals(OptionalLong.empty(), snapshot(f).foodNutritionReceived());
		for (int i = 0; i < 2; i++) {
			f = reloadUnchanged(f);
			assertEquals(Long.MIN_VALUE, f.state().save().getLong("FoodAllocationDay"));
		}
	}

	@Test
	void futureDateIsPreservedWithoutClaimingToRepairTheAbnormalSave() {
		Fixture f = fixture();
		CompoundTag tag = f.data().save(new CompoundTag(), null);
		savedSimulation(tag).putLong("FoodAllocationDay", 100);
		f = load(tag);
		for (int i = 0; i < 2; i++) {
			f = reloadUnchanged(f);
			assertEquals(100, f.state().save().getLong("FoodAllocationDay"));
			assertEquals(3, f.state().save().getList("FoodAllocations", ListTag.TAG_COMPOUND).getCompound(0).getInt("Units"));
			assertEquals(0, f.state().allocatedFoodUnits(f.person()));
			assertEquals(3, f.state().remainingFoodUnits(f.person()));
			assertEquals(OptionalLong.empty(), snapshot(f).foodNutritionReceived());
		}
	}

	private static Fixture fixture() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Daily allowance", 0);
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		for (UUID resident : new UUID[] {RESIDENT, OTHER_RESIDENT}) {
			data.registerMember(settlement, resident, 0);
			state.mapResident(resident, false, 0);
		}
		state.setSurvivalEnabled(true, 0);
		state.world().importExternalBatch(ResourceType.WILD_FRUIT, 20, state.campOwner());
		assertEquals(54, SettlementSimulationService.eat(data, settlement, RESIDENT));
		return new Fixture(data, settlement, state);
	}

	/** A full save equality check includes inventory, events, quota date/counts and all other fields. */
	private static Fixture reloadUnchanged(Fixture f) {
		CompoundTag before = f.data().save(new CompoundTag(), null);
		Fixture restored = load(before);
		assertEquals(before, restored.data().save(new CompoundTag(), null));
		restored.data().setDirty(false);
		SettlementRations.inspect(restored.data(), restored.settlement());
		assertFalse(restored.data().isDirty());
		assertEquals(before, restored.data().save(new CompoundTag(), null));
		return restored;
	}

	private static Fixture load(CompoundTag tag) {
		SettlementSavedData data = SettlementSavedData.load(tag, null);
		Settlement settlement = data.find(BlockPos.ZERO).orElseThrow();
		return new Fixture(data, settlement, data.simulationOf(settlement).orElseThrow());
	}

	private static CompoundTag savedSimulation(CompoundTag tag) {
		return tag.getList("Simulations", ListTag.TAG_COMPOUND).getCompound(0);
	}

	private static SettlementRations.Snapshot snapshot(Fixture f) {
		return SettlementRations.inspect(f.data(), f.settlement()).getFirst();
	}

	private record Fixture(SettlementSavedData data, Settlement settlement, SettlementSimulationState state) {
		String person() { return state.residentId(RESIDENT).orElseThrow(); }
		String otherPerson() { return state.residentId(OTHER_RESIDENT).orElseThrow(); }
	}
}
