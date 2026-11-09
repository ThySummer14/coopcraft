package com.thysummer.coopcraft.civilization;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Standard synchronous container handoffs only; no game, entity or navigation execution. */
class SettlementAgedFoodCargoTest {
	private static final Settlement CAMP = new Settlement(new UUID(680, 1), BlockPos.ZERO, "Berry intake", 0);
	private static final UUID RESIDENT = new UUID(680, 2);

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void arrivalImportsOnlyIncomingDeltasAndPreservesExistingIdenticalChestFood() {
		var state = stateAt(2);
		state.mapResident(RESIDENT, false, 10);
		state.setSkillsEnabled(true); state.setNormsEnabled(true);
		CompoundTag residentBefore = state.save().getList("Residents", Tag.TAG_COMPOUND).getCompound(0).copy();
		TrackingContainer bag = tracked(marked(state, 5, 0));
		TrackingContainer store = tracked(marked(state, 61, 0));
		List<ItemStack> boxBefore = snapshot(store);
		assertTrue(SettlementAgedFoodCargo.hasDeliverable(state, bag));
		assertEquals(3, SettlementAgedFoodCargo.deliver(state, bag, store));
		assertStack(marked(state, 2, 0), bag.getItem(0));
		assertStacks(boxBefore, store); assertEquals(0, store.writes);
		assertEquals(3, stock(state));
		var batch = state.world().batches().getFirst();
		assertEquals(0, batch.harvestDay()); assertEquals(3, batch.expiryDay());
		assertEquals(state.campOwner(), batch.holder());
		var event = state.world().ledger().events().getFirst();
		assertEquals(EventType.EXTERNAL_IMPORT, event.type()); assertEquals(2, event.day());
		assertTrue(event.actors().isEmpty()); assertTrue(event.causalLinks().isEmpty());
		assertEquals(Map.of("activity", "cargo_delivery", "holder", state.campOwner().toString()), event.context());
		assertEquals(residentBefore, state.save().getList("Residents", Tag.TAG_COMPOUND).getCompound(0));
		assertEquals(66, stock(state) + bag.countItem(Items.SWEET_BERRIES) + store.countItem(Items.SWEET_BERRIES));
		state.world().assertConservation();
	}

	@Test void mixedAgesShareOneTripBudgetAndRetainTheirOwnDates() {
		var state = stateAt(3);
		TrackingContainer bag = tracked(marked(state, 5, 0), marked(state, 6, 2), marked(state, 4, 0));
		TrackingContainer store = tracked(marked(state, 63, 0), marked(state, 62, 2));
		List<ItemStack> boxBefore = snapshot(store);
		assertEquals(3, SettlementAgedFoodCargo.deliver(state, bag, store));
		assertStack(marked(state, 4, 0), bag.getItem(0));
		assertStack(marked(state, 4, 2), bag.getItem(1));
		assertStack(marked(state, 4, 0), bag.getItem(2));
		assertStacks(boxBefore, store); assertEquals(0, store.writes);
		var batches = state.world().batches(); assertEquals(2, batches.size());
		assertEquals(1, batches.get(0).quantity()); assertEquals(0, batches.get(0).harvestDay());
		assertEquals(3, batches.get(0).expiryDay());
		assertEquals(2, batches.get(1).quantity()); assertEquals(2, batches.get(1).harvestDay());
		assertEquals(5, batches.get(1).expiryDay()); state.world().assertConservation();
	}

	@Test void sharedEmptySlotCannotBeReusedForLaterSourcesEvenOfTheSameAge() {
		var state = stateAt(2);
		SimpleContainer bag = new SimpleContainer(marked(state, 3, 1), marked(state, 3, 1), marked(state, 3, 2));
		TrackingContainer store = new TrackingContainer(1) {
			@Override public int getMaxStackSize() { return 4; }
		};
		assertEquals(4, SettlementAgedFoodCargo.deliver(state, bag, store));
		assertTrue(bag.getItem(0).isEmpty()); assertStack(marked(state, 2, 1), bag.getItem(1));
		assertStack(marked(state, 3, 2), bag.getItem(2));
		assertTrue(store.isEmpty()); assertEquals(0, store.writes); assertEquals(4, stock(state));
		assertEquals(List.of(3L, 1L), state.world().batches().stream().map(batch -> batch.quantity()).toList());
		state.world().assertConservation();
	}

