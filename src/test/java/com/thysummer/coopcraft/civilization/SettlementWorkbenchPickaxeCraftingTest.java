package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
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

class SettlementWorkbenchPickaxeCraftingTest {
	private static final UUID ACTOR = new UUID(83, 1);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void lastFullBatchCraftsOneMarkedToolAndNeverSpendsPersonalMaterials() {
		Fixture f = new Fixture(); f.supply();
		f.bag.setItem(6, new ItemStack(Items.COBBLESTONE, 9)); f.bag.setItem(7, new ItemStack(Items.STICK, 8));
		var t = f.prepare(); assertNotNull(t); assertTrue(f.pickup(t)); assertTrue(f.store.isEmpty());
		assertTrue(f.finish(t)); assertFalse(f.finish(t));
		assertEquals(9, f.bag.countItem(Items.COBBLESTONE)); assertEquals(8, f.bag.countItem(Items.STICK));
		assertEquals(1, f.bag.countItem(Items.STONE_PICKAXE));
		assertTrue(SettlementWorkbenchCrafting.matchesFinishedPickaxe(t, f.bag.getItem(0)));
		var event = f.state().world().ledger().events().stream().filter(e -> e.type() == EventType.CRAFT_COMPLETED).findFirst().orElseThrow();
		assertEquals("stone_pickaxe", event.context().get("recipe")); assertEquals("1", event.context().get("outputCount"));
		assertTrue(event.causalLinks().isEmpty()); f.state().world().assertConservation();
	}

