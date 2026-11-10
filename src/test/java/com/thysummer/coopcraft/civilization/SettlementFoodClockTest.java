package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.ResourceType;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettlementFoodClockTest {
	private static final UUID RESIDENT = new UUID(429, 1);

	@Test void sameCampIdentityInIndependentStatesNeverDefinesTheFoodClock() {
		Fixture first = fixture();
		Fixture second = fixture();
		assertEquals(first.settlement, second.settlement);
		assertEquals(first.state.campOwner(), second.state.campOwner());
		assertNotEquals(first.state.foodClockId().orElseThrow(), second.state.foodClockId().orElseThrow());
		SettlementSimulationState another = SettlementSimulationState.create(first.settlement);
		assertNotEquals(first.state.foodClockId(), another.foodClockId());
		assertNotEquals(second.state.foodClockId(), another.foodClockId());
		assertSame(first.state, first.data.getOrCreateSimulation(first.settlement));
	}

	@Test void clockAndExistingSimulationStateSurviveRepeatedNbtRoundTripsAtVersionNineteen() {
		Fixture original = fixture();
		UUID clock = original.state.foodClockId().orElseThrow();
		CompoundTag saved = original.save();
		assertEquals(21, saved.getInt("DataVersion"));
		assertTrue(simulation(saved).hasUUID("FoodClockId"));
		for (int i = 0; i < 3; i++) {
			Fixture restored = load(saved);
			assertEquals(clock, restored.state.foodClockId().orElseThrow());
			assertEquals(saved, restored.save());
			restored.state.world().assertConservation();
			saved = restored.save();
		}
	}

	@Test void oldSaveWithoutClockMigratesOnceWithoutResettingAnyOtherState() {
		CompoundTag legacy = fixture().save();
		simulation(legacy).remove("FoodClockId");
		Fixture migrated = load(legacy);
		UUID clock = migrated.state.foodClockId().orElseThrow();
		assertNotEquals(clock, load(legacy).state.foodClockId().orElseThrow());
		CompoundTag saved = migrated.save();
		assertEquals(21, saved.getInt("DataVersion"));
		assertEquals(clock, simulation(saved).getUUID("FoodClockId"));
		CompoundTag withoutClock = saved.copy(); simulation(withoutClock).remove("FoodClockId");
		assertEquals(legacy, withoutClock);
		assertEquals(clock, load(saved).state.foodClockId().orElseThrow());
		assertEquals(saved, load(saved).save());
	}

	@Test void presentCorruptClockFailsClosedAndRetainsTheExactRawTagAcrossReloads() {
		CompoundTag future = new CompoundTag();
		future.putString("Format", "future"); future.putLongArray("Clock", new long[] {12, 34});
		List<Tag> invalid = List.of(StringTag.valueOf("not a uuid"),
			StringTag.valueOf(new UUID(429, 2).toString()), IntTag.valueOf(0),
			new IntArrayTag(new int[0]), new IntArrayTag(new int[] {1, 2, 3}),
			new IntArrayTag(new int[] {1, 2, 3, 4, 5}), new LongArrayTag(new long[] {1, 2}),
			new ListTag(), future);
		for (Tag raw : invalid) {
			CompoundTag saved = fixture().save(); simulation(saved).put("FoodClockId", raw.copy());
			for (int i = 0; i < 3; i++) {
				Fixture restored = load(saved);
				assertTrue(restored.state.foodClockId().isEmpty(), raw::toString);
				assertEquals(raw, simulation(restored.save()).get("FoodClockId"));
				assertEquals(saved, restored.save());
				restored.state.world().assertConservation();
				saved = restored.save();
			}
		}
	}

	@Test void corruptMutableTagIsDetachedFromBothInputAndSavedOutput() {
		CompoundTag future = new CompoundTag(); future.putString("Future", "keep exactly");
		CompoundTag input = fixture().save(); simulation(input).put("FoodClockId", future);
		Fixture restored = load(input);
		Tag expected = future.copy();
		future.putString("Future", "mutated input");
		CompoundTag output = restored.save();
		assertEquals(expected, simulation(output).get("FoodClockId"));
		simulation(output).getCompound("FoodClockId").putString("Future", "mutated output");
		assertEquals(expected, simulation(restored.save()).get("FoodClockId"));
		assertTrue(restored.state.foodClockId().isEmpty());
	}

	@Test void everyModeToggleAndDayAdvancePreservesBothValidAndUnavailableClocks() {
		for (boolean corrupt : List.of(false, true)) {
			CompoundTag input = fixture().save();
			if (corrupt) simulation(input).putString("FoodClockId", "preserve invalid identity");
			Fixture f = load(input);
			var clock = f.state.foodClockId();
			Tag raw = simulation(input).get("FoodClockId").copy();
			for (boolean enabled : List.of(false, true, false, true)) {
				f.state.setProductionEnabled(enabled);
				f.state.setGrowthEnabled(enabled);
				f.state.setSkillsEnabled(enabled);
				f.state.setNormsEnabled(enabled);
				f.state.setSurvivalEnabled(enabled, 50);
				f.state.markSurvivalDay(51);
				f.state.world().advanceDay();
				assertEquals(clock, f.state.foodClockId());
				assertEquals(raw, simulation(f.save()).get("FoodClockId"));
			}
			assertEquals(clock, load(f.save()).state.foodClockId());
		}
	}

	private static Fixture fixture() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(new BlockPos(7, 64, 9), "Same camp across dimensions", 2);
		data.registerMember(settlement, RESIDENT, 3);
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		state.mapResident(RESIDENT, false, 40);
		state.setSurvivalEnabled(true, 42);
		state.setProductionEnabled(true);
		state.setGrowthEnabled(true);
		state.setSkillsEnabled(true);
		state.setNormsEnabled(true);
		state.world().advanceDay();
		state.world().advanceDay();
		state.world().importExternalBatch(ResourceType.TUBERS, 8, state.campOwner());
		state.recordFoodAllocation(state.residentId(RESIDENT).orElseThrow(), 2);
		return new Fixture(data, settlement, state);
	}

	private static Fixture load(CompoundTag saved) {
		SettlementSavedData data = SettlementSavedData.load(saved, null);
		Settlement settlement = data.find(new BlockPos(7, 64, 9)).orElseThrow();
		return new Fixture(data, settlement, data.simulationOf(settlement).orElseThrow());
	}

	private static CompoundTag simulation(CompoundTag saved) {
		return saved.getList("Simulations", Tag.TAG_COMPOUND).getCompound(0);
	}

	private record Fixture(SettlementSavedData data, Settlement settlement, SettlementSimulationState state) {
		CompoundTag save() { return data.save(new CompoundTag(), null); }
	}
}
