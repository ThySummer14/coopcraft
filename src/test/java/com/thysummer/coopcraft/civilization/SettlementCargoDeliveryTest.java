package com.thysummer.coopcraft.civilization;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.junit.jupiter.api.parallel.ResourceLock;
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

/** Server-free service boundary tests, not pathfinding or live-world validation. */
class SettlementCargoDeliveryTest {
	static final UUID ACTOR = new UUID(91, 1);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	static class Fixture {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(BlockPos.ZERO, "Cargo", 0);
		final SimpleContainer bag = new SimpleContainer(8), store = new SimpleContainer(2);
		final AtomicBoolean present = new AtomicBoolean(true);
		Fixture() { data.registerMember(camp, ACTOR, 0); data.setStockpile(camp, BlockPos.ZERO.east()); }
		SettlementCargoDelivery.Ticket prepare() { return SettlementCargoDelivery.prepare(data, camp, ACTOR, bag, present::get); }
		SettlementCargoDelivery.Result finish(SettlementCargoDelivery.Ticket t) {
			return SettlementCargoDelivery.finish(t, data, camp, ACTOR, bag, store, () -> true);
		}
	}

	@Test void noInventoryReadsBeforeArrivalAndEveryAttemptIsConsumed() {
		Fixture f = new Fixture();
		SimpleContainer unreadable = new SimpleContainer(1) {
			@Override public int getContainerSize() { return fail("Must not inspect inventory before arrival"); }
			@Override public ItemStack getItem(int slot) { return fail("Must not read stacks before arrival"); }
		};
		var t = SettlementCargoDelivery.prepare(f.data, f.camp, ACTOR, unreadable, () -> true);
		assertNotNull(t);
		assertTrue(SettlementCargoDelivery.valid(t, f.data, f.camp, ACTOR, unreadable));
		assertEquals(SettlementCargoDelivery.Status.NOT_AT_STORE,
			SettlementCargoDelivery.finish(t, f.data, f.camp, ACTOR, unreadable, f.store, () -> false).status());
		assertEquals(SettlementCargoDelivery.Status.INVALID,
			SettlementCargoDelivery.finish(t, f.data, f.camp, ACTOR, unreadable, f.store, () -> true).status());
	}

	@Test void changedBindingsMemberDataActorAndInventoryInvalidateWithoutMoving() {
		for (int condition = 0; condition < 7; condition++) {
			Fixture f = new Fixture(); f.bag.setItem(0, new ItemStack(Items.COBBLESTONE, 7));
			var t = f.prepare();
			SettlementSavedData data = f.data; Settlement camp = f.camp;
			UUID actor = ACTOR; SimpleContainer bag = f.bag;
			switch (condition) {
				case 0 -> f.present.set(false);
				case 1 -> { f.data.setStockpile(f.camp, BlockPos.ZERO.west()); f.data.setStockpile(f.camp, BlockPos.ZERO.east()); }
				case 2 -> { f.data.removeMember(ACTOR); f.data.registerMember(f.camp, ACTOR, 0); }
				case 3 -> { data = SettlementSavedData.load(f.data.save(new CompoundTag(), null), null); camp = data.find(BlockPos.ZERO).orElseThrow(); }
				case 4 -> actor = new UUID(91, 2);
				case 5 -> bag = new SimpleContainer(8);
				case 6 -> f.data.removeMember(ACTOR);
			}
			assertFalse(SettlementCargoDelivery.valid(t, data, camp, actor, bag));
			assertEquals(0, SettlementCargoDelivery.finish(t, data, camp, actor, bag, f.store, () -> true).moved());
			f.present.set(true);
			assertEquals(0, f.finish(t).moved());
			assertEquals(7, f.bag.countItem(Items.COBBLESTONE)); assertTrue(f.store.isEmpty());
		}
	}

	@Test void finishUsesCurrentStacksPreservesComponentsAndLeavesFoodAndTools() {
		Fixture f = new Fixture(); var t = f.prepare();
		ItemStack named = new ItemStack(Items.COBBLESTONE, 9);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Carried stone"));
		f.bag.setItem(0, named.copy()); f.bag.setItem(1, new ItemStack(Items.POTATO, 4));
		f.bag.setItem(2, new ItemStack(Items.STONE_PICKAXE));
		f.store.setItem(0, named.copyWithCount(62)); f.store.setItem(1, new ItemStack(Items.DIRT, 64));
		assertEquals(2, f.finish(t).moved());
		assertEquals(7, f.bag.countItem(Items.COBBLESTONE));
		assertTrue(ItemStack.isSameItemSameComponents(named, f.store.getItem(0)));
		assertTrue(ItemStack.isSameItemSameComponents(named, f.bag.getItem(0)));
		assertEquals(4, f.bag.countItem(Items.POTATO)); assertEquals(1, f.bag.countItem(Items.STONE_PICKAXE));
		assertEquals(0, f.finish(t).moved());
		assertEquals(SettlementCargoDelivery.Status.NO_CAPACITY, f.finish(f.prepare()).status());
		f.store.setItem(1, ItemStack.EMPTY);
		assertEquals(7, f.finish(f.prepare()).moved());
		assertFalse(SettlementCargo.hasCargo(f.bag));
	}