	@Test void shortagesPersonalAndCustomIngredientsCannotSupplyBatch() {
		for (int scenario = 0; scenario < 4; scenario++) {
			Fixture f = new Fixture(); f.supply();
			if (scenario == 0) f.store.setItem(0, new ItemStack(Items.COBBLESTONE, 2));
			if (scenario == 1) f.store.setItem(1, new ItemStack(Items.STICK));
			if (scenario == 2) f.store.getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("Keep"));
			if (scenario == 3) f.store.getItem(1).set(DataComponents.CUSTOM_NAME, Component.literal("Keep"));
			f.bag.setItem(6, new ItemStack(Items.COBBLESTONE, 64)); f.bag.setItem(7, new ItemStack(Items.STICK, 64));
			var store = snapshot(f.store); var bag = snapshot(f.bag);
			assertFalse(f.pickup(f.prepare())); unchanged(store, f.store); unchanged(bag, f.bag);
		}
	}

	@Test void bothAbstractDeficitsAreBoundedAndSealedBeforeWalking() {
		Fixture f = new Fixture(); f.abstractInputs(7, 6);
		f.store.setItem(0, new ItemStack(Items.COBBLESTONE)); f.store.setItem(1, new ItemStack(Items.STICK));
		var t = f.prepare(); assertTrue(f.pickup(t)); assertTrue(f.store.isEmpty());
		assertEquals(5, f.abstractCount(ResourceType.STONE)); assertEquals(5, f.abstractCount(ResourceType.STICK));
		var exports = f.state().world().ledger().events().stream().filter(e -> e.type() == EventType.EXTERNAL_EXPORT).map(e -> e.eventId()).toList();
		assertEquals(2, exports.size());
		var batch = f.state().world().batchesAt(f.state().campOwner()).stream().filter(b -> b.resourceType() == ResourceType.STONE).findFirst().orElseThrow();
		assertEquals(1, MinecraftStockpileAdapter.exportToContainer(f.state().world(), f.state().campOwner(), batch.batchId(), 1,
			f.store, List.of(f.state().residentId(ACTOR).orElseThrow()), Map.of("activity", "craft", "recipe", "stone_pickaxe")));
		assertTrue(f.finish(t));
		var completed = f.state().world().ledger().events().stream().filter(e -> e.type() == EventType.CRAFT_COMPLETED).findFirst().orElseThrow();
		assertEquals(exports, completed.causalLinks()); f.state().world().assertConservation();
	}

	@Test void absentOtherIngredientAndTotalExportSpacePreflightPreventAllExports() {
		for (int scenario = 0; scenario < 5; scenario++) {
			SimpleContainer bag = scenario == 2 ? new SimpleContainer(1) : scenario == 3 ? new SimpleContainer(8) {
				@Override public boolean canPlaceItem(int slot, ItemStack stack) { return !stack.is(Items.STONE_PICKAXE); }
			} : new SimpleContainer(8);
			Fixture f = new Fixture(bag); f.abstractInputs(9, scenario == 0 ? 1 : 9);
			if (scenario == 1) for (int i = 0; i < 8; i++) f.store.setItem(i, new ItemStack(Items.DIRT, 64)); // Only one free slot, but two exports.
			if (scenario == 4) for (int i = 0; i < 8; i++) f.bag.setItem(i, new ItemStack(Items.DIRT, 64));
			var before = snapshot(f.store); int events = f.state().world().ledger().events().size();
			assertFalse(f.pickup(f.prepare())); unchanged(before, f.store);
			assertEquals(events, f.state().world().ledger().events().size());
		}
	}

	@Test void exportsRequireActualArrivalAndEnabledModes() {
		for (int scenario = 0; scenario < 3; scenario++) {
			Fixture f = new Fixture(); f.abstractInputs(3, 2);
			if (scenario == 1) f.state().setProductionEnabled(false);
			if (scenario == 2) f.state().setSurvivalEnabled(false, 1);
			int events = f.state().world().ledger().events().size();
			boolean arrived = scenario != 0;
			assertFalse(SettlementWorkbenchCrafting.pickup(f.prepare(), f.data, f.camp, ACTOR, f.bag, f.store, () -> arrived));
			assertEquals(events, f.state().world().ledger().events().size()); assertTrue(f.bag.isEmpty()); assertTrue(f.store.isEmpty());
		}
	}

	@Test void laterExportFailureRetainsEarlierRealItemsWithoutAbstractDuplication() {
		Fixture f = new Fixture(); f.abstractInputs(3, 2);
		var t = SettlementWorkbenchCrafting.prepare(f.data, f.camp, ACTOR, f.bag, Recipe.STONE_PICKAXE,
			() -> f.state().world().ledger().events().stream().noneMatch(e -> e.type() == EventType.EXTERNAL_EXPORT), () -> true);
		assertFalse(f.pickup(t)); assertTrue(f.bag.isEmpty());
		assertEquals(3, f.store.countItem(Items.COBBLESTONE)); assertEquals(0, f.abstractCount(ResourceType.STONE));
		assertEquals(0, f.store.countItem(Items.STICK)); assertEquals(2, f.abstractCount(ResourceType.STICK));
		assertFalse(f.pickup(t)); assertFalse(f.finish(t)); f.state().world().assertConservation();
	}

	@Test void cancellationAndChangedArrivalAuthorityPreserveWholeInputs() {
		for (int scenario = 0; scenario < 5; scenario++) {
			Fixture f = new Fixture(); f.supply(); var t = f.prepare(); assertTrue(f.pickup(t)); var before = snapshot(f.bag);
			if (scenario == 0) SettlementWorkbenchCrafting.cancel(t);
			if (scenario == 1) f.demand.set(false);
			if (scenario == 2) { f.data.setCraftingStation(f.camp, t.station().above()); f.data.setCraftingStation(f.camp, t.station()); }
			if (scenario == 3) { f.state().setProductionEnabled(false); f.state().setProductionEnabled(true); }
			if (scenario == 4) assertFalse(SettlementWorkbenchCrafting.finish(t, f.data, f.camp, ACTOR, f.bag, () -> { f.demand.set(false); return true; }));
			assertFalse(f.finish(t)); unchanged(before, f.bag); assertEquals(5, SettlementWorkbenchCargo.deposit(f.bag, f.store, f.camp.id()));
		}
	}

	@Test void missingDuplicatedForeignAndModifiedCustodyCannotFinish() {
		for (int scenario = 0; scenario < 5; scenario++) {
			Fixture f = new Fixture(); f.supply(); var t = f.prepare(); assertTrue(f.pickup(t));
			switch (scenario) {
				case 0 -> f.bag.getItem(0).shrink(1);
				case 1 -> f.bag.setItem(2, f.bag.getItem(0).copyWithCount(1));
				case 2 -> f.bag.setItem(0, SettlementWorkbenchCargo.inputs(f.camp.id(), UUID.randomUUID(), Recipe.STONE_PICKAXE).getFirst());
				case 3 -> f.bag.getItem(1).set(DataComponents.CUSTOM_NAME, Component.literal("Changed"));
				case 4 -> f.bag.setItem(2, SettlementWorkbenchCargo.output(f.camp.id(), UUID.randomUUID(), Recipe.PLANKS));
			}
			var before = snapshot(f.bag); assertFalse(f.finish(t)); unchanged(before, f.bag);
			assertEquals(0, f.bag.countItem(Items.STONE_PICKAXE));
		}
	}

	@Test void changedOutputCapacityAtTableKeepsInputsAndCancelsTicket() {
		AtomicBoolean allow = new AtomicBoolean(true);
		Fixture f = new Fixture(new SimpleContainer(2) {
			@Override public boolean canPlaceItem(int slot, ItemStack stack) { return !stack.is(Items.STONE_PICKAXE) || allow.get(); }
		});
		f.supply(); var t = f.prepare(); assertTrue(f.pickup(t)); var before = snapshot(f.bag);
		allow.set(false); assertFalse(f.finish(t)); unchanged(before, f.bag);
		allow.set(true); assertFalse(f.finish(t));
	}

	@Test void cancellationInsideFinalWorldOrDemandCallbackPreservesInputs() {
		for (boolean cancelFromWorld : new boolean[] { false, true }) {
			Fixture f = new Fixture(); f.supply();
			AtomicBoolean finalCheck = new AtomicBoolean(false);
			SettlementWorkbenchCrafting.Ticket[] holder = new SettlementWorkbenchCrafting.Ticket[1];
			BooleanSupplier cancel = () -> {
				if (finalCheck.get()) SettlementWorkbenchCrafting.cancel(holder[0]);
				return true;
			};
			holder[0] = SettlementWorkbenchCrafting.prepare(f.data, f.camp, ACTOR, f.bag, Recipe.STONE_PICKAXE,
				cancelFromWorld ? cancel : () -> true, cancelFromWorld ? () -> true : cancel);
			assertNotNull(holder[0]); assertTrue(f.pickup(holder[0])); var before = snapshot(f.bag);
			assertFalse(SettlementWorkbenchCrafting.finish(holder[0], f.data, f.camp, ACTOR, f.bag,
				() -> { finalCheck.set(true); return true; }));
			unchanged(before, f.bag); assertEquals(0, f.bag.countItem(Items.STONE_PICKAXE));
			assertFalse(f.finish(holder[0]));
			assertFalse(f.state().world().ledger().events().stream().anyMatch(e -> e.type() == EventType.CRAFT_COMPLETED));
		}
	}

	@Test void storeArrivalBindingChangeImmediatelyBeforeExportPreventsExport() {
		Fixture f = new Fixture(); f.abstractInputs(3, 2); var t = f.prepare();
		AtomicInteger arrivals = new AtomicInteger();
		int events = f.state().world().ledger().events().size();
		assertFalse(SettlementWorkbenchCrafting.pickup(t, f.data, f.camp, ACTOR, f.bag, f.store, () -> {
			if (arrivals.incrementAndGet() == 2) {
				f.data.setStockpile(f.camp, t.store().above()); f.data.setStockpile(f.camp, t.store());
			}
			return true;
		}));
		assertEquals(2, arrivals.get()); assertTrue(f.bag.isEmpty()); assertTrue(f.store.isEmpty());
		assertEquals(3, f.abstractCount(ResourceType.STONE)); assertEquals(2, f.abstractCount(ResourceType.STICK));
		assertEquals(events, f.state().world().ledger().events().size());
	}

	@Test void completeIdentityAllowsOnlyOneEquipmentClaimAndIgnoresFulfilledDemand() {
		Fixture f = new Fixture(); f.supply(); var t = f.prepare(); assertTrue(f.pickup(t)); assertTrue(f.finish(t)); f.demand.set(false);
		assertTrue(SettlementWorkbenchCrafting.isFinishedPickaxeCurrent(t, f.data, f.camp, ACTOR, f.bag));
		assertTrue(SettlementWorkbenchCrafting.claimFinishedPickaxe(t, f.data, f.camp, ACTOR, f.bag));
		assertFalse(SettlementWorkbenchCrafting.claimFinishedPickaxe(t, f.data, f.camp, ACTOR, f.bag));
		f.data.setStockpile(f.camp, t.store().above()); f.data.setStockpile(f.camp, t.store());
		assertFalse(SettlementWorkbenchCrafting.isFinishedPickaxeCurrent(t, f.data, f.camp, ACTOR, f.bag));
		assertEquals(1, SettlementWorkbenchCargo.deposit(f.bag, f.store, f.camp.id()));
	}

	@Test void pickaxesNeverBecomeConstructionDrivenStockpileCrafts() {
		Fixture f = new Fixture(); f.supply();
		assertTrue(SettlementMaterialCrafting.chooseRecipe(f.store, f.state(), Map.of(Items.STONE_PICKAXE, 1)).isEmpty());
		assertFalse(SettlementMaterialCrafting.craft(f.store, f.state(), Recipe.STONE_PICKAXE, ACTOR, () -> fail("dirty")));
		assertEquals(3, f.store.countItem(Items.COBBLESTONE)); assertEquals(2, f.store.countItem(Items.STICK));
	}

	private static ItemStack[] snapshot(SimpleContainer c) {
		ItemStack[] result = new ItemStack[c.getContainerSize()];
		for (int i = 0; i < result.length; i++) result[i] = c.getItem(i).copy(); return result;
	}
	private static void unchanged(ItemStack[] before, SimpleContainer c) {
		for (int i = 0; i < before.length; i++) assertTrue(ItemStack.matches(before[i], c.getItem(i)), "slot " + i);
	}
	private static final class Fixture {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(BlockPos.ZERO, "Pickaxe workbench", 0);
		final SimpleContainer bag, store = new SimpleContainer(9);
		final AtomicBoolean demand = new AtomicBoolean(true);
		Fixture() { this(new SimpleContainer(8)); }
		Fixture(SimpleContainer bag) {
			this.bag = bag; data.registerMember(camp, ACTOR, 0);
			data.setStockpile(camp, new BlockPos(1, 0, 0)); data.setCraftingStation(camp, new BlockPos(3, 0, 0));
			var s = data.getOrCreateSimulation(camp); s.mapResident(ACTOR, false, 0); s.setSurvivalEnabled(true, 0); s.setProductionEnabled(true);
		}
		void supply() { store.setItem(0, new ItemStack(Items.COBBLESTONE, 3)); store.setItem(1, new ItemStack(Items.STICK, 2)); }
		void abstractInputs(int stone, int sticks) {
			state().world().importExternalBatch(ResourceType.STONE, stone, state().campOwner());
			state().world().importExternalBatch(ResourceType.STICK, sticks, state().campOwner());
		}
		long abstractCount(ResourceType type) { return state().world().batchesAt(state().campOwner()).stream().filter(b -> b.resourceType() == type).mapToLong(b -> b.quantity()).sum(); }
		SettlementSimulationState state() { return data.simulationOf(camp).orElseThrow(); }
		SettlementWorkbenchCrafting.Ticket prepare() { return SettlementWorkbenchCrafting.prepare(data, camp, ACTOR, bag, Recipe.STONE_PICKAXE, () -> true, demand::get); }
		boolean pickup(SettlementWorkbenchCrafting.Ticket t) { return SettlementWorkbenchCrafting.pickup(t, data, camp, ACTOR, bag, store, () -> true); }
		boolean finish(SettlementWorkbenchCrafting.Ticket t) { return SettlementWorkbenchCrafting.finish(t, data, camp, ACTOR, bag, () -> true); }
	}
}
