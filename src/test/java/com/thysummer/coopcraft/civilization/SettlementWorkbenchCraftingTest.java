package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementWorkbenchCraftingTest {
	private static final UUID ACTOR = new UUID(81, 1);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void prepareIsReadOnlyAndLastLogCompletesOnlyAtStationOnce() {
		Fixture f = new Fixture();
		f.store.setItem(0, new ItemStack(Items.OAK_LOG));
		var before = f.data.save(new CompoundTag(), null);
		var ticket = f.prepare();
		assertNotNull(ticket);
		assertEquals(before, f.data.save(new CompoundTag(), null));
		assertEquals(1, f.store.countItem(Items.OAK_LOG));
		assertTrue(f.pickup(ticket));
		assertEquals(0, f.store.countItem(Items.OAK_LOG));
		assertEquals(1, f.bag.countItem(Items.OAK_LOG));
		assertTrue(f.finish(ticket));
		assertEquals(0, f.bag.countItem(Items.OAK_LOG));
		assertEquals(4, f.bag.countItem(Items.OAK_PLANKS));
		assertEquals(0, f.store.countItem(Items.OAK_PLANKS));
		assertTrue(SettlementWorkbenchCargo.isMarked(f.bag.getItem(0)));
		assertNull(f.prepare(), "Pending output must return before another batch starts");
		assertFalse(f.finish(ticket));
		assertEquals(4, f.bag.countItem(Items.OAK_PLANKS));
		var completion = f.state().world().ledger().events().stream().filter(e -> e.type() == EventType.CRAFT_COMPLETED).findFirst().orElseThrow();
		assertTrue(completion.causalLinks().isEmpty());
		assertEquals("unknown", completion.context().get("preexistingInventoryProvenance"));
	}

	@Test void cancelledDemandWrongArrivalAndFullOutputNeverConsumeInput() {
		Fixture f = new Fixture();
		f.store.setItem(0, new ItemStack(Items.OAK_LOG, 3));
		var ticket = f.prepare();
		assertTrue(f.pickup(ticket));
		f.demand.set(false);
		assertFalse(f.finish(ticket));
		f.demand.set(true);
		assertFalse(f.finish(ticket));
		assertEquals(1, f.bag.countItem(Items.OAK_LOG));
		assertNull(SettlementWorkbenchCrafting.prepare(f.data, f.camp, ACTOR, f.bag, () -> true, null));
		f.demand.set(false);
		assertNull(f.prepare());
		f.demand.set(true);
		assertNull(f.prepare(), "Cancelled input must return before another batch starts");
		SimpleContainer emptyBag = new SimpleContainer(8);
		var away = SettlementWorkbenchCrafting.prepare(f.data, f.camp, ACTOR, emptyBag, () -> true, () -> true);
		assertNotNull(away);
		assertFalse(SettlementWorkbenchCrafting.pickup(away, f.data, f.camp, ACTOR, emptyBag, f.store, () -> false));
		assertEquals(2, f.store.countItem(Items.OAK_LOG));
		SimpleContainer limited = new SimpleContainer(1) {
			@Override public int getMaxStackSize(ItemStack stack) { return 1; }
		};
		var tooSmall = SettlementWorkbenchCrafting.prepare(f.data, f.camp, ACTOR, limited, () -> true, () -> true);
		assertFalse(SettlementWorkbenchCrafting.pickup(tooSmall, f.data, f.camp, ACTOR, limited, f.store, () -> true));
		assertEquals(2, f.store.countItem(Items.OAK_LOG));
		assertTrue(limited.isEmpty());
	}

	@Test void stationIdentityRegistrationAbaInventoryAndModesInvalidate() {
		Fixture f = new Fixture();
		var ticket = f.prepare();
		Object token = f.data.craftingStationBindingToken(f.camp);
		f.data.setCraftingStation(f.camp, ticket.station());
		assertSame(token, f.data.craftingStationBindingToken(f.camp));
		assertTrue(f.valid(ticket));
		f.data.setCraftingStation(f.camp, ticket.station().above());
		f.data.setCraftingStation(f.camp, ticket.station());
		assertFalse(f.valid(ticket));
		ticket = f.prepare();
		assertFalse(SettlementWorkbenchCrafting.valid(ticket, f.data, f.camp, ACTOR, new SimpleContainer(8)));
		f.world.set(false);
		assertFalse(f.valid(ticket));
		f.world.set(true);
		f.state().setProductionEnabled(false);
		f.state().setProductionEnabled(true);
		assertFalse(f.valid(ticket));
		ticket = f.prepare();
		f.data.setStockpile(f.camp, ticket.store());
		assertFalse(f.valid(ticket));
	}

	@Test void competingWorkersCannotDoubleWithdrawOrReuseOtherBatches() {
		Fixture f = new Fixture();
		UUID other = new UUID(81, 2);
		f.data.registerMember(f.camp, other, 0);
		f.state().mapResident(other, false, 0);
		SimpleContainer otherBag = new SimpleContainer(8);
		var a = f.prepare();
		var b = SettlementWorkbenchCrafting.prepare(f.data, f.camp, other, otherBag, () -> true, () -> true);
		f.store.setItem(0, new ItemStack(Items.OAK_LOG));
		assertTrue(f.pickup(a));
		assertFalse(SettlementWorkbenchCrafting.pickup(b, f.data, f.camp, other, otherBag, f.store, () -> true));
		SettlementWorkbenchCrafting.cancel(a);
		assertFalse(f.finish(a));
		assertEquals(1, f.bag.countItem(Items.OAK_LOG));
		assertNull(f.prepare());
		assertEquals(1, f.bag.countItem(Items.OAK_LOG));
		var loaded = SettlementSavedData.load(f.data.save(new CompoundTag(), null), null);
		var loadedCamp = loaded.find(BlockPos.ZERO).orElseThrow();
		assertFalse(SettlementWorkbenchCrafting.valid(a, loaded, loadedCamp, ACTOR, f.bag));
		assertTrue(SettlementWorkbenchCargo.hasReturnable(f.bag, f.camp.id()));
	}

	@Test void customLogIsNotAnIngredientAndFinishFailureLeavesActualCargo() {
		Fixture f = new Fixture();
		ItemStack named = new ItemStack(Items.OAK_LOG);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep"));
		f.store.setItem(0, named);
		assertFalse(f.pickup(f.prepare()));
		assertTrue(ItemStack.matches(named, f.store.getItem(0)));
		f.store.setItem(1, new ItemStack(Items.OAK_LOG));
		var t = f.prepare();
		assertTrue(f.pickup(t));
		assertFalse(SettlementWorkbenchCrafting.finish(t, f.data, f.camp, ACTOR, f.bag, () -> false));
		assertEquals(1, f.bag.countItem(Items.OAK_LOG));
		assertFalse(f.finish(t));
	}

	@Test void missingOrForeignCarriedInputCannotFinishAndCapacityFailurePreservesInput() {
		Fixture f = new Fixture();
		f.store.setItem(0, new ItemStack(Items.OAK_LOG, 2));
		var t = f.prepare();
		assertTrue(f.pickup(t));
		ItemStack actual = f.bag.removeItem(0, 1);
		assertFalse(f.valid(t));
		f.bag.setItem(0, SettlementWorkbenchCargo.input(f.camp.id(), UUID.randomUUID()));
		assertFalse(f.finish(t));
		f.bag.setItem(0, actual);
		assertFalse(f.finish(t));
		AtomicBoolean outputAllowed = new AtomicBoolean(true);
		SimpleContainer restricted = new SimpleContainer(1) {
			@Override public boolean canPlaceItem(int slot, ItemStack stack) {
				return stack.is(Items.OAK_LOG) || outputAllowed.get();
			}
		};
		outputAllowed.set(true);
		var blocked = SettlementWorkbenchCrafting.prepare(f.data, f.camp, ACTOR, restricted, () -> true, () -> true);
		assertTrue(SettlementWorkbenchCrafting.pickup(blocked, f.data, f.camp, ACTOR, restricted, f.store, () -> true));
		outputAllowed.set(false);
		assertFalse(SettlementWorkbenchCrafting.finish(blocked, f.data, f.camp, ACTOR, restricted, () -> true));
		assertEquals(1, restricted.countItem(Items.OAK_LOG));
		assertEquals(0, restricted.countItem(Items.OAK_PLANKS));
	}

	@Test void malformedMarkedCargoAlsoBlocksPreparingAnotherBatch() {
		Fixture f = new Fixture();
		ItemStack malformed = SettlementWorkbenchCargo.output(f.camp.id(), UUID.randomUUID());
		malformed.setCount(5);
		f.bag.setItem(0, malformed);
		assertTrue(SettlementWorkbenchCargo.isMarked(malformed));
		assertEquals(SettlementWorkbenchCargo.Phase.UNKNOWN, SettlementWorkbenchCargo.phase(malformed));
		assertNull(f.prepare());
		assertTrue(ItemStack.matches(malformed, f.bag.getItem(0)));
	}

	@Test void abstractExportEvidenceIsFrozenAtPickupAndNoProductIsImported() {
		Fixture f = new Fixture();
		f.state().world().importExternalBatch(ResourceType.WOOD, 2, f.state().campOwner());
		var t = f.prepare();
		assertTrue(f.pickup(t));
		long pickupExport = f.state().world().ledger().events().stream().filter(e -> e.type() == EventType.EXTERNAL_EXPORT).findFirst().orElseThrow().eventId();
		var batch = f.state().world().batchesAt(f.state().campOwner()).stream().filter(b -> b.resourceType() == ResourceType.WOOD).findFirst().orElseThrow();
		assertEquals(1, MinecraftStockpileAdapter.exportToContainer(f.state().world(), f.state().campOwner(), batch.batchId(), 1,
			f.store, List.of(f.state().residentId(ACTOR).orElseThrow()), Map.of("activity", "craft", "recipe", "material_planks")));
		assertTrue(f.finish(t));
		var completion = f.state().world().ledger().events().stream().filter(e -> e.type() == EventType.CRAFT_COMPLETED).findFirst().orElseThrow();
		assertEquals(List.of(pickupExport), completion.causalLinks());
		assertEquals(1, f.store.countItem(Items.OAK_LOG));
		assertEquals(0, f.store.countItem(Items.OAK_PLANKS));
		assertEquals(4, f.bag.countItem(Items.OAK_PLANKS));
		f.state().world().assertConservation();
	}

	private static final class Fixture {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(BlockPos.ZERO, "Workbench", 0);
		final SimpleContainer bag = new SimpleContainer(8), store = new SimpleContainer(9);
		final AtomicBoolean world = new AtomicBoolean(true), demand = new AtomicBoolean(true);
		Fixture() {
			data.registerMember(camp, ACTOR, 0);
			data.setStockpile(camp, new BlockPos(1, 0, 0));
			data.setCraftingStation(camp, new BlockPos(3, 0, 0));
			var state = data.getOrCreateSimulation(camp);
			state.mapResident(ACTOR, false, 0);
			state.setSurvivalEnabled(true, 0);
			state.setProductionEnabled(true);
		}
		SettlementSimulationState state() { return data.simulationOf(camp).orElseThrow(); }
		SettlementWorkbenchCrafting.Ticket prepare() { return SettlementWorkbenchCrafting.prepare(data, camp, ACTOR, bag, world::get, demand::get); }
		boolean valid(SettlementWorkbenchCrafting.Ticket t) { return SettlementWorkbenchCrafting.valid(t, data, camp, ACTOR, bag); }
		boolean pickup(SettlementWorkbenchCrafting.Ticket t) { return SettlementWorkbenchCrafting.pickup(t, data, camp, ACTOR, bag, store, () -> true); }
		boolean finish(SettlementWorkbenchCrafting.Ticket t) { return SettlementWorkbenchCrafting.finish(t, data, camp, ACTOR, bag, () -> true); }
	}
}