	@Test void aReservedEmptySlotRetainsItsAgeForTheRestOfTheTrip() {
		var state = stateAt(2);
		SimpleContainer bag = new SimpleContainer(marked(state, 2, 0), marked(state, 3, 2));
		SimpleContainer store = new SimpleContainer(1);
		assertEquals(2, SettlementAgedFoodCargo.deliver(state, bag, store));
		assertTrue(bag.getItem(0).isEmpty()); assertStack(marked(state, 3, 2), bag.getItem(1));
		assertTrue(store.isEmpty()); assertEquals(2, stock(state));
		// A separate arrival may use that space again, but can only debit the remaining real cargo.
		assertEquals(3, SettlementAgedFoodCargo.deliver(state, bag, store));
		CompoundTag before = state.save();
		for (int repeat = 0; repeat < 3; repeat++) {
			assertFalse(SettlementAgedFoodCargo.hasDeliverable(state, bag));
			assertEquals(0, SettlementAgedFoodCargo.deliver(state, bag, store));
			assertEquals(before, state.save());
		}
		assertEquals(5, stock(state)); assertTrue(store.isEmpty()); state.world().assertConservation();
	}

	@Test void oneSourceMaySpanMultipleReceivingSlotsButCreatesOnlyOneBatch() {
		var state = stateAt(1);
		SimpleContainer bag = new SimpleContainer(marked(state, 8, 0));
		TrackingContainer store = new TrackingContainer(3) {
			@Override public int getMaxStackSize() { return 3; }
			@Override public boolean canPlaceItem(int slot, ItemStack stack) { return slot != 1; }
		};
		assertEquals(6, SettlementAgedFoodCargo.deliver(state, bag, store));
		assertStack(marked(state, 2, 0), bag.getItem(0));
		assertEquals(1, state.world().batches().size()); assertEquals(6, stock(state));
		assertTrue(store.isEmpty()); assertEquals(0, store.writes); state.world().assertConservation();
	}

	@Test void fullOrRefusingChestCreatesNoEventsAndDoesNotCallTheFinalGate() {
		for (TrackingContainer store : List.of(tracked(new ItemStack(Items.DIRT, 64)), new TrackingContainer(1) {
			@Override public boolean canPlaceItem(int slot, ItemStack stack) { return false; }
		}, new TrackingContainer(0))) {
			var state = stateAt(0); TrackingContainer bag = tracked(marked(state, 5, 0));
			CompoundTag before = state.save(); List<ItemStack> boxBefore = snapshot(store);
			assertTrue(SettlementAgedFoodCargo.hasDeliverable(state, bag));
			assertEquals(0, SettlementAgedFoodCargo.deliver(state, bag, store, () -> {
				fail("No accepted cargo should reach the commit gate"); return true;
			}));
			assertStack(marked(state, 5, 0), bag.getItem(0)); assertStacks(boxBefore, store);
			assertEquals(before, state.save()); assertEquals(0, bag.writes); assertEquals(0, store.writes);
		}
	}

	@Test void slotPlacementAndFullComponentsAreRequiredBeforeCountingCapacity() {
		var state = stateAt(1); ItemStack named = marked(state, 60, 0);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Unrelated existing food"));
		TrackingContainer store = tracked(named, marked(state, 63, 1));
		TrackingContainer bag = tracked(marked(state, 4, 0), marked(state, 4, 1));
		List<ItemStack> boxBefore = snapshot(store);
		assertEquals(1, SettlementAgedFoodCargo.deliver(state, bag, store));
		assertStack(marked(state, 4, 0), bag.getItem(0)); assertStack(marked(state, 3, 1), bag.getItem(1));
		assertStacks(boxBefore, store); assertEquals(1, stock(state)); assertEquals(0, store.writes);
	}

