package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConstructionMaterialsTest {
	private final UUID villagerId = UUID.randomUUID();
	private final Settlement settlement = new Settlement(UUID.randomUUID(), BlockPos.ZERO, "Test", 0);
	private final ConstructionJob job = new ConstructionJob(ConstructionPlanner.CAMPFIRE_HEARTH, new BlockPos(6, 64, 0), 2);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private SettlementSimulationState state() {
		var state = SettlementSimulationState.create(settlement);
		state.mapResident(villagerId, false, 100);
		state.setSurvivalEnabled(true, 0);
		state.setProductionEnabled(true);
		state.world().importExternalBatch(ResourceType.STONE, 8, state.campOwner());
		return state;
	}

	private List<SimulationEvent> exports(SettlementSimulationState state) {
		return state.world().ledger().events().stream().filter(event -> event.type() == EventType.EXTERNAL_EXPORT).toList();
	}

	@Test
	void combinesRealAndAbstractStockWithoutMovingIt() {
		var state = state();
		var stockpile = new SimpleContainer(2);
		stockpile.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
		stockpile.setItem(1, new ItemStack(Items.CAMPFIRE));
		assertEquals(11, ConstructionMaterials.available(stockpile, state, Items.COBBLESTONE));
		assertEquals(1, ConstructionMaterials.available(stockpile, state, Items.CAMPFIRE));
		assertEquals(0, ConstructionMaterials.available(null, state, Items.COBBLESTONE));
		assertTrue(exports(state).isEmpty());
		assertEquals(3, stockpile.countItem(Items.COBBLESTONE));
	}

	@Test
	void projectReadinessUsesMixedInventoryAndStillRequiresUnmappedItems() {
		var state = state();
		var stockpile = new SimpleContainer(2);
		stockpile.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
		var instructions = new ArrayList<ConstructionInstruction>();
		for (int index = 0; index < 11; index++) {
			instructions.add(new ConstructionInstruction(new BlockPos(index, 0, 0),
				Blocks.COBBLESTONE.defaultBlockState(), Items.COBBLESTONE, true));
		}
		instructions.add(new ConstructionInstruction(new BlockPos(12, 0, 0),
			Blocks.CAMPFIRE.defaultBlockState(), Items.CAMPFIRE, true));
		var plan = new ConstructionPlan(job.blueprintId(), instructions);
		assertFalse(ConstructionMaterials.hasAll(stockpile, state, plan));
		stockpile.setItem(1, new ItemStack(Items.CAMPFIRE));
		assertTrue(ConstructionMaterials.hasAll(stockpile, state, plan));
		state.setProductionEnabled(false);
		assertFalse(ConstructionMaterials.hasAll(stockpile, state, plan));
		assertTrue(exports(state).isEmpty());
	}

	@Test
	void requiresBothProductionAndSurvivalForAbstractMaterials() {
		var state = state();
		var stockpile = new SimpleContainer(1);
		stockpile.setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		state.setProductionEnabled(false);
		assertEquals(2, ConstructionMaterials.available(stockpile, state, Items.COBBLESTONE));
		stockpile.clearContent();
		assertFalse(ConstructionMaterials.place(stockpile, state, Items.COBBLESTONE, true, job, villagerId,
			() -> fail("No export"), () -> fail("No placement")));
		state.setProductionEnabled(true);
		state.setSurvivalEnabled(false, 0);
		assertEquals(0, ConstructionMaterials.available(stockpile, state, Items.COBBLESTONE));
		assertFalse(ConstructionMaterials.place(stockpile, state, Items.COBBLESTONE, true, job, villagerId,
			() -> fail("No export"), () -> fail("No placement")));
	}

	@Test
	void fullStockpileWaitsWithoutExportingOrPlacing() {
		var state = state();
		var stockpile = new SimpleContainer(1);
		stockpile.setItem(0, new ItemStack(Items.DIRT, 64));
		assertFalse(ConstructionMaterials.place(stockpile, state, Items.COBBLESTONE, true, job, villagerId,
			() -> fail("No export"), () -> fail("No placement")));
		assertEquals(8, ConstructionMaterials.available(stockpile, state, Items.COBBLESTONE));
		state.world().assertConservation();
		assertEquals(64, stockpile.countItem(Items.DIRT));
		assertTrue(exports(state).isEmpty());
	}

	@Test
	void failedPlacementRetainsExportAndRetryExportsExactlyOnce() {
		var state = state();
		var stockpile = new SimpleContainer(1);
		var dirty = new AtomicInteger();
		long source = state.world().batchesAt(state.campOwner()).getFirst().lastEventId();
		String batchId = state.world().batchesAt(state.campOwner()).getFirst().batchId();
		assertFalse(ConstructionMaterials.place(stockpile, state, Items.COBBLESTONE, true, job, villagerId,
			dirty::incrementAndGet, () -> false));
		assertEquals(1, dirty.get());
		assertEquals(1, stockpile.countItem(Items.COBBLESTONE));
		assertEquals(8, ConstructionMaterials.available(stockpile, state, Items.COBBLESTONE));
		state.world().assertConservation();
		assertTrue(ConstructionMaterials.place(stockpile, state, Items.COBBLESTONE, true, job, villagerId,
			dirty::incrementAndGet, () -> {
				assertEquals(1, stockpile.countItem(Items.COBBLESTONE), "Consume only after placement");
				return true;
			}));
		assertEquals(0, stockpile.countItem(Items.COBBLESTONE));
		assertEquals(7, ConstructionMaterials.available(stockpile, state, Items.COBBLESTONE));
		state.world().assertConservation();
		assertEquals(1, dirty.get());
		assertEquals(1, exports(state).size());
		var export = exports(state).getFirst();
		assertEquals(List.of(source), export.causalLinks());
		assertEquals(List.of(state.residentId(villagerId).orElseThrow()), export.actors());
		assertEquals("build", export.context().get("activity"));
		assertEquals(job.blueprintId().toString(), export.context().get("blueprint"));
		assertEquals(job.origin().toShortString(), export.context().get("origin"));
		assertEquals("2", export.context().get("index"));
		assertEquals(villagerId.toString(), export.context().get("villagerId"));
		assertEquals(batchId, export.context().get("batchId"));
		assertEquals(1, export.inputs().getFirst().quantity());
	}

	@Test
	void reloadAfterFailedPlacementDoesNotExportTheRetainedUnitAgain() {
		var state = state();
		var stockpile = new SimpleContainer(1);
		assertFalse(ConstructionMaterials.place(stockpile, state, Items.COBBLESTONE, true, job, villagerId,
			() -> {}, () -> false));
		state.world().assertConservation();
		var restoredState = SettlementSimulationState.load(settlement, state.save());
		// The real chest is persisted separately from the simulation. Preserve its exported unit.
		var restoredStockpile = new SimpleContainer(stockpile.getItem(0).copy());
		var restoredJob = ConstructionJob.load(job.save());
		assertEquals(1, exports(restoredState).size());
		assertEquals(8, ConstructionMaterials.available(restoredStockpile, restoredState, Items.COBBLESTONE));
		assertTrue(ConstructionMaterials.place(restoredStockpile, restoredState, Items.COBBLESTONE, true,
			restoredJob, villagerId, () -> fail("Retained item must not be exported twice"), () -> true));
		assertEquals(1, exports(restoredState).size());
		assertEquals(0, restoredStockpile.countItem(Items.COBBLESTONE));
		assertEquals(7, ConstructionMaterials.available(restoredStockpile, restoredState, Items.COBBLESTONE));
		restoredState.world().assertConservation();
	}

	@Test
	void usesRealMaterialsFirstAndMultipartSecondPartIsFree() {
		var state = state();
		var stockpile = new SimpleContainer(1);
		stockpile.setItem(0, new ItemStack(Items.COBBLESTONE));
		assertTrue(ConstructionMaterials.place(stockpile, state, Items.COBBLESTONE, true, job, villagerId,
			() -> fail("Real item must be used first"), () -> true));
		assertTrue(exports(state).isEmpty());
		assertEquals(8, ConstructionMaterials.available(stockpile, state, Items.COBBLESTONE));
		state.world().assertConservation();
		assertTrue(ConstructionMaterials.place(stockpile, state, Items.COBBLESTONE, false, job, villagerId,
			() -> fail("No export for multipart continuation"), () -> true));
		assertTrue(exports(state).isEmpty());
		assertTrue(stockpile.isEmpty());
	}

	@Test
	void failedPlacementKeepsRealMaterialsUntouched() {
		var stockpile = new SimpleContainer(1);
		stockpile.setItem(0, new ItemStack(Items.COBBLESTONE));
		assertFalse(ConstructionMaterials.place(stockpile, null, Items.COBBLESTONE, true, job, villagerId,
			() -> fail("No simulation"), () -> false));
		assertEquals(1, stockpile.countItem(Items.COBBLESTONE));
	}
}
