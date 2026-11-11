package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Physical container contracts only. No game/server is started. */
class SettlementWorkbenchTorchCraftingTest {
	private static final UUID ACTOR = new UUID(81, 1);
	private static final List<Recipe> RECIPES = List.of(Recipe.TORCH_COAL, Recipe.TORCH_CHARCOAL);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void bothExactFuelRecipesWithdrawLastInputsAndMakeFourOnlyOnce() {
		for (Recipe recipe : RECIPES) {
			Fixture f = new Fixture(recipe); f.supply();
			f.bag.setItem(7, new ItemStack(f.fuel(), 9)); // Personal plain fuel is never consumed.
			var t = f.prepare(); assertEquals(recipe, t.recipe());
			assertTrue(f.pickup(t)); assertTrue(f.store.isEmpty());
			assertTrue(f.valid(t)); assertTrue(f.finish(t)); assertFalse(f.finish(t));
			assertEquals(4, f.bag.countItem(Items.TORCH)); assertEquals(9, f.bag.countItem(f.fuel()));
			assertEquals(0, f.bag.countItem(Items.STICK));
			var event = f.state().world().ledger().events().stream().filter(e -> e.type() == EventType.CRAFT_COMPLETED).findFirst().orElseThrow();
			assertEquals(recipe.id(), event.context().get("recipe"));
			assertEquals("4", event.context().get("outputCount"));
			assertTrue(event.causalLinks().isEmpty());
			f.state().world().assertConservation();
		}
	}