	@Test void itemStackLimitStillBoundsAnOversizedContainerLimit() {
		var state = stateAt(0);
		SimpleContainer bag = new SimpleContainer(marked(state, 64, 0), marked(state, 64, 0));
		TrackingContainer store = new TrackingContainer(1) {
			@Override public int getMaxStackSize() { return 1024; }
		};
		assertEquals(64, SettlementAgedFoodCargo.deliver(state, bag, store));
		assertTrue(bag.getItem(0).isEmpty()); assertStack(marked(state, 64, 0), bag.getItem(1));
		assertTrue(store.isEmpty()); assertEquals(0, store.writes); state.world().assertConservation();
	}

	@Test void ordinaryForeignExpiredFutureUnknownCorruptAndOtherFoodRemainUntouched() {
		var state = stateAt(4);
		List<ItemStack> invalid = new ArrayList<>(List.of(new ItemStack(Items.SWEET_BERRIES, 3),
			SettlementFoodAge.stamp(new ItemStack(Items.SWEET_BERRIES, 3), new UUID(680, 99), 4),
			marked(state, 3, 0), marked(state, 3, 5),
			mutateAge(marked(state, 3, 4), age -> age.putInt("Format", 2)),
			mutateAge(marked(state, 3, 4), age -> age.putString("Resource", "TUBERS")),
			mutateAge(marked(state, 3, 4), age -> age.remove("ClockId")),
			mutateAge(marked(state, 3, 4), age -> age.putLong("ExpiryDay", 100))));
		ItemStack unknown = new ItemStack(Items.SWEET_BERRIES, 3);
		CompoundTag tag = new CompoundTag(); tag.putString("coopcraft_food_age", "unknown");
		unknown.set(DataComponents.CUSTOM_DATA, CustomData.of(tag)); invalid.add(unknown);
		ItemStack named = marked(state, 3, 4); named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep")); invalid.add(named);
		for (var item : List.of(Items.POTATO, Items.COD, Items.BEEF)) {
			invalid.add(SettlementFoodAge.stamp(new ItemStack(item, 3), state.foodClockId().orElseThrow(), 4));
		}
		for (ItemStack stack : invalid) {
			TrackingContainer bag = tracked(stack.copy()); TrackingContainer store = new TrackingContainer(2);
			CompoundTag before = state.save();
			assertFalse(SettlementAgedFoodCargo.hasDeliverable(state, bag));
			assertEquals(0, SettlementAgedFoodCargo.deliver(state, bag, store));
			assertStack(stack, bag.getItem(0)); assertTrue(store.isEmpty()); assertEquals(before, state.save());
			assertEquals(0, bag.writes); assertEquals(0, store.writes);
		}
		invalid.add(5, marked(state, 2, 4));
		SimpleContainer bag = new SimpleContainer(invalid.toArray(ItemStack[]::new));
		assertTrue(SettlementAgedFoodCargo.hasDeliverable(state, bag));
		assertEquals(2, SettlementAgedFoodCargo.deliver(state, bag, new SimpleContainer(20)));
		for (int slot = 0; slot < invalid.size(); slot++) {
			if (slot == 5) assertTrue(bag.getItem(slot).isEmpty()); else assertStack(invalid.get(slot), bag.getItem(slot));
		}
		assertEquals(2, stock(state)); state.world().assertConservation();
	}

	@Test void currentObservedDayControlsBothThePredicateAndTheCommitIncludingExpiryDay() {
		var state = stateAt(3); SimpleContainer store = new SimpleContainer(2);
		SimpleContainer expiredSoon = new SimpleContainer(marked(state, 2, 0));
		assertTrue(SettlementAgedFoodCargo.hasDeliverable(state, expiredSoon));
		assertEquals(2, SettlementAgedFoodCargo.deliver(state, expiredSoon, store));
		SimpleContainer carried = new SimpleContainer(marked(state, 2, 0));
		assertTrue(SettlementAgedFoodCargo.hasDeliverable(state, carried)); state.world().advanceDay();
		assertFalse(SettlementAgedFoodCargo.hasDeliverable(state, carried));
		assertEquals(0, SettlementAgedFoodCargo.deliver(state, carried, store));
		assertStack(marked(state, 2, 0), carried.getItem(0));
		SimpleContainer future = new SimpleContainer(marked(state, 3, 5));
		assertFalse(SettlementAgedFoodCargo.hasDeliverable(state, future)); state.world().advanceDay();
		assertTrue(SettlementAgedFoodCargo.hasDeliverable(state, future));
		assertEquals(3, SettlementAgedFoodCargo.deliver(state, future, store));
		assertEquals(5, state.world().batches().getLast().harvestDay()); state.world().assertConservation();
	}

