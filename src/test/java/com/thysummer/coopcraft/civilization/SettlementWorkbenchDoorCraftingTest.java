package com.thysummer.coopcraft.civilization;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.thysummer.coopcraft.civilization.SettlementMaterialCrafting.Recipe;
import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementWorkbenchDoorCraftingTest {
	private static final UUID ACTOR = new UUID(83, 1);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void crossSlotPickupAndSplitCustodyConserveSixPlanksIntoThreeDoors() {
		Fixture f = new Fixture(limitedBag(2, 3));
		f.store.setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
		f.store.setItem(1, new ItemStack(Items.OAK_PLANKS, 3));
		var ticket = f.prepare();
		assertEquals(Recipe.DOOR, ticket.recipe());
		assertTrue(f.pickup(ticket));
		assertTrue(f.store.isEmpty());
		assertEquals(3, f.bag.getItem(0).getCount());
		assertEquals(3, f.bag.getItem(1).getCount());
		assertTrue(f.valid(ticket));
		assertTrue(f.finish(ticket));
		assertEquals(0, f.bag.countItem(Items.OAK_PLANKS));
		assertEquals(3, f.bag.countItem(Items.OAK_DOOR));
		assertEquals(0, f.store.countItem(Items.OAK_DOOR));
		assertFalse(f.finish(ticket));
		var completed = f.state().world().ledger().events().stream()
			.filter(e -> e.type() == EventType.CRAFT_COMPLETED).findFirst().orElseThrow();
		assertEquals("material_door", completed.context().get("recipe"));
		assertEquals("3", completed.context().get("outputCount"));
		assertEquals("minecraft:oak_door", completed.context().get("outputItem"));
		assertTrue(completed.causalLinks().isEmpty());
		assertFalse(f.state().world().ledger().events().stream().anyMatch(e -> e.type() == EventType.EXTERNAL_EXPORT));
		f.state().world().assertConservation();
	}

	@Test void fiveRealPlanksCannotBeSupplementedByAbstractWoodOrCustomPlanks() {
		Fixture f = new Fixture();
		f.store.setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
		f.store.setItem(1, new ItemStack(Items.OAK_PLANKS, 2));
		ItemStack named = new ItemStack(Items.OAK_PLANKS);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep"));
		f.store.setItem(2, named);
		f.state().world().importExternalBatch(ResourceType.WOOD, 64, f.state().campOwner());
		int events = f.state().world().ledger().events().size();
		assertFalse(f.pickup(f.prepare()));
		assertEquals(5, SettlementMaterialCrafting.countPlain(f.store, Items.OAK_PLANKS));
		assertTrue(ItemStack.matches(named, f.store.getItem(2)));
		assertTrue(f.bag.isEmpty());
		assertEquals(events, f.state().world().ledger().events().size());
	}

	@Test void completeInputAndCompleteOutputCapacityAreRequiredBeforeWithdrawal() {
		Fixture tooSmall = new Fixture(limitedBag(1, 5));
		tooSmall.supply();
		assertFalse(tooSmall.pickup(tooSmall.prepare()));
		assertEquals(6, tooSmall.store.countItem(Items.OAK_PLANKS));
		assertTrue(tooSmall.bag.isEmpty());
		Fixture noOutputSpace = new Fixture(new SimpleContainer(1) {
			@Override public int getMaxStackSize(ItemStack stack) { return stack.is(Items.OAK_DOOR) ? 2 : 64; }
		});
		noOutputSpace.supply();
		assertFalse(noOutputSpace.pickup(noOutputSpace.prepare()));
		assertEquals(6, noOutputSpace.store.countItem(Items.OAK_PLANKS));
		assertTrue(noOutputSpace.bag.isEmpty());
	}

	@Test void capacityChangesAtStationPreserveAllSixInputsAndConsumeTicket() {
		AtomicBoolean outputAllowed = new AtomicBoolean(true);
		Fixture f = new Fixture(new SimpleContainer(1) {
			@Override public boolean canPlaceItem(int slot, ItemStack stack) {
				return !stack.is(Items.OAK_DOOR) || outputAllowed.get();
			}
		});
		f.supply();
		var ticket = f.prepare();
		assertTrue(f.pickup(ticket));
		ItemStack before = f.bag.getItem(0).copy();
		outputAllowed.set(false);
		assertFalse(f.finish(ticket));
		assertTrue(ItemStack.matches(before, f.bag.getItem(0)));
		outputAllowed.set(true);
		assertFalse(f.finish(ticket));
		assertEquals(0, f.bag.countItem(Items.OAK_DOOR));
	}

	@Test void missingDuplicateWrongRecipeAndForeignBatchCannotComplete() {
		for (int scenario = 0; scenario < 5; scenario++) {
			Fixture f = new Fixture();
			f.supply();
			var ticket = f.prepare();
			assertTrue(f.pickup(ticket));
			switch (scenario) {
				case 0 -> f.bag.getItem(0).shrink(1);
				case 1 -> f.bag.setItem(1, f.bag.getItem(0).copy());
				case 2 -> f.bag.setItem(0, SettlementWorkbenchCargo.input(f.camp.id(), UUID.randomUUID()));
				case 3 -> f.bag.setItem(0, SettlementWorkbenchCargo.input(f.camp.id(), UUID.randomUUID(), Recipe.DOOR));
				case 4 -> f.bag.setItem(0, SettlementWorkbenchCargo.input(UUID.randomUUID(), UUID.randomUUID(), Recipe.DOOR));
			}
			int before = f.bag.countItem(Items.OAK_PLANKS);
			assertFalse(f.valid(ticket));
			assertFalse(f.finish(ticket));
			assertEquals(before, f.bag.countItem(Items.OAK_PLANKS));
			assertEquals(0, f.bag.countItem(Items.OAK_DOOR));
		}
	}

	@Test void cancelledDemandAndPartialReturnKeepActualRemainingInput() {
		Fixture f = new Fixture();
		f.supply();
		var ticket = f.prepare();
		assertTrue(f.pickup(ticket));
		f.demand.set(false);
		assertFalse(f.finish(ticket));
		SimpleContainer partial = limitedBag(1, 2);
		assertEquals(2, SettlementWorkbenchCargo.deposit(f.bag, partial, f.camp.id()));
		assertEquals(4, f.bag.countItem(Items.OAK_PLANKS));
		assertTrue(SettlementWorkbenchCargo.hasReturnable(f.bag, f.camp.id()));
		assertEquals(4, SettlementWorkbenchCargo.deposit(f.bag, f.store, f.camp.id()));
		assertTrue(f.bag.isEmpty());
		assertEquals(6, partial.countItem(Items.OAK_PLANKS) + f.store.countItem(Items.OAK_PLANKS));
	}

	@Test void twoPlankTripsThenDoorTripUseRealArrivalGatedDelivery() {
		Fixture f = new Fixture();
		f.store.setItem(0, new ItemStack(Items.OAK_LOG, 2));
		for (int batch = 0; batch < 2; batch++) {
			var plank = SettlementWorkbenchCrafting.prepare(f.data, f.camp, ACTOR, f.bag, () -> true, () -> true);
			assertEquals(Recipe.PLANKS, plank.recipe());
			assertTrue(f.pickup(plank));
			assertTrue(f.finish(plank));
			assertEquals(4, f.deliver(true).moved());
			assertTrue(f.bag.isEmpty());
		}
		assertEquals(8, SettlementMaterialCrafting.countPlain(f.store, Items.OAK_PLANKS));
		var door = f.prepare();
		assertTrue(f.pickup(door));
		assertEquals(2, f.store.countItem(Items.OAK_PLANKS));
		assertTrue(f.finish(door));
		assertEquals(3, f.bag.countItem(Items.OAK_DOOR));
		assertEquals(0, f.store.countItem(Items.OAK_DOOR));
		assertEquals(SettlementCargoDelivery.Status.NOT_AT_STORE, f.deliver(false).status());
		assertEquals(3, f.bag.countItem(Items.OAK_DOOR));
		assertEquals(3, f.deliver(true).moved());
		assertTrue(f.bag.isEmpty());
		assertEquals(3, SettlementMaterialCrafting.countPlain(f.store, Items.OAK_DOOR));
		assertEquals(2, SettlementMaterialCrafting.countPlain(f.store, Items.OAK_PLANKS));
	}

	@Test void doorArrivalCancellationBindingAndModeGuardsKeepPhysicalCargo() {
		Fixture away = new Fixture();
		away.supply();
		var notAtStore = away.prepare();
		assertFalse(SettlementWorkbenchCrafting.pickup(notAtStore, away.data, away.camp, ACTOR,
			away.bag, away.store, () -> false));
		assertEquals(6, away.store.countItem(Items.OAK_PLANKS));
		for (int scenario = 0; scenario < 4; scenario++) {
			Fixture f = new Fixture();
			f.supply();
			var ticket = f.prepare();
			assertTrue(f.pickup(ticket));
			switch (scenario) {
				case 0 -> SettlementWorkbenchCrafting.cancel(ticket);
				case 1 -> f.data.setStockpile(f.camp, ticket.store());
				case 2 -> {
					f.state().setProductionEnabled(false);
					f.state().setProductionEnabled(true);
				}
				case 3 -> assertFalse(SettlementWorkbenchCrafting.finish(ticket, f.data, f.camp,
					ACTOR, f.bag, () -> false));
			}
			assertFalse(f.finish(ticket));
			assertEquals(6, f.bag.countItem(Items.OAK_PLANKS));
			assertEquals(0, f.bag.countItem(Items.OAK_DOOR));
		}
	}

	@Test void unsupportedRecipesCannotStartAndTwoWorkersCannotClaimSameSixPlanks() {
		Fixture f = new Fixture();
		assertNull(SettlementWorkbenchCrafting.prepare(f.data, f.camp, ACTOR, f.bag, Recipe.STICKS, () -> true, () -> true));
		assertNull(SettlementWorkbenchCrafting.prepare(f.data, f.camp, ACTOR, f.bag, null, () -> true, () -> true));
		UUID other = new UUID(83, 2);
		f.data.registerMember(f.camp, other, 0);
		SimpleContainer otherBag = new SimpleContainer(8);
		var first = f.prepare();
		var second = SettlementWorkbenchCrafting.prepare(f.data, f.camp, other, otherBag, Recipe.DOOR, () -> true, () -> true);
		f.supply();
		assertTrue(f.pickup(first));
		assertFalse(SettlementWorkbenchCrafting.pickup(second, f.data, f.camp, other, otherBag, f.store, () -> true));
		assertTrue(otherBag.isEmpty());
		assertEquals(6, f.bag.countItem(Items.OAK_PLANKS));
	}

	private static SimpleContainer limitedBag(int slots, int limit) {
		return new SimpleContainer(slots) {
			@Override public int getMaxStackSize(ItemStack stack) { return limit; }
		};
	}

	private static final class Fixture {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(BlockPos.ZERO, "Door workbench", 0);
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
		void supply() { store.setItem(0, new ItemStack(Items.OAK_PLANKS, 6)); }
		SettlementSimulationState state() { return data.simulationOf(camp).orElseThrow(); }
		SettlementWorkbenchCrafting.Ticket prepare() { return SettlementWorkbenchCrafting.prepare(data, camp, ACTOR, bag, Recipe.DOOR, () -> true, demand::get); }
		boolean valid(SettlementWorkbenchCrafting.Ticket t) { return SettlementWorkbenchCrafting.valid(t, data, camp, ACTOR, bag); }
		boolean pickup(SettlementWorkbenchCrafting.Ticket t) { return SettlementWorkbenchCrafting.pickup(t, data, camp, ACTOR, bag, store, () -> true); }
		boolean finish(SettlementWorkbenchCrafting.Ticket t) { return SettlementWorkbenchCrafting.finish(t, data, camp, ACTOR, bag, () -> true); }
		SettlementCargoDelivery.Result deliver(boolean arrived) {
			var ticket = SettlementCargoDelivery.prepare(data, camp, ACTOR, bag, () -> true);
			return SettlementCargoDelivery.finish(ticket, data, camp, ACTOR, bag, store, () -> arrived);
		}
	}
}
