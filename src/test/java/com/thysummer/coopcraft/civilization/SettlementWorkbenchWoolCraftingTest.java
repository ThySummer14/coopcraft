package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
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

class SettlementWorkbenchWoolCraftingTest {
	private static final UUID ACTOR = new UUID(85, 1);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void crossSlotPickupAndSplitCustodyConserveFourStringsIntoOneWool() {
		Fixture f = new Fixture(limitedBag(2, 2));
		f.store.setItem(0, new ItemStack(Items.STRING, 2));
		f.store.setItem(1, new ItemStack(Items.STRING, 2));
		var ticket = f.prepare();
		assertEquals(Recipe.WOOL, ticket.recipe());
		assertTrue(f.pickup(ticket));
		assertTrue(f.store.isEmpty());
		assertEquals(2, f.bag.getItem(0).getCount());
		assertEquals(2, f.bag.getItem(1).getCount());
		assertTrue(f.valid(ticket));
		assertTrue(f.finish(ticket));
		assertEquals(0, f.bag.countItem(Items.STRING));
		assertEquals(1, f.bag.countItem(Items.WHITE_WOOL));
		assertEquals(0, f.store.countItem(Items.WHITE_WOOL));
		assertFalse(f.finish(ticket));
		var completed = f.state().world().ledger().events().stream()
			.filter(e -> e.type() == EventType.CRAFT_COMPLETED).findFirst().orElseThrow();
		assertEquals("material_wool", completed.context().get("recipe"));
		assertEquals("1", completed.context().get("outputCount"));
		assertEquals("minecraft:white_wool", completed.context().get("outputItem"));
		assertTrue(completed.causalLinks().isEmpty());
		assertFalse(f.state().world().ledger().events().stream().anyMatch(e -> e.type() == EventType.EXTERNAL_EXPORT));
		f.state().world().assertConservation();
	}

	@Test void threeRealStringsCannotBeSupplementedByAbstractWoodOrCustomStrings() {
		Fixture f = new Fixture();
		f.store.setItem(0, new ItemStack(Items.STRING, 2));
		f.store.setItem(1, new ItemStack(Items.STRING, 1));
		ItemStack named = new ItemStack(Items.STRING);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep"));
		f.store.setItem(2, named);
		f.state().world().importExternalBatch(ResourceType.WOOD, 64, f.state().campOwner());
		int events = f.state().world().ledger().events().size();
		assertFalse(f.pickup(f.prepare()));
		assertEquals(3, SettlementMaterialCrafting.countPlain(f.store, Items.STRING));
		assertTrue(ItemStack.matches(named, f.store.getItem(2)));
		assertTrue(f.bag.isEmpty());
		assertEquals(events, f.state().world().ledger().events().size());
	}

	@Test void completeInputAndCompleteOutputCapacityAreRequiredBeforeWithdrawal() {
		Fixture tooSmall = new Fixture(limitedBag(1, 3));
		tooSmall.supply();
		assertFalse(tooSmall.pickup(tooSmall.prepare()));
		assertEquals(4, tooSmall.store.countItem(Items.STRING));
		assertTrue(tooSmall.bag.isEmpty());
		Fixture noOutputSpace = new Fixture(new SimpleContainer(1) {
			@Override public boolean canPlaceItem(int slot, ItemStack stack) { return !stack.is(Items.WHITE_WOOL); }
		});
		noOutputSpace.supply();
		assertFalse(noOutputSpace.pickup(noOutputSpace.prepare()));
		assertEquals(4, noOutputSpace.store.countItem(Items.STRING));
		assertTrue(noOutputSpace.bag.isEmpty());
	}

