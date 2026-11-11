package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.StorageOwner;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Calls the same observed-day service as the server; no ServerLevel or game is started. */
class SettlementObservedSpoilageTest {
	static final UUID ACTOR = new UUID(58, 1);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	static class Fixture {
		final SettlementSavedData data;
		final Settlement camp;
		final SettlementSimulationState state;
		Fixture() {
			data = new SettlementSavedData(); camp = data.getOrCreate(BlockPos.ZERO, "Expiry", 0);
			data.registerMember(camp, ACTOR, 0); state = data.getOrCreateSimulation(camp);
			state.mapResident(ACTOR, false, 50); state.setSurvivalEnabled(true, 0);
		}
		Fixture(CompoundTag saved) {
			data = SettlementSavedData.load(saved, null); camp = data.find(BlockPos.ZERO).orElseThrow();
			state = data.simulationOf(camp).orElseThrow();
		}
		Person person() { return state.world().person(state.residentId(ACTOR).orElseThrow()); }
		boolean close(long gameDay) { return SettlementSimulationService.closeObservedDay(data, camp, state, gameDay, Map.of(ACTOR, person())); }
		CompoundTag save() { return data.save(new CompoundTag(), null); }
		long spoilEvents() { return state.world().ledger().events().stream().filter(e -> e.type() == EventType.SPOIL).count(); }
	}

	@Test void activeObservedDaySettlesExpiryOnceWithoutDestroyingUnexpiredOrMaterialStock() {
		Fixture f = new Fixture(); var world = f.state.world();
		var fruit = world.importExternalBatch(ResourceType.WILD_FRUIT, 2, f.state.campOwner());
		world.importExternalBatch(ResourceType.TUBERS, 3, f.state.campOwner());
		world.importExternalBatch(ResourceType.WOOD, 4, f.state.campOwner());
		for (int day = 1; day <= 3; day++) assertTrue(f.close(day));
		assertEquals(108, world.availableNutrition(f.state.campOwner())); assertEquals(0, f.spoilEvents());
		assertTrue(f.close(4)); assertEquals(72, world.availableNutrition(f.state.campOwner()));
		assertEquals(0L, world.inventoryTotals().getOrDefault(ResourceType.WILD_FRUIT, 0L));
		assertEquals(4L, world.inventoryTotals().getOrDefault(ResourceType.WOOD, 0L)); assertEquals(1, f.spoilEvents());
		var spoil = world.ledger().events().stream().filter(e -> e.type() == EventType.SPOIL).findFirst().orElseThrow();
		assertEquals(List.of(fruit.lastEventId()), spoil.causalLinks()); assertEquals(2, spoil.inputs().getFirst().quantity());
		var saved = f.save(); assertFalse(f.close(4)); assertEquals(saved, f.save());
		Fixture loaded = new Fixture(saved); assertFalse(loaded.close(4)); assertEquals(1, loaded.spoilEvents());
		loaded.close(5); assertEquals(1, loaded.spoilEvents()); loaded.state.world().assertConservation();
	}

	@Test void missingGameDaysAndInactiveOrDisabledSettlementsDoNotInventElapsedSimulationDays() {
		Fixture f = new Fixture(); var world = f.state.world(); world.importExternalBatch(ResourceType.FISH, 2, f.state.campOwner());
		assertTrue(SettlementSimulationService.closeObservedDay(f.data, f.camp, f.state, 1000, Map.of()));
		assertEquals(0, world.day()); assertEquals(40, world.availableNutrition(f.state.campOwner()));
		assertTrue(f.close(2000)); assertEquals(1, world.day()); assertEquals(0, f.spoilEvents());
		f.state.setSurvivalEnabled(false, 2000); var before = f.save();
		assertFalse(f.close(3000)); assertEquals(before, f.save());
		f.state.setSurvivalEnabled(true, 3000); assertTrue(f.close(4000)); assertEquals(2, world.day());
		assertEquals(40, world.availableNutrition(f.state.campOwner())); assertTrue(f.close(5000));
		assertEquals(0, world.availableNutrition(f.state.campOwner())); assertEquals(1, f.spoilEvents()); world.assertConservation();
	}

	@Test void restoredOverdueFoodIsUnavailableAndInedibleBeforeNextActiveSettlementTick() {
		Fixture f = new Fixture(); var world = f.state.world();
		world.importExternalBatch(ResourceType.WILD_FRUIT, 2, f.state.campOwner());
		var personal = world.importExternalBatch(ResourceType.WILD_FRUIT, 1, StorageOwner.household(f.person().householdId()));
		for (int i = 0; i < 4; i++) world.advanceDay(); // Model an old save whose live tick omitted spoilExpired.
		Fixture loaded = new Fixture(f.save()); var before = loaded.save();
		assertFalse(SettlementSimulationService.hasFood(loaded.data, loaded.camp));
		assertEquals(0, SettlementReserves.inspect(loaded.data, loaded.camp, null).current().orElseThrow());
		assertEquals(0, SettlementSimulationService.eat(loaded.data, loaded.camp, ACTOR));
		assertThrows(IllegalArgumentException.class, () -> loaded.state.world().consume(loaded.person().id(), personal.batchId(), 1, EventType.CONSUME));
		assertEquals(before, loaded.save()); assertEquals(0, loaded.spoilEvents());
		assertTrue(loaded.close(1)); assertEquals(2, loaded.spoilEvents()); loaded.state.world().assertConservation();
	}

	@Test void mealSkipsUnsettledExpiredBatchAndConsumesOnlyFreshFood() {
		Fixture f = new Fixture(); var world = f.state.world();
		var old = world.importExternalBatch(ResourceType.WILD_FRUIT, 5, f.state.campOwner());
		for (int i = 0; i < 4; i++) world.advanceDay();
		world.importExternalBatch(ResourceType.FISH, 2, f.state.campOwner()); f.person().setHunger(95);
		assertEquals(40, SettlementSimulationService.eat(f.data, f.camp, ACTOR));
		assertEquals(55, f.person().hunger()); assertEquals(2, f.state.allocatedFoodUnits(f.person().id()));
		assertEquals(5, world.batch(old.batchId()).quantity()); assertEquals(0, world.availableNutrition(f.state.campOwner()));
		world.assertConservation();
	}

	@Test void delayedFoodIntakeRetainsAgeThroughExistingSavedDataFormat() {
		Fixture f = new Fixture(); var world = f.state.world();
		for (int i = 0; i < 2; i++) world.advanceDay();
		var batch = world.importExternalFoodBatch(ResourceType.WILD_FRUIT, 7, f.state.campOwner(), 0,
			List.of(f.person().id()), Map.of("activity", "test-aged-intake"), List.of());
		Fixture loaded = new Fixture(f.save()); var restored = loaded.state.world().batch(batch.batchId());
		assertEquals(0, restored.harvestDay()); assertEquals(3, restored.expiryDay()); assertEquals(7, restored.quantity());
		assertEquals(2, loaded.state.world().ledger().event(restored.originEventId()).orElseThrow().day());
		assertEquals(f.save(), loaded.save()); assertTrue(loaded.close(1)); assertEquals(126, loaded.state.world().availableNutrition(loaded.state.campOwner()));
		assertTrue(loaded.close(2)); assertEquals(0, loaded.state.world().availableNutrition(loaded.state.campOwner()));
		assertEquals(1, loaded.spoilEvents()); loaded.state.world().assertConservation();
	}
}