	@Test void arrivalCallbackCannotReviveChangedBindingAndFailureConsumesTicket() {
		Fixture f = new Fixture(); f.bag.setItem(0, new ItemStack(Items.SMOOTH_STONE, 3));
		var t = f.prepare();
		assertEquals(SettlementCargoDelivery.Status.INVALID,
			SettlementCargoDelivery.finish(t, f.data, f.camp, ACTOR, f.bag, f.store, () -> {
				f.data.setStockpile(f.camp, BlockPos.ZERO.east()); return true;
			}).status());
		assertEquals(3, f.bag.countItem(Items.SMOOTH_STONE)); assertTrue(f.store.isEmpty());
		assertEquals(0, f.finish(t).moved());
		assertEquals(3, f.finish(f.prepare()).moved());
	}

	@Test void supportedNonTagMaterialsShareClassificationWhileFoodAndToolsStayPersonal() {
		for (var item : java.util.List.of(Items.COBBLESTONE, Items.STICK, Items.SMOOTH_STONE)) {
			assertTrue(SettlementCargo.isMaterial(new ItemStack(item)));
		}
		for (var item : java.util.List.of(Items.POTATO, Items.BREAD, Items.STONE_PICKAXE, Items.OAK_PLANKS)) {
			assertFalse(SettlementCargo.isMaterial(new ItemStack(item)));
		}
		assertFalse(SettlementCargo.isMaterial(ItemStack.EMPTY)); assertFalse(SettlementCargo.hasCargo(null));
		// LOGS membership is provided by runtime tags, not Bootstrap's server-free registry.
	}

	@Test
	@ResourceLock("minecraft-item-tags")
	void runtimeLogTagMembersStayPhysicalThroughProductionAndDelivery() {
		// Bind a controlled runtime tag through the registry API, restoring every original tag in finally.
		var registry = BuiltInRegistries.ITEM;
		var original = new java.util.HashMap<TagKey<Item>, java.util.List<Holder<Item>>>();
		registry.getTags().forEach(pair -> original.put(pair.getFirst(), pair.getSecond().stream().toList()));
		var controlled = new java.util.HashMap<>(original);
		controlled.put(ItemTags.LOGS, java.util.List.of(Items.OAK_LOG.builtInRegistryHolder(), Items.BIRCH_LOG.builtInRegistryHolder()));
		try {
			registry.bindTags(controlled);
			Fixture f = new Fixture(); var state = f.data.getOrCreateSimulation(f.camp);
			state.mapResident(ACTOR, false, 0); state.setSurvivalEnabled(true, 0);
			state.setProductionEnabled(true); state.setSkillsEnabled(true);
			for (var log : java.util.List.of(Items.OAK_LOG, Items.BIRCH_LOG)) {
				ItemStack named = new ItemStack(log, 5);
				named.set(DataComponents.CUSTOM_NAME, Component.literal("Gathered log"));
				assertTrue(SettlementCargo.isMaterial(named));
				f.bag.setItem(0, new ItemStack(log, 5)); assertTrue(SettlementCargo.hasCargo(f.bag));
				assertEquals(0, SettlementSimulationService.recordProduction(f.data, f.camp, ACTOR, f.bag, log, 5, "gather"));
				assertEquals(5, f.bag.countItem(log)); assertTrue(state.world().batches().isEmpty());
				f.bag.setItem(0, named.copy());
				f.store.clearContent(); assertEquals(5, f.finish(f.prepare()).moved());
				assertTrue(f.bag.isEmpty()); assertTrue(ItemStack.isSameItemSameComponents(named, f.store.getItem(0)));
				assertEquals(5, f.store.countItem(log)); assertTrue(state.world().batches().isEmpty());
			}
		} finally {
			registry.resetTags();
			registry.bindTags(original);
		}
		for (var entry : original.entrySet()) {
			assertEquals(entry.getValue(), registry.getTag(entry.getKey()).orElseThrow().stream().toList());
		}
	}

