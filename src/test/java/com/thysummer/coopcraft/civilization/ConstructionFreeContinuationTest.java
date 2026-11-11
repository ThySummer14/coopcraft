package com.thysummer.coopcraft.civilization;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure probes and material callbacks, without starting a server or claiming navigation coverage. */
class ConstructionFreeContinuationTest {
	private static final BlockPos ORIGIN = new BlockPos(15, 64, 15);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	private static final class Probe implements ConstructionReadiness.Probe {
		final Map<BlockPos, BlockState> blocks = new HashMap<>();
		final Set<BlockPos> unloaded = new HashSet<>();
		boolean entity;
		boolean completionUnloaded;
		int stockpileReads;
		public boolean inBounds(BlockPos position) { return true; }
		public boolean ticking(BlockPos position) { return !unloaded.contains(position); }
		public BlockState state(BlockPos position) {
			assertFalse(unloaded.contains(position), "Must preflight loading before reading blocks");
			return blocks.getOrDefault(position, Blocks.AIR.defaultBlockState());
		}
		public boolean entityObstructed(BlockPos position) { return entity; }
		public SettlementHousing.BedCheck inspectBed(BlockPos position) {
			return SettlementHousing.inspectBed(new SettlementHousing.BedAccess() {
				public boolean isLoaded(BlockPos at) { return !completionUnloaded && !unloaded.contains(at); }
				public BlockState stateAt(BlockPos at) { return state(at); }
			}, position);
		}
		public Container stockpile() { stockpileReads++; return null; }
		public SettlementSimulationState simulation() { throw new AssertionError("Must not resolve simulation"); }
	}

