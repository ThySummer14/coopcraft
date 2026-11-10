package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure real-stack transactions, without a live server or entity simulation. */
class SettlementPersonalMealTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	static class Fixture {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(new BlockPos(0, 64, 0), "Meal", 0);
		final UUID actor = new UUID(91, 1);
		final SimpleContainer bag = new SimpleContainer(8), store = new SimpleContainer(8);
		boolean live = true;
		Fixture() { data.registerMember(camp, actor, 0); data.setSatiety(camp, actor, 35); bag.setItem(0, new ItemStack(Items.BREAD, 3)); }
		SettlementPersonalMeal.Ticket prepare() { return SettlementPersonalMeal.prepare(data, camp, actor, bag, () -> live); }
		boolean finish(SettlementPersonalMeal.Ticket t) { return SettlementPersonalMeal.finish(t, () -> true); }
		int satiety() { return data.needsOf(camp, actor).satiety(); }
	}
	@Test void noWarehouseOrPersonRequiredAndExactlyOneRealPieceRestoresExistingNutrition() {
		var f = new Fixture(); assertTrue(f.data.getStockpile(f.camp).isEmpty()); assertTrue(f.data.simulationOf(f.camp).isEmpty());
		ItemStack real = f.bag.getItem(0); var t = f.prepare(); assertNotNull(t);
		assertTrue(f.finish(t)); assertSame(real, f.bag.getItem(0)); assertEquals(2, real.getCount());
		assertEquals(SettlementNeeds.restoredSatiety(35, SettlementFood.points(Items.BREAD)), f.satiety());
		assertFalse(f.finish(t)); assertTrue(f.data.simulationOf(f.camp).isEmpty()); assertTrue(f.data.resourcesOf(f.camp).isEmpty());
	}
	@Test void emptyBagNeverFallsBackToPublicOrAbstractFood() {
		var f = new Fixture(); f.bag.clearContent(); f.store.setItem(0, new ItemStack(Items.BREAD, 64));
		assertNull(f.prepare()); assertEquals(64, f.store.countItem(Items.BREAD)); assertEquals(35, f.satiety());
		var state = f.data.getOrCreateSimulation(f.camp); var totals = state.world().inventoryTotals();
		assertNull(f.prepare()); assertEquals(totals, state.world().inventoryTotals());
	}
	@Test void allExistingOrdinaryFoodsUseExistingPointsAndFullnessBoundary() {
		for (var food : List.of(Items.BREAD, Items.CARROT, Items.POTATO, Items.BEETROOT, Items.SWEET_BERRIES)) {
			var f = new Fixture(); f.bag.setItem(0, new ItemStack(food, 2));
			assertTrue(f.finish(f.prepare())); assertEquals(SettlementNeeds.restoredSatiety(35, SettlementFood.points(food)), f.satiety());
			f.data.setSatiety(f.camp, f.actor, 94); assertTrue(f.finish(f.prepare())); assertTrue(f.satiety() >= 95);
			assertNull(f.prepare());
		}
		assertFalse(SettlementPersonalMeal.ordinary(new ItemStack(Items.POISONOUS_POTATO)));
		assertFalse(SettlementPersonalMeal.ordinary(new ItemStack(Items.COBBLESTONE)));
	}
	@Test void allPurposeMarkersIncludingMalformedDataAndComponentRemovalsAreRejected() {
		for (String key : List.of("coopcraft_food_age", SettlementShearingCargo.KEY, SettlementShearsLoan.KEY,
			SettlementWorkbenchCargo.KEY, ConstructionSupply.KEY, "unrelated")) {
			for (boolean malformed : List.of(false, true)) {
				var f = new Fixture(); CompoundTag tag = new CompoundTag();
				if (malformed) tag.putString(key, "future"); else tag.put(key, new CompoundTag());
				f.bag.getItem(0).set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
				assertFalse(SettlementPersonalMeal.ordinary(f.bag.getItem(0))); assertNull(f.prepare());
			}
		}
		for (String patch : List.of("name", "empty_custom", "removed_food")) {
			var f = new Fixture(); var stack = f.bag.getItem(0);
			switch (patch) {
				case "name" -> stack.set(DataComponents.CUSTOM_NAME, Component.literal("Saved"));
				case "empty_custom" -> stack.set(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
				case "removed_food" -> stack.remove(DataComponents.FOOD);
			}
			assertNull(f.prepare(), patch);
		}
	}
	@Test void mixedCargoAndPersonalToolsStayIdentical() {
		var f = new Fixture();
		for (int slot = 1; slot < 6; slot++) {
			ItemStack stack = new ItemStack(slot == 1 ? Items.SHEARS : slot == 2 ? Items.STONE_PICKAXE : Items.BREAD, 1);
			CompoundTag tag = new CompoundTag(); tag.putString(List.of(SettlementShearsLoan.KEY, "tool", SettlementWorkbenchCargo.KEY,
				ConstructionSupply.KEY, "coopcraft_food_age").get(slot - 1), "custody");
			stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag)); f.bag.setItem(slot, stack);
		}
		var t = f.prepare(); assertTrue(f.finish(t));
		for (int slot = 1; slot < 8; slot++) {
			assertSame(t.identities[slot], f.bag.getItem(slot)); assertTrue(ItemStack.matches(t.snapshots[slot], f.bag.getItem(slot)));
		}
	}
	@Test void replacementResizeAndCompetingTicketsCannotSpendAStaleSnapshot() {
		var f = new Fixture(); var a = f.prepare(); var b = f.prepare(); assertTrue(f.finish(a)); assertFalse(f.finish(b));
		var c = f.prepare(); f.bag.setItem(0, f.bag.getItem(0).copy()); assertFalse(f.finish(c));
		var d = f.prepare(); f.bag.getItem(0).shrink(1); assertFalse(f.finish(d));
		assertEquals(1, f.bag.getItem(0).getCount());
	}
	@Test void callbacksCannotChangeInventoryNeedsMembershipOrCancellationThenSpendOldTicket() {
		for (String change : List.of("bag", "other_slot", "satiety", "satiety_aba", "member", "cancel", "presence")) {
			var f = new Fixture(); var t = f.prepare();
			assertFalse(SettlementPersonalMeal.finish(t, () -> {
				switch (change) {
					case "bag" -> f.bag.setItem(0, f.bag.getItem(0).copy());
					case "other_slot" -> f.bag.setItem(7, new ItemStack(Items.STICK));
					case "satiety" -> f.data.setSatiety(f.camp, f.actor, 40);
					case "satiety_aba" -> { f.data.setSatiety(f.camp, f.actor, 40); f.data.setSatiety(f.camp, f.actor, 35); }
					case "member" -> { f.data.removeMember(f.actor); f.data.registerMember(f.camp, f.actor, 0); }
					case "cancel" -> t.cancel();
					case "presence" -> f.live = false;
				}
				return true;
			}), change);
			assertEquals(3, f.bag.countItem(Items.BREAD), change);
		}
	}
	@Test void reentryAndMissingArrivalCannotDoubleConsume() {
		var f = new Fixture(); var t = f.prepare(); AtomicInteger nested = new AtomicInteger();
		assertFalse(SettlementPersonalMeal.finish(f.prepare(), null)); assertFalse(SettlementPersonalMeal.finish(f.prepare(), () -> false));
		assertTrue(SettlementPersonalMeal.finish(t, () -> { if (f.finish(t)) nested.incrementAndGet(); return true; }));
		assertEquals(0, nested.get()); assertEquals(2, f.bag.countItem(Items.BREAD));
		assertFalse(f.finish(t));
	}
	@Test void callbackReentryThroughAnotherTicketInvalidatesTheOuterSnapshot() {
		var f = new Fixture(); var a = f.prepare(); var b = f.prepare();
		assertFalse(SettlementPersonalMeal.finish(a, () -> f.finish(b)));
		assertEquals(2, f.bag.countItem(Items.BREAD));
	}
	@Test void modeAbaMissingMembershipAndAbsentCampFailClosed() {
		var f = new Fixture(); var state = f.data.getOrCreateSimulation(f.camp); var t = f.prepare();
		state.setSurvivalEnabled(true, 0); assertNull(f.prepare()); state.setSurvivalEnabled(false, 0);
		assertFalse(f.finish(t)); assertFalse(SettlementPersonalMeal.contextValid(t));
		var fresh = f.prepare(); assertTrue(f.finish(fresh));
		state.setSurvivalEnabled(true, 0); state.setSurvivalEnabled(false, 0);
		assertFalse(SettlementPersonalMeal.contextValid(fresh), "The whole session retains its original mode token after a bite");
		f.data.removeMember(f.actor); assertNull(f.prepare());
		assertNull(SettlementPersonalMeal.prepare(f.data, null, f.actor, f.bag, () -> true));
	}
	@Test void initialAbsentSimulationCannotBeReplacedUnderTheTicket() {
		var f = new Fixture(); var t = f.prepare(); f.data.getOrCreateSimulation(f.camp); assertFalse(f.finish(t));
		assertEquals(3, f.bag.countItem(Items.BREAD));
	}
	@Test void actualNbtReloadPreservesFoodButCannotReviveOldLiveIdentity() {
		var f = new Fixture(); var t = f.prepare();
		var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		CompoundTag saved = (CompoundTag) f.bag.getItem(0).save(registries);
		f.bag.setItem(0, ItemStack.parseOptional(registries, saved));
		assertFalse(f.finish(t)); assertEquals(3, f.bag.countItem(Items.BREAD)); assertTrue(f.finish(f.prepare()));
		var restoredData = SettlementSavedData.load(f.data.save(new CompoundTag(), registries), registries);
		var restoredCamp = restoredData.findSettlementForVillager(f.actor).orElseThrow();
		assertNotSame(f.camp, restoredCamp); assertEquals(f.satiety(), restoredData.needsOf(restoredCamp, f.actor).satiety());
		assertNotNull(SettlementPersonalMeal.prepare(restoredData, restoredCamp, f.actor, f.bag, () -> true));
	}
	@Test void fortyConsecutiveTicksAndDuplicateFramesNeverSpeedEating() {
		var clock = new SettlementPersonalMeal.Clock();
		for (long tick = 100; tick < 139; tick++) { assertEquals(0, clock.frame(tick)); assertEquals(0, clock.frame(tick)); }
		assertEquals(1, clock.frame(139)); assertEquals(0, clock.frame(139));
		for (long tick = 140; tick < 179; tick++) assertEquals(0, clock.frame(tick));
		assertEquals(1, clock.frame(179));
	}
	@Test void gapsRewindsOverflowAndBoundedSessionNeverCatchUp() {
		for (long next : new long[] {9, 12, 999999}) {
			var clock = new SettlementPersonalMeal.Clock(); assertEquals(0, clock.frame(10));
			assertEquals(-1, clock.frame(next)); assertEquals(-1, clock.frame(11));
		}
		var overflow = new SettlementPersonalMeal.Clock(); overflow.frame(Long.MAX_VALUE); assertEquals(-1, overflow.frame(Long.MIN_VALUE));
		var bounded = new SettlementPersonalMeal.Clock(); int bites = 0;
		for (int tick = 0; tick < 800; tick++) if (bounded.frame(tick) == 1) bites++;
		assertEquals(20, bites); assertEquals(-1, bounded.frame(800));
	}
	@Test void stableSupportIncludesFarmlandAndSlabsWithBoundedBody() {
		for (double height : new double[] {1, 15.0 / 16, 0.5}) {
			var body = new net.minecraft.world.phys.AABB(0.2, 64 + height, 0.2, 0.8, 65.95 + height, 0.8);
			assertTrue(SettlementPersonalMeal.boundedBody(body));
			assertTrue(SettlementPersonalMeal.supported(body, new BlockPos(0, 64, 0),
				List.of(new net.minecraft.world.phys.AABB(0, 0, 0, 1, height, 1))));
			assertFalse(SettlementPersonalMeal.supported(body, new BlockPos(0, 63, 0),
				List.of(new net.minecraft.world.phys.AABB(0, 0, 0, 1, height, 1))));
		}
		assertFalse(SettlementPersonalMeal.boundedBody(new net.minecraft.world.phys.AABB(0, 0, 0, 100, 2, 1)));
		assertFalse(SettlementPersonalMeal.boundedBody(new net.minecraft.world.phys.AABB(Double.NaN, 0, 0, 1, 2, 1)));
	}
	@Test void failedFinalAttemptCannotBeRevived() {
		var f = new Fixture(); var t = f.prepare();
		assertFalse(SettlementPersonalMeal.finish(t, () -> false)); assertFalse(f.finish(t));
		assertEquals(3, f.bag.countItem(Items.BREAD));
	}
	@Test void integerCoordinateSeamsRemainSupportedOnOrdinaryGround() {
		var shape = List.of(new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 1));
		for (double x : new double[] {0, 0.5, 1}) for (double z : new double[] {0, 0.5, 1}) {
			var body = new net.minecraft.world.phys.AABB(x - 0.3, 65, z - 0.3, x + 0.3, 66.95, z + 0.3);
			assertTrue(SettlementPersonalMeal.supported(body, new BlockPos(0, 64, 0), shape));
			assertTrue(SettlementPersonalMeal.supported(body, BlockPos.containing(x, 64, z), shape));
		}
		var outside = new net.minecraft.world.phys.AABB(0.71, 65, 0.2, 1.31, 66.95, 0.8);
		assertFalse(SettlementPersonalMeal.supported(outside, new BlockPos(0, 64, 0), shape));
	}
	@Test void zeroAndOversizedStacksAreNotOrdinaryFood() {
		assertFalse(SettlementPersonalMeal.ordinary(new ItemStack(Items.BREAD, 0)));
		assertFalse(SettlementPersonalMeal.ordinary(new ItemStack(Items.BREAD, 65)));
		assertTrue(SettlementPersonalMeal.ordinary(new ItemStack(Items.BREAD, 64)));
	}
	@Test void stoppedTicketNeverConsumesAndFreshClockStartsFromZero() {
		var f = new Fixture(); var t = f.prepare(); t.cancel(); assertFalse(f.finish(t));
		assertEquals(3, f.bag.countItem(Items.BREAD));
		var clock = new SettlementPersonalMeal.Clock(); for (int tick = 0; tick < 39; tick++) assertEquals(0, clock.frame(tick));
		clock = new SettlementPersonalMeal.Clock(); assertEquals(0, clock.frame(1000)); assertEquals(1, clock.biteTicks);
	}
}
