package com.thysummer.coopcraft.civilization;

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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Controlled world access around real readiness, material and housing services, not a ServerLevel test. */
class ConstructionCompletionReadinessTest {
	private static final BlockPos ORIGIN = new BlockPos(15, 64, 0);

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	private static class World implements ConstructionReadiness.Probe, SettlementHousing.BedAccess {
		final Map<BlockPos, BlockState> blocks = new HashMap<>();
		final Set<BlockPos> unknown = new HashSet<>(), reads = new HashSet<>(), outside = new HashSet<>();
		final SimpleContainer inventory = new SimpleContainer(4);
		int bedChecks, inventoryReads;
		public boolean inBounds(BlockPos position) { return !outside.contains(position); }
		public boolean ticking(BlockPos position) { return isLoaded(position); }
		public boolean isLoaded(BlockPos position) { return !unknown.contains(position); }
		public BlockState state(BlockPos position) { return stateAt(position); }
		public BlockState stateAt(BlockPos position) {
			assertTrue(isLoaded(position), "Never access unknown bed state");
			reads.add(position);
			return blocks.getOrDefault(position, Blocks.AIR.defaultBlockState());
		}
		public boolean entityObstructed(BlockPos position) { return false; }
		public SettlementHousing.BedCheck inspectBed(BlockPos position) {
			bedChecks++;
			return SettlementHousing.inspectBed(this, position);
		}
		public Container stockpile() { inventoryReads++; return inventory; }
		public SettlementSimulationState simulation() { return null; }
	}