	@Test void disabledModesNullInputsAndSameContainerCannotReadOrMutateCargo() {
		var state = stateAt(0);
		SimpleContainer unreadable = new SimpleContainer(1) {
			@Override public ItemStack getItem(int slot) { throw new AssertionError("Must not inspect disabled cargo"); }
		};
		assertFalse(SettlementAgedFoodCargo.hasDeliverable(null, unreadable));
		assertEquals(0, SettlementAgedFoodCargo.deliver(null, unreadable, unreadable));
		assertFalse(SettlementAgedFoodCargo.hasDeliverable(state, null));
		assertEquals(0, SettlementAgedFoodCargo.deliver(state, null, unreadable));
		assertEquals(0, SettlementAgedFoodCargo.deliver(state, unreadable, null));
		assertEquals(0, SettlementAgedFoodCargo.deliver(state, unreadable, unreadable));
		assertEquals(0, SettlementAgedFoodCargo.deliver(state, unreadable, new SimpleContainer(1), null));
		for (boolean survival : List.of(false, true)) for (boolean production : List.of(false, true)) {
			if (survival && production) continue;
			state.setSurvivalEnabled(survival, 0); state.setProductionEnabled(production);
			assertFalse(SettlementAgedFoodCargo.hasDeliverable(state, unreadable));
			assertEquals(0, SettlementAgedFoodCargo.deliver(state, unreadable, new SimpleContainer(1)));
		}
		state.setSurvivalEnabled(true, 0); state.setProductionEnabled(true);
		SimpleContainer bag = new SimpleContainer(marked(state, 2, 0));
		assertTrue(SettlementAgedFoodCargo.hasDeliverable(state, bag));
		assertEquals(2, SettlementAgedFoodCargo.deliver(state, bag, new SimpleContainer(1)));
	}

	@Test void malformedClockIsNeverRepairedAndCannotImportTheFormerClockCargo() {
		var valid = stateAt(1); ItemStack cargo = marked(valid, 3, 0); CompoundTag saved = valid.save();
		saved.putString("FoodClockId", "bad clock");
		var state = SettlementSimulationState.load(CAMP, saved);
		TrackingContainer bag = tracked(cargo); TrackingContainer store = new TrackingContainer(1);
		assertTrue(state.foodClockId().isEmpty());
		assertFalse(SettlementAgedFoodCargo.hasDeliverable(state, bag));
		assertEquals(0, SettlementAgedFoodCargo.deliver(state, bag, store));
		assertEquals(saved, state.save()); assertStack(cargo, bag.getItem(0));
		assertEquals(0, bag.writes); assertEquals(0, store.writes);
	}

	@Test void authorizationFailureOrExceptionOccursBeforeAnyOwnWrites() {
		var state = stateAt(0); TrackingContainer bag = tracked(marked(state, 3, 0));
		TrackingContainer store = new TrackingContainer(1); CompoundTag before = state.save();
		assertEquals(0, SettlementAgedFoodCargo.deliver(state, bag, store, () -> {
			assertEquals(before, state.save()); assertStack(marked(state, 3, 0), bag.getItem(0));
			assertTrue(store.isEmpty()); return false;
		}));
		assertThrows(IllegalStateException.class, () -> SettlementAgedFoodCargo.deliver(state, bag, store,
			() -> { throw new IllegalStateException("Authorization unavailable"); }));
		assertEquals(before, state.save()); assertStack(marked(state, 3, 0), bag.getItem(0));
		assertTrue(store.isEmpty()); assertEquals(0, bag.writes); assertEquals(0, store.writes);
	}

