package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.Norm;
import com.thysummer.coopcraft.civilization.simulation.ResourceFlow;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementRationsTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void threeFruitAndThreeGameHaveEqualUnitsButDifferentNutrition() {
		Fixture fruit = fixture(false);
		Fixture game = fixture(false);
		eat(fruit, ResourceType.WILD_FRUIT, 3);
		eat(game, ResourceType.GAME, 3);
		assertEquals(3, snapshot(fruit).allocatedUnits());
		assertEquals(3, snapshot(game).allocatedUnits());
		assertEquals(OptionalLong.of(54), snapshot(fruit).foodNutritionReceived());
		assertEquals(OptionalLong.of(84), snapshot(game).foodNutritionReceived());
		assertEquals(46, snapshot(fruit).hunger());
		assertEquals(16, snapshot(game).hunger());
		assertEquals(0, snapshot(fruit).remainingUnits());
	}

	@Test
	void mixedFoodsAreCountedOnceAndConsumptionDoesNotDoubleCount() {
		Fixture f = fixture(false);
		eat(f, ResourceType.WILD_FRUIT, 1);
		eat(f, ResourceType.GAME, 2);
		assertEquals(3, snapshot(f).allocatedUnits());
		assertEquals(OptionalLong.of(74), snapshot(f).foodNutritionReceived());
		assertTrue(f.state().world().ledger().events().stream().anyMatch(e -> e.type() == EventType.CONSUME));
		f.state().world().assertConservation();
	}

	@Test
	void allocatedButNotYetConsumedFoodIsAlreadyReceived() {
		Fixture f = fixture(false);
		var batch = f.state().world().importExternalBatch(ResourceType.GAME, 2, f.state().campOwner());
		f.state().world().allocateCommon(f.camp(), f.person(), batch.batchId(), 2, 0);
		f.state().recordFoodAllocation(f.person(), 2);
		assertEquals(OptionalLong.of(56), snapshot(f).foodNutritionReceived());
		assertEquals(100, snapshot(f).hunger());
		f.state().world().assertConservation();
	}

	@Test
	void nutritionIsFoodContentRatherThanHungerRelief() {
		Fixture f = fixture(false);
		f.state().world().person(f.person()).setHunger(10);
		eat(f, ResourceType.GAME, 1);
		assertEquals(OptionalLong.of(28), snapshot(f).foodNutritionReceived());
		assertEquals(0, snapshot(f).hunger());
		assertEquals(1, snapshot(f).allocatedUnits());
	}

	@Test
	void historicalFoodNutritionIsNotReestimatedFromResourceType() {
		Fixture f = fixture(false);
		restoreEvents(f, 0, List.of(event(1, 0, f.person(), f.camp(), EventType.SHARE, "camp-storage",
			flow(ResourceType.WILD_FRUIT, 2, 37))));
		f.state().recordFoodAllocation(f.person(), 2);
		assertEquals(OptionalLong.of(74), snapshot(f).foodNutritionReceived());
	}

	@Test
	void onlyCurrentDayCommonFoodAllocatedToThisPersonAndCampCounts() {
		Fixture f = fixture(false);
		restoreEvents(f, 1, List.of(
			event(1, 0, f.person(), f.camp(), EventType.SHARE, "camp-storage", flow(ResourceType.GAME, 1, 28)),
			event(2, 1, "someone-else", f.camp(), EventType.SHARE, "camp-storage", flow(ResourceType.GAME, 1, 28)),
			event(3, 1, f.person(), "another-camp", EventType.SHARE, "camp-storage", flow(ResourceType.GAME, 1, 28)),
			event(4, 1, f.person(), f.camp(), EventType.SHARE, "personal", flow(ResourceType.GAME, 1, 28)),
			event(5, 1, f.person(), f.camp(), EventType.CONSUME, "camp-storage", flow(ResourceType.GAME, 1, 28)),
			event(6, 1, f.person(), f.camp(), EventType.SHARE, "camp-storage", flow(ResourceType.WOOD, 99, 0)),
			event(7, 1, f.person(), f.camp(), EventType.SHARE, "camp-storage", flow(ResourceType.FISH, 1, 20))));
		f.state().recordFoodAllocation(f.person(), 1);
		assertEquals(OptionalLong.of(20), snapshot(f).foodNutritionReceived());
		assertEquals(1, snapshot(f).day());
	}

	@Test
	void missingOrMismatchedHistoryNeverFabricatesNutrition() {
		Fixture missing = fixture(false);
		missing.state().recordFoodAllocation(missing.person(), 2);
		assertEquals(OptionalLong.empty(), snapshot(missing).foodNutritionReceived());
		assertEquals(2, snapshot(missing).allocatedUnits());
		assertEquals(1, snapshot(missing).remainingUnits());

		Fixture partial = fixture(false);
		restoreEvents(partial, 0, List.of(event(1, 0, partial.person(), partial.camp(), EventType.SHARE,
			"camp-storage", flow(ResourceType.FISH, 1, 20))));
		partial.state().recordFoodAllocation(partial.person(), 2);
		assertEquals(OptionalLong.empty(), snapshot(partial).foodNutritionReceived());

		Fixture unrecorded = fixture(false);
		restoreEvents(unrecorded, 0, List.of(event(1, 0, unrecorded.person(), unrecorded.camp(), EventType.SHARE,
			"camp-storage", flow(ResourceType.FISH, 1, 20))));
		assertEquals(0, snapshot(unrecorded).allocatedUnits());
		assertEquals(OptionalLong.empty(), snapshot(unrecorded).foodNutritionReceived());
	}

	@Test
	void absentSavedFoodNutritionAndOverflowAreUnknown() {
		for (long nutrition : new long[] {0, Long.MAX_VALUE}) {
			Fixture f = fixture(false);
			restoreEvents(f, 0, List.of(event(1, 0, f.person(), f.camp(), EventType.SHARE,
				"camp-storage", flow(ResourceType.GAME, 2, nutrition))));
			f.state().recordFoodAllocation(f.person(), 2);
			assertEquals(OptionalLong.empty(), snapshot(f).foodNutritionReceived());
		}
		Fixture sum = fixture(false);
		restoreEvents(sum, 0, List.of(
			event(1, 0, sum.person(), sum.camp(), EventType.SHARE, "camp-storage", flow(ResourceType.FISH, 1, Long.MAX_VALUE)),
			event(2, 0, sum.person(), sum.camp(), EventType.SHARE, "camp-storage", flow(ResourceType.FISH, 1, 1))));
		sum.state().recordFoodAllocation(sum.person(), 2);
		assertEquals(OptionalLong.empty(), snapshot(sum).foodNutritionReceived());
	}

	@Test
	void overflowingHistoricalUnitTotalIsUnknown() {
		Fixture f = fixture(false);
		restoreEvents(f, 0, List.of(
			event(1, 0, f.person(), f.camp(), EventType.SHARE, "camp-storage", flow(ResourceType.FISH, Long.MAX_VALUE, 1)),
			event(2, 0, f.person(), f.camp(), EventType.SHARE, "camp-storage", flow(ResourceType.FISH, 1, 1))));
		f.state().recordFoodAllocation(f.person(), 1);
		assertEquals(OptionalLong.empty(), snapshot(f).foodNutritionReceived());
	}

	@Test
	void queryingNextDayDoesNotResetPersistedAllocationsOrMutateAnything() {
		Fixture f = fixture(false);
		eat(f, ResourceType.WILD_FRUIT, 3);
		f.state().world().advanceDay();
		CompoundTag before = f.data().save(new CompoundTag(), null);
		f.data().setDirty(false);
		for (int i = 0; i < 10; i++) {
			assertEquals(0, snapshot(f).allocatedUnits());
			assertEquals(3, snapshot(f).remainingUnits());
			assertEquals(OptionalLong.of(0), snapshot(f).foodNutritionReceived());
		}
		assertFalse(f.data().isDirty());
		assertEquals(before, f.data().save(new CompoundTag(), null));
		CompoundTag simulation = f.state().save();
		assertEquals(0, simulation.getLong("FoodAllocationDay"));
		assertEquals(3, simulation.getList("FoodAllocations", ListTag.TAG_COMPOUND).getCompound(0).getInt("Units"));
	}

	@Test
	void careAllowanceUsesExistingActiveNormAndFeatureFlag() {
		Fixture child = fixture(true);
		child.state().setNormsEnabled(true);
		child.state().world().registerNorm(new Norm(Norm.CARE_FIRST_COMMON_STORE, child.camp(), 2,
			Set.of(), Set.of(), Set.of(), 100, Norm.Status.ACTIVE, List.of()));
		child.state().world().registerNorm(new Norm(Norm.NEED_FIRST_COMMON_STORE, child.camp(), 4,
			Set.of(), Set.of(), Set.of(), 100, Norm.Status.ACTIVE, List.of()));
		assertEquals(6, snapshot(child).dailyUnitCap());
		assertEquals(6, snapshot(child).remainingUnits());
		child.state().setNormsEnabled(false);
		assertEquals(3, snapshot(child).dailyUnitCap());
	}

	@Test
	void twoSaveLoadCyclesPreserveSnapshotsAndReadOnlyQueries() {
		Fixture original = fixture(false);
		eat(original, ResourceType.WILD_FRUIT, 1);
		eat(original, ResourceType.GAME, 2);
		List<SettlementRations.Snapshot> expected = SettlementRations.inspect(original.data(), original.settlement());
		SettlementSavedData data = original.data();
		for (int i = 0; i < 2; i++) {
			data = SettlementSavedData.load(data.save(new CompoundTag(), null), null);
			Settlement settlement = data.find(BlockPos.ZERO).orElseThrow();
			CompoundTag before = data.save(new CompoundTag(), null);
			data.setDirty(false);
			assertEquals(expected, SettlementRations.inspect(data, settlement));
			assertFalse(data.isDirty());
			assertEquals(before, data.save(new CompoundTag(), null));
		}
	}

	@Test
	void unmappedOrFormerMembersAreExcludedAndSnapshotsAreSortedImmutableValues() {
		Fixture f = fixture(false);
		UUID earlier = new UUID(0, 1);
		UUID former = new UUID(0, 2);
		f.data().registerMember(f.settlement(), earlier, 0);
		f.state().mapResident(earlier, false, 50);
		f.state().mapResident(former, false, 50);
		f.data().registerMember(f.settlement(), new UUID(0, 3), 0);
		List<SettlementRations.Snapshot> snapshots = SettlementRations.inspect(f.data(), f.settlement());
		assertEquals(List.of(earlier, f.resident()), snapshots.stream().map(SettlementRations.Snapshot::villagerId).toList());
		assertThrows(UnsupportedOperationException.class, () -> snapshots.clear());
		f.state().world().person(f.person()).setHunger(1);
		assertEquals(100, snapshots.getLast().hunger());
		f.data().removeMember(f.resident());
		assertEquals(List.of(earlier), SettlementRations.inspect(f.data(), f.settlement()).stream()
			.map(SettlementRations.Snapshot::villagerId).toList());
	}

	@Test
	void noSimulationIsCreatedForARead() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Unmapped", 0);
		data.setDirty(false);
		assertEquals(List.of(), SettlementRations.inspect(data, settlement));
		assertTrue(data.simulationOf(settlement).isEmpty());
		assertFalse(data.isDirty());
	}

	private static Fixture fixture(boolean child) {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Rations", 0);
		UUID resident = new UUID(1, 2);
		data.registerMember(settlement, resident, 0);
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		state.mapResident(resident, child, 0);
		state.setSurvivalEnabled(true, 0);
		return new Fixture(data, settlement, state, resident);
	}

	private static void eat(Fixture f, ResourceType type, int quantity) {
		f.state().world().importExternalBatch(type, quantity, f.state().campOwner());
		SettlementSimulationService.eat(f.data(), f.settlement(), f.resident());
	}

	private static SettlementRations.Snapshot snapshot(Fixture f) {
		return SettlementRations.inspect(f.data(), f.settlement()).getFirst();
	}

	// Restored event-only fixtures isolate malformed/legacy history without inventing live stock.
	private static void restoreEvents(Fixture f, long day, List<SimulationEvent> events) {
		f.state().world().restoreState(day, 1, List.of(), events);
	}

	private static ResourceFlow flow(ResourceType type, long quantity, long nutrition) {
		return new ResourceFlow(null, type, quantity, nutrition, null, "household:test");
	}

	private static SimulationEvent event(long id, long day, String person, String camp, EventType type,
		String allocation, ResourceFlow output) {
		return new SimulationEvent(id, day, type, List.of(person, camp), List.of(), List.of(output),
			Map.of("allocation", allocation), List.of());
	}

	private record Fixture(SettlementSavedData data, Settlement settlement, SettlementSimulationState state, UUID resident) {
		String person() { return state.residentId(resident).orElseThrow(); }
		String camp() { return state.campOwner().id(); }
	}
}
