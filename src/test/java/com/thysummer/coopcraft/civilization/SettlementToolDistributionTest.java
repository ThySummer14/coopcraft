package com.thysummer.coopcraft.civilization;

import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.thysummer.coopcraft.civilization.SettlementMaterialCrafting.Recipe;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure-container tests: no level, Minecraft client/server, or GameTest. */
class SettlementToolDistributionTest {
	private static final UUID ACTOR = new UUID(98, 1), OTHER = new UUID(98, 2);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void prepareAndValidAreReadOnlyAndFinishMovesSameReferenceExactlyOnce() {
		Fixture f = new Fixture(); ItemStack actual = f.store.getItem(0), before = actual.copy();
		f.prepare(); assertTrue(f.valid()); assertTrue(f.valid());
		assertSame(actual, f.store.getItem(0)); assertTrue(f.hand.get().isEmpty());
		assertTrue(ItemStack.matches(before, actual));
		assertTrue(f.finish()); assertSame(actual, f.hand.get()); assertTrue(f.store.isEmpty());
		assertTrue(f.bag.isEmpty()); assertTrue(ItemStack.matches(before, actual));
		assertFalse(f.finish()); assertFalse(f.valid());
	}

	@Test void physicalMemberNeedsNoOptionalSimulationAndQueriesNeverCreateOne() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement camp = data.getOrCreate(BlockPos.ZERO, "Physical tools", 0);
		data.registerMember(camp, ACTOR, 0); data.setStockpile(camp, new BlockPos(1, 0, 0));
		SimpleContainer bag = new SimpleContainer(4), store = new SimpleContainer(4);
		Hand hand = new Hand(); ItemStack actual = new ItemStack(Items.STONE_PICKAXE);
		store.setItem(0, actual); assertTrue(data.simulationOf(camp).isEmpty());
		var ticket = SettlementToolDistribution.prepare(data, camp, ACTOR, bag, store, hand,
			() -> ItemStack.EMPTY, () -> true, () -> true);
		assertNotNull(ticket); assertTrue(data.simulationOf(camp).isEmpty());
		assertTrue(SettlementToolDistribution.valid(ticket, data, camp, ACTOR, bag));
		assertTrue(data.simulationOf(camp).isEmpty());
		assertTrue(SettlementToolDistribution.finish(ticket, data, camp, ACTOR, bag, () -> true));
		assertSame(actual, hand.get()); assertTrue(store.isEmpty()); assertTrue(bag.isEmpty());
		assertTrue(data.simulationOf(camp).isEmpty());
	}

	@Test void twoActorsMayPrepareButOnlyOneCanTakeTheActualTool() {
		Fixture f = new Fixture(); f.data.registerMember(f.camp, OTHER, 0); f.prepare();
		Hand otherHand = new Hand(); SimpleContainer otherBag = new SimpleContainer(4);
		var second = SettlementToolDistribution.prepare(f.data, f.camp, OTHER, otherBag, f.store,
			otherHand, () -> ItemStack.EMPTY, () -> true, () -> true);
		assertNotNull(second); ItemStack actual = f.store.getItem(0);
		assertTrue(f.finish());
		assertFalse(SettlementToolDistribution.finish(second, f.data, f.camp, OTHER, otherBag, () -> true));
		assertSame(actual, f.hand.get()); assertTrue(otherHand.get().isEmpty()); assertTrue(f.store.isEmpty());
	}

	@Test void onlySingleOrdinaryStonePickaxeIsEligible() {
		for (int scenario = 0; scenario < 10; scenario++) {
			Fixture f = new Fixture(); ItemStack stack = new ItemStack(Items.STONE_PICKAXE);
			switch (scenario) {
				case 0 -> stack.setCount(2);
				case 1 -> stack.setDamageValue(1);
				case 2 -> stack.set(DataComponents.CUSTOM_NAME, Component.literal("Private"));
				case 3 -> stack.set(DataComponents.CUSTOM_DATA, CustomData.of(new CompoundTag()));
				case 4 -> stack.remove(DataComponents.TOOL);
				case 5 -> stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
				case 6 -> stack = SettlementWorkbenchCargo.output(f.camp.id(), UUID.randomUUID(), Recipe.STONE_PICKAXE);
				case 7 -> stack = new ItemStack(Items.IRON_PICKAXE);
				case 8 -> stack.remove(DataComponents.ENCHANTMENTS);
				case 9 -> stack.enchant(VanillaRegistries.createLookup().lookupOrThrow(Registries.ENCHANTMENT)
					.getOrThrow(Enchantments.UNBREAKING), 1);
			}
			f.store.setItem(0, stack); ItemStack before = stack.copy();
			assertNull(f.prepareOptional(), "scenario " + scenario);
			assertSame(stack, f.store.getItem(0)); assertTrue(ItemStack.matches(before, stack));
			assertTrue(f.hand.get().isEmpty());
		}
	}

	@Test void everyPersonalPickaxeLocationAndOccupiedMainHandArePreserved() {
		for (int scenario = 0; scenario < 4; scenario++) {
			Fixture f = new Fixture(); f.prepare(); ItemStack actual = f.store.getItem(0);
			ItemStack personal = new ItemStack(scenario == 3 ? Items.APPLE : Items.DIAMOND_PICKAXE);
			personal.set(DataComponents.CUSTOM_NAME, Component.literal("Keep")); ItemStack before = personal.copy();
			if (scenario == 0 || scenario == 3) f.hand.stack = personal;
			if (scenario == 1) f.off = personal;
			if (scenario == 2) f.bag.setItem(0, personal);
			assertFalse(f.valid()); assertFalse(f.finish()); assertSame(actual, f.store.getItem(0));
			assertTrue(ItemStack.matches(before, personal)); assertNull(f.prepareOptional());
		}
	}

	@Test void wrongCallerAndChangedMemberModeOrBindingCannotCommit() {
		for (int scenario = 0; scenario < 7; scenario++) {
			Fixture f = new Fixture(); f.prepare(); ItemStack actual = f.store.getItem(0);
			boolean result;
			if (scenario == 0) result = SettlementToolDistribution.finish(f.ticket, f.data,
				f.data.getOrCreate(new BlockPos(100, 0, 0), "Other", 0), ACTOR, f.bag, () -> true);
			else if (scenario == 1) result = SettlementToolDistribution.finish(f.ticket, f.data, f.camp, OTHER, f.bag, () -> true);
			else if (scenario == 2) result = SettlementToolDistribution.finish(f.ticket, f.data, f.camp, ACTOR, new SimpleContainer(4), () -> true);
			else {
				if (scenario == 3) { f.data.removeMember(ACTOR); f.data.registerMember(f.camp, ACTOR, 0); }
				if (scenario == 4) { var state = f.data.getOrCreateSimulation(f.camp); state.setProductionEnabled(false); state.setProductionEnabled(true); }
				if (scenario == 5) f.rebind();
				if (scenario == 6) f.world = () -> false;
				result = f.finish();
			}
			assertFalse(result, "scenario " + scenario); assertSame(actual, f.store.getItem(0));
			assertTrue(f.hand.get().isEmpty()); assertFalse(f.finish());
		}
	}

	@Test void callbacksCannotCancelReenterReplaceMoveOrCustomizeSourceOrFillHand() {
		for (int gate = 0; gate < 3; gate++) for (int scenario = 0; scenario < 8; scenario++) {
			Fixture f = new Fixture(); f.prepare(); ItemStack actual = f.store.getItem(0); int action = scenario;
			BooleanSupplier callback = () -> {
				switch (action) {
					case 0 -> SettlementToolDistribution.cancel(f.ticket);
					case 1 -> assertFalse(f.finish());
					case 2 -> f.store.setItem(0, actual.copy());
					case 3 -> f.hand.stack = new ItemStack(Items.APPLE);
					case 4 -> f.rebind();
					case 5 -> actual.setDamageValue(1);
					case 6 -> f.off = new ItemStack(Items.WOODEN_PICKAXE);
					case 7 -> f.bag.setItem(0, new ItemStack(Items.IRON_PICKAXE));
				}
				return true;
			};
			if (gate == 0) f.arrival = callback;
			if (gate == 1) f.world = callback;
			if (gate == 2) f.demand = callback;
			assertFalse(f.finish(), "gate " + gate + " scenario " + scenario);
			assertEquals(1, f.store.countItem(Items.STONE_PICKAXE)); assertFalse(f.finish());
		}
	}

	@Test void validCallbackCancellationAndReentryCannotReturnUsableTicket() {
		for (boolean reenter : new boolean[] { false, true }) {
			Fixture f = new Fixture(); f.prepare();
			f.demand = () -> { if (reenter) assertFalse(f.finish()); else SettlementToolDistribution.cancel(f.ticket); return true; };
			assertFalse(f.valid()); assertFalse(f.finish()); assertEquals(1, f.store.countItem(Items.STONE_PICKAXE));
		}
	}

	@Test void failedArrivalDemandMovedOrDepletedSourceNeverFindsAReplacement() {
		for (int scenario = 0; scenario < 5; scenario++) {
			Fixture f = new Fixture(); f.prepare(); ItemStack actual = f.store.getItem(0);
			if (scenario == 0) f.arrival = () -> false;
			if (scenario == 1) f.demand = () -> false;
			if (scenario == 2) { f.store.removeItemNoUpdate(0); f.store.setItem(1, actual); }
			if (scenario == 3) f.store.removeItemNoUpdate(0);
			if (scenario == 4) f.store.setItem(0, actual.copy());
			assertFalse(f.finish()); assertTrue(f.hand.get().isEmpty());
			f.store.setItem(0, actual); f.arrival = () -> true; f.demand = () -> true;
			assertFalse(f.finish());
		}
	}

	@Test void removalAndHandSetterExceptionsUseActualCustodyWithoutDuplication() {
		for (boolean remove : new boolean[] { false, true }) for (boolean after : new boolean[] { false, true }) {
			Fixture f = new Fixture(); f.prepare(); ItemStack actual = f.store.getItem(0);
			if (remove) { f.store.failRemoval = true; f.store.after = after; }
			else { f.hand.fail = true; f.hand.after = after; }
			assertEquals(after, f.finish()); assertSame(actual, after ? f.hand.get() : f.store.getItem(0));
			assertEquals(1, f.store.countItem(Items.STONE_PICKAXE) + (f.hand.get().isEmpty() ? 0 : 1));
			assertFalse(f.finish());
		}
	}

	@Test void restorationAfterSetterFailureSurvivesPostWriteAndNotificationExceptions() {
		Fixture f = new Fixture(); f.prepare(); ItemStack actual = f.store.getItem(0);
		f.hand.fail = true; f.store.failRestore = true; f.store.failNotification = true;
		assertFalse(f.finish()); assertSame(actual, f.store.getItem(0)); assertTrue(f.hand.get().isEmpty());
		assertEquals(1, f.store.countItem(Items.STONE_PICKAXE));
	}

	private static final class Hand implements SettlementShearsLoan.Hand {
		ItemStack stack = ItemStack.EMPTY; boolean fail, after;
		public ItemStack get() { return stack; }
		public void set(ItemStack value) {
			if (fail && !after) throw new IllegalStateException("before hand");
			stack = value;
			if (fail) throw new IllegalStateException("after hand");
		}
	}
	private static final class Store extends SimpleContainer {
		boolean failRemoval, after, failRestore, failNotification;
		Store() { super(4); }
		@Override public ItemStack removeItemNoUpdate(int slot) {
			if (failRemoval && !after) throw new IllegalStateException("before removal");
			ItemStack result = super.removeItemNoUpdate(slot);
			if (failRemoval) throw new IllegalStateException("after removal");
			return result;
		}
		@Override public void setItem(int slot, ItemStack stack) {
			super.setItem(slot, stack);
			if (failRestore) throw new IllegalStateException("after restoration");
		}
		@Override public void setChanged() {
			if (failNotification) throw new IllegalStateException("notification");
			super.setChanged();
		}
	}
	private static final class Fixture {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(BlockPos.ZERO, "Public tools", 0);
		final SimpleContainer bag = new SimpleContainer(4);
		final Store store = new Store(); final Hand hand = new Hand();
		ItemStack off = ItemStack.EMPTY;
		BooleanSupplier world = () -> true, demand = () -> true, arrival = () -> true;
		SettlementToolDistribution.Ticket ticket;
		Fixture() {
			data.registerMember(camp, ACTOR, 0); data.setStockpile(camp, new BlockPos(1, 0, 0));
			var state = data.getOrCreateSimulation(camp); state.mapResident(ACTOR, false, 0);
			state.setSurvivalEnabled(true, 0); state.setProductionEnabled(true);
			store.setItem(0, new ItemStack(Items.STONE_PICKAXE));
		}
		SettlementToolDistribution.Ticket prepareOptional() {
			return SettlementToolDistribution.prepare(data, camp, ACTOR, bag, store, hand, () -> off,
				() -> world.getAsBoolean(), () -> demand.getAsBoolean());
		}
		void prepare() { ticket = prepareOptional(); assertNotNull(ticket); }
		boolean valid() { return SettlementToolDistribution.valid(ticket, data, camp, ACTOR, bag); }
		boolean finish() { return SettlementToolDistribution.finish(ticket, data, camp, ACTOR, bag, () -> arrival.getAsBoolean()); }
		void rebind() { data.setStockpile(camp, new BlockPos(2, 0, 0)); data.setStockpile(camp, ticket.store()); }
	}
}
