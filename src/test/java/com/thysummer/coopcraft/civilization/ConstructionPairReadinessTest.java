package com.thysummer.coopcraft.civilization;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConstructionPairReadinessTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static final BlockPos ORIGIN = new BlockPos(15, 64, 15);

	private static class Probe implements ConstructionReadiness.Probe {
		final Map<BlockPos, BlockState> blocks = new HashMap<>();
		final Set<BlockPos> unknown = new HashSet<>(), outside = new HashSet<>(), reads = new HashSet<>();
		int checks, entities, stockpileReads;
		boolean entity;
		final SimpleContainer inventory = new SimpleContainer(4);
		public boolean inBounds(BlockPos pos) { checks++; return !outside.contains(pos); }
		public boolean ticking(BlockPos pos) { checks++; return !unknown.contains(pos); }
		public BlockState state(BlockPos pos) {
			assertFalse(unknown.contains(pos)); assertFalse(outside.contains(pos));
			reads.add(pos); return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
		}
		public boolean entityObstructed(BlockPos pos) { entities++; return entity; }
		public Container stockpile() { stockpileReads++; return inventory; }
		public SettlementSimulationState simulation() { return null; }
	}

	private ConstructionPlan pair(boolean door, Direction facing) {
		BlockState paid = door ? Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing)
			: Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, facing);
		BlockState free = door ? paid.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)
			: paid.setValue(BedBlock.PART, BedPart.HEAD);
		BlockPos offset = door ? new BlockPos(0, 1, 0) : new BlockPos(facing.getStepX(), 0, facing.getStepZ());
		return new ConstructionPlan(ResourceLocation.parse("coopcraft:pair_test"), List.of(
			new ConstructionInstruction(BlockPos.ZERO, paid, paid.getBlock().asItem(), true),
			new ConstructionInstruction(offset, free, free.getBlock().asItem(), false)));
	}
	private ConstructionJob job(ConstructionPlan plan, int index) { return new ConstructionJob(plan.id(), ORIGIN, index, plan.fingerprint()); }
	private BlockPos at(ConstructionPlan plan, int index) { return ORIGIN.offset(plan.instructions().get(index).offset()); }
	private String inspect(ConstructionPlan plan, int index, Probe probe) {
		return ConstructionPairReadiness.inspect(job(plan, index), plan, probe, false).blocker();
	}

	@Test void paidHalvesRequireEmptyCompanionAndNeverReadInventoriesWhenBlocked() {
		for (boolean door : List.of(true, false)) {
			var plan = pair(door, Direction.EAST); var probe = new Probe();
			for (var occupied : List.of(Blocks.STONE.defaultBlockState(), plan.instructions().get(1).state())) {
				probe.blocks.put(at(plan, 1), occupied);
				assertEquals("pair_occupied", inspect(plan, 0, probe));
				assertEquals("pair_occupied", ConstructionReadiness.inspect(job(plan, 0), plan, probe).blocker());
				assertEquals(0, probe.stockpileReads);
			}
			probe.blocks.clear(); assertEquals("ready", inspect(plan, 0, probe));
		}
	}

	@Test void freeHalvesRequireExactPaidStateIncludingPlayerChangedProperties() {
		for (boolean door : List.of(true, false)) {
			var plan = pair(door, Direction.NORTH); var probe = new Probe();
			var paid = plan.instructions().getFirst().state();
			var changed = door ? paid.setValue(DoorBlock.OPEN, true) : paid.setValue(BedBlock.OCCUPIED, true);
			for (var actual : List.of(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), changed)) {
				probe.blocks.put(at(plan, 0), actual); assertEquals("pair_changed", inspect(plan, 1, probe));
			}
			probe.blocks.put(at(plan, 0), paid); assertEquals("ready", inspect(plan, 1, probe));
		}
	}

	@Test void unknownOrOutOfBoundsCompanionPreflightsBeforeAnyStateOrEntityReads() {
		for (boolean door : List.of(true, false)) for (int index : List.of(0, 1)) {
			var plan = pair(door, Direction.EAST); var probe = new Probe();
			// East bed crosses the x=15 chunk boundary.
			probe.unknown.add(at(plan, 1 - index));
			assertEquals("pair_not_ticking", inspect(plan, index, probe));
			assertTrue(probe.reads.isEmpty()); assertEquals(0, probe.entities);
			probe.unknown.clear(); probe.outside.add(at(plan, 1 - index));
			assertEquals("pair_out_of_bounds", inspect(plan, index, probe));
			assertTrue(probe.reads.isEmpty()); assertEquals(0, probe.entities);
		}
	}

	@Test void overflowNeverWrapsIntoWorldProbes() {
		var plan = pair(false, Direction.EAST); var probe = new Probe();
		var job = new ConstructionJob(plan.id(), new BlockPos(Integer.MAX_VALUE, 64, 0), 0, plan.fingerprint());
		assertEquals("target_out_of_bounds", ConstructionPairReadiness.inspect(job, plan, probe, true).blocker());
		assertEquals(0, probe.checks); assertTrue(probe.reads.isEmpty());
		var door = pair(true, Direction.NORTH);
		var lower = door.instructions().getFirst(); var upper = door.instructions().getLast();
		var overflow = new ConstructionPlan(door.id(), List.of(
			new ConstructionInstruction(new BlockPos(0, Integer.MAX_VALUE, 0), lower.state(), lower.item(), true),
			new ConstructionInstruction(new BlockPos(0, Integer.MIN_VALUE, 0), upper.state(), upper.item(), false)));
		assertEquals("target_out_of_bounds", inspect(overflow, 0, probe)); assertEquals(0, probe.checks);
	}

	@Test void malformedSyntheticPairsNeverProbeWorld() {
		for (boolean door : List.of(true, false)) {
			var pair = pair(door, Direction.NORTH); var paid = pair.instructions().getFirst(); var free = pair.instructions().getLast();
			for (var instructions : List.of(List.of(paid), List.of(free), List.of(free, paid), List.of(paid, paid),
				List.of(paid, new ConstructionInstruction(BlockPos.ZERO, free.state(), free.item(), false)),
				List.of(paid, new ConstructionInstruction(free.offset(), free.state(), Items.DIRT, false)))) {
				var malformed = new ConstructionPlan(pair.id(), instructions); var probe = new Probe();
				assertEquals("pair_invalid", inspect(malformed, 0, probe)); assertEquals(0, probe.checks);
				assertTrue(probe.reads.isEmpty());
			}
		}
	}

	@Test void ordinaryBlockHasNoNeighborReadsAndClaimIgnoresBuilderEntity() {
		var instruction = new ConstructionInstruction(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), Items.STONE, true);
		var plan = new ConstructionPlan(ResourceLocation.parse("coopcraft:single"), List.of(instruction));
		var probe = new Probe(); probe.entity = true;
		assertEquals("ready", inspect(plan, 0, probe)); assertEquals(Set.of(ORIGIN), probe.reads);
		assertEquals(0, probe.entities); assertEquals(2, probe.checks);
		assertEquals("entity_obstructed", ConstructionPairReadiness.inspect(job(plan, 0), plan, probe, true).blocker());
	}

	@Test void targetOccupiedAndEntityDiagnosticsKeepPrecedenceAfterEnvelopeChecks() {
		var plan = pair(true, Direction.NORTH); var probe = new Probe(); probe.entity = true;
		probe.blocks.put(ORIGIN, Blocks.STONE.defaultBlockState());
		probe.blocks.put(at(plan, 1), Blocks.STONE.defaultBlockState());
		assertEquals("target_occupied", ConstructionReadiness.inspect(job(plan, 0), plan, probe).blocker());
		probe.blocks.remove(ORIGIN);
		assertEquals("entity_obstructed", ConstructionReadiness.inspect(job(plan, 0), plan, probe).blocker());
	}

	@Test void staleClaimRechecksBeforeTransactionAndLeavesSavedDataAndInventoryUntouched() {
		for (boolean door : List.of(true, false)) for (int index : List.of(0, 1)) {
			var plan = pair(door, Direction.EAST); var probe = new Probe(); var job = job(plan, index);
			if (index == 1) probe.blocks.put(ORIGIN, plan.instructions().getFirst().state());
			assertEquals("ready", inspect(plan, index, probe));
			var data = new SettlementSavedData(); var settlement = data.getOrCreate(ORIGIN, "Pair", 0);
			data.setConstructionJob(settlement, job); data.setDirty(false);
			probe.inventory.setItem(0, new ItemStack(plan.instructions().getFirst().item(), 3));
			var saved = job.save(); var calls = new AtomicInteger();
			probe.blocks.put(at(plan, 1 - index), Blocks.STONE.defaultBlockState());
			for (int repeat = 0; repeat < 3; repeat++) {
				assertFalse(ConstructionSystem.placeIfReady(job, plan, probe, () -> {
					calls.incrementAndGet(); probe.inventory.removeItem(0, 1); data.setDirty(); return true;
				}));
			}
			assertEquals(0, calls.get()); assertEquals(3, probe.inventory.getItem(0).getCount());
			assertEquals(saved, data.getConstructionJob(settlement).orElseThrow().save()); assertFalse(data.isDirty());
			probe.blocks.put(at(plan, 1 - index), index == 0 ? Blocks.AIR.defaultBlockState() : plan.instructions().getFirst().state());
			assertTrue(ConstructionSystem.placeIfReady(ConstructionJob.load(saved), plan, probe, () -> { calls.incrementAndGet(); return true; }));
			assertEquals(1, calls.get());
		}
	}

	@Test void exactPairsInBothOrientationsRemainReadyAcrossJobReload() {
		for (boolean door : List.of(true, false)) for (var facing : List.of(Direction.NORTH, Direction.EAST)) {
			var plan = pair(door, facing); var probe = new Probe();
			for (int index : List.of(0, 1)) {
				if (index == 1) probe.blocks.put(ORIGIN, plan.instructions().getFirst().state());
				var restored = ConstructionJob.load(job(plan, index).save());
				assertTrue(ConstructionPairReadiness.inspect(restored, plan, probe, true).ready());
			}
		}
	}
	@Test void publishedOldStoneAndNorthEastPlansKeepExactPairsAcrossFullSaveReload() throws Exception {
		for (String name : List.of("stone_cottage", "timber_porch_cabin_v1", "timber_side_porch_cabin_v1")) {
			try (var input = getClass().getResourceAsStream("/data/coopcraft/civilization/blueprints/" + name + ".json")) {
				assertNotNull(input, name);
				var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(input,
					java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
				var plan = ConstructionPlan.fromJson(ResourceLocation.parse("coopcraft:" + name), json);
				int pairedSteps = 0;
				for (int index = 0; index < plan.instructions().size(); index++) {
					var instruction = plan.instructions().get(index);
					if (!(instruction.state().getBlock() instanceof DoorBlock) && !(instruction.state().getBlock() instanceof BedBlock)) continue;
					pairedSteps++;
					var data = new SettlementSavedData(); var settlement = data.getOrCreate(ORIGIN, "Roundtrip", 0);
					data.setConstructionJob(settlement, job(plan, index));
					var saved = data.save(new net.minecraft.nbt.CompoundTag(), null);
					var restored = SettlementSavedData.load(saved, null); restored.setDirty(false);
					var probe = new Probe();
					if (!instruction.consume()) probe.blocks.put(at(plan, index - 1), plan.instructions().get(index - 1).state());
					probe.inventory.setItem(0, new ItemStack(instruction.item(), 2));
					var restoredJob = restored.getConstructionJob(settlement).orElseThrow();
					assertEquals("ready", ConstructionReadiness.inspect(restoredJob, plan, probe).blocker());
					assertEquals(saved, restored.save(new net.minecraft.nbt.CompoundTag(), null));
					assertFalse(restored.isDirty()); assertEquals(2, probe.inventory.getItem(0).getCount());
				}
				assertTrue(pairedSteps >= 4, name);
			}
		}
	}

	@Test void guardComposesWithRealMaterialTransactionForBothHalvesAndRetries() {
		for (boolean door : List.of(true, false)) {
			var plan = pair(door, Direction.EAST); var probe = new Probe();
			var first = ConstructionJob.load(job(plan, 0).save());
			var paid = plan.instructions().getFirst(); var free = plan.instructions().getLast();
			var villager = java.util.UUID.randomUUID(); var dirty = new AtomicInteger();
			var simulation = SettlementSimulationState.create(new Settlement(java.util.UUID.randomUUID(), ORIGIN, "Pair", 0));
			simulation.setSurvivalEnabled(true, 0); simulation.setProductionEnabled(true);
			simulation.world().importExternalBatch(com.thysummer.coopcraft.civilization.simulation.ResourceType.STONE,
				3, simulation.campOwner());
			var ledgerBefore = simulation.save();
			probe.inventory.setItem(0, new ItemStack(paid.item()));
			// A failed block-placement callback leaves the one physical item available for retry.
			assertFalse(ConstructionSystem.placeIfReady(first, plan, probe, () -> ConstructionMaterials.place(
				probe.inventory, simulation, paid.item(), true, first, villager, dirty::incrementAndGet, () -> false)));
			assertEquals(1, probe.inventory.countItem(paid.item()));
			assertEquals(ledgerBefore, simulation.save()); assertEquals(0, dirty.get());
			assertTrue(ConstructionSystem.placeIfReady(first, plan, probe, () -> ConstructionMaterials.place(
				probe.inventory, simulation, paid.item(), true, first, villager, dirty::incrementAndGet, () -> {
					probe.blocks.put(at(plan, 0), paid.state()); return true;
				})));
			assertEquals(0, probe.inventory.countItem(paid.item()));
			var second = ConstructionJob.load(first.advance().save());
			var savedSecond = second.save(); var entered = new AtomicInteger();
			probe.blocks.put(at(plan, 0), door ? paid.state().setValue(DoorBlock.OPEN, true)
				: paid.state().setValue(BedBlock.OCCUPIED, true));
			assertFalse(ConstructionSystem.placeIfReady(second, plan, probe, () -> {
				entered.incrementAndGet();
				return ConstructionMaterials.place(probe.inventory, simulation, free.item(), false, second,
					villager, dirty::incrementAndGet, () -> true);
			}));
			assertEquals(0, entered.get()); assertEquals(savedSecond, second.save());
			assertEquals(ledgerBefore, simulation.save()); simulation.world().assertConservation();
			probe.blocks.put(at(plan, 0), paid.state());
			// An already filled target is still never silently adopted, even with an exact paid half.
			probe.blocks.put(at(plan, 1), free.state());
			assertFalse(ConstructionSystem.placeIfReady(second, plan, probe, () -> { entered.incrementAndGet(); return true; }));
			assertEquals(0, entered.get()); probe.blocks.remove(at(plan, 1));
			assertTrue(ConstructionSystem.placeIfReady(second, plan, probe, () -> ConstructionMaterials.place(
				probe.inventory, simulation, free.item(), false, second, villager, dirty::incrementAndGet, () -> {
					probe.blocks.put(at(plan, 1), free.state()); return true;
				})));
			assertTrue(probe.inventory.isEmpty()); assertEquals(0, dirty.get());
			assertEquals(ledgerBefore, simulation.save()); simulation.world().assertConservation();
			assertEquals(paid.state(), probe.blocks.get(at(plan, 0)));
			assertEquals(free.state(), probe.blocks.get(at(plan, 1)));
		}
	}

	@Test void ordinaryTargetBoundsAndOverflowStopWithoutStateReads() {
		var instruction = new ConstructionInstruction(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState(), Items.STONE, true);
		var plan = new ConstructionPlan(ResourceLocation.parse("coopcraft:single_bounds"), List.of(instruction));
		var probe = new Probe(); probe.outside.add(at(plan, 0));
		assertEquals("target_out_of_bounds", inspect(plan, 0, probe));
		assertTrue(probe.reads.isEmpty()); assertEquals(0, probe.entities);
		probe.outside.clear(); probe.unknown.add(at(plan, 0));
		assertEquals("target_not_ticking", inspect(plan, 0, probe)); assertTrue(probe.reads.isEmpty());
		var overflow = new ConstructionJob(plan.id(), new BlockPos(Integer.MAX_VALUE, 64, 0), 0, plan.fingerprint());
		var untouched = new Probe();
		assertEquals("target_out_of_bounds", ConstructionPairReadiness.inspect(overflow, plan, untouched, true).blocker());
		assertEquals(0, untouched.checks); assertTrue(untouched.reads.isEmpty());
	}

}
