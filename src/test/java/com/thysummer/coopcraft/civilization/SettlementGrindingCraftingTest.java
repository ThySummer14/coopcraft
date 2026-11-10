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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementGrindingCraftingTest {
	private static final UUID ACTOR = new UUID(92, 1), OTHER = new UUID(92, 2);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void lastCobbleBecomesOneRealSmoothStoneAndCompletionIsSingleUse() {
		Fixture f = new Fixture(); f.supply(); var t = f.prepare();
		assertNotNull(t); assertEquals(1, f.store.countItem(Items.COBBLESTONE));
		assertTrue(f.pickup(t)); assertTrue(f.store.isEmpty()); assertTrue(f.finish(t)); assertFalse(f.finish(t));
		assertEquals(1, f.bag.countItem(Items.SMOOTH_STONE)); assertEquals(0, f.bag.countItem(Items.COBBLESTONE));
		assertEquals(0, f.abstractCount(ResourceType.SMOOTH_STONE));
		var completed = f.state().world().ledger().events().stream().filter(e -> e.type() == EventType.CRAFT_COMPLETED).toList();
		assertEquals(1, completed.size()); assertEquals("smooth_stone", completed.getFirst().context().get("recipe"));
		assertEquals("1", completed.getFirst().context().get("outputCount")); assertTrue(completed.getFirst().causalLinks().isEmpty());
		assertEquals(1, SettlementWorkbenchCargo.deposit(f.bag, f.store, f.camp.id()));
		assertEquals(1, SettlementMaterialCrafting.countPlain(f.store, Items.SMOOTH_STONE));
		assertEquals(0, f.abstractCount(ResourceType.SMOOTH_STONE)); f.state().world().assertConservation();
	}

	@Test void privateAndCustomCobblesNeverSupplyPublicBatch() {
		for (int scenario = 0; scenario < 4; scenario++) {
			Fixture f = new Fixture(); f.bag.setItem(7, new ItemStack(Items.COBBLESTONE, 9));
			if (scenario > 0) f.supply();
			if (scenario == 1) f.store.getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("Keep"));
			if (scenario == 2) f.store.getItem(0).remove(DataComponents.MAX_STACK_SIZE);
			if (scenario == 3) f.store.setItem(0, new ItemStack(Items.BIRCH_LOG));
			var bag = snapshot(f.bag); var store = snapshot(f.store);
			assertFalse(f.pickup(f.prepare())); unchanged(bag, f.bag); unchanged(store, f.store);
		}
	}

	@Test void personalCobblesAndSmoothStoneSurviveSuccessfulPublicBatch() {
		Fixture f = new Fixture(); f.supply();
		f.bag.setItem(6, new ItemStack(Items.COBBLESTONE, 9)); f.bag.setItem(7, new ItemStack(Items.SMOOTH_STONE, 11));
		var t = f.prepare(); assertTrue(f.pickup(t)); assertTrue(f.finish(t));
		assertEquals(1, SettlementWorkbenchCargo.deposit(f.bag, f.store, f.camp.id()));
		assertEquals(9, f.bag.countItem(Items.COBBLESTONE)); assertEquals(11, f.bag.countItem(Items.SMOOTH_STONE));
	}

	@Test void cancelledOrUnneededWorkReturnsWholeCobbleWithoutCrafting() {
		for (boolean cancel : new boolean[] { false, true }) {
			Fixture f = new Fixture(); f.supply(); var t = f.prepare(); assertTrue(f.pickup(t)); var before = snapshot(f.bag);
			if (cancel) SettlementWorkbenchCrafting.cancel(t); else f.demand.set(false);
			assertFalse(f.finish(t)); unchanged(before, f.bag);
			assertEquals(1, SettlementWorkbenchCargo.deposit(f.bag, f.store, f.camp.id()));
			assertEquals(1, f.store.countItem(Items.COBBLESTONE)); assertEquals(0, f.store.countItem(Items.SMOOTH_STONE));
		}
	}

	@Test void changedMemberStoreStationOrModesInvalidateEvenAfterRestoration() {
		for (int scenario = 0; scenario < 5; scenario++) {
			Fixture f = new Fixture(); f.supply(); var t = f.prepare(); assertTrue(f.pickup(t)); var before = snapshot(f.bag);
			switch (scenario) {
				case 0 -> { f.data.removeMember(ACTOR); f.data.registerMember(f.camp, ACTOR, 1); }
				case 1 -> { f.data.setStockpile(f.camp, t.store().above()); f.data.setStockpile(f.camp, t.store()); }
				case 2 -> { f.data.setGrindingStation(f.camp, t.station().above()); f.data.setGrindingStation(f.camp, t.station()); }
				case 3 -> { f.state().setProductionEnabled(false); f.state().setProductionEnabled(true); }
				case 4 -> { f.state().setSurvivalEnabled(false, 1); f.state().setSurvivalEnabled(true, 2); }
			}
			assertFalse(f.finish(t)); unchanged(before, f.bag);
			assertEquals(1, SettlementWorkbenchCargo.deposit(f.bag, f.store, f.camp.id()));
		}
	}

	@Test void fullBagAndRejectedOutputPreventWithdrawalAndExport() {
		for (boolean full : new boolean[] { false, true }) {
			Fixture f = new Fixture(full ? new SimpleContainer(8) : new SimpleContainer(8) {
				@Override public boolean canPlaceItem(int slot, ItemStack stack) { return !stack.is(Items.SMOOTH_STONE); }
			});
			if (full) for (int slot = 0; slot < 8; slot++) f.bag.setItem(slot, new ItemStack(Items.DIRT, 64));
			f.stone(3); int events = f.state().world().ledger().events().size(); var before = snapshot(f.bag);
			assertFalse(f.pickup(f.prepare())); unchanged(before, f.bag); assertTrue(f.store.isEmpty());
			assertEquals(3, f.abstractCount(ResourceType.STONE)); assertEquals(events, f.state().world().ledger().events().size());
		}
	}

	@Test void outputCapacityChangedAtStationKeepsCobbleAndConsumesTicket() {
		AtomicBoolean allow = new AtomicBoolean(true);
		Fixture f = new Fixture(new SimpleContainer(1) {
			@Override public boolean canPlaceItem(int slot, ItemStack stack) { return !stack.is(Items.SMOOTH_STONE) || allow.get(); }
		});
		f.supply(); var t = f.prepare(); assertTrue(f.pickup(t)); var before = snapshot(f.bag);
		allow.set(false); assertFalse(f.finish(t)); unchanged(before, f.bag);
		allow.set(true); assertFalse(f.finish(t));
		assertEquals(1, SettlementWorkbenchCargo.deposit(f.bag, f.store, f.camp.id()));
	}

	@Test void twoWorkersCannotWithdrawSameLastCobble() {
		Fixture f = new Fixture(); f.supply(); f.data.registerMember(f.camp, OTHER, 0); f.state().mapResident(OTHER, false, 0);
		SimpleContainer otherBag = new SimpleContainer(8);
		var first = f.prepare();
		var second = SettlementWorkbenchCrafting.prepare(f.data, f.camp, OTHER, otherBag, Recipe.SMOOTH_STONE, () -> true, () -> true);
		assertNotNull(second); assertTrue(f.pickup(first));
		assertFalse(SettlementWorkbenchCrafting.pickup(second, f.data, f.camp, OTHER, otherBag, f.store, () -> true));
		assertTrue(f.finish(first)); assertFalse(f.finish(first)); assertTrue(otherBag.isEmpty());
		assertEquals(1, f.bag.countItem(Items.SMOOTH_STONE));
	}

	@Test void duplicatedForeignOrMissingCustodyPreventsCompletion() {
		for (int scenario = 0; scenario < 4; scenario++) {
			Fixture f = new Fixture(); f.supply(); var t = f.prepare(); assertTrue(f.pickup(t));
			switch (scenario) {
				case 0 -> f.bag.setItem(1, f.bag.getItem(0).copy());
				case 1 -> f.bag.setItem(0, SettlementWorkbenchCargo.input(f.camp.id(), UUID.randomUUID(), Recipe.SMOOTH_STONE));
				case 2 -> f.bag.setItem(0, ItemStack.EMPTY);
				case 3 -> f.bag.getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("Changed"));
			}
			var before = snapshot(f.bag); assertFalse(f.finish(t)); unchanged(before, f.bag);
			assertEquals(0, f.bag.countItem(Items.SMOOTH_STONE));
		}
	}

	@Test void abstractStoneExportsExactlyOneAndSealsOnlyItsOwnProvenance() {
		Fixture f = new Fixture(); f.stone(4); var t = f.prepare(); assertTrue(f.pickup(t));
		assertEquals(3, f.abstractCount(ResourceType.STONE)); assertTrue(f.store.isEmpty());
		var exports = f.state().world().ledger().events().stream().filter(e -> e.type() == EventType.EXTERNAL_EXPORT).toList();
		assertEquals(1, exports.size()); assertEquals("smooth_stone", exports.getFirst().context().get("recipe"));
		var batch = f.state().world().batchesAt(f.state().campOwner()).stream().filter(b -> b.resourceType() == ResourceType.STONE).findFirst().orElseThrow();
		assertEquals(1, MinecraftStockpileAdapter.exportToContainer(f.state().world(), f.state().campOwner(), batch.batchId(), 1,
			f.store, List.of(f.state().residentId(ACTOR).orElseThrow()), Map.of("activity", "craft", "recipe", Recipe.SMOOTH_STONE.id())));
		assertTrue(f.finish(t));
		var completed = f.state().world().ledger().events().stream().filter(e -> e.type() == EventType.CRAFT_COMPLETED).findFirst().orElseThrow();
		assertEquals(List.of(exports.getFirst().eventId()), completed.causalLinks());
		assertEquals(1, SettlementWorkbenchCargo.deposit(f.bag, f.store, f.camp.id()));
		assertEquals(0, f.abstractCount(ResourceType.SMOOTH_STONE)); assertEquals(2, f.abstractCount(ResourceType.STONE));
		f.state().world().assertConservation();
	}

	@Test void publicCobbleAvoidsUnnecessaryAbstractExportAndAttribution() {
		Fixture f = new Fixture(); f.supply(); f.stone(5); var t = f.prepare();
		assertTrue(f.pickup(t)); assertTrue(f.finish(t)); assertEquals(5, f.abstractCount(ResourceType.STONE));
		assertFalse(f.state().world().ledger().events().stream().anyMatch(e -> e.type() == EventType.EXTERNAL_EXPORT));
		var event = f.state().world().ledger().events().stream().filter(e -> e.type() == EventType.CRAFT_COMPLETED).findFirst().orElseThrow();
		assertTrue(event.causalLinks().isEmpty()); f.state().world().assertConservation();
	}

	@Test void absentArrivalDisabledModesOrFullStorePreventAbstractExport() {
		for (int scenario = 0; scenario < 4; scenario++) {
			Fixture f = new Fixture(); f.stone(3);
			if (scenario == 1) f.state().setProductionEnabled(false);
			if (scenario == 2) f.state().setSurvivalEnabled(false, 1);
			if (scenario == 3) for (int slot = 0; slot < 9; slot++) f.store.setItem(slot, new ItemStack(Items.DIRT, 64));
			boolean arrived = scenario != 0; var store = snapshot(f.store); int events = f.state().world().ledger().events().size();
			assertFalse(SettlementWorkbenchCrafting.pickup(f.prepare(), f.data, f.camp, ACTOR, f.bag, f.store, () -> arrived));
			assertTrue(f.bag.isEmpty()); unchanged(store, f.store); assertEquals(3, f.abstractCount(ResourceType.STONE));
			assertEquals(events, f.state().world().ledger().events().size());
		}
	}

	@Test void arrivalCallbacksCannotChangeAuthorityAndStillConsumeTheBatch() {
		for (boolean atStation : new boolean[] { false, true }) {
			Fixture f = new Fixture(); f.supply(); var t = f.prepare();
			if (atStation) assertTrue(f.pickup(t));
			var bag = snapshot(f.bag); var store = snapshot(f.store);
			java.util.function.BooleanSupplier arrival = () -> {
				f.state().setProductionEnabled(false); f.state().setProductionEnabled(true); return true;
			};
			assertFalse(atStation
				? SettlementWorkbenchCrafting.finish(t, f.data, f.camp, ACTOR, f.bag, arrival)
				: SettlementWorkbenchCrafting.pickup(t, f.data, f.camp, ACTOR, f.bag, f.store, arrival));
			unchanged(bag, f.bag); unchanged(store, f.store);
		}
	}

	@Test void demandDisappearingAfterExportLeavesOnlyTheRealPublicCobble() {
		Fixture f = new Fixture(); f.stone(1);
		var t = SettlementWorkbenchCrafting.prepare(f.data, f.camp, ACTOR, f.bag, Recipe.SMOOTH_STONE,
			() -> true, () -> f.store.countItem(Items.COBBLESTONE) == 0);
		assertNotNull(t); assertFalse(f.pickup(t));
		assertTrue(f.bag.isEmpty()); assertEquals(1, f.store.countItem(Items.COBBLESTONE));
		assertEquals(0, f.abstractCount(ResourceType.STONE)); assertEquals(0, f.abstractCount(ResourceType.SMOOTH_STONE));
		assertFalse(f.state().world().ledger().events().stream().anyMatch(e -> e.type() == EventType.CRAFT_COMPLETED));
		f.state().world().assertConservation();
	}

	@Test void defaultPhysicalModeWithoutSimulationPersonCompletesAndReturnsRealProduct() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement camp = data.getOrCreate(BlockPos.ZERO, "Physical grinding", 0);
		data.registerMember(camp, ACTOR, 0); data.setStockpile(camp, new BlockPos(1, 0, 0));
		data.setGrindingStation(camp, new BlockPos(3, 0, 0));
		assertTrue(data.simulationOf(camp).isEmpty());
		SimpleContainer bag = new SimpleContainer(8), store = new SimpleContainer(new ItemStack(Items.COBBLESTONE));
		ItemStack privateTool = new ItemStack(Items.STONE_PICKAXE); privateTool.setDamageValue(37);
		privateTool.set(DataComponents.CUSTOM_NAME, Component.literal("Keep this tool")); bag.setItem(7, privateTool.copy());
		var t = SettlementWorkbenchCrafting.prepare(data, camp, ACTOR, bag, Recipe.SMOOTH_STONE, () -> true, () -> true);
		assertTrue(SettlementWorkbenchCrafting.pickup(t, data, camp, ACTOR, bag, store, () -> true));
		assertTrue(SettlementWorkbenchCrafting.finish(t, data, camp, ACTOR, bag, () -> true));
		var delivery = SettlementCargoDelivery.prepare(data, camp, ACTOR, bag, () -> true);
		assertEquals(SettlementCargoDelivery.Status.DELIVERED,
			SettlementCargoDelivery.finish(delivery, data, camp, ACTOR, bag, store, () -> true).status());
		assertEquals(0, bag.countItem(Items.COBBLESTONE)); assertEquals(0, bag.countItem(Items.SMOOTH_STONE));
		assertEquals(1, store.countItem(Items.SMOOTH_STONE)); assertTrue(ItemStack.matches(privateTool, bag.getItem(7)));
		assertTrue(data.simulationOf(camp).isEmpty());
	}

	@Test void reloadCannotReuseOldAuthorityButReloadedInputCanReturn() {
		Fixture f = new Fixture(); f.supply(); var t = f.prepare(); assertTrue(f.pickup(t));
		var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
		var loaded = SettlementSavedData.load(f.data.save(new net.minecraft.nbt.CompoundTag(), registries), registries);
		var camp = loaded.find(BlockPos.ZERO).orElseThrow();
		SimpleContainer bag = new SimpleContainer(ItemStack.parseOptional(registries,
			(net.minecraft.nbt.CompoundTag) f.bag.getItem(0).save(registries)));
		assertFalse(SettlementWorkbenchCrafting.finish(t, loaded, camp, ACTOR, bag, () -> true));
		assertEquals(1, bag.countItem(Items.COBBLESTONE)); assertEquals(0, bag.countItem(Items.SMOOTH_STONE));
		var delivery = SettlementCargoDelivery.prepare(loaded, camp, ACTOR, bag, () -> true);
		assertNotNull(delivery);
		assertEquals(SettlementCargoDelivery.Status.DELIVERED,
			SettlementCargoDelivery.finish(delivery, loaded, camp, ACTOR, bag, f.store, () -> true).status());
		assertTrue(bag.isEmpty()); assertEquals(1, f.store.countItem(Items.COBBLESTONE));
	}

	@Test void physicalInputWorksWithAbstractModesDisabledAndNoCraftingTable() {
		for (boolean survivalOff : new boolean[] { false, true }) {
			Fixture f = new Fixture(); f.supply();
			if (survivalOff) f.state().setSurvivalEnabled(false, 1); else f.state().setProductionEnabled(false);
			assertTrue(f.data.getCraftingStation(f.camp).isEmpty());
			var t = f.prepare(); assertTrue(f.pickup(t)); assertTrue(f.finish(t));
			assertEquals(1, SettlementWorkbenchCargo.deposit(f.bag, f.store, f.camp.id()));
			assertEquals(1, f.store.countItem(Items.SMOOTH_STONE));
			assertEquals(0, f.abstractCount(ResourceType.SMOOTH_STONE));
		}
	}

	@Test void samePositionReregistrationRevokesTheOldGrindingTicket() {
		Fixture f = new Fixture(); f.supply(); var t = f.prepare(); assertTrue(f.pickup(t));
		f.data.setGrindingStation(f.camp, t.station());
		assertFalse(f.finish(t)); assertEquals(1, f.bag.countItem(Items.COBBLESTONE));
		assertEquals(1, SettlementWorkbenchCargo.deposit(f.bag, f.store, f.camp.id()));
	}

	@Test void missingGrindingStationCannotFallBackToCraftingTable() {
		Fixture f = new Fixture(); f.supply();
		SettlementSavedData data = new SettlementSavedData();
		Settlement camp = data.getOrCreate(BlockPos.ZERO, "No stonecutter", 0);
		data.registerMember(camp, ACTOR, 0); data.setStockpile(camp, new BlockPos(1, 0, 0));
		data.setCraftingStation(camp, new BlockPos(3, 0, 0));
		assertNull(SettlementWorkbenchCrafting.prepare(data, camp, ACTOR, f.bag, Recipe.SMOOTH_STONE, () -> true, () -> true));
	}

	@Test void genericMaterialCraftingCannotBypassGrindingTransport() {
		Fixture f = new Fixture(); f.supply();
		assertTrue(SettlementMaterialCrafting.chooseRecipe(f.store, f.state(), Map.of(Items.SMOOTH_STONE, 1)).isEmpty());
		assertFalse(SettlementMaterialCrafting.craft(f.store, f.state(), Recipe.SMOOTH_STONE, ACTOR, () -> fail("dirty")));
		assertEquals(1, f.store.countItem(Items.COBBLESTONE));
	}

	@Test void returnRequiresRealArrivalAndRetainsOutputUntilSpaceExists() {
		Fixture f = new Fixture(); f.supply(); var t = f.prepare(); assertTrue(f.pickup(t)); assertTrue(f.finish(t));
		var delivery = SettlementCargoDelivery.prepare(f.data, f.camp, ACTOR, f.bag, () -> true);
		assertEquals(SettlementCargoDelivery.Status.NOT_AT_STORE,
			SettlementCargoDelivery.finish(delivery, f.data, f.camp, ACTOR, f.bag, f.store, () -> false).status());
		assertEquals(1, f.bag.countItem(Items.SMOOTH_STONE)); assertTrue(f.store.isEmpty());
		for (int i = 0; i < f.store.getContainerSize(); i++) f.store.setItem(i, new ItemStack(Items.DIRT, 64));
		delivery = SettlementCargoDelivery.prepare(f.data, f.camp, ACTOR, f.bag, () -> true);
		assertEquals(SettlementCargoDelivery.Status.NO_CAPACITY,
			SettlementCargoDelivery.finish(delivery, f.data, f.camp, ACTOR, f.bag, f.store, () -> true).status());
		f.store.setItem(0, ItemStack.EMPTY);
		delivery = SettlementCargoDelivery.prepare(f.data, f.camp, ACTOR, f.bag, () -> true);
		assertEquals(SettlementCargoDelivery.Status.DELIVERED,
			SettlementCargoDelivery.finish(delivery, f.data, f.camp, ACTOR, f.bag, f.store, () -> true).status());
		assertTrue(f.bag.isEmpty()); assertEquals(1, SettlementMaterialCrafting.countPlain(f.store, Items.SMOOTH_STONE));
		assertEquals(0, f.abstractCount(ResourceType.SMOOTH_STONE));
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
		final Settlement camp = data.getOrCreate(BlockPos.ZERO, "Grinding station", 0);
		final SimpleContainer bag, store = new SimpleContainer(9);
		final AtomicBoolean demand = new AtomicBoolean(true);
		Fixture() { this(new SimpleContainer(8)); }
		Fixture(SimpleContainer bag) {
			this.bag = bag; data.registerMember(camp, ACTOR, 0);
			data.setStockpile(camp, new BlockPos(1, 0, 0)); data.setGrindingStation(camp, new BlockPos(3, 0, 0));
			var s = data.getOrCreateSimulation(camp); s.mapResident(ACTOR, false, 0); s.setSurvivalEnabled(true, 0); s.setProductionEnabled(true);
		}
		void supply() { store.setItem(0, new ItemStack(Items.COBBLESTONE)); }
		void stone(int count) { state().world().importExternalBatch(ResourceType.STONE, count, state().campOwner()); }
		long abstractCount(ResourceType type) { return state().world().batchesAt(state().campOwner()).stream().filter(b -> b.resourceType() == type).mapToLong(b -> b.quantity()).sum(); }
		SettlementSimulationState state() { return data.simulationOf(camp).orElseThrow(); }
		SettlementWorkbenchCrafting.Ticket prepare() { return SettlementWorkbenchCrafting.prepare(data, camp, ACTOR, bag, Recipe.SMOOTH_STONE, () -> true, demand::get); }
		boolean pickup(SettlementWorkbenchCrafting.Ticket t) { return SettlementWorkbenchCrafting.pickup(t, data, camp, ACTOR, bag, store, () -> true); }
		boolean finish(SettlementWorkbenchCrafting.Ticket t) { return SettlementWorkbenchCrafting.finish(t, data, camp, ACTOR, bag, () -> true); }
	}
}
