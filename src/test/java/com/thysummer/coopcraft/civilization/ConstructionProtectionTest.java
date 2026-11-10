package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
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

/** Bounded data/probe tests only; no ServerLevel, game, navigation or GameTest execution. */
class ConstructionProtectionTest {
	private static final UUID OWNER = new UUID(91, 1);
	private static final BlockPos ORIGIN = new BlockPos(8, 64, 8);
	private static ConstructionPlan cottage, cabin, workshop, hearth;

	@BeforeAll static void bootstrap() throws Exception {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		cottage = resource("stone_cottage"); cabin = resource("timber_porch_cabin_v1");
		workshop = resource("starter_workshop_v1"); hearth = resource("campfire_hearth");
	}

	private static ConstructionPlan resource(String name) throws Exception {
		try (var reader = new InputStreamReader(ConstructionProtectionTest.class.getResourceAsStream(
			"/data/coopcraft/civilization/blueprints/" + name + ".json"), StandardCharsets.UTF_8)) {
			return ConstructionPlan.fromJson(ResourceLocation.parse("coopcraft:" + name), JsonParser.parseReader(reader).getAsJsonObject());
		}
	}

	private static UUID protect(SettlementSavedData data, int minX, int minZ, int maxX, int maxZ) {
		var added = data.addProtectedArea(OWNER, "Player build", minX, minZ, maxX, maxZ);
		assertEquals(PlayerProtectedAreas.Status.ADDED, added.status());
		return added.area().id();
	}

	private static ConstructionPlan sparsePlan() {
		return new ConstructionPlan(ResourceLocation.parse("coopcraft:sparse_protected_plan"), List.of(
			new ConstructionInstruction(new BlockPos(-2, 0, -2), Blocks.STONE.defaultBlockState(), Items.STONE, true),
			new ConstructionInstruction(new BlockPos(4, 3, 4), Blocks.STONE.defaultBlockState(), Items.STONE, true)));
	}