	@Test
	@ResourceLock("minecraft-item-tags")
	void untaggedLogsNeverRegainAutomaticAbstractImport() {
		var registry = BuiltInRegistries.ITEM;
		var original = new java.util.HashMap<TagKey<Item>, java.util.List<Holder<Item>>>();
		registry.getTags().forEach(pair -> original.put(pair.getFirst(), pair.getSecond().stream().toList()));
		var controlled = new java.util.HashMap<>(original);
		controlled.put(ItemTags.LOGS, java.util.List.of());
		try {
			registry.bindTags(controlled);
			Fixture f = new Fixture(); var state = f.data.getOrCreateSimulation(f.camp);
			state.mapResident(ACTOR, false, 0); state.setSurvivalEnabled(true, 0);
			state.setProductionEnabled(true); state.setSkillsEnabled(true);
			f.bag.setItem(0, new ItemStack(Items.OAK_LOG, 5));
			assertFalse(SettlementCargo.isMaterial(f.bag.getItem(0)));
			assertEquals(0, SettlementSimulationService.recordProduction(f.data, f.camp, ACTOR, f.bag, Items.OAK_LOG, 5, "gather"));
			assertEquals(5, f.bag.countItem(Items.OAK_LOG)); assertTrue(state.world().batches().isEmpty());
			var attempt = SettlementSimulationService.beginCrafting(f.data, f.camp, ACTOR, "test_log", Items.OAK_LOG, 5);
			var completion = SettlementSimulationService.recordCrafting(f.data, f.camp, ACTOR, attempt).orElseThrow();
			assertEquals(0, SettlementSimulationService.recordCraftedProduct(f.data, f.camp, ACTOR, f.bag, completion, null, 5));
			assertEquals(0, SettlementSimulationService.recordCraftedProduct(f.data, f.camp, ACTOR, f.bag, completion, Items.OAK_LOG, 5));
			assertEquals(0, SettlementSimulationService.recordCraftedProduct(f.data, f.camp, ACTOR, f.bag, completion, Items.OAK_LOG, 5));
			assertEquals(5, f.bag.countItem(Items.OAK_LOG)); assertTrue(state.world().batches().isEmpty());
			assertEquals(0, f.finish(f.prepare()).moved());
			assertEquals(5, f.bag.countItem(Items.OAK_LOG)); assertTrue(f.store.isEmpty());
		} finally {
			registry.resetTags(); registry.bindTags(original);
		}
	}

	@Test void sameContainerAndRejectedSlotsLeaveSourceUntouched() {
		Fixture f = new Fixture(); f.bag.setItem(0, new ItemStack(Items.STICK, 8));
		assertEquals(0, SettlementCargoDelivery.finish(f.prepare(), f.data, f.camp, ACTOR, f.bag, f.bag, () -> true).moved());
		SimpleContainer rejects = new SimpleContainer(2) {
			@Override public boolean canPlaceItem(int slot, ItemStack stack) { return false; }
		};
		assertEquals(0, SettlementCargoDelivery.finish(f.prepare(), f.data, f.camp, ACTOR, f.bag, rejects, () -> true).moved());
		assertEquals(8, f.bag.countItem(Items.STICK)); assertTrue(rejects.isEmpty());
	}

	@Test void newProductionAndDeliveryDoNotMigrateOldAbstractStock() {
		Fixture f = new Fixture(); var state = f.data.getOrCreateSimulation(f.camp);
		state.mapResident(ACTOR, false, 0); state.setSurvivalEnabled(true, 0);
		state.setProductionEnabled(true); state.setSkillsEnabled(true);
		state.world().importExternalBatch(com.thysummer.coopcraft.civilization.simulation.ResourceType.STONE, 11, state.campOwner());
		var old = java.util.List.copyOf(state.world().batchesAt(state.campOwner()));
		f.bag.setItem(0, new ItemStack(Items.COBBLESTONE, 4));
		assertEquals(0, SettlementSimulationService.recordProduction(f.data, f.camp, ACTOR, f.bag, Items.COBBLESTONE, 4, "gather"));
		assertEquals(4, f.bag.countItem(Items.COBBLESTONE));
		assertTrue(state.world().ledger().events().stream().anyMatch(e -> e.type() == com.thysummer.coopcraft.civilization.simulation.EventType.SKILL_PRACTICED));
		assertEquals(old, state.world().batchesAt(state.campOwner()));
		assertEquals(4, f.finish(f.prepare()).moved());
		assertEquals(old, state.world().batchesAt(state.campOwner())); state.world().assertConservation();
	}
}