	@Test void missingExactFuelCannotExportSticksOrSpendPersonalOrCustomFuel() {
		for (Recipe recipe : RECIPES) for (int scenario = 0; scenario < 3; scenario++) {
			Fixture f = new Fixture(recipe); f.abstractSticks(8);
			if (scenario == 0) f.store.setItem(0, new ItemStack(f.fuel() == Items.COAL ? Items.CHARCOAL : Items.COAL));
			if (scenario == 1) { ItemStack named = new ItemStack(f.fuel()); named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep")); f.store.setItem(0, named); }
			if (scenario == 2) f.bag.setItem(7, new ItemStack(f.fuel()));
			var store = snapshot(f.store); var bag = snapshot(f.bag);
			int events = f.state().world().ledger().events().size();
			assertFalse(f.pickup(f.prepare())); assertUnchanged(store, f.store); assertUnchanged(bag, f.bag);
			assertEquals(events, f.state().world().ledger().events().size());
		}
	}

	@Test void stickExportOccursOnlyAtStoreOnceAndFreezesExactProvenance() {
		for (Recipe recipe : RECIPES) {
			Fixture f = new Fixture(recipe); f.store.setItem(0, new ItemStack(f.fuel())); f.abstractSticks(3);
			int events = f.state().world().ledger().events().size();
			var away = f.prepare(); assertEquals(events, f.state().world().ledger().events().size());
			assertFalse(SettlementWorkbenchCrafting.pickup(away, f.data, f.camp, ACTOR, f.bag, f.store, () -> false));
			assertEquals(events, f.state().world().ledger().events().size());
			var t = f.prepare(); assertTrue(f.pickup(t));
			var exports = f.state().world().ledger().events().stream().filter(e -> e.type() == EventType.EXTERNAL_EXPORT).toList();
			assertEquals(1, exports.size()); assertEquals(1, f.bag.countItem(Items.STICK)); assertTrue(f.store.isEmpty());
			var batch = f.state().world().batchesAt(f.state().campOwner()).stream().filter(b -> b.resourceType() == ResourceType.STICK).findFirst().orElseThrow();
			assertEquals(2, batch.quantity());
			assertEquals(1, MinecraftStockpileAdapter.exportToContainer(f.state().world(), f.state().campOwner(), batch.batchId(), 1,
				f.store, List.of(f.state().residentId(ACTOR).orElseThrow()), Map.of("activity", "craft", "recipe", recipe.id())));
			assertTrue(f.finish(t));
			var complete = f.state().world().ledger().events().stream().filter(e -> e.type() == EventType.CRAFT_COMPLETED).findFirst().orElseThrow();
			assertEquals(List.of(exports.getFirst().eventId()), complete.causalLinks());
			assertEquals(1, f.store.countItem(Items.STICK));
			assertFalse(f.pickup(t)); assertFalse(f.finish(t));
			f.state().world().assertConservation();
		}
	}

	@Test void existingPhysicalStickPreventsAbstractExportAndDisabledModesCannotExport() {
		for (Recipe recipe : RECIPES) {
			Fixture f = new Fixture(recipe); f.supply(); f.abstractSticks(8);
			assertTrue(f.pickup(f.prepare()));
			assertFalse(f.state().world().ledger().events().stream().anyMatch(e -> e.type() == EventType.EXTERNAL_EXPORT));
			for (boolean production : new boolean[] { false, true }) {
				Fixture disabled = new Fixture(recipe); disabled.store.setItem(0, new ItemStack(disabled.fuel())); disabled.abstractSticks(8);
				if (production) disabled.state().setProductionEnabled(false); else disabled.state().setSurvivalEnabled(false, 1);
				assertFalse(disabled.pickup(disabled.prepare())); assertTrue(disabled.bag.isEmpty());
				assertEquals(1, disabled.store.countItem(disabled.fuel()));
			}
		}
	}

	@Test void fullBagRejectedOutputAndFullStorePreflightBeforeExport() {
		for (Recipe recipe : RECIPES) for (int scenario = 0; scenario < 4; scenario++) {
			SimpleContainer bag = scenario == 0 ? new SimpleContainer(1) : scenario == 1 ? new SimpleContainer(2) {
				@Override public boolean canPlaceItem(int slot, ItemStack stack) { return !stack.is(Items.TORCH); }
			} : new SimpleContainer(8);
			Fixture f = new Fixture(recipe, bag); f.store.setItem(0, new ItemStack(f.fuel())); f.abstractSticks(8);
			if (scenario == 2) for (int slot = 1; slot < 9; slot++) f.store.setItem(slot, new ItemStack(Items.DIRT, 64));
			if (scenario == 3) for (int slot = 0; slot < 8; slot++) bag.setItem(slot, new ItemStack(Items.DIRT, 64));
			var before = snapshot(f.store); int events = f.state().world().ledger().events().size();
			assertFalse(f.pickup(f.prepare())); assertUnchanged(before, f.store);
			assertEquals(events, f.state().world().ledger().events().size());
		}
	}

	@Test void changedCapacityAtStationPreservesInputsAndPermanentlyCancelsTicket() {
		AtomicBoolean allow = new AtomicBoolean(true);
		Fixture f = new Fixture(Recipe.TORCH_COAL, new SimpleContainer(2) {
			@Override public boolean canPlaceItem(int slot, ItemStack stack) { return !stack.is(Items.TORCH) || allow.get(); }
		});
		f.supply(); var t = f.prepare(); assertTrue(f.pickup(t)); var before = snapshot(f.bag);
		allow.set(false); assertFalse(f.finish(t)); assertUnchanged(before, f.bag);
		allow.set(true); assertFalse(f.finish(t));
	}

	@Test void demandBindingModeAndArrivalCancellationKeepAllCargo() {
		for (int scenario = 0; scenario < 5; scenario++) {
			Fixture f = new Fixture(Recipe.TORCH_CHARCOAL); f.supply(); var t = f.prepare(); assertTrue(f.pickup(t));
			var before = snapshot(f.bag);
			switch (scenario) {
				case 0 -> f.demand.set(false);
				case 1 -> { f.data.setStockpile(f.camp, t.store().offset(1, 0, 0)); f.data.setStockpile(f.camp, t.store()); }
				case 2 -> { f.data.setCraftingStation(f.camp, t.station().offset(1, 0, 0)); f.data.setCraftingStation(f.camp, t.station()); }
				case 3 -> { f.state().setProductionEnabled(false); f.state().setProductionEnabled(true); }
				case 4 -> assertFalse(SettlementWorkbenchCrafting.finish(t, f.data, f.camp, ACTOR, f.bag, () -> false));
			}
			assertFalse(f.finish(t)); assertUnchanged(before, f.bag);
			assertEquals(2, SettlementWorkbenchCargo.deposit(f.bag, f.store, f.camp.id()));
		}
	}

	@Test void mixedForeignMalformedDuplicateAndMissingInputsCannotFinish() {
		for (int scenario = 0; scenario < 6; scenario++) {
			Fixture f = new Fixture(Recipe.TORCH_COAL); f.supply(); var t = f.prepare(); assertTrue(f.pickup(t));
			int fuel = f.bag.getItem(0).is(Items.COAL) ? 0 : 1;
			switch (scenario) {
				case 0 -> f.bag.setItem(fuel, ItemStack.EMPTY);
				case 1 -> f.bag.setItem(2, f.bag.getItem(fuel).copy());
				case 2 -> f.bag.setItem(fuel, SettlementWorkbenchCargo.inputs(f.camp.id(), UUID.randomUUID(), Recipe.TORCH_COAL).stream().filter(s -> s.is(Items.COAL)).findFirst().orElseThrow());
				case 3 -> f.bag.setItem(fuel, SettlementWorkbenchCargo.inputs(UUID.randomUUID(), UUID.randomUUID(), Recipe.TORCH_COAL).stream().filter(s -> s.is(Items.COAL)).findFirst().orElseThrow());
				case 4 -> f.bag.getItem(fuel).set(DataComponents.CUSTOM_NAME, Component.literal("Changed"));
				case 5 -> f.bag.setItem(2, SettlementWorkbenchCargo.output(f.camp.id(), UUID.randomUUID(), Recipe.TORCH_CHARCOAL));
			}
			var before = snapshot(f.bag); assertFalse(f.valid(t)); assertFalse(f.finish(t)); assertUnchanged(before, f.bag);
		}
	}

	@Test void fourTorchesReturnThenTwoConstructionPaymentsLeaveEnoughToSuppressNextBatch() {
		Fixture f = new Fixture(Recipe.TORCH_COAL); f.supply(); var t = f.prepare();
		assertTrue(f.pickup(t)); assertTrue(f.finish(t));
		var delivery = SettlementCargoDelivery.prepare(f.data, f.camp, ACTOR, f.bag, () -> true);
		assertEquals(4, SettlementCargoDelivery.finish(delivery, f.data, f.camp, ACTOR, f.bag, f.store, () -> true).moved());
		for (int i = 0; i < 2; i++) assertTrue(ConstructionMaterials.place(f.store, f.state(), Items.TORCH, true, null, ACTOR, () -> {}, () -> true));
		assertEquals(2, ConstructionMaterials.available(f.store, f.state(), Items.TORCH));
		assertNull(SettlementWorkbenchCrafting.prepare(f.data, f.camp, ACTOR, f.bag, f.recipe, () -> true,
			() -> SettlementMaterialCrafting.countPlain(f.store, Items.TORCH) < 2));
	}

	@Test void canceledAfterExportLeavesOneRealStickButCannotRetryOrAwardCompletion() {
		Fixture f = new Fixture(Recipe.TORCH_COAL); f.store.setItem(0, new ItemStack(Items.COAL)); f.abstractSticks(3);
		var t = SettlementWorkbenchCrafting.prepare(f.data, f.camp, ACTOR, f.bag, f.recipe,
			() -> f.state().world().ledger().events().stream().noneMatch(e -> e.type() == EventType.EXTERNAL_EXPORT), () -> true);
		assertNotNull(t); assertFalse(f.pickup(t));
		assertTrue(f.bag.isEmpty()); assertEquals(1, f.store.countItem(Items.STICK)); assertEquals(1, f.store.countItem(Items.COAL));
		assertFalse(f.pickup(t)); assertFalse(f.finish(t));
		assertEquals(1, f.state().world().ledger().events().stream().filter(e -> e.type() == EventType.EXTERNAL_EXPORT).count());
		assertFalse(f.state().world().ledger().events().stream().anyMatch(e -> e.type() == EventType.CRAFT_COMPLETED));
		f.state().world().assertConservation();
	}

	@Test void anyExistingCargoBlocksPreparingAndAnotherInventoryCannotResumeTicket() {
		for (Recipe recipe : RECIPES) {
			Fixture f = new Fixture(recipe);
			for (ItemStack existing : List.of(SettlementWorkbenchCargo.output(f.camp.id(), UUID.randomUUID(), recipe),
				SettlementWorkbenchCargo.output(UUID.randomUUID(), UUID.randomUUID(), recipe),
				SettlementWorkbenchCargo.output(f.camp.id(), UUID.randomUUID(), recipe).copyWithCount(5))) {
				f.bag.setItem(0, existing); assertNull(f.prepare()); assertTrue(ItemStack.matches(existing, f.bag.getItem(0)));
			}
			f.bag.setItem(0, ItemStack.EMPTY); f.supply(); var t = f.prepare(); assertTrue(f.pickup(t));
			SimpleContainer replacement = new SimpleContainer(snapshot(f.bag));
			assertFalse(SettlementWorkbenchCrafting.finish(t, f.data, f.camp, ACTOR, replacement, () -> true));
			assertEquals(0, replacement.countItem(Items.TORCH));
			assertEquals(2, SettlementWorkbenchCargo.deposit(replacement, f.store, f.camp.id()));
		}
	}

	private static ItemStack[] snapshot(SimpleContainer c) {
		ItemStack[] result = new ItemStack[c.getContainerSize()];
		for (int i = 0; i < result.length; i++) result[i] = c.getItem(i).copy(); return result;
	}
	private static void assertUnchanged(ItemStack[] before, SimpleContainer c) {
		for (int i = 0; i < before.length; i++) assertTrue(ItemStack.matches(before[i], c.getItem(i)), "slot " + i);
	}
	private static final class Fixture {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(BlockPos.ZERO, "Torch workbench", 0);
		final SimpleContainer bag, store = new SimpleContainer(9);
		final Recipe recipe;
		final AtomicBoolean demand = new AtomicBoolean(true);
		Fixture(Recipe recipe) { this(recipe, new SimpleContainer(8)); }
		Fixture(Recipe recipe, SimpleContainer bag) {
			this.recipe = recipe; this.bag = bag;
			data.registerMember(camp, ACTOR, 0); data.setStockpile(camp, new BlockPos(1, 0, 0)); data.setCraftingStation(camp, new BlockPos(3, 0, 0));
			var s = data.getOrCreateSimulation(camp); s.mapResident(ACTOR, false, 0); s.setSurvivalEnabled(true, 0); s.setProductionEnabled(true);
		}
		Item fuel() { return recipe == Recipe.TORCH_COAL ? Items.COAL : Items.CHARCOAL; }
		void supply() { store.setItem(0, new ItemStack(fuel())); store.setItem(1, new ItemStack(Items.STICK)); }
		void abstractSticks(int count) { state().world().importExternalBatch(ResourceType.STICK, count, state().campOwner()); }
		SettlementSimulationState state() { return data.simulationOf(camp).orElseThrow(); }
		SettlementWorkbenchCrafting.Ticket prepare() { return SettlementWorkbenchCrafting.prepare(data, camp, ACTOR, bag, recipe, () -> true, demand::get); }
		boolean valid(SettlementWorkbenchCrafting.Ticket t) { return SettlementWorkbenchCrafting.valid(t, data, camp, ACTOR, bag); }
		boolean pickup(SettlementWorkbenchCrafting.Ticket t) { return SettlementWorkbenchCrafting.pickup(t, data, camp, ACTOR, bag, store, () -> true); }
		boolean finish(SettlementWorkbenchCrafting.Ticket t) { return SettlementWorkbenchCrafting.finish(t, data, camp, ACTOR, bag, () -> true); }
	}
}