	@Test void capacityChangesAtStationPreserveAllFourInputsAndConsumeTicket() {
		AtomicBoolean outputAllowed = new AtomicBoolean(true);
		Fixture f = new Fixture(new SimpleContainer(1) {
			@Override public boolean canPlaceItem(int slot, ItemStack stack) {
				return !stack.is(Items.WHITE_WOOL) || outputAllowed.get();
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
		assertEquals(0, f.bag.countItem(Items.WHITE_WOOL));
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
				case 3 -> f.bag.setItem(0, SettlementWorkbenchCargo.input(f.camp.id(), UUID.randomUUID(), Recipe.WOOL));
				case 4 -> f.bag.setItem(0, SettlementWorkbenchCargo.input(UUID.randomUUID(), UUID.randomUUID(), Recipe.WOOL));
			}
			int before = f.bag.countItem(Items.STRING);
			assertFalse(f.valid(ticket));
			assertFalse(f.finish(ticket));
			assertEquals(before, f.bag.countItem(Items.STRING));
			assertEquals(0, f.bag.countItem(Items.WHITE_WOOL));
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
		assertEquals(2, f.bag.countItem(Items.STRING));
		assertTrue(SettlementWorkbenchCargo.hasReturnable(f.bag, f.camp.id()));
		assertEquals(2, SettlementWorkbenchCargo.deposit(f.bag, f.store, f.camp.id()));
		assertTrue(f.bag.isEmpty());
		assertEquals(4, partial.countItem(Items.STRING) + f.store.countItem(Items.STRING));
	}

	@Test void twelveStringsTravelAsThreeWoolBatchesThenThreePlanksBecomeOneReturnedBed() {
		Fixture f = new Fixture();
		f.store.setItem(0, new ItemStack(Items.STRING, 12));
		f.store.setItem(1, new ItemStack(Items.OAK_PLANKS, 3));
		BooleanSupplier needsWool = () -> SettlementWoolSupply.nextBedNeedsWool(
			List.of(Map.of(Items.WHITE_BED, 1)), f.store, f.state());
		for (int batch = 0; batch < 3; batch++) {
			var wool = SettlementWorkbenchCrafting.prepare(f.data, f.camp, ACTOR, f.bag,
				Recipe.WOOL, () -> true, needsWool);
			assertNotNull(wool);
			assertTrue(f.pickup(wool));
			assertEquals(8 - 4 * batch, f.store.countItem(Items.STRING));
			assertTrue(needsWool.getAsBoolean());
			assertTrue(f.valid(wool));
			assertEquals(batch, f.store.countItem(Items.WHITE_WOOL));
			assertTrue(f.finish(wool));
			assertEquals(1, f.bag.countItem(Items.WHITE_WOOL));
			assertEquals(SettlementCargoDelivery.Status.NOT_AT_STORE, f.deliver(false).status());
			assertEquals(batch, f.store.countItem(Items.WHITE_WOOL));
			assertEquals(1, f.deliver(true).moved());
			assertTrue(f.bag.isEmpty());
		}
		assertEquals(3, SettlementMaterialCrafting.countPlain(f.store, Items.WHITE_WOOL));
		assertFalse(needsWool.getAsBoolean());
		assertNull(SettlementWorkbenchCrafting.prepare(f.data, f.camp, ACTOR, f.bag,
			Recipe.WOOL, () -> true, needsWool));
		var bed = SettlementWorkbenchCrafting.prepare(f.data, f.camp, ACTOR, f.bag,
			Recipe.BED, () -> true, () -> true);
		assertNotNull(bed);
		assertTrue(f.pickup(bed));
		assertTrue(f.store.isEmpty());
		assertTrue(f.finish(bed));
		assertEquals(1, f.bag.countItem(Items.WHITE_BED));
		assertEquals(0, f.store.countItem(Items.WHITE_BED));
		assertEquals(SettlementCargoDelivery.Status.NOT_AT_STORE, f.deliver(false).status());
		assertEquals(1, f.deliver(true).moved());
		assertTrue(f.bag.isEmpty());
		assertEquals(1, SettlementMaterialCrafting.countPlain(f.store, Items.WHITE_BED));
		assertFalse(needsWool.getAsBoolean());
		assertNull(SettlementWorkbenchCrafting.prepare(f.data, f.camp, ACTOR, f.bag,
			Recipe.WOOL, () -> true, needsWool));
		assertEquals(0, f.store.countItem(Items.STRING));
		assertEquals(0, f.store.countItem(Items.WHITE_WOOL));
		assertEquals(0, f.store.countItem(Items.OAK_PLANKS));
		assertEquals(4, f.state().world().ledger().events().stream()
			.filter(e -> e.type() == EventType.CRAFT_COMPLETED).count());
		assertFalse(f.state().world().ledger().events().stream().anyMatch(e -> e.type() == EventType.EXTERNAL_EXPORT));
		f.state().world().assertConservation();
	}

	@Test void realBedDemandSatisfiedInTransitCancelsAndReturnsAllFourStrings() {
		for (boolean suppliedBed : new boolean[] { false, true }) {
			Fixture f = new Fixture();
			f.supply();
			BooleanSupplier needsWool = () -> SettlementWoolSupply.nextBedNeedsWool(
				List.of(Map.of(Items.WHITE_BED, 1)), f.store, f.state());
			var ticket = SettlementWorkbenchCrafting.prepare(f.data, f.camp, ACTOR, f.bag,
				Recipe.WOOL, () -> true, needsWool);
			assertNotNull(ticket);
			assertTrue(f.pickup(ticket));
			ItemStack inputs = f.bag.getItem(0).copy();
			f.store.setItem(0, suppliedBed ? new ItemStack(Items.WHITE_BED) : new ItemStack(Items.WHITE_WOOL, 3));
			assertFalse(needsWool.getAsBoolean());
			assertFalse(f.finish(ticket));
			assertTrue(ItemStack.matches(inputs, f.bag.getItem(0)));
			assertEquals(4, f.bag.countItem(Items.STRING));
			assertEquals(0, f.bag.countItem(Items.WHITE_WOOL));
			assertEquals(4, f.deliver(true).moved());
			assertTrue(f.bag.isEmpty());
			assertEquals(4, SettlementMaterialCrafting.countPlain(f.store, Items.STRING));
			assertEquals(suppliedBed ? 1 : 0, f.store.countItem(Items.WHITE_BED));
			assertEquals(suppliedBed ? 0 : 3, f.store.countItem(Items.WHITE_WOOL));
		}
	}

	@Test void concurrentBatchesMayExceedNextBedTargetButConserveEightStringsIntoTwoWool() {
		Fixture f = new Fixture();
		UUID other = new UUID(85, 3);
		f.data.registerMember(f.camp, other, 0);
		f.state().mapResident(other, false, 0);
		SimpleContainer otherBag = new SimpleContainer(8);
		f.store.setItem(0, new ItemStack(Items.WHITE_WOOL, 2));
		f.store.setItem(1, new ItemStack(Items.STRING, 8));
		BooleanSupplier needsWool = () -> SettlementWoolSupply.nextBedNeedsWool(
			List.of(Map.of(Items.WHITE_BED, 1)), f.store, f.state());
		var first = SettlementWorkbenchCrafting.prepare(f.data, f.camp, ACTOR, f.bag,
			Recipe.WOOL, () -> true, needsWool);
		var second = SettlementWorkbenchCrafting.prepare(f.data, f.camp, other, otherBag,
			Recipe.WOOL, () -> true, needsWool);
		assertNotNull(first); assertNotNull(second);
		assertTrue(f.pickup(first));
		assertTrue(SettlementWorkbenchCrafting.pickup(second, f.data, f.camp, other, otherBag, f.store, () -> true));
		assertEquals(0, f.store.countItem(Items.STRING));
		assertEquals(4, f.bag.countItem(Items.STRING));
		assertEquals(4, otherBag.countItem(Items.STRING));
		// No village-wide reservation: both workers may finish before either product arrives at the store.
		assertTrue(f.finish(first));
		assertTrue(SettlementWorkbenchCrafting.finish(second, f.data, f.camp, other, otherBag, () -> true));
		assertEquals(1, f.bag.countItem(Items.WHITE_WOOL));
		assertEquals(1, otherBag.countItem(Items.WHITE_WOOL));
		assertEquals(2, f.store.countItem(Items.WHITE_WOOL));
		assertEquals(1, f.deliver(true).moved());
		assertFalse(needsWool.getAsBoolean());
		var delivery = SettlementCargoDelivery.prepare(f.data, f.camp, other, otherBag, () -> true);
		assertEquals(1, SettlementCargoDelivery.finish(delivery, f.data, f.camp, other, otherBag, f.store, () -> true).moved());
		assertTrue(f.bag.isEmpty()); assertTrue(otherBag.isEmpty());
		assertEquals(4, SettlementMaterialCrafting.countPlain(f.store, Items.WHITE_WOOL));
		assertEquals(0, f.store.countItem(Items.STRING));
		assertNull(SettlementWorkbenchCrafting.prepare(f.data, f.camp, ACTOR, f.bag,
			Recipe.WOOL, () -> true, needsWool));
		assertNull(SettlementWorkbenchCrafting.prepare(f.data, f.camp, other, otherBag,
			Recipe.WOOL, () -> true, needsWool));
		assertFalse(f.finish(first));
		assertFalse(SettlementWorkbenchCrafting.finish(second, f.data, f.camp, other, otherBag, () -> true));
		assertEquals(0, f.deliver(true).moved());
		assertEquals(4, SettlementMaterialCrafting.countPlain(f.store, Items.WHITE_WOOL));
		assertEquals(2, f.state().world().ledger().events().stream()
			.filter(e -> e.type() == EventType.CRAFT_COMPLETED).count());
		f.state().world().assertConservation();
	}

	@Test void woolArrivalCancellationBindingAndModeGuardsKeepPhysicalCargo() {
		Fixture away = new Fixture();
		away.supply();
		var notAtStore = away.prepare();
		assertFalse(SettlementWorkbenchCrafting.pickup(notAtStore, away.data, away.camp, ACTOR,
			away.bag, away.store, () -> false));
		assertEquals(4, away.store.countItem(Items.STRING));
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
			assertEquals(4, f.bag.countItem(Items.STRING));
			assertEquals(0, f.bag.countItem(Items.WHITE_WOOL));
		}
	}

	@Test void unsupportedRecipesCannotStartAndTwoWorkersCannotClaimLastFourStrings() {
		Fixture f = new Fixture();
		assertNull(SettlementWorkbenchCrafting.prepare(f.data, f.camp, ACTOR, f.bag, Recipe.CAMPFIRE_COAL, () -> true, () -> true));
		assertNull(SettlementWorkbenchCrafting.prepare(f.data, f.camp, ACTOR, f.bag, null, () -> true, () -> true));
		UUID other = new UUID(85, 2);
		f.data.registerMember(f.camp, other, 0);
		SimpleContainer otherBag = new SimpleContainer(8);
		var first = f.prepare();
		var second = SettlementWorkbenchCrafting.prepare(f.data, f.camp, other, otherBag, Recipe.WOOL, () -> true, () -> true);
		f.supply();
		assertTrue(f.pickup(first));
		assertFalse(SettlementWorkbenchCrafting.pickup(second, f.data, f.camp, other, otherBag, f.store, () -> true));
		assertTrue(otherBag.isEmpty());
		assertEquals(4, f.bag.countItem(Items.STRING));
	}

	private static SimpleContainer limitedBag(int slots, int limit) {
		return new SimpleContainer(slots) {
			@Override public int getMaxStackSize(ItemStack stack) { return limit; }
		};
	}

	private static final class Fixture {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(BlockPos.ZERO, "Wool workbench", 0);
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
		void supply() { store.setItem(0, new ItemStack(Items.STRING, 4)); }
		SettlementSimulationState state() { return data.simulationOf(camp).orElseThrow(); }
		SettlementWorkbenchCrafting.Ticket prepare() { return SettlementWorkbenchCrafting.prepare(data, camp, ACTOR, bag, Recipe.WOOL, () -> true, demand::get); }
		boolean valid(SettlementWorkbenchCrafting.Ticket t) { return SettlementWorkbenchCrafting.valid(t, data, camp, ACTOR, bag); }
		boolean pickup(SettlementWorkbenchCrafting.Ticket t) { return SettlementWorkbenchCrafting.pickup(t, data, camp, ACTOR, bag, store, () -> true); }
		boolean finish(SettlementWorkbenchCrafting.Ticket t) { return SettlementWorkbenchCrafting.finish(t, data, camp, ACTOR, bag, () -> true); }
		SettlementCargoDelivery.Result deliver(boolean arrived) {
			var ticket = SettlementCargoDelivery.prepare(data, camp, ACTOR, bag, () -> true);
			return SettlementCargoDelivery.finish(ticket, data, camp, ACTOR, bag, store, () -> arrived);
		}
	}
}
