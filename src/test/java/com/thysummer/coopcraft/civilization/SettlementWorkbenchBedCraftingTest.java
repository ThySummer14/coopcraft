package com.thysummer.coopcraft.civilization;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.thysummer.coopcraft.civilization.SettlementMaterialCrafting.Recipe;
import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementWorkbenchBedCraftingTest {
	private static final UUID ACTOR = new UUID(85, 1);
	private static HolderLookup.Provider registries;
	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}

	@Test void crossSlotTwoIngredientPickupAndLastBatchCompletionConservePhysicalItems() {
		Fixture f = new Fixture(limitedBag(4, 2));
		f.store.setItem(0, new ItemStack(Items.WHITE_WOOL, 1));
		f.store.setItem(1, new ItemStack(Items.WHITE_WOOL, 2));
		f.store.setItem(2, new ItemStack(Items.OAK_PLANKS, 2));
		f.store.setItem(3, new ItemStack(Items.OAK_PLANKS, 1));
		var t = f.prepare();
		assertEquals(Recipe.BED, t.recipe());
		assertTrue(f.pickup(t));
		assertTrue(f.store.isEmpty());
		assertEquals(3, f.bag.countItem(Items.WHITE_WOOL));
		assertEquals(3, f.bag.countItem(Items.OAK_PLANKS));
		for (int slot = 0; slot < 4; slot++) assertTrue(SettlementWorkbenchCargo.isMarked(f.bag.getItem(slot)));
		assertTrue(f.valid(t));
		assertTrue(f.finish(t));
		assertEquals(0, f.bag.countItem(Items.WHITE_WOOL));
		assertEquals(0, f.bag.countItem(Items.OAK_PLANKS));
		assertEquals(1, f.bag.countItem(Items.WHITE_BED));
		assertFalse(f.finish(t));
		var events = f.state().world().ledger().events();
		var completed = events.stream().filter(e -> e.type() == EventType.CRAFT_COMPLETED).findFirst().orElseThrow();
		assertEquals("material_bed", completed.context().get("recipe"));
		assertEquals("1", completed.context().get("outputCount"));
		assertEquals("minecraft:white_bed", completed.context().get("outputItem"));
		assertTrue(completed.causalLinks().isEmpty());
		assertFalse(events.stream().anyMatch(e -> e.type() == EventType.EXTERNAL_EXPORT));
		f.state().world().assertConservation();
	}

	@Test void missingEitherIngredientCannotHalfWithdrawOrUseAbstractOrCustomSupplies() {
		for (Item missing : new Item[] { Items.WHITE_WOOL, Items.OAK_PLANKS }) {
			Fixture f = new Fixture();
			f.supply();
			int slot = missing == Items.WHITE_WOOL ? 0 : 1;
			f.store.getItem(slot).shrink(1);
			ItemStack named = new ItemStack(missing);
			named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep"));
			f.store.setItem(2, named);
			f.state().world().importExternalBatch(ResourceType.WOOD, 64, f.state().campOwner());
			var before = snapshot(f.store);
			int events = f.state().world().ledger().events().size();
			assertFalse(f.pickup(f.prepare()));
			assertUnchanged(before, f.store);
			assertTrue(f.bag.isEmpty());
			assertEquals(events, f.state().world().ledger().events().size());
		}
	}

	@Test void wholeInputAndOutputCapacityArePreflightedBeforeAnyWithdrawal() {
		for (SimpleContainer bag : new SimpleContainer[] {
			new SimpleContainer(1), limitedBag(2, 2),
			new SimpleContainer(2) {
				@Override public boolean canPlaceItem(int slot, ItemStack stack) { return !stack.is(Items.WHITE_BED); }
			}
		}) {
			Fixture f = new Fixture(bag);
			f.supply();
			var before = snapshot(f.store);
			assertFalse(f.pickup(f.prepare()));
			assertUnchanged(before, f.store);
			assertTrue(f.bag.isEmpty());
		}
	}

	@Test void outputCapacityChangeAtStationPreservesBothInputsAndConsumesTicket() {
		AtomicBoolean allow = new AtomicBoolean(true);
		Fixture f = new Fixture(new SimpleContainer(2) {
			@Override public boolean canPlaceItem(int slot, ItemStack stack) { return !stack.is(Items.WHITE_BED) || allow.get(); }
		});
		f.supply();
		var t = f.prepare();
		assertTrue(f.pickup(t));
		var before = snapshot(f.bag);
		allow.set(false);
		assertFalse(f.finish(t));
		assertUnchanged(before, f.bag);
		allow.set(true);
		assertFalse(f.finish(t));
		assertEquals(0, f.bag.countItem(Items.WHITE_BED));
	}

	@Test void exactCountsAreCheckedPerItemAndEveryMarkedStackMustMatchTheBatch() {
		for (int scenario = 0; scenario < 8; scenario++) {
			Fixture f = new Fixture();
			f.supply();
			var t = f.prepare();
			assertTrue(f.pickup(t));
			int wool = find(f.bag, Items.WHITE_WOOL), planks = find(f.bag, Items.OAK_PLANKS);
			switch (scenario) {
				case 0 -> f.bag.getItem(wool).shrink(1);
				case 1 -> f.bag.getItem(planks).shrink(1);
				case 2 -> f.bag.setItem(2, f.bag.getItem(wool).copyWithCount(1));
				case 3 -> f.bag.setItem(2, f.bag.getItem(planks).copyWithCount(1));
				// Total still six, each stack individually valid, but wool/plank totals are 4/2.
				case 4 -> {
					f.bag.getItem(planks).shrink(1);
					f.bag.setItem(2, f.bag.getItem(wool).copyWithCount(1));
				}
				case 5 -> f.bag.setItem(wool, SettlementWorkbenchCargo.inputs(f.camp.id(), UUID.randomUUID(), Recipe.BED)
					.stream().filter(s -> s.is(Items.WHITE_WOOL)).findFirst().orElseThrow());
				case 6 -> f.bag.setItem(planks, SettlementWorkbenchCargo.input(f.camp.id(), UUID.randomUUID(), Recipe.DOOR).copyWithCount(3));
				case 7 -> f.bag.getItem(wool).set(DataComponents.CUSTOM_NAME, Component.literal("Changed"));
			}
			var before = snapshot(f.bag);
			assertFalse(f.valid(t), "scenario " + scenario);
			assertFalse(f.finish(t));
			assertUnchanged(before, f.bag);
			assertEquals(0, f.bag.countItem(Items.WHITE_BED));
		}
	}

	@Test void cancellationAndPartialReturnKeepEveryUnacceptedIngredient() {
		Fixture f = new Fixture();
		f.supply();
		var t = f.prepare();
		assertTrue(f.pickup(t));
		SettlementWorkbenchCrafting.cancel(t);
		assertFalse(f.finish(t));
		SimpleContainer partial = limitedBag(1, 2);
		assertEquals(2, SettlementWorkbenchCargo.deposit(f.bag, partial, f.camp.id()));
		assertEquals(4, f.bag.countItem(Items.WHITE_WOOL) + f.bag.countItem(Items.OAK_PLANKS));
		assertNull(f.prepare());
		assertTrue(SettlementWorkbenchCargo.hasReturnable(f.bag, f.camp.id()));
		assertEquals(4, SettlementWorkbenchCargo.deposit(f.bag, f.store, f.camp.id()));
		assertTrue(f.bag.isEmpty());
		assertEquals(3, partial.countItem(Items.WHITE_WOOL) + f.store.countItem(Items.WHITE_WOOL));
		assertEquals(3, partial.countItem(Items.OAK_PLANKS) + f.store.countItem(Items.OAK_PLANKS));
	}

	@Test void normalNbtReloadKeepsFullCargoButCannotResumeOrRenewLiveTicket() {
		Fixture f = new Fixture();
		f.supply();
		var t = f.prepare();
		assertTrue(f.pickup(t));
		assertTrue(f.valid(t));
		SimpleContainer loadedBag = new SimpleContainer(f.bag.getContainerSize());
		for (int slot = 0; slot < f.bag.getContainerSize(); slot++) {
			ItemStack stack = f.bag.getItem(slot);
			if (!stack.isEmpty()) loadedBag.setItem(slot, ItemStack.parseOptional(registries, (CompoundTag) stack.save(registries)));
		}
		assertUnchanged(snapshot(f.bag), loadedBag);
		var loaded = SettlementSavedData.load(f.data.save(new CompoundTag(), registries), registries);
		var camp = loaded.find(BlockPos.ZERO).orElseThrow();
		assertFalse(SettlementWorkbenchCrafting.valid(t, loaded, camp, ACTOR, loadedBag));
		assertFalse(SettlementWorkbenchCrafting.finish(t, loaded, camp, ACTOR, loadedBag, () -> true));
		assertNull(SettlementWorkbenchCrafting.prepare(loaded, camp, ACTOR, loadedBag, Recipe.BED, () -> true, () -> true));
		assertTrue(SettlementWorkbenchCargo.hasReturnable(loadedBag, camp.id()));
		assertEquals(6, SettlementWorkbenchCargo.deposit(loadedBag, f.store, camp.id()));
		assertTrue(loadedBag.isEmpty());
		assertEquals(3, f.store.countItem(Items.WHITE_WOOL));
		assertEquals(3, f.store.countItem(Items.OAK_PLANKS));
	}

	@Test void logToPlanksToBedLoopRequiresRealDeliveryArrivalAtEachStage() {
		Fixture f = new Fixture();
		f.store.setItem(0, new ItemStack(Items.OAK_LOG));
		f.store.setItem(1, new ItemStack(Items.WHITE_WOOL, 3));
		var planks = SettlementWorkbenchCrafting.prepare(f.data, f.camp, ACTOR, f.bag, () -> true, () -> true);
		assertTrue(f.pickup(planks));
		assertTrue(f.finish(planks));
		assertEquals(SettlementCargoDelivery.Status.NOT_AT_STORE, f.deliver(false).status());
		assertEquals(0, f.store.countItem(Items.OAK_PLANKS));
		assertEquals(4, f.deliver(true).moved());
		var bed = f.prepare();
		assertTrue(f.pickup(bed));
		assertTrue(f.finish(bed));
		assertEquals(0, f.store.countItem(Items.WHITE_BED));
		assertEquals(SettlementCargoDelivery.Status.NOT_AT_STORE, f.deliver(false).status());
		assertEquals(1, f.bag.countItem(Items.WHITE_BED));
		assertEquals(1, f.deliver(true).moved());
		assertTrue(f.bag.isEmpty());
		assertEquals(1, SettlementMaterialCrafting.countPlain(f.store, Items.WHITE_BED));
		assertEquals(1, SettlementMaterialCrafting.countPlain(f.store, Items.OAK_PLANKS));
		assertEquals(0, f.store.countItem(Items.WHITE_WOOL));
		assertEquals(0, f.store.countItem(Items.OAK_LOG));
	}

	@Test void arrivalDemandAndBindingGuardsPreserveBothIngredients() {
		Fixture away = new Fixture();
		away.supply();
		assertFalse(SettlementWorkbenchCrafting.pickup(away.prepare(), away.data, away.camp, ACTOR,
			away.bag, away.store, () -> false));
		assertEquals(3, away.store.countItem(Items.WHITE_WOOL));
		assertEquals(3, away.store.countItem(Items.OAK_PLANKS));
		for (int scenario = 0; scenario < 4; scenario++) {
			Fixture f = new Fixture();
			f.supply();
			var t = f.prepare();
			assertTrue(f.pickup(t));
			var before = snapshot(f.bag);
			switch (scenario) {
				case 0 -> f.demand.set(false);
				case 1 -> {
					f.data.setCraftingStation(f.camp, t.station().offset(1, 0, 0));
					f.data.setCraftingStation(f.camp, t.station());
				}
				case 2 -> { f.state().setProductionEnabled(false); f.state().setProductionEnabled(true); }
				case 3 -> assertFalse(SettlementWorkbenchCrafting.finish(t, f.data, f.camp, ACTOR, f.bag, () -> false));
			}
			assertFalse(f.finish(t));
			assertUnchanged(before, f.bag);
		}
	}

	private static int find(SimpleContainer bag, Item item) {
		for (int slot = 0; slot < bag.getContainerSize(); slot++) if (bag.getItem(slot).is(item)) return slot;
		throw new AssertionError("Missing ingredient");
	}
	private static ItemStack[] snapshot(SimpleContainer container) {
		ItemStack[] result = new ItemStack[container.getContainerSize()];
		for (int slot = 0; slot < result.length; slot++) result[slot] = container.getItem(slot).copy();
		return result;
	}
	private static void assertUnchanged(ItemStack[] before, SimpleContainer container) {
		for (int slot = 0; slot < before.length; slot++) assertTrue(ItemStack.matches(before[slot], container.getItem(slot)), "slot " + slot);
	}
	private static SimpleContainer limitedBag(int slots, int limit) {
		return new SimpleContainer(slots) {
			@Override public int getMaxStackSize(ItemStack stack) { return limit; }
		};
	}
	private static final class Fixture {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(BlockPos.ZERO, "Bed workbench", 0);
		final SimpleContainer bag, store = new SimpleContainer(9);
		final AtomicBoolean demand = new AtomicBoolean(true);
		Fixture() { this(new SimpleContainer(8)); }
		Fixture(SimpleContainer bag) {
			this.bag = bag;
			data.registerMember(camp, ACTOR, 0);
			data.setStockpile(camp, new BlockPos(1, 0, 0));
			data.setCraftingStation(camp, new BlockPos(3, 0, 0));
			var state = data.getOrCreateSimulation(camp);
			state.mapResident(ACTOR, false, 0);
			state.setSurvivalEnabled(true, 0);
			state.setProductionEnabled(true);
		}
		void supply() {
			store.setItem(0, new ItemStack(Items.WHITE_WOOL, 3));
			store.setItem(1, new ItemStack(Items.OAK_PLANKS, 3));
		}
		SettlementSimulationState state() { return data.simulationOf(camp).orElseThrow(); }
		SettlementWorkbenchCrafting.Ticket prepare() { return SettlementWorkbenchCrafting.prepare(data, camp, ACTOR, bag, Recipe.BED, () -> true, demand::get); }
		boolean valid(SettlementWorkbenchCrafting.Ticket t) { return SettlementWorkbenchCrafting.valid(t, data, camp, ACTOR, bag); }
		boolean pickup(SettlementWorkbenchCrafting.Ticket t) { return SettlementWorkbenchCrafting.pickup(t, data, camp, ACTOR, bag, store, () -> true); }
		boolean finish(SettlementWorkbenchCrafting.Ticket t) { return SettlementWorkbenchCrafting.finish(t, data, camp, ACTOR, bag, () -> true); }
		SettlementCargoDelivery.Result deliver(boolean arrived) {
			var t = SettlementCargoDelivery.prepare(data, camp, ACTOR, bag, () -> true);
			return SettlementCargoDelivery.finish(t, data, camp, ACTOR, bag, store, () -> arrived);
		}
	}
}
