package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
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

class SettlementChildCareTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	static class Fixture {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(new BlockPos(0, 64, 0), "Care", 0);
		final UUID donor = new UUID(89, 1), child = new UUID(89, 2);
		final SimpleContainer bag = new SimpleContainer(8), childBag = new SimpleContainer(8);
		boolean live = true, arrived = true;
		Fixture() {
			data.registerMember(camp, donor, 0); data.registerMember(camp, child, 0);
			data.setSatiety(camp, donor, 80); data.setSatiety(camp, child, 30);
			bag.setItem(0, new ItemStack(Items.SWEET_BERRIES, retained() + 2));
		}
		int retained() { return SettlementFoodCargo.retainedFoodPoints(data, camp, donor); }
		SettlementChildCare.Ticket prepare() {
			return SettlementChildCare.prepare(data, camp, donor, child, bag, childBag, new BlockPos(1,64,0), () -> live, () -> arrived);
		}
		boolean finish(SettlementChildCare.Ticket t) { return SettlementChildCare.finish(t, () -> true); }
	}
	@Test void exactlyOneRealDonorItemRestoresOnlyChildAndTicketCannotReplay() {
		var f = new Fixture(); var ticket = f.prepare(); assertNotNull(ticket);
		f.bag.setItem(1, new ItemStack(Items.STONE_AXE)); ItemStack tool = f.bag.getItem(1).copy();
		assertTrue(f.finish(ticket)); assertFalse(f.finish(ticket));
		assertEquals(f.retained() + 1, f.bag.countItem(Items.SWEET_BERRIES));
		assertEquals(35, f.data.needsOf(f.camp, f.child).satiety());
		assertEquals(80, f.data.needsOf(f.camp, f.donor).satiety());
		assertTrue(f.childBag.isEmpty()); assertTrue(ItemStack.matches(tool, f.bag.getItem(1)));
		assertTrue(f.data.simulationOf(f.camp).isEmpty()); assertTrue(f.data.resourcesOf(f.camp).isEmpty());
	}
	@Test void retainedRationAndHungerThresholdsAreStrict() {
		var f = new Fixture(); f.bag.setItem(0, new ItemStack(Items.POTATO, f.retained()));
		assertNull(f.prepare()); f.bag.getItem(0).grow(1); assertNotNull(f.prepare());
		f.data.setSatiety(f.camp, f.donor, 35); assertNull(f.prepare());
		f.data.setSatiety(f.camp, f.donor, 36); f.data.setSatiety(f.camp, f.child, 36); assertNull(f.prepare());
		f.data.setSatiety(f.camp, f.child, 35); assertTrue(f.finish(f.prepare()));
		assertEquals(f.retained(), f.bag.countItem(Items.POTATO)); assertEquals(40, f.data.needsOf(f.camp, f.child).satiety());
	}
	@Test void mixedOrdinaryFoodProtectsRetentionWithoutBeingDonated() {
		var f = new Fixture(); f.bag.clearContent();
		f.bag.setItem(0, new ItemStack(Items.BREAD, 64)); f.bag.setItem(1, new ItemStack(Items.POTATO));
		assertTrue(f.finish(f.prepare())); assertEquals(64, f.bag.countItem(Items.BREAD));
		assertEquals(0, f.bag.countItem(Items.POTATO)); assertEquals(35, f.data.needsOf(f.camp, f.child).satiety());
	}
	@Test void samePersonSameBagOrUnregisteredRecipientCannotPrepare() {
		var f = new Fixture();
		assertNull(SettlementChildCare.prepare(f.data, f.camp, f.donor, f.donor, f.bag, f.childBag, BlockPos.ZERO, () -> true, () -> true));
		assertNull(SettlementChildCare.prepare(f.data, f.camp, f.donor, f.child, f.bag, f.bag, BlockPos.ZERO, () -> true, () -> true));
		f.data.removeMember(f.child); assertNull(f.prepare());
	}

	@Test void childWithOwnFoodDoesNotReceiveAndNothingIsTakenFromIt() {
		var f = new Fixture(); var ticket = f.prepare(); f.childBag.setItem(0, new ItemStack(Items.BREAD));
		assertNull(f.prepare()); assertFalse(f.finish(ticket)); assertEquals(1, f.childBag.countItem(Items.BREAD));
		assertEquals(f.retained() + 2, f.bag.countItem(Items.SWEET_BERRIES));
	}
	@Test void privateMarkedAndNonSurplusFoodNeverBecomesCareFood() {
		for (String kind : List.of("named", "removed", "marked", "other")) {
			var f = new Fixture(); f.bag.clearContent(); f.bag.setItem(0, new ItemStack(Items.BREAD, 64));
			ItemStack stack = new ItemStack(kind.equals("other") ? Items.CARROT : Items.POTATO);
			switch (kind) {
				case "named" -> stack.set(DataComponents.CUSTOM_NAME, Component.literal("Private"));
				case "removed" -> stack.remove(DataComponents.FOOD);
				case "marked" -> { CompoundTag tag = new CompoundTag(); tag.putString("coopcraft_food_age", "future"); stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag)); }
			}
			f.bag.setItem(1, stack); assertNull(f.prepare(), kind); assertEquals(1, f.bag.getItem(1).getCount());
		}
	}
	@Test void memberReplacementModeAbaAndPresenceChangesInvalidateBothParties() {
		for (String change : List.of("donor", "child", "mode", "unload", "cancel", "contact")) {
			var f = new Fixture(); var state = f.data.getOrCreateSimulation(f.camp); var ticket = f.prepare();
			assertNotNull(ticket);
			switch (change) {
				case "donor", "child" -> { UUID id = change.equals("donor") ? f.donor : f.child; f.data.removeMember(id); f.data.registerMember(f.camp, id, 0); }
				case "mode" -> { state.setSurvivalEnabled(true, 0); state.setSurvivalEnabled(false, 0); }
				case "unload" -> f.live = false;
				case "cancel" -> SettlementChildCare.cancel(ticket);
				case "contact" -> f.arrived = false;
			}
			assertFalse(f.finish(ticket), change); assertEquals(f.retained() + 2, f.bag.countItem(Items.SWEET_BERRIES));
		}
		var f = new Fixture(); var ticket = f.prepare(); f.data.getOrCreateSimulation(f.camp); assertFalse(f.finish(ticket));
	}
	@Test void everyFinalAttemptIsOneUseIncludingMissingArrival() {
		var f = new Fixture(); var ticket = f.prepare(); assertFalse(SettlementChildCare.finish(ticket, () -> false));
		assertFalse(f.finish(ticket)); assertEquals(f.retained() + 2, f.bag.countItem(Items.SWEET_BERRIES));
	}
	@Test void callbackMutationsCannotSpendStaleBagNeedsMembershipOrContact() {
		for (String change : List.of("bag", "donorNeeds", "childNeeds", "childBag", "cancel", "contact", "member", "mode")) {
			var f = new Fixture(); var state = f.data.getOrCreateSimulation(f.camp); var ticket = f.prepare();
			assertFalse(SettlementChildCare.finish(ticket, () -> {
				switch (change) {
					case "bag" -> f.bag.getItem(0).shrink(1);
					case "donorNeeds" -> f.data.setSatiety(f.camp, f.donor, 79);
					case "childNeeds" -> f.data.setSatiety(f.camp, f.child, 29);
					case "childBag" -> f.childBag.setItem(0, new ItemStack(Items.STICK));
					case "cancel" -> SettlementChildCare.cancel(ticket);
					case "contact" -> f.arrived = false;
					case "member" -> { f.data.removeMember(f.child); f.data.registerMember(f.camp, f.child, 0); }
					case "mode" -> { state.setSurvivalEnabled(true, 0); state.setSurvivalEnabled(false, 0); }
				}
				return true;
			}), change);
			assertEquals(f.retained() + (change.equals("bag") ? 1 : 2), f.bag.countItem(Items.SWEET_BERRIES), change);
			assertFalse(f.finish(ticket));
		}
	}
	@Test void arrivalCanValidateTicketButCannotReenterFinish() {
		var f = new Fixture(); var ticket = f.prepare();
		assertTrue(SettlementChildCare.finish(ticket, () -> {
			assertTrue(SettlementChildCare.valid(ticket));
			assertFalse(f.finish(ticket));
			return SettlementChildCare.valid(ticket);
		}));
		assertEquals(f.retained() + 1, f.bag.countItem(Items.SWEET_BERRIES));
	}
	@Test void concurrentPreparedTicketsRecheckChildNeedAndConserveItems() {
		var f = new Fixture(); f.data.setSatiety(f.camp, f.child, 35);
		var first = f.prepare(); var second = f.prepare(); assertTrue(f.finish(first)); assertFalse(f.finish(second));
		assertEquals(40, f.data.needsOf(f.camp, f.child).satiety()); assertEquals(f.retained() + 1, f.bag.countItem(Items.SWEET_BERRIES));
	}
}
