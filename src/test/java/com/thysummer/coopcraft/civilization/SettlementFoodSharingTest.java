package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
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

class SettlementFoodSharingTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	static class Fixture {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(new BlockPos(0, 64, 0), "Sharing", 0);
		final UUID giver, recipient = new UUID(92, 2);
		final SimpleContainer bag = new SimpleContainer(8), other = new SimpleContainer(8);
		boolean present = true;
		Fixture() { this(new UUID(92, 1)); }
		Fixture(UUID giver) {
			this.giver = giver; data.registerMember(camp, giver, 0); data.registerMember(camp, recipient, 0);
			data.setSatiety(camp, giver, 80); data.setSatiety(camp, recipient, 35);
			bag.setItem(0, new ItemStack(Items.BREAD, 16));
		}
		SettlementFoodSharing.Ticket prepare() { return SettlementFoodSharing.prepare(data, camp, giver, bag, recipient, other, () -> present); }
		boolean finish(SettlementFoodSharing.Ticket t) { return SettlementFoodSharing.finish(t, () -> true); }
		int retained() { return SettlementFoodCargo.retainedFoodPoints(data, camp, giver); }
	}
	@Test void realItemConservationWithoutWarehousePersonOrSatietyWrites() {
		var f = new Fixture(); var original = f.bag.getItem(0); var t = f.prepare(); assertNotNull(t);
		assertTrue(f.finish(t)); assertSame(original, f.bag.getItem(0));
		assertEquals(15, f.bag.countItem(Items.BREAD)); assertEquals(1, f.other.countItem(Items.BREAD));
		assertEquals(80, f.data.needsOf(f.camp, f.giver).satiety()); assertEquals(35, f.data.needsOf(f.camp, f.recipient).satiety());
		assertTrue(f.data.getStockpile(f.camp).isEmpty()); assertTrue(f.data.simulationOf(f.camp).isEmpty());
		assertTrue(f.data.resourcesOf(f.camp).isEmpty()); assertFalse(f.finish(t));
	}
	@Test void recipientThenUsesExistingPersonalMeal() {
		var f = new Fixture(); assertTrue(f.finish(f.prepare()));
		var meal = SettlementPersonalMeal.prepare(f.data, f.camp, f.recipient, f.other, () -> true);
		assertTrue(SettlementPersonalMeal.finish(meal, () -> true)); assertTrue(f.other.isEmpty());
		assertEquals(SettlementNeeds.restoredSatiety(35, SettlementFood.points(Items.BREAD)), f.data.needsOf(f.camp, f.recipient).satiety());
	}
	@Test void exactHungerBoundariesAndNoSelfOrSameBag() {
		var f = new Fixture(); f.data.setSatiety(f.camp, f.giver, 35); assertNull(f.prepare());
		f.data.setSatiety(f.camp, f.giver, 36); assertNotNull(f.prepare());
		f.data.setSatiety(f.camp, f.recipient, 36); assertNull(f.prepare());
		assertNull(SettlementFoodSharing.prepare(f.data, f.camp, f.giver, f.bag, f.giver, f.other, () -> true));
		assertNull(SettlementFoodSharing.prepare(f.data, f.camp, f.giver, f.bag, f.recipient, f.bag, () -> true));
	}
	@Test void anyOrdinaryFoodAlreadyOwnedBlocksReceiving() {
		for (var food : List.of(Items.BREAD, Items.CARROT, Items.POTATO, Items.BEETROOT, Items.SWEET_BERRIES)) {
			var f = new Fixture(); f.other.setItem(7, new ItemStack(food)); assertNull(f.prepare());
		}
	}
	@Test void fullRecipientNeverMergesOrDrops() {
		var f = new Fixture(); for (int slot = 0; slot < 8; slot++) f.other.setItem(slot, new ItemStack(Items.STICK));
		assertNull(f.prepare()); assertEquals(16, f.bag.countItem(Items.BREAD));
		f.other.setItem(5, ItemStack.EMPTY); assertTrue(f.finish(f.prepare())); assertTrue(f.other.getItem(5).is(Items.BREAD));
		assertEquals(7, f.other.countItem(Items.STICK));
	}
	@Test void allOrdinaryFoodsContributeToSharedBudgetAndMayBeGiven() {
		for (var food : List.of(Items.BREAD, Items.CARROT, Items.POTATO, Items.BEETROOT, Items.SWEET_BERRIES)) {
			var f = new Fixture(); f.bag.setItem(0, new ItemStack(food)); f.bag.setItem(1, new ItemStack(Items.POTATO, f.retained()));
			long before = SettlementFoodSharing.ordinaryFoodPoints(f.bag); assertTrue(f.finish(f.prepare()));
			assertTrue(f.other.getItem(0).is(food)); assertEquals(before - SettlementFood.points(food), SettlementFoodSharing.ordinaryFoodPoints(f.bag));
		}
	}
	@Test void retainedBudgetUsesEachExistingPersonalityAndNeverRoundsUp() {
		java.util.Set<Integer> budgets = new java.util.HashSet<>();
		for (int seed = 10; seed < 90; seed++) {
			var f = new Fixture(new UUID(92, seed)); budgets.add(f.retained());
			f.bag.setItem(0, new ItemStack(Items.POTATO, f.retained())); assertNull(f.prepare());
			f.bag.setItem(0, new ItemStack(Items.POTATO, f.retained() + 1)); assertTrue(f.finish(f.prepare()));
			assertEquals(f.retained(), SettlementFoodSharing.ordinaryFoodPoints(f.bag));
		}
		assertTrue(budgets.size() > 1);
	}
	@Test void markedUnknownAndRemovedComponentsNeverCountOrTransfer() {
		for (String key : List.of("coopcraft_food_age", SettlementShearingCargo.KEY, SettlementShearsLoan.KEY,
			SettlementWorkbenchCargo.KEY, ConstructionSupply.KEY, "unknown")) {
			var f = new Fixture(); var stack = f.bag.getItem(0); CompoundTag tag = new CompoundTag(); tag.putString(key, "malformed");
			stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag)); assertNull(f.prepare());
			assertEquals(0L, SettlementFoodSharing.ordinaryFoodPoints(f.bag));
		}
		var f = new Fixture(); f.bag.getItem(0).remove(DataComponents.FOOD); assertNull(f.prepare());
		f.bag.setItem(0, new ItemStack(Items.BREAD));
		f.bag.getItem(0).setCount(65); // setItem clamps to the container limit; mutate the actual fixture stack.
		assertEquals(65, f.bag.getItem(0).getCount()); assertNull(f.prepare());
	}
	@Test void unrelatedCargoAndNamedRecipientFoodRemainUntouched() {
		var f = new Fixture(); var cargo = new ItemStack(Items.BREAD, 10); cargo.set(DataComponents.CUSTOM_NAME, Component.literal("Private"));
		f.bag.setItem(4, cargo); f.other.setItem(3, cargo.copy()); var named = f.other.getItem(3); var snapshot = named.copy();
		assertTrue(f.finish(f.prepare())); assertSame(cargo, f.bag.getItem(4)); assertEquals(10, cargo.getCount());
		assertSame(named, f.other.getItem(3)); assertTrue(ItemStack.matches(snapshot, named)); assertEquals(1, f.other.getItem(0).getCount());
	}
	@Test void callbacksChangingEitherBagIdentityOrContentsFailClosed() {
		for (boolean giver : List.of(false, true)) for (boolean identityOnly : List.of(false, true)) {
			var f = new Fixture(); f.other.setItem(7, new ItemStack(Items.STICK)); var t = f.prepare();
			assertFalse(SettlementFoodSharing.finish(t, () -> {
				SimpleContainer bag = giver ? f.bag : f.other; int slot = giver ? 0 : 7;
				if (identityOnly) bag.setItem(slot, bag.getItem(slot).copy()); else bag.getItem(slot).grow(1);
				return true;
			}));
			assertEquals(0, f.other.countItem(Items.BREAD)); assertFalse(f.finish(t));
		}
	}
	@Test void needsAndMembershipAbaOfEitherResidentInvalidateTicket() {
		for (boolean giver : List.of(false, true)) for (boolean member : List.of(false, true)) {
			var f = new Fixture(); var t = f.prepare(); UUID actor = giver ? f.giver : f.recipient;
			assertFalse(SettlementFoodSharing.finish(t, () -> {
				if (member) { f.data.removeMember(actor); f.data.registerMember(f.camp, actor, 0); }
				else { int old = f.data.needsOf(f.camp, actor).satiety(); f.data.setSatiety(f.camp, actor, old - 1); f.data.setSatiety(f.camp, actor, old); }
				return true;
			})); assertEquals(16, f.bag.countItem(Items.BREAD)); assertTrue(f.other.isEmpty());
		}
	}
	@Test void modeAbaAndNewSimulationInvalidatePinnedMode() {
		var f = new Fixture(); var first = f.prepare(); var state = f.data.getOrCreateSimulation(f.camp); assertFalse(f.finish(first));
		var second = f.prepare(); state.setSurvivalEnabled(true, 0); assertNull(f.prepare()); state.setSurvivalEnabled(false, 0);
		assertFalse(f.finish(second)); assertTrue(f.finish(f.prepare()));
	}
	@Test void failedArrivalCancellationAndFailedValidationCannotRevive() {
		for (String failure : List.of("arrival", "null", "cancel", "presence")) {
			var f = new Fixture(); var t = f.prepare();
			switch (failure) {
				case "arrival" -> assertFalse(SettlementFoodSharing.finish(t, () -> false));
				case "null" -> assertFalse(SettlementFoodSharing.finish(t, null));
				case "cancel" -> SettlementFoodSharing.cancel(t);
				case "presence" -> { f.present = false; assertFalse(SettlementFoodSharing.valid(t)); f.present = true; }
			}
			assertFalse(f.finish(t)); assertEquals(16, f.bag.countItem(Items.BREAD)); assertTrue(f.other.isEmpty());
		}
	}
	@Test void sameTicketAndTwoTicketCallbackReentryCannotDuplicate() {
		var f = new Fixture(); var a = f.prepare();
		assertTrue(SettlementFoodSharing.finish(a, () -> { assertFalse(f.finish(a)); return true; }));
		assertEquals(15, f.bag.countItem(Items.BREAD)); assertEquals(1, f.other.countItem(Items.BREAD));
		var g = new Fixture(); var outer = g.prepare(); var inner = g.prepare();
		assertFalse(SettlementFoodSharing.finish(outer, () -> g.finish(inner)));
		assertEquals(15, g.bag.countItem(Items.BREAD)); assertEquals(1, g.other.countItem(Items.BREAD));
		assertFalse(g.finish(outer)); assertFalse(g.finish(inner));
	}
	@Test void throwingCallbackConsumesTicketWithoutCreatingCompensation() {
		var f = new Fixture(); var t = f.prepare();
		assertThrows(IllegalStateException.class, () -> SettlementFoodSharing.finish(t, () -> { throw new IllegalStateException("lost scene"); }));
		assertFalse(f.finish(t)); assertEquals(16, f.bag.countItem(Items.BREAD)); assertTrue(f.other.isEmpty());
	}
	@Test void actualStackReloadInvalidatesLiveIdentityAndFreshTicketCanTransfer() {
		var f = new Fixture(); var t = f.prepare(); var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		CompoundTag saved = (CompoundTag) f.bag.getItem(0).save(registries);
		f.bag.setItem(0, ItemStack.parseOptional(registries, saved)); assertFalse(f.finish(t)); assertTrue(f.finish(f.prepare()));
	}
}
