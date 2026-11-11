package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Real inventory and simulation services only; no level, entity navigation or GameTest. */
class ConstructionMaterialSupplyIsolationTest {
	private final UUID actor = UUID.randomUUID();
	private final Settlement settlement = new Settlement(UUID.randomUUID(), BlockPos.ZERO, "Isolation", 0);
	private final ConstructionJob job = new ConstructionJob(ConstructionPlanner.CAMPFIRE_HEARTH, BlockPos.ZERO, 0);

	@BeforeAll static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static ItemStack marked(Item item, int count, Tag marker) {
		ItemStack stack = new ItemStack(item, count);
		CompoundTag root = new CompoundTag();
		root.put(ConstructionSupply.KEY, marker.copy());
		root.putString("other", "preserve");
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Keep this exact cargo"));
		return stack;
	}

	private static ItemStack marked(Item item) {
		return marked(item, 1, StringTag.valueOf("unknown"));
	}

	private static ItemStack named(Item item, int count) {
		ItemStack stack = new ItemStack(item, count);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Legacy named material"));
		return stack;
	}

	private SettlementSimulationState state() {
		var state = SettlementSimulationState.create(settlement);
		state.mapResident(actor, false, 100);
		state.setSurvivalEnabled(true, 0);
		state.setProductionEnabled(true);
		state.world().importExternalBatch(ResourceType.STONE, 8, state.campOwner());
		return state;
	}

	private static long exports(SettlementSimulationState state) {
		return state.world().ledger().events().stream().filter(event -> event.type() == EventType.EXTERNAL_EXPORT).count();
	}

	private void assertReadOnlyReadiness(SimpleContainer store, SettlementSimulationState state,
		boolean expectedReady, String expectedBlocker) {
		var instruction = new ConstructionInstruction(BlockPos.ZERO, Blocks.COBBLESTONE.defaultBlockState(), Items.COBBLESTONE, true);
		var plan = new ConstructionPlan(ResourceLocation.parse("coopcraft:material_readiness"), List.of(instruction));
		var current = ConstructionJob.start(plan, BlockPos.ZERO);
		CompoundTag jobBefore = current.save(), stateBefore = state.save();
		ItemStack[] before = new ItemStack[store.getContainerSize()];
		for (int slot = 0; slot < before.length; slot++) before[slot] = store.getItem(slot).copy();
		assertEquals(expectedReady, ConstructionSystem.materialReady(current, instruction,
			ResourceLocation.parse("minecraft:overworld"), settlement.id(), actor, null, () -> store, () -> state));
		var diagnostic = ConstructionReadiness.inspect(current, plan, new ConstructionReadiness.Probe() {
			public boolean inBounds(BlockPos position) { return true; }
			public boolean ticking(BlockPos position) { return true; }
			public BlockState state(BlockPos position) { return Blocks.AIR.defaultBlockState(); }
			public boolean entityObstructed(BlockPos position) { return false; }
			public SettlementHousing.BedCheck inspectBed(BlockPos position) { return fail("No bed in this plan"); }
			public Container stockpile() { return store; }
			public SettlementSimulationState simulation() { return state; }
		});
		assertEquals(expectedBlocker, diagnostic.blocker());
		for (int slot = 0; slot < before.length; slot++) assertTrue(ItemStack.matches(before[slot], store.getItem(slot)));
		assertEquals(jobBefore, current.save());
		assertEquals(stateBefore, state.save());
		assertEquals(0, exports(state));
	}

	@Test void everyMarkerShapeIsExcludedFromRealMaterialAvailabilityAndPlanReadiness() {
		CompoundTag future = new CompoundTag(); future.putInt("Version", 2);
		for (Item item : List.of(Items.COBBLESTONE, Items.OAK_LOG, Items.STICK, Items.SMOOTH_STONE,
			Items.OAK_PLANKS, Items.CAMPFIRE, Items.OAK_DOOR, Items.WHITE_BED)) {
			for (Tag marker : List.of(StringTag.valueOf("malformed"), IntTag.valueOf(1), new CompoundTag(), future)) {
				ItemStack cargo = marked(item, 1, marker);
				ItemStack before = cargo.copy();
				var store = new SimpleContainer(cargo);
				var plan = new ConstructionPlan(ResourceLocation.parse("coopcraft:marked_material"), List.of(
					new ConstructionInstruction(BlockPos.ZERO, Blocks.COBBLESTONE.defaultBlockState(), item, true)));
				assertEquals(0, ConstructionMaterials.available(store, null, item));
				assertFalse(ConstructionMaterials.hasAll(store, null, plan));
				assertTrue(ItemStack.matches(before, store.getItem(0)));
			}
		}
	}