	@Test void finalAuthorizationSeesTheCompletePlanAndRunsOnlyOnceBeforeSuccessfulCommit() {
		var state = stateAt(1); TrackingContainer bag = tracked(marked(state, 2, 0), marked(state, 3, 1));
		int[] placements = {0}, gates = {0};
		TrackingContainer store = new TrackingContainer(2) {
			@Override public boolean canPlaceItem(int slot, ItemStack stack) { placements[0]++; return true; }
		};
		assertEquals(5, SettlementAgedFoodCargo.deliver(state, bag, store, () -> {
			gates[0]++;
			assertEquals(3, placements[0]); assertEquals(0, bag.writes); assertEquals(0, store.writes);
			assertEquals(5, bag.countItem(Items.SWEET_BERRIES)); assertTrue(store.isEmpty());
			assertTrue(state.world().batches().isEmpty()); assertTrue(state.world().ledger().events().isEmpty());
			return true;
		}));
		assertEquals(1, gates[0]); assertEquals(3, placements[0]);
		assertTrue(bag.isEmpty()); assertTrue(store.isEmpty()); assertEquals(0, store.writes);
		assertEquals(5, stock(state)); state.world().assertConservation();
	}

	@Test void changesToCoreStockDuringFinalAuthorizationDoNotConsumeThePlannedBagCargo() {
		var state = stateAt(0); TrackingContainer bag = tracked(marked(state, 3, 0));
		TrackingContainer store = new TrackingContainer(1);
		assertEquals(0, SettlementAgedFoodCargo.deliver(state, bag, store, () -> {
			state.world().importExternalBatch(ResourceType.WOOD, 2, state.campOwner()); return true;
		}));
		assertStack(marked(state, 3, 0), bag.getItem(0)); assertTrue(store.isEmpty());
		assertEquals(0, bag.writes); assertEquals(0, store.writes); assertEquals(0, stock(state));
		assertEquals(1, state.world().ledger().events().size());
		assertEquals(ResourceType.WOOD, state.world().batches().getFirst().resourceType());
		state.world().assertConservation();
	}

	@Test void modeDayOrInventoryChangesDuringPlanningOrFinalAuthorizationInvalidateThePlan() {
		for (boolean atGate : List.of(false, true)) for (int change = 0; change < 6; change++) {
			var state = stateAt(1); TrackingContainer bag = tracked(marked(state, 3, 0));
			final int mutation = change; final TrackingContainer[] storeRef = new TrackingContainer[1];
			Runnable mutate = () -> {
				switch (mutation) {
					case 0 -> state.setProductionEnabled(false);
					case 1 -> { state.setProductionEnabled(false); state.setProductionEnabled(true); }
					case 2 -> { state.setSurvivalEnabled(false, 0); state.setSurvivalEnabled(true, 0); }
					case 3 -> state.world().advanceDay();
					case 4 -> bag.setItem(0, new ItemStack(Items.DIRT, 2));
					case 5 -> storeRef[0].setItem(0, new ItemStack(Items.DIRT, 2));
				}
			};
			TrackingContainer store = new TrackingContainer(1) {
				@Override public boolean canPlaceItem(int slot, ItemStack stack) {
					if (!atGate) mutate.run(); return true;
				}
			};
			storeRef[0] = store;
			assertEquals(0, SettlementAgedFoodCargo.deliver(state, bag, store, () -> {
				if (atGate) mutate.run(); return true;
			}));
			assertTrue(state.world().batches().isEmpty()); assertTrue(state.world().ledger().events().isEmpty());
			assertStack(mutation == 4 ? new ItemStack(Items.DIRT, 2) : marked(state, 3, 0), bag.getItem(0));
			assertStack(mutation == 5 ? new ItemStack(Items.DIRT, 2) : ItemStack.EMPTY, store.getItem(0));
			// The only writes belong to the deliberately mutating test callback, never the handoff.
			assertEquals(mutation == 4 ? 1 : 0, bag.writes); assertEquals(mutation == 5 ? 1 : 0, store.writes);
		}
	}

