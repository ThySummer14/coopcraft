package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConstructionReadinessTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private final ConstructionInstruction instruction = new ConstructionInstruction(new BlockPos(1, 0, 0),
		Blocks.COBBLESTONE.defaultBlockState(), Items.COBBLESTONE, true);
	private final ConstructionPlan plan = new ConstructionPlan(ConstructionPlanner.CAMPFIRE_HEARTH, List.of(instruction, instruction));
	private final ConstructionJob job = new ConstructionJob(plan.id(), new BlockPos(6, 64, 0), 1, plan.fingerprint());

	private static class Probe implements ConstructionReadiness.Probe {
		boolean ticking = true;
		boolean air = true;
		boolean entity;
		Container stockpile = new SimpleContainer(2);
		SettlementSimulationState state;
		int worldReads;
		public boolean ticking(BlockPos position) { return ticking; }
		public boolean inBounds(BlockPos position) { return true; }
		public net.minecraft.world.level.block.state.BlockState state(BlockPos position) {
			worldReads++; return (air ? Blocks.AIR : Blocks.STONE).defaultBlockState();
		}
		public boolean entityObstructed(BlockPos position) { worldReads++; return entity; }
		public Container stockpile() { worldReads++; return stockpile; }
		public SettlementSimulationState simulation() { return state; }
	}

	@Test
	void reportsMissingPlanAndInvalidIndexWithoutAnyWorldAccess() {
		assertEquals("plan_unavailable", ConstructionReadiness.inspect(job, null, null).blocker());
		var invalid = new ConstructionJob(job.blueprintId(), job.origin(), plan.instructions().size());
		var result = ConstructionReadiness.inspect(invalid, plan, null);
		assertEquals("invalid_index", result.blocker());
		assertNull(result.position());
		assertEquals(2, result.total());
	}

	@Test
	void unloadedTargetsDoNotReadBlocksEntitiesOrStockpile() {
		var probe = new Probe();
		probe.ticking = false;
		var result = ConstructionReadiness.inspect(job, plan, probe);
		assertEquals("target_not_ticking", result.blocker());
		assertEquals(job.origin().offset(instruction.offset()), result.position());
		assertEquals(0, probe.worldReads);
	}

	@Test
	void explainsEachBlockingReasonAndRecoversAfterClearingIt() {
		var probe = new Probe();
		probe.air = false;
		assertEquals("target_occupied", ConstructionReadiness.inspect(job, plan, probe).blocker());
		assertEquals(1, probe.worldReads);
		probe.air = true;
		probe.entity = true;
		assertEquals("entity_obstructed", ConstructionReadiness.inspect(job, plan, probe).blocker());
		probe.entity = false;
		probe.stockpile = null;
		assertEquals("stockpile_unavailable", ConstructionReadiness.inspect(job, plan, probe).blocker());
		probe.stockpile = new SimpleContainer(2);
		assertEquals("missing_material", ConstructionReadiness.inspect(job, plan, probe).blocker());
		probe.stockpile.setItem(0, new ItemStack(Items.COBBLESTONE));
		assertEquals("ready", ConstructionReadiness.inspect(job, plan, probe).blocker());
		assertEquals(1, probe.stockpile.countItem(Items.COBBLESTONE));
	}

	@Test
	void repeatedInspectionAndPausedJobReloadAreReadOnly() {
		var settlement = new Settlement(UUID.randomUUID(), BlockPos.ZERO, "Test", 0);
		var probe = new Probe();
		probe.state = SettlementSimulationState.create(settlement);
		probe.state.setSurvivalEnabled(true, 0);
		probe.state.setProductionEnabled(true);
		probe.state.world().importExternalBatch(ResourceType.STONE, 3, probe.state.campOwner());
		var before = probe.state.save();
		var savedJob = job.save();
		probe.air = false;
		for (int i = 0; i < 5; i++) {
			assertEquals("target_occupied", ConstructionReadiness.inspect(job, plan, probe).blocker());
		}
		var restored = ConstructionJob.load(savedJob);
		assertEquals(job, restored);
		assertEquals("target_occupied", ConstructionReadiness.inspect(restored, plan, probe).blocker());
		probe.air = true;
		assertEquals("ready", ConstructionReadiness.inspect(restored, plan, probe).blocker());
		assertEquals(before, probe.state.save());
		assertEquals(savedJob, job.save());
		assertTrue(probe.stockpile.isEmpty());
		probe.state.world().assertConservation();
	}

	private Probe abstractMaterialProbe() {
		var probe = new Probe();
		probe.state = SettlementSimulationState.create(new Settlement(UUID.randomUUID(), BlockPos.ZERO, "Test", 0));
		probe.state.setSurvivalEnabled(true, 0);
		probe.state.setProductionEnabled(true);
		probe.state.world().importExternalBatch(ResourceType.STONE, 3, probe.state.campOwner());
		return probe;
	}

	@Test
	void abstractMaterialNeedsRoomAndBecomesReadyWhenSlotIsCleared() {
		var probe = abstractMaterialProbe();
		probe.stockpile = new SimpleContainer(new ItemStack(Items.DIRT, 64));
		var before = probe.state.save();
		for (int i = 0; i < 3; i++) {
			assertEquals("stockpile_full", ConstructionReadiness.inspect(job, plan, probe).blocker());
		}
		assertEquals(64, probe.stockpile.countItem(Items.DIRT));
		assertEquals(0, probe.stockpile.countItem(Items.COBBLESTONE));
		assertEquals(before, probe.state.save());
		probe.stockpile.setItem(0, ItemStack.EMPTY);
		assertEquals("ready", ConstructionReadiness.inspect(job, plan, probe).blocker());
		assertTrue(probe.stockpile.isEmpty());
		assertEquals(before, probe.state.save());
	}

	@Test
	void emptyRejectedOrZeroCapacitySlotCannotAcceptAnAbstractUnit() {
		var probe = abstractMaterialProbe();
		var before = probe.state.save();
		probe.stockpile = new SimpleContainer(1) {
			@Override
			public boolean canPlaceItem(int slot, ItemStack stack) { return false; }
		};
		assertEquals("stockpile_full", ConstructionReadiness.inspect(job, plan, probe).blocker());
		assertTrue(probe.stockpile.isEmpty());
		probe.stockpile = new SimpleContainer(1) {
			@Override
			public int getMaxStackSize(ItemStack stack) { return 0; }
		};
		assertEquals("stockpile_full", ConstructionReadiness.inspect(job, plan, probe).blocker());
		assertTrue(probe.stockpile.isEmpty());
		assertEquals(before, probe.state.save());
	}

	@Test
	void fullStockpileWithRealMaterialAndFreeMultipartContinuationRemainReady() {
		var probe = abstractMaterialProbe();
		probe.stockpile = new SimpleContainer(new ItemStack(Items.COBBLESTONE, 64));
		var before = probe.state.save();
		assertEquals("ready", ConstructionReadiness.inspect(job, plan, probe).blocker());
		assertEquals(64, probe.stockpile.countItem(Items.COBBLESTONE));
		probe.stockpile = new SimpleContainer(new ItemStack(Items.DIRT, 64));
		var free = new ConstructionInstruction(instruction.offset(), instruction.state(), instruction.item(), false);
		var multipartPlan = new ConstructionPlan(job.blueprintId(), List.of(instruction, free));
		assertEquals("ready", ConstructionReadiness.inspect(new ConstructionJob(job.blueprintId(), job.origin(), job.nextIndex(),
			multipartPlan.fingerprint()), multipartPlan, probe).blocker());
		assertEquals(64, probe.stockpile.countItem(Items.DIRT));
		assertEquals(before, probe.state.save());
	}

	@Test
	void capacityProbeMatchesInsertionStackComponentsAndSlotAcceptance() {
		var stack = new ItemStack(Items.COBBLESTONE);
		var stockpile = new SimpleContainer(new ItemStack(Items.COBBLESTONE, 64));
		assertFalse(ConstructionReadiness.canInsertOne(stockpile, stack));
		stockpile.setItem(0, new ItemStack(Items.COBBLESTONE, 63));
		assertTrue(ConstructionReadiness.canInsertOne(stockpile, stack));
		stockpile.getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("Player's stack"));
		assertFalse(ConstructionReadiness.canInsertOne(stockpile, stack));
		assertEquals(63, stockpile.countItem(Items.COBBLESTONE));
		var rejects = new SimpleContainer(new ItemStack(Items.COBBLESTONE, 63)) {
			@Override
			public boolean canPlaceItem(int slot, ItemStack candidate) { return false; }
		};
		assertFalse(ConstructionReadiness.canInsertOne(rejects, stack));
		assertEquals(63, rejects.countItem(Items.COBBLESTONE));
	}

	@Test
	void inspectionDoesNotCreateOrCancelJobs() {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "Test", 0);
		assertTrue(ConstructionSystem.inspect(null, data, settlement).isEmpty());
		var missing = new ConstructionJob(ResourceLocation.parse("coopcraft:missing_readiness_test_plan"), job.origin(), job.nextIndex());
		data.setConstructionJob(settlement, missing);
		data.setDirty(false);
		assertEquals("plan_unavailable", ConstructionSystem.inspect(null, data, settlement).orElseThrow().blocker());
		assertEquals(missing, data.getConstructionJob(settlement).orElseThrow());
		assertFalse(data.isDirty());
	}
}