	@Test void onlyMarkedStockNeverAuthorizesAPaidPlacement() {
		for (Item item : List.of(Items.COBBLESTONE, Items.OAK_LOG, Items.OAK_PLANKS,
			Items.CAMPFIRE, Items.OAK_DOOR, Items.WHITE_BED)) {
			ItemStack cargo = marked(item); ItemStack before = cargo.copy();
			var store = new SimpleContainer(cargo);
			assertFalse(ConstructionMaterials.place(store, null, item, true, job, actor,
				() -> fail("No abstract source"), () -> fail("Marked stock must not allow a free placement")));
			assertTrue(ItemStack.matches(before, store.getItem(0)));
		}
	}

	@Test void mixedStockConsumesOnlyTheUnmarkedNamedWoodOrStone() {
		for (Item item : List.of(Items.OAK_LOG, Items.COBBLESTONE)) {
			ItemStack cargo = marked(item, 3, StringTag.valueOf("unknown"));
			ItemStack before = cargo.copy();
			ItemStack material = named(item, 2); ItemStack remaining = material.copy(); remaining.shrink(1);
			var store = new SimpleContainer(cargo, material);
			assertEquals(2, ConstructionMaterials.available(store, null, item));
			assertTrue(ConstructionMaterials.place(store, null, item, true, job, actor,
				() -> fail("Use the real material"), () -> true));
			assertTrue(ItemStack.matches(before, store.getItem(0)));
			assertTrue(ItemStack.matches(remaining, store.getItem(1)));
			assertEquals(1, ConstructionMaterials.available(store, null, item));
		}
	}

	@Test void doorAndBedStillRequireOrdinaryComponentsInBothAvailabilityAndPayment() {
		for (Item item : List.of(Items.OAK_DOOR, Items.WHITE_BED)) {
			ItemStack custom = named(item, 1); ItemStack customBefore = custom.copy();
			ItemStack cargo = marked(item); ItemStack cargoBefore = cargo.copy();
			var store = new SimpleContainer(custom, cargo, ItemStack.EMPTY);
			assertEquals(0, ConstructionMaterials.available(store, null, item));
			assertFalse(ConstructionMaterials.place(store, null, item, true, job, actor,
				() -> fail("No abstract door/bed"), () -> fail("Custom door/bed is not ordinary supply")));
			store.setItem(2, new ItemStack(item));
			assertEquals(1, ConstructionMaterials.available(store, null, item));
			assertTrue(ConstructionMaterials.place(store, null, item, true, job, actor,
				() -> fail("Use the ordinary item"), () -> true));
			assertTrue(ItemStack.matches(customBefore, store.getItem(0)));
			assertTrue(ItemStack.matches(cargoBefore, store.getItem(1)));
			assertTrue(store.getItem(2).isEmpty());
		}
	}

	@Test void markedLookalikeDoesNotSuppressAbstractExportOrBecomeItsPayment() {
		var state = state();
		ItemStack cargo = marked(Items.COBBLESTONE, 3, StringTag.valueOf("unknown"));
		ItemStack before = cargo.copy();
		var store = new SimpleContainer(cargo, ItemStack.EMPTY);
		var dirty = new AtomicInteger();
		assertEquals(8, ConstructionMaterials.available(store, state, Items.COBBLESTONE));
		assertTrue(ConstructionMaterials.place(store, state, Items.COBBLESTONE, true, job, actor,
			dirty::incrementAndGet, () -> {
				assertTrue(ItemStack.matches(before, store.getItem(0)));
				assertTrue(ItemStack.matches(new ItemStack(Items.COBBLESTONE), store.getItem(1)));
				return true;
			}));
		assertTrue(ItemStack.matches(before, store.getItem(0)));
		assertTrue(store.getItem(1).isEmpty());
		assertEquals(1, exports(state)); assertEquals(1, dirty.get());
		assertEquals(7, ConstructionMaterials.available(store, state, Items.COBBLESTONE));
		state.world().assertConservation();
	}

	@Test void markedOnlyFullStoreCannotAcceptOrSpendAbstractSupply() {
		var state = state(); CompoundTag beforeState = state.save();
		ItemStack cargo = marked(Items.COBBLESTONE); ItemStack before = cargo.copy();
		var store = new SimpleContainer(cargo);
		assertFalse(ConstructionMaterials.place(store, state, Items.COBBLESTONE, true, job, actor,
			() -> fail("No room for an exported item"), () -> fail("No real payment available")));
		assertTrue(ItemStack.matches(before, store.getItem(0)));
		assertEquals(beforeState, state.save());
		assertEquals(0, exports(state));
		assertEquals(8, ConstructionMaterials.available(store, state, Items.COBBLESTONE));
		state.world().assertConservation();
	}