	private static ConstructionPlan pair(boolean door) {
		BlockState paid = door ? Blocks.OAK_DOOR.defaultBlockState()
			: Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
		BlockState free = door ? paid.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)
			: paid.setValue(BedBlock.PART, BedPart.HEAD);
		return new ConstructionPlan(ResourceLocation.parse("coopcraft:protected_pair"), List.of(
			new ConstructionInstruction(BlockPos.ZERO, paid, paid.getBlock().asItem(), true),
			new ConstructionInstruction(door ? BlockPos.ZERO.above() : BlockPos.ZERO.east(), free, free.getBlock().asItem(), false)));
	}

	private static final class World implements ConstructionReadiness.Probe {
		final SettlementSavedData data;
		final Map<BlockPos, BlockState> blocks = new HashMap<>();
		int materialReads, worldReads;
		Runnable onStateRead;
		boolean ticking = true, entity;
		World(SettlementSavedData data) { this.data = data; }
		public boolean modificationBlocked(int minX, int minZ, int maxX, int maxZ) {
			return data.isResidentModificationBlocked(minX, minZ, maxX, maxZ);
		}
		public boolean inBounds(BlockPos at) { worldReads++; return at.getY() >= -64 && at.getY() < 320; }
		public boolean ticking(BlockPos at) { worldReads++; return ticking; }
		public BlockState state(BlockPos at) {
			worldReads++;
			if (onStateRead != null) { Runnable callback = onStateRead; onStateRead = null; callback.run(); }
			return blocks.getOrDefault(at, at.getY() == 63 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
		}
		public boolean entityObstructed(BlockPos at) { worldReads++; return entity; }
		public SettlementHousing.BedCheck inspectBed(BlockPos at) {
			worldReads++;
			return SettlementHousing.inspectBed(new SettlementHousing.BedAccess() {
				public boolean isLoaded(BlockPos cell) { return true; }
				public BlockState stateAt(BlockPos cell) { return state(cell); }
			}, at);
		}
		public Container stockpile() { materialReads++; return null; }
		public SettlementSimulationState simulation() { fail("Protection check must not request material simulation"); return null; }
	}

	@Test void oldWorldsHaveNoImplicitProtectionAndClearResultsGrantNoNewWorldPermission() {
		CompoundTag legacy = new CompoundTag(); legacy.putInt("DataVersion", 20);
		var data = SettlementSavedData.load(legacy, null); var world = new World(data);
		assertFalse(ConstructionProtection.isBlocked(hearth, ORIGIN, data::isResidentModificationBlocked));
		assertTrue(ConstructionSystem.canPlace(data, hearth, ORIGIN, world));
		for (ConstructionPlan plan : List.of(cottage, cabin, workshop)) assertTrue(ConstructionSite.inspect(plan, ORIGIN, world).ready());
		var plan = sparsePlan(); var job = ConstructionJob.start(plan, ORIGIN);
		assertSame(job, ConstructionSystem.returnSupplyJob(data, job, plan));
		assertEquals("stockpile_unavailable", ConstructionReadiness.inspect(job, plan, world).blocker());
		world.entity = true;
		assertFalse(ConstructionSystem.placeIfReady(job, plan, world, () -> fail("Protection is not permission")));
		world.ticking = false;
		assertFalse(ConstructionSystem.canPlace(data, hearth, ORIGIN, world));
	}

	@Test void footprintIncludesBoundaryInteriorBuiltPrefixAndFutureStepsAcrossAllHeights() {
		var plan = sparsePlan();
		for (BlockPos protectedCell : List.of(ORIGIN.offset(-2, -100, -2), ORIGIN.offset(4, 200, 4), ORIGIN)) {
			var data = new SettlementSavedData();
			protect(data, protectedCell.getX(), protectedCell.getZ(), protectedCell.getX(), protectedCell.getZ());
			var world = new World(data);
			for (int index : List.of(0, 1)) {
				var job = new ConstructionJob(plan.id(), ORIGIN, index, plan.fingerprint());
				var saved = job.save();
				assertEquals("protected_area", ConstructionReadiness.inspect(job, plan, world).blocker());
				assertFalse(ConstructionSystem.placeIfReady(job, plan, world, () -> fail("No material transaction")));
				assertNull(ConstructionSystem.returnSupplyJob(data, job, plan));
				assertEquals(saved, job.save());
			}
			assertEquals(0, world.materialReads);
			assertTrue(ConstructionProtection.isBlocked(plan, ORIGIN.above(100), data::isResidentModificationBlocked));
		}
		var outside = new SettlementSavedData(); protect(outside, 13, 6, 13, 12);
		assertFalse(ConstructionProtection.isBlocked(plan, ORIGIN, outside::isResidentModificationBlocked));
		assertFalse(ConstructionProtection.isBlocked(plan, ORIGIN, new SettlementSavedData()::isResidentModificationBlocked));
	}

	@Test void pausedJobSurvivesSaveReloadAndResumesAtItsExactOriginalIdentityAndProgress() {
		var plan = sparsePlan(); var data = new SettlementSavedData();
		var camp = data.getOrCreate(ORIGIN, "Saved project", 0);
		var job = new ConstructionJob(plan.id(), ORIGIN, 1, plan.fingerprint()); data.setConstructionJob(camp, job);
		UUID area = protect(data, 6, 6, 6, 6);
		var restored = SettlementSavedData.load(data.save(new CompoundTag(), null), null);
		var current = restored.getConstructionJob(camp).orElseThrow();
		assertEquals(job, current);
		var before = restored.save(new CompoundTag(), null); restored.setDirty(false);
		assertFalse(ConstructionSystem.placeIfReady(current, plan, new World(restored), () -> fail("Paused")));
		assertNull(ConstructionSystem.returnSupplyJob(restored, current, plan));
		assertEquals(before, restored.save(new CompoundTag(), null)); assertFalse(restored.isDirty());
		assertEquals(PlayerProtectedAreas.Status.REMOVED, restored.removeProtectedArea(area, OWNER, false));
		assertTrue(ConstructionStepReadiness.inspect(current, plan, new World(restored), true).ready());
		assertSame(current, ConstructionSystem.returnSupplyJob(restored, current, plan));
		assertEquals(job, restored.getConstructionJob(camp).orElseThrow());
	}

	@Test void protectedPaidOrFreeDoorAndBedHalfNeverReachesMaterialOperations() {
		for (boolean door : List.of(true, false)) {
			var plan = pair(door); var data = new SettlementSavedData();
			// Bed's head alone blocks its paid foot. A door's full-height column covers both halves.
			int x = ORIGIN.getX() + (door ? 0 : 1); protect(data, x, ORIGIN.getZ(), x, ORIGIN.getZ());
			var world = new World(data);
			for (int index : List.of(0, 1)) {
				var job = new ConstructionJob(plan.id(), ORIGIN, index, plan.fingerprint());
				if (index == 1) world.blocks.put(ORIGIN, plan.instructions().getFirst().state());
				assertEquals("protected_area", ConstructionReadiness.inspect(job, plan, world).blocker());
				assertFalse(ConstructionSystem.placeIfReady(job, plan, world, () -> fail("Paid and free halves are vetoed")));
				assertEquals(0, world.materialReads);
			}
		}
	}

	@Test void housingWorkshopAndIndependentHearthSelectionAvoidProtectedSites() {
		for (ConstructionPlan plan : List.of(cottage, cabin, workshop, hearth)) {
			var data = new SettlementSavedData(); var camp = data.getOrCreate(new BlockPos(0, 64, 0), "Candidate", 0);
			BlockPos first = ConstructionSite.candidates(plan, camp.center()).getFirst();
			protect(data, first.getX(), first.getZ(), first.getX(), first.getZ());
			var world = new World(data);
			assertEquals("protected_area", ConstructionSite.inspect(plan, first, world).reason());
			if (plan != hearth) {
				BlockPos chosen = ConstructionSystem.chooseGuardedOrigin(data, camp, plan, 0, world);
				assertNotNull(chosen); assertNotEquals(first, chosen);
				assertFalse(ConstructionProtection.isBlocked(plan, chosen, data::isResidentModificationBlocked));
				assertEquals("protected_area", ConstructionSystem.inspectSites(data, camp, plan, world).getFirst().reason());
			} else {
				assertFalse(ConstructionSystem.canPlace(data, plan, first, world));
				assertTrue(ConstructionSystem.canPlace(data, plan, first.offset(30, 0, 30), world));
			}
		}
	}

	@Test void protectionPrecedesUnloadedOccupiedEntityAndBedWorldProbes() {
		for (ConstructionPlan plan : List.of(cottage, cabin, workshop, hearth, pair(true), pair(false))) {
			var data = new SettlementSavedData(); protect(data, 8, 8, 8, 8);
			var world = new World(data); world.ticking = false; world.entity = true;
			world.blocks.put(ORIGIN, Blocks.STONE.defaultBlockState());
			var job = ConstructionJob.start(plan, ORIGIN);
			assertEquals("protected_area", ConstructionReadiness.inspect(job, plan, world).blocker());
			assertFalse(ConstructionSystem.placeIfReady(job, plan, world, () -> fail("No transaction")));
			if (plan == cottage || plan == cabin || plan == workshop || plan == hearth) {
				assertEquals("protected_area", ConstructionSite.inspect(plan, ORIGIN, world).reason());
			}
			assertEquals(0, world.worldReads); assertEquals(0, world.materialReads);
		}
	}

	@Test void siteAndStepRecheckProtectionAfterControlledWorldCallbacks() {
		for (ConstructionPlan plan : List.of(cottage, cabin, workshop, hearth)) {
			var siteData = new SettlementSavedData(); var siteWorld = new World(siteData);
			siteWorld.onStateRead = () -> protect(siteData, 8, 8, 8, 8);
			assertEquals("protected_area", ConstructionSite.inspect(plan, ORIGIN, siteWorld).reason());
			var stepData = new SettlementSavedData(); var stepWorld = new World(stepData);
			stepWorld.onStateRead = () -> protect(stepData, 8, 8, 8, 8);
			assertEquals("protected_area", ConstructionReadiness.inspect(ConstructionJob.start(plan, ORIGIN), plan, stepWorld).blocker());
			assertEquals(0, stepWorld.materialReads);
		}
	}

	@Test void liveProbeProtectionCannotBeWeakenedByAnUnprotectedBusinessSnapshot() {
		var snapshot = new SettlementSavedData(); var current = new SettlementSavedData();
		protect(current, 8, 8, 8, 8); var live = new World(current);
		assertFalse(ConstructionSystem.canPlace(snapshot, hearth, ORIGIN, live));
		assertEquals(0, live.worldReads);
		var camp = snapshot.getOrCreate(new BlockPos(0, 64, 0), "Snapshot", 0);
		BlockPos first = ConstructionSite.candidates(workshop, camp.center()).getFirst();
		protect(current, first.getX(), first.getZ(), first.getX(), first.getZ());
		assertNotEquals(first, ConstructionSystem.chooseGuardedOrigin(snapshot, camp, workshop, 0, live));
		assertEquals("protected_area", ConstructionSystem.inspectSites(snapshot, camp, workshop, live).getFirst().reason());
	}

	@Test void projectSelectionRechecksTheCandidateAndNeverReplacesAnExistingPausedJob() throws Exception {
		var plansField = BlueprintManager.class.getDeclaredField("plans"); plansField.setAccessible(true);
		Object previous = plansField.get(null);
		try {
			plansField.set(null, Map.of(hearth.id(), hearth));
			var data = new SettlementSavedData(); var camp = data.getOrCreate(ORIGIN, "Selection", 0);
			var store = new SimpleContainer(16);
			hearth.materials().forEach((item, amount) -> store.addItem(new ItemStack(item, amount)));
			protect(data, 8, 8, 8, 8);
			assertNull(ConstructionSystem.chooseProject(data, camp, store, plan -> ORIGIN));
			var existing = ConstructionJob.start(hearth, ORIGIN); data.setConstructionJob(camp, existing);
			assertSame(existing, ConstructionSystem.chooseProject(data, camp, store, plan -> fail("No relocation")));
		} finally { plansField.set(null, previous); }
	}

	@Test void malformedProtectionFailsClosedWhileUnavailableOrChangedPlansKeepTheirOriginalBlocker() {
		var raw = new CompoundTag(); raw.put("PlayerProtectedAreas", StringTag.valueOf("broken"));
		var data = SettlementSavedData.load(raw, null); var world = new World(data);
		var plan = sparsePlan(); var job = ConstructionJob.start(plan, ORIGIN);
		assertEquals("protected_area", ConstructionReadiness.inspect(job, plan, world).blocker());
		assertFalse(ConstructionSystem.canPlace(data, hearth, ORIGIN, world));
		assertNull(ConstructionSystem.returnSupplyJob(data, job, plan));
		assertEquals("plan_unavailable", ConstructionReadiness.inspect(job, null, world).blocker());
		var changed = new ConstructionPlan(plan.id(), List.of(plan.instructions().getFirst()));
		assertEquals("plan_changed", ConstructionReadiness.inspect(job, changed, world).blocker());
		assertEquals("plan_unverified", ConstructionReadiness.inspect(new ConstructionJob(plan.id(), ORIGIN, 0), plan, world).blocker());
		assertEquals(0, world.materialReads);
	}

	@Test void unknownEmptyOrOverflowedFootprintNeverBecomesAnUnprotectedGrant() {
		ConstructionProtection.Probe clear = (minX, minZ, maxX, maxZ) -> false;
		assertTrue(ConstructionProtection.isBlocked(null, ORIGIN, clear));
		assertTrue(ConstructionProtection.isBlocked(new ConstructionPlan(hearth.id(), List.of()), ORIGIN, clear));
		assertTrue(ConstructionProtection.isBlocked(sparsePlan(), new BlockPos(Integer.MAX_VALUE, 0, 0), clear));
		assertTrue(ConstructionProtection.isBlocked(sparsePlan(), new BlockPos(Integer.MIN_VALUE, 0, 0), clear));
	}
}