	private static ConstructionInstruction bed(BlockPos offset, Direction facing, boolean foot) {
		var state = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, facing)
			.setValue(BedBlock.PART, foot ? BedPart.FOOT : BedPart.HEAD);
		return new ConstructionInstruction(offset, state, Items.RED_BED, foot);
	}

	private static ConstructionPlan plan(boolean finalHead) {
		var instructions = new java.util.ArrayList<>(List.of(
			bed(BlockPos.ZERO, Direction.EAST, true), bed(new BlockPos(1, 0, 0), Direction.EAST, false),
			bed(new BlockPos(-4, 0, 0), Direction.NORTH, true), bed(new BlockPos(-4, 0, -1), Direction.NORTH, false)));
		if (!finalHead) instructions.add(new ConstructionInstruction(new BlockPos(-6, 0, 0),
			Blocks.STONE.defaultBlockState(), Items.STONE, true));
		return new ConstructionPlan(ResourceLocation.parse("coopcraft:completion_test"), instructions);
	}

	private static ConstructionJob job(ConstructionPlan plan, int index) {
		return new ConstructionJob(plan.id(), ORIGIN, index, plan.fingerprint());
	}

	private static World prefix(ConstructionPlan plan) {
		var world = new World();
		for (int index = 0; index < plan.instructions().size() - 1; index++) {
			var part = plan.instructions().get(index);
			world.blocks.put(ORIGIN.offset(part.offset()), part.state());
		}
		world.inventory.setItem(0, new ItemStack(Items.STONE));
		// Beds stack to one; no impossible two-bed inventory fixtures.
		world.inventory.setItem(1, new ItemStack(Items.RED_BED));
		world.inventory.setItem(2, new ItemStack(Items.RED_BED));
		return world;
	}

	@Test void earlierUnknownHalfDefersKnownFinalTargetWithoutAnyMutationAndReloadResumes() {
		for (boolean finalHead : List.of(false, true)) for (BlockPos unknown : List.of(ORIGIN, ORIGIN.east())) {
			var plan = plan(finalHead); var job = job(plan, plan.instructions().size() - 1);
			var world = prefix(plan);
			var data = new SettlementSavedData(); var settlement = data.getOrCreate(ORIGIN, "Completion", 0);
			data.setConstructionJob(settlement, job); data.setDirty(false);
			var saved = data.save(new CompoundTag(), null);
			var beforeBlocks = Map.copyOf(world.blocks);
			var calls = new AtomicInteger();
			assertTrue(ConstructionStepReadiness.inspect(job, plan, world, false).ready());
			world.unknown.add(unknown); world.reads.clear();
			for (int repeat = 0; repeat < 3; repeat++) {
				assertEquals("completion_beds_unloaded", ConstructionStepReadiness.inspect(job, plan, world, false).blocker());
				assertEquals("completion_beds_unloaded", ConstructionReadiness.inspect(job, plan, world).blocker());
				assertFalse(ConstructionSystem.placeIfReady(job, plan, world, () -> {
					calls.incrementAndGet(); data.setDirty(); world.inventory.removeItem(0, 1); return true;
				}));
			}
			assertEquals(0, calls.get()); assertEquals(0, world.inventoryReads);
			assertFalse(world.reads.contains(unknown)); assertEquals(beforeBlocks, world.blocks);
			assertEquals(1, world.inventory.getItem(0).getCount());
			assertEquals(1, world.inventory.getItem(1).getCount());
			assertEquals(1, world.inventory.getItem(2).getCount());
			assertEquals(saved, data.save(new CompoundTag(), null)); assertFalse(data.isDirty());

			var restored = SettlementSavedData.load(saved, null);
			var resumed = restored.getConstructionJob(settlement).orElseThrow();
			assertEquals(job, resumed); // Origin, index and plan identity survive unchanged.
			world.unknown.clear();
			var last = plan.instructions().getLast();
			assertTrue(ConstructionSystem.placeIfReady(resumed, plan, world, () -> ConstructionMaterials.place(
				world.inventory, null, last.item(), last.consume(), resumed, UUID.randomUUID(), restored::setDirty,
				() -> { world.blocks.put(ORIGIN.offset(last.offset()), last.state()); return true; })));
			restored.completeConstruction(settlement);
			registerPlannedBeds(plan, world, restored, settlement);
			assertEquals(2, restored.housingCapacity(settlement));
			assertTrue(restored.getConstructionJob(settlement).isEmpty());
			registerPlannedBeds(plan, world, restored, settlement);
			SettlementHousing.reconcile(world, restored, settlement);
			assertEquals(2, restored.housingCapacity(settlement));
			assertEquals(finalHead ? 1 : 0, world.inventory.countItem(Items.STONE));
			assertEquals(2, world.inventory.countItem(Items.RED_BED));
		}
	}

	private static void registerPlannedBeds(ConstructionPlan plan, World world,
		SettlementSavedData data, Settlement settlement) {
		for (var part : plan.instructions()) {
			if (part.consume() && part.state().getBlock() instanceof BedBlock) {
				SettlementHousing.registerBed(world, data, settlement, ORIGIN.offset(part.offset()));
			}
		}
	}

	@Test void loadedDamagedBedDoesNotFreezeCompletionOrBecomeRegistered() {
		var plan = plan(false); var job = job(plan, plan.instructions().size() - 1); var world = prefix(plan);
		world.blocks.remove(ORIGIN.east());
		assertEquals(SettlementHousing.BedStatus.INVALID, world.inspectBed(ORIGIN).status());
		assertTrue(ConstructionStepReadiness.inspect(job, plan, world, false).ready());
		assertEquals("ready", ConstructionReadiness.inspect(job, plan, world).blocker());
		assertTrue(ConstructionSystem.placeIfReady(job, plan, world, () -> true));
		var data = new SettlementSavedData(); var settlement = data.getOrCreate(ORIGIN, "Damaged", 0);
		registerPlannedBeds(plan, world, data, settlement);
		assertEquals(1, data.housingCapacity(settlement));
		assertFalse(data.hasHousingBed(settlement, ORIGIN));
	}

	@Test void finalFreeHeadCanPassInvalidPrecheckThenRegisterNormally() {
		var plan = plan(true); var job = job(plan, plan.instructions().size() - 1); var world = prefix(plan);
		BlockPos foot = ORIGIN.offset(plan.instructions().get(2).offset());
		assertEquals(SettlementHousing.BedStatus.INVALID, world.inspectBed(foot).status());
		assertEquals("ready", ConstructionReadiness.inspect(job, plan, world).blocker());
		var last = plan.instructions().getLast();
		assertTrue(ConstructionSystem.placeIfReady(job, plan, world, () -> {
			world.blocks.put(ORIGIN.offset(last.offset()), last.state()); return true;
		}));
		var data = new SettlementSavedData(); var settlement = data.getOrCreate(ORIGIN, "Head", 0);
		registerPlannedBeds(plan, world, data, settlement);
		assertEquals(2, data.housingCapacity(settlement));
	}

	@Test void ownershipConflictDoesNotBlockCompletionOrStealRegisteredBeds() {
		var plan = plan(false); var job = job(plan, plan.instructions().size() - 1); var world = prefix(plan);
		var data = new SettlementSavedData(); var settlement = data.getOrCreate(ORIGIN, "Builder", 0);
		var other = data.getOrCreate(ORIGIN.offset(1000, 0, 0), "Owner", 0);
		assertEquals(SettlementHousing.RegistrationResult.ADDED, SettlementHousing.registerBed(world, data, other, ORIGIN));
		assertTrue(ConstructionStepReadiness.inspect(job, plan, world, false).ready());
		registerPlannedBeds(plan, world, data, settlement);
		assertEquals(1, data.housingCapacity(settlement)); assertEquals(1, data.housingCapacity(other));
		assertFalse(data.hasHousingBed(settlement, ORIGIN));
	}

	@Test void intermediateAndNonHousingStepsNeverInspectBeds() {
		var plan = plan(false); var world = new World(); world.inventory.setItem(0, new ItemStack(Items.RED_BED));
		world.unknown.add(ORIGIN.offset(-4, 0, 0));
		assertTrue(ConstructionStepReadiness.inspect(job(plan, 0), plan, world, false).ready());
		assertEquals("ready", ConstructionReadiness.inspect(job(plan, 0), plan, world).blocker());
		assertTrue(ConstructionSystem.placeIfReady(job(plan, 0), plan, world, () -> true));
		assertEquals(0, world.bedChecks);
		var ordinary = new ConstructionPlan(ResourceLocation.parse("coopcraft:no_beds"), List.of(plan.instructions().getLast()));
		world.inventory.setItem(0, new ItemStack(Items.STONE));
		assertTrue(ConstructionStepReadiness.inspect(job(ordinary, 0), ordinary, world, false).ready());
		assertEquals("ready", ConstructionReadiness.inspect(job(ordinary, 0), ordinary, world).blocker());
		assertTrue(ConstructionSystem.placeIfReady(job(ordinary, 0), ordinary, world, () -> true));
		assertEquals(0, world.bedChecks);
	}

	@Test void completionBedCoordinateOverflowOrBoundsNeverReachesBedInspection() {
		for (int sign : List.of(-1, 1)) {
			var facing = sign > 0 ? Direction.EAST : Direction.WEST;
			var plan = new ConstructionPlan(ResourceLocation.parse("coopcraft:overflow_bed"), List.of(
				bed(new BlockPos(sign, 0, 0), facing, true),
				bed(new BlockPos(sign + facing.getStepX(), 0, 0), facing, false),
				new ConstructionInstruction(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), Items.STONE, true)));
			var job = new ConstructionJob(plan.id(), new BlockPos(sign > 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE, 64, 0),
				2, plan.fingerprint());
			var world = new World();
			assertEquals("completion_beds_out_of_bounds", ConstructionStepReadiness.inspect(job, plan, world, false).blocker());
			assertEquals("completion_beds_out_of_bounds", ConstructionReadiness.inspect(job, plan, world).blocker());
			assertFalse(ConstructionSystem.placeIfReady(job, plan, world, () -> fail("No transaction after overflow")));
			assertEquals(0, world.bedChecks); assertEquals(0, world.inventoryReads);
			assertTrue(world.reads.isEmpty(), "Pure coordinate failure precedes protection and world access");
		}
		var plan = plan(false); var world = prefix(plan);
		world.outside.add(ORIGIN);
		var job = job(plan, plan.instructions().size() - 1);
		assertEquals("completion_beds_out_of_bounds", ConstructionStepReadiness.inspect(job, plan, world, false).blocker());
		assertEquals(0, world.bedChecks);
	}

	@Test void publishedStoneNorthAndEastPlansDeferEarlierUnknownBedAndResumeFromSavedFinalIndex() throws Exception {
		for (String name : List.of("stone_cottage", "timber_porch_cabin_v1", "timber_side_porch_cabin_v1")) {
			try (var input = getClass().getResourceAsStream("/data/coopcraft/civilization/blueprints/" + name + ".json")) {
				assertNotNull(input, name);
				var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(input,
					java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
				var plan = ConstructionPlan.fromJson(ResourceLocation.parse("coopcraft:" + name), json);
				var job = ConstructionJob.load(job(plan, plan.instructions().size() - 1).save());
				var world = prefix(plan);
				var bed = plan.instructions().stream().filter(part -> part.consume()
					&& part.state().getBlock() instanceof BedBlock).findFirst().orElseThrow();
				var foot = ORIGIN.offset(bed.offset());
				var head = foot.relative(bed.state().getValue(BedBlock.FACING));
				assertTrue(ConstructionStepReadiness.inspect(job, plan, world, false).ready(), name);
				world.unknown.add(head);
				assertEquals("completion_beds_unloaded", ConstructionStepReadiness.inspect(job, plan, world, false).blocker(), name);
				assertFalse(ConstructionSystem.placeIfReady(job, plan, world, () -> fail("No transaction while unknown")));
				world.unknown.clear();
				assertTrue(ConstructionStepReadiness.inspect(job, plan, world, false).ready(), name);
				assertEquals(plan.fingerprint(), job.planFingerprint());
			}
		}
	}
}