	private static ConstructionPlan pair(boolean door) {
		BlockState paid = door ? Blocks.OAK_DOOR.defaultBlockState()
			: Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
		BlockState free = door ? paid.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)
			: paid.setValue(BedBlock.PART, BedPart.HEAD);
		return new ConstructionPlan(ResourceLocation.parse("coopcraft:free_continuation"), List.of(
			new ConstructionInstruction(BlockPos.ZERO, paid, paid.getBlock().asItem(), true),
			new ConstructionInstruction(door ? new BlockPos(0, 1, 0) : new BlockPos(1, 0, 0),
				free, free.getBlock().asItem(), false)));
	}

	private static ConstructionJob freeJob(ConstructionPlan plan) {
		return ConstructionJob.start(plan, ORIGIN).advance();
	}

	private static boolean place(ConstructionPlan plan, ConstructionJob job, Probe probe,
		Container inventory, SettlementSimulationState simulation, AtomicInteger writes, AtomicInteger dirty) {
		var instruction = plan.instructions().get(job.nextIndex());
		return ConstructionSystem.placeIfReady(job, plan, probe, () -> ConstructionMaterials.place(
			inventory, simulation, instruction.item(), instruction.consume(), job, UUID.randomUUID(),
			dirty::incrementAndGet, () -> { writes.incrementAndGet(); return true; }));
	}

	@Test void exactPaidDoorAndBedContinueAfterReloadWithoutAStockpileOrInventoryCalls() {
		Container forbidden = (Container) Proxy.newProxyInstance(Container.class.getClassLoader(),
			new Class<?>[] {Container.class}, (proxy, method, args) -> {
				throw new AssertionError("Unexpected inventory operation: " + method.getName());
			});
		for (boolean door : List.of(true, false)) {
			var plan = pair(door);
			var original = freeJob(plan);
			var saved = original.save();
			var restored = ConstructionJob.load(saved);
			var probe = new Probe(); probe.blocks.put(ORIGIN, plan.instructions().getFirst().state());
			assertEquals("ready", ConstructionReadiness.inspect(restored, plan, probe).blocker());
			assertEquals(0, probe.stockpileReads);
			var simulation = SettlementSimulationState.create(new Settlement(UUID.randomUUID(), ORIGIN, "Free", 0));
			simulation.setSurvivalEnabled(true, 0); simulation.setProductionEnabled(true);
			simulation.world().importExternalBatch(com.thysummer.coopcraft.civilization.simulation.ResourceType.STONE,
				3, simulation.campOwner());
			var before = simulation.save();
			var writes = new AtomicInteger(); var dirty = new AtomicInteger();
			assertTrue(place(plan, restored, probe, null, null, writes, dirty));
			assertTrue(place(plan, restored, probe, new SimpleContainer(1), simulation, writes, dirty));
			assertTrue(place(plan, restored, probe, forbidden, simulation, writes, dirty));
			assertEquals(3, writes.get()); assertEquals(0, dirty.get());
			assertEquals(before, simulation.save(), "No ledger, inventory, or event changes");
			assertEquals(saved, restored.save()); assertEquals(plan.fingerprint(), restored.planFingerprint());
			simulation.world().assertConservation();
		}
	}

	@Test void changedMissingOrUnloadedPaidHalfRejectsBeforeAnyMaterialOperation() {
		for (boolean door : List.of(true, false)) {
			var plan = pair(door); var job = freeJob(plan); var paid = plan.instructions().getFirst().state();
			var changed = door ? paid.setValue(DoorBlock.OPEN, true) : paid.setValue(BedBlock.OCCUPIED, true);
			for (BlockState actual : List.of(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), changed)) {
				var probe = new Probe(); probe.blocks.put(ORIGIN, actual);
				assertBlocked(plan, job, probe, "pair_changed");
			}
			var probe = new Probe(); probe.blocks.put(ORIGIN, paid); probe.unloaded.add(ORIGIN);
			assertBlocked(plan, job, probe, "pair_not_ticking");
		}
	}

	@Test void freeContinuationStillChecksTargetSpaceEntitiesAndLoading() {
		for (boolean door : List.of(true, false)) {
			var plan = pair(door); var job = freeJob(plan); var probe = new Probe();
			probe.blocks.put(ORIGIN, plan.instructions().getFirst().state());
			var target = ORIGIN.offset(plan.instructions().getLast().offset());
			probe.blocks.put(target, plan.instructions().getLast().state());
			assertBlocked(plan, job, probe, "target_occupied"); probe.blocks.remove(target);
			probe.entity = true; assertBlocked(plan, job, probe, "entity_obstructed"); probe.entity = false;
			probe.unloaded.add(target); assertBlocked(plan, job, probe, "target_not_ticking");
		}
	}

	@Test void finalFreeBedStillRequiresCompletionBedLoading() {
		var plan = pair(false); var probe = new Probe();
		probe.blocks.put(ORIGIN, plan.instructions().getFirst().state()); probe.completionUnloaded = true;
		assertBlocked(plan, freeJob(plan), probe, "completion_beds_unloaded");
	}

	@Test void freeContinuationDoesNotAdoptLegacyOrChangedJobs() {
		for (boolean door : List.of(true, false)) {
			var plan = pair(door); var probe = new Probe();
			probe.blocks.put(ORIGIN, plan.instructions().getFirst().state());
			assertBlocked(plan, new ConstructionJob(plan.id(), ORIGIN, 1), probe, "plan_unverified");
			assertBlocked(plan, new ConstructionJob(plan.id(), ORIGIN, 1, "changed"), probe, "plan_changed");
		}
	}

	@Test void paidHalvesStillRequireStockpileAndFreePlacementPreservesFailure() {
		for (boolean door : List.of(true, false)) {
			var plan = pair(door); var job = ConstructionJob.start(plan, ORIGIN); var probe = new Probe();
			assertEquals("stockpile_unavailable", ConstructionReadiness.inspect(job, plan, probe).blocker());
			assertEquals(1, probe.stockpileReads);
			var writes = new AtomicInteger(); var dirty = new AtomicInteger();
			assertFalse(place(plan, job, probe, null, null, writes, dirty)); assertEquals(0, writes.get());
			assertFalse(ConstructionMaterials.place(null, null, plan.instructions().getLast().item(), false,
				freeJob(plan), UUID.randomUUID(), dirty::incrementAndGet, () -> false));
			assertEquals(0, dirty.get());
		}
	}

	private static void assertBlocked(ConstructionPlan plan, ConstructionJob job, Probe probe, String blocker) {
		var saved = job.save(); var writes = new AtomicInteger(); var dirty = new AtomicInteger();
		assertEquals(blocker, ConstructionReadiness.inspect(job, plan, probe).blocker());
		assertFalse(place(plan, job, probe, null, null, writes, dirty));
		assertEquals(0, writes.get()); assertEquals(0, dirty.get()); assertEquals(0, probe.stockpileReads);
		assertEquals(saved, job.save());
	}
}