	@Test void aLaterPlacementExceptionCannotPartiallyCommitEarlierAcceptedCargo() {
		var state = stateAt(1); TrackingContainer bag = tracked(marked(state, 2, 0), marked(state, 3, 1));
		TrackingContainer store = new TrackingContainer(2) {
			@Override public boolean canPlaceItem(int slot, ItemStack stack) {
				if (SettlementFoodAge.read(stack).orElseThrow().harvestDay() == 1) throw new IllegalStateException("No capacity result");
				return true;
			}
		};
		CompoundTag before = state.save(); List<ItemStack> bagBefore = snapshot(bag);
		assertThrows(IllegalStateException.class, () -> SettlementAgedFoodCargo.deliver(state, bag, store));
		assertEquals(before, state.save()); assertStacks(bagBefore, bag);
		assertTrue(store.isEmpty()); assertEquals(0, bag.writes); assertEquals(0, store.writes);
	}

	@Test void entireBatchIdBudgetIsValidatedBeforeAnySlotIsDebited() {
		var original = stateAt(1); CompoundTag saved = original.save();
		saved.putLong("NextBatchId", Long.MAX_VALUE - 1);
		var state = SettlementSimulationState.load(CAMP, saved);
		TrackingContainer bag = tracked(marked(state, 2, 0), marked(state, 3, 1));
		TrackingContainer store = new TrackingContainer(2); List<ItemStack> bagBefore = snapshot(bag);
		assertThrows(IllegalArgumentException.class, () -> SettlementAgedFoodCargo.deliver(state, bag, store, () -> {
			fail("The entire ID plan must be validated before final authorization"); return true;
		}));
		assertEquals(saved, state.save()); assertStacks(bagBefore, bag);
		assertEquals(0, bag.writes); assertEquals(0, store.writes); assertTrue(store.isEmpty());
		// One actual incoming batch still fits. A full whole-trip plan must not silently truncate for IDs.
		assertEquals(2, SettlementAgedFoodCargo.deliver(state, bag, new SimpleContainer(1)));
		assertEquals("batch-" + (Long.MAX_VALUE - 1), state.world().batches().getFirst().batchId());
		CompoundTag before = state.save(); bagBefore = snapshot(bag); bag.writes = 0;
		assertThrows(IllegalArgumentException.class, () -> SettlementAgedFoodCargo.deliver(state, bag, store));
		assertEquals(before, state.save()); assertStacks(bagBefore, bag);
		assertEquals(0, bag.writes); assertEquals(0, store.writes); state.world().assertConservation();
	}

	@Test void aggregateEventIdOverflowFailsBeforeTheFirstBatchOrBagWrite() throws ReflectiveOperationException {
		var state = stateAt(1); var ledger = state.world().ledger();
		// Reaching this boundary naturally would need Long.MAX_VALUE events. Keep the sentinel and
		// counter consistent with each other so per-batch validation passes but the full plan cannot.
		Field eventsField = ledger.getClass().getDeclaredField("events"); eventsField.setAccessible(true);
		@SuppressWarnings("unchecked") List<SimulationEvent> events = (List<SimulationEvent>) eventsField.get(ledger);
		events.add(new SimulationEvent(Long.MAX_VALUE - 2, 1, EventType.STORE,
			List.of(), List.of(), List.of(), Map.of(), List.of()));
		Field nextId = ledger.getClass().getDeclaredField("nextEventId"); nextId.setAccessible(true);
		nextId.setLong(ledger, Long.MAX_VALUE - 1);
		TrackingContainer bag = tracked(marked(state, 2, 0), marked(state, 3, 1));
		TrackingContainer store = new TrackingContainer(2); List<ItemStack> beforeBag = snapshot(bag);
		CompoundTag before = state.save();
		assertThrows(IllegalArgumentException.class, () -> SettlementAgedFoodCargo.deliver(state, bag, store));
		assertEquals(before, state.save()); assertStacks(beforeBag, bag); assertTrue(store.isEmpty());
		assertEquals(Long.MAX_VALUE - 1, nextId.getLong(ledger));
		assertEquals(0, bag.writes); assertEquals(0, store.writes); assertTrue(state.world().batches().isEmpty());
	}