	@Test void failedOrThrowingPlacementLeavesAllRealStacksUnchanged() {
		ItemStack cargo = marked(Items.COBBLESTONE, 3, StringTag.valueOf("unknown"));
		ItemStack cargoBefore = cargo.copy();
		ItemStack material = named(Items.COBBLESTONE, 2); ItemStack materialBefore = material.copy();
		var store = new SimpleContainer(cargo, material);
		assertFalse(ConstructionMaterials.place(store, null, Items.COBBLESTONE, true, job, actor,
			() -> fail("Use the real material"), () -> false));
		assertTrue(ItemStack.matches(cargoBefore, store.getItem(0)));
		assertTrue(ItemStack.matches(materialBefore, store.getItem(1)));
		assertThrows(IllegalStateException.class, () -> ConstructionMaterials.place(store, null,
			Items.COBBLESTONE, true, job, actor, () -> fail("Use the real material"),
			() -> { throw new IllegalStateException("Placement aborted"); }));
		assertTrue(ItemStack.matches(cargoBefore, store.getItem(0)));
		assertTrue(ItemStack.matches(materialBefore, store.getItem(1)));
	}

	@Test void failedExportedPlacementRetainsRealUnitAndRetryDoesNotReexportOrTouchMarkedCargo() {
		var state = state();
		ItemStack cargo = marked(Items.COBBLESTONE); ItemStack before = cargo.copy();
		var store = new SimpleContainer(cargo, ItemStack.EMPTY);
		var dirty = new AtomicInteger();
		assertFalse(ConstructionMaterials.place(store, state, Items.COBBLESTONE, true, job, actor,
			dirty::incrementAndGet, () -> false));
		assertTrue(ItemStack.matches(before, store.getItem(0)));
		assertTrue(ItemStack.matches(new ItemStack(Items.COBBLESTONE), store.getItem(1)));
		assertEquals(8, ConstructionMaterials.available(store, state, Items.COBBLESTONE));
		assertTrue(ConstructionMaterials.place(store, state, Items.COBBLESTONE, true, job, actor,
			() -> fail("The retained real item must not be exported twice"), () -> true));
		assertTrue(ItemStack.matches(before, store.getItem(0)));
		assertTrue(store.getItem(1).isEmpty());
		assertEquals(1, exports(state)); assertEquals(1, dirty.get());
		assertEquals(7, ConstructionMaterials.available(store, state, Items.COBBLESTONE));
		state.world().assertConservation();
	}

	@Test void paidCompanionStepStillNeedsNoPaymentAndNeverTouchesMarkedStock() {
		ItemStack cargo = marked(Items.OAK_DOOR); ItemStack before = cargo.copy();
		var store = new SimpleContainer(cargo);
		assertTrue(ConstructionMaterials.place(store, null, Items.OAK_DOOR, false, job, actor,
			() -> fail("Companion already paid"), () -> true));
		assertTrue(ItemStack.matches(before, store.getItem(0)));
	}

	@Test void markedOnlyFullStoreIsNotAReadyTargetAndReportsStockpileFullDespiteAbstractSupply() {
		var state = state();
		var store = new SimpleContainer(marked(Items.COBBLESTONE));
		assertEquals(0, ConstructionMaterials.realAvailable(store, Items.COBBLESTONE));
		assertEquals(8, ConstructionMaterials.available(store, state, Items.COBBLESTONE));
		assertReadOnlyReadiness(store, state, false, "stockpile_full");
	}

	@Test void emptyCapacityOrUnmarkedRealSupplyMakesTargetAndDiagnosticReadyWithoutMutation() {
		var state = state();
		var withCapacity = new SimpleContainer(marked(Items.COBBLESTONE), ItemStack.EMPTY);
		assertEquals(0, ConstructionMaterials.realAvailable(withCapacity, Items.COBBLESTONE));
		assertReadOnlyReadiness(withCapacity, state, true, "ready");
		var mixed = new SimpleContainer(marked(Items.COBBLESTONE), named(Items.COBBLESTONE, 2));
		assertEquals(2, ConstructionMaterials.realAvailable(mixed, Items.COBBLESTONE));
		assertReadOnlyReadiness(mixed, state, true, "ready");
	}
}