	@Test void invalidCoreOwnerFailsBeforeAnyContainerWrite() throws ReflectiveOperationException {
		var state = stateAt(0); Field camps = state.world().getClass().getDeclaredField("camps");
		camps.setAccessible(true); ((Map<?, ?>) camps.get(state.world())).clear();
		TrackingContainer bag = tracked(marked(state, 3, 0)); TrackingContainer store = new TrackingContainer(1);
		assertThrows(IllegalArgumentException.class, () -> SettlementAgedFoodCargo.deliver(state, bag, store));
		assertStack(marked(state, 3, 0), bag.getItem(0)); assertTrue(store.isEmpty());
		assertTrue(state.world().batches().isEmpty()); assertTrue(state.world().ledger().events().isEmpty());
		assertEquals(0, bag.writes); assertEquals(0, store.writes);
	}

	@Test void saturatedExpiryAndRepeatedReloadsPreserveAcceptedQuantitiesAndAges() {
		var state = stateAt(Long.MAX_VALUE);
		SimpleContainer bag = new SimpleContainer(marked(state, 4, Long.MAX_VALUE));
		SimpleContainer store = new SimpleContainer(1);
		assertEquals(4, SettlementAgedFoodCargo.deliver(state, bag, store));
		for (int reload = 0; reload < 3; reload++) {
			CompoundTag saved = state.save(); state = SettlementSimulationState.load(CAMP, saved);
			assertEquals(saved, state.save()); assertEquals(4, stock(state));
			var batch = state.world().batches().getFirst();
			assertEquals(Long.MAX_VALUE, batch.harvestDay()); assertEquals(Long.MAX_VALUE, batch.expiryDay());
			assertEquals(0, SettlementAgedFoodCargo.deliver(state, bag, store));
			assertEquals(saved, state.save()); assertTrue(store.isEmpty()); state.world().assertConservation();
		}
	}

	private static SettlementSimulationState stateAt(long day) {
		var state = SettlementSimulationState.create(CAMP);
		state.world().restoreState(day, 1, List.of(), List.of());
		state.setSurvivalEnabled(true, day); state.setProductionEnabled(true);
		return state;
	}

	private static ItemStack marked(SettlementSimulationState state, int count, long day) {
		return SettlementFoodAge.stamp(new ItemStack(Items.SWEET_BERRIES, count), state.foodClockId().orElseThrow(), day);
	}

	private static ItemStack mutateAge(ItemStack stack, Consumer<CompoundTag> mutation) {
		CompoundTag data = stack.get(DataComponents.CUSTOM_DATA).copyTag();
		mutation.accept(data.getCompound("coopcraft_food_age"));
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data)); return stack;
	}

	private static long stock(SettlementSimulationState state) {
		return state.world().inventoryTotals().getOrDefault(ResourceType.WILD_FRUIT, 0L);
	}

	private static List<ItemStack> snapshot(Container container) {
		List<ItemStack> stacks = new ArrayList<>();
		for (int slot = 0; slot < container.getContainerSize(); slot++) stacks.add(container.getItem(slot).copy());
		return stacks;
	}

	private static void assertStacks(List<ItemStack> expected, Container actual) {
		assertEquals(expected.size(), actual.getContainerSize());
		for (int slot = 0; slot < expected.size(); slot++) assertStack(expected.get(slot), actual.getItem(slot));
	}

	private static void assertStack(ItemStack expected, ItemStack actual) {
		assertTrue(ItemStack.matches(expected, actual), () -> "Expected " + expected + " but was " + actual);
	}

	private static TrackingContainer tracked(ItemStack... stacks) {
		TrackingContainer container = new TrackingContainer(stacks.length);
		for (int slot = 0; slot < stacks.length; slot++) container.setItem(slot, stacks[slot]);
		container.writes = 0; return container;
	}

	private static class TrackingContainer extends SimpleContainer {
		int writes;
		TrackingContainer(int slots) { super(slots); }
		@Override public void setItem(int slot, ItemStack stack) { writes++; super.setItem(slot, stack); }
	}
}
