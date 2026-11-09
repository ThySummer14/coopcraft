package com.thysummer.coopcraft.civilization;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import static org.junit.jupiter.api.Assertions.*;

@Isolated("BlueprintManager has a static reload snapshot")
class ConstructionCompatibilityTest {
	private static final ResourceLocation ID = ResourceLocation.parse("coopcraft:version_fixture");
	private static final BlockPos ORIGIN = new BlockPos(6, 64, 0);

	@BeforeAll static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test void changedCompletedPrefixAndUnbuiltSuffixCannotResumeEvenWithAnUnchangedTarget() throws Exception {
		var original = plan(Blocks.COBBLESTONE, Blocks.COBBLESTONE);
		for (var changed : List.of(plan(Blocks.STONE, Blocks.COBBLESTONE), plan(Blocks.COBBLESTONE, Blocks.STONE))) {
			var job = new ConstructionJob(ID, ORIGIN, 1, original.fingerprint());
			var fixture = fixture(job);
			var target = new ConstructionSystem.BuildTarget(job, original.instructions().get(1), ORIGIN.east());
			withPlan(changed, () -> assertStopped(fixture, target, "plan_changed"));
			// Restoring the exact original compiled semantics reopens the guard; no migration or rewinding.
			assertEquals("ready", job.compatibilityBlocker(original));
			assertEquals(1, fixture.data().getConstructionJob(fixture.settlement()).orElseThrow().nextIndex());
		}
	}

	@Test void repeatedSaveReloadCannotBindAnOldIndexToChangedPlan() throws Exception {
		var original = plan(Blocks.COBBLESTONE, Blocks.COBBLESTONE);
		var changed = plan(Blocks.STONE, Blocks.COBBLESTONE);
		var job = new ConstructionJob(ID, ORIGIN, 1, original.fingerprint());
		var fixture = fixture(job);
		var saved = fixture.data().save(new CompoundTag(), null);
		withPlan(changed, () -> {
			for (int reload = 0; reload < 3; reload++) {
				var loaded = SettlementSavedData.load(saved, null);
				var settlement = loaded.find(BlockPos.ZERO).orElseThrow();
				var restored = loaded.getConstructionJob(settlement).orElseThrow();
				assertEquals(job, restored);
				assertEquals(saved, loaded.save(new CompoundTag(), null));
				assertStopped(new Fixture(loaded, settlement),
					new ConstructionSystem.BuildTarget(restored, original.instructions().get(1), ORIGIN.east()), "plan_changed");
			}
		});
	}

	@Test void legacyAndUnknownVersionFingerprintsFailClosedBeforeReadingTheWorld() throws Exception {
		var plan = plan(Blocks.COBBLESTONE, Blocks.COBBLESTONE);
		withPlan(plan, () -> {
			for (String fingerprint : List.of("", "v2-unknown", "corrupt-value")) {
				var job = new ConstructionJob(ID, ORIGIN, 1, fingerprint);
				var f = fixture(job);
				assertStopped(f, new ConstructionSystem.BuildTarget(job, plan.instructions().get(1), ORIGIN.east()),
					fingerprint.isEmpty() ? "plan_unverified" : "plan_changed");
				assertEquals(job, ConstructionJob.load(job.save()));
			}
		});
	}

	@Test void startAdvanceAndFullNbtRoundTripPreservePinnedPlan() {
		var plan = plan(Blocks.COBBLESTONE, Blocks.COBBLESTONE);
		var mutable = new BlockPos.MutableBlockPos(6, 64, 0);
		var job = ConstructionJob.start(plan, mutable);
		mutable.set(99, 99, 99);
		assertEquals(ORIGIN, job.origin());
		assertEquals(plan.fingerprint(), job.planFingerprint());
		assertEquals(0, job.nextIndex());
		var f = fixture(job);
		var simulationBefore = f.data().simulationOf(f.settlement()).orElseThrow().save();
		f.data().advanceConstruction(f.settlement());
		var advanced = f.data().getConstructionJob(f.settlement()).orElseThrow();
		assertEquals(1, advanced.nextIndex());
		assertEquals(job.planFingerprint(), advanced.planFingerprint());
		var saved = f.data().save(new CompoundTag(), null);
		assertEquals(20, saved.getInt("DataVersion"));
		var loaded = SettlementSavedData.load(saved, null);
		assertEquals(advanced, loaded.getConstructionJob(f.settlement()).orElseThrow());
		assertEquals(saved, loaded.save(new CompoundTag(), null));
		assertEquals(simulationBefore, loaded.simulationOf(f.settlement()).orElseThrow().save());
	}

	@Test void legacyNbtRoundTripPreservesAbsenceInsteadOfGuessingCurrentPlan() {
		var job = new ConstructionJob(ID, ORIGIN, 1);
		var saved = job.save();
		assertFalse(saved.contains("PlanFingerprint"));
		var loaded = ConstructionJob.load(saved);
		assertEquals(job, loaded);
		assertEquals(saved, loaded.save());
		assertTrue(loaded.advance().planFingerprint().isEmpty());
		assertEquals("plan_unverified", loaded.compatibilityBlocker(plan(Blocks.COBBLESTONE, Blocks.COBBLESTONE)));
	}

	@Test void missingAndOutOfRangePlansStillRetainTheirOriginalDiagnostics() {
		var plan = plan(Blocks.COBBLESTONE, Blocks.COBBLESTONE);
		var job = ConstructionJob.start(plan, ORIGIN);
		assertEquals("plan_unavailable", job.compatibilityBlocker(null));
		assertEquals("invalid_index", new ConstructionJob(ID, ORIGIN, 3, plan.fingerprint()).compatibilityBlocker(plan));
		assertEquals("invalid_index", new ConstructionJob(ID, ORIGIN, 3).compatibilityBlocker(plan));
	}

	@Test void materialProductionRechecksPinnedPlanAfterReloadAndDoesNotFallBackToNewProjects() throws Exception {
		var original = new ConstructionPlan(ID, List.of(
			new ConstructionInstruction(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), Items.STONE, true),
			new ConstructionInstruction(BlockPos.ZERO.east(), Blocks.OAK_PLANKS.defaultBlockState(), Items.OAK_PLANKS, true)));
		var changed = new ConstructionPlan(ID, List.of(
			new ConstructionInstruction(BlockPos.ZERO, Blocks.COBBLESTONE.defaultBlockState(), Items.COBBLESTONE, true),
			original.instructions().get(1)));
		var f = fixture(new ConstructionJob(ID, ORIGIN, 1, original.fingerprint()));
		var stockpile = new SimpleContainer(3);
		stockpile.setItem(0, new ItemStack(Items.OAK_LOG, 10));
		var saved = f.data().save(new CompoundTag(), null);
		withPlan(original, () -> {
			var demands = ConstructionPlanner.materialDemands(f.data(), f.settlement());
			assertEquals(List.of(Map.of(Items.OAK_PLANKS, 1)), demands);
			assertEquals(SettlementMaterialCrafting.Recipe.PLANKS,
				SettlementMaterialCrafting.chooseRecipe(stockpile, null, demands.getFirst()).orElseThrow());
		});
		var futureHearth = new ConstructionPlan(ConstructionPlanner.CAMPFIRE_HEARTH, List.of(
			new ConstructionInstruction(BlockPos.ZERO, Blocks.CAMPFIRE.defaultBlockState(), Items.CAMPFIRE, true)));
		assertFalse(ConstructionPlanner.projects(f.data(), f.settlement()).isEmpty());
		withPlans(Map.of(changed.id(), changed, futureHearth.id(), futureHearth), () -> {
			assertTrue(ConstructionPlanner.materialDemands(f.data(), f.settlement()).isEmpty());
			var loaded = SettlementSavedData.load(saved, null);
			assertTrue(ConstructionPlanner.materialDemands(loaded, f.settlement()).isEmpty());
			assertEquals(saved, loaded.save(new CompoundTag(), null));
		});
		withPlan(original, () -> assertEquals(List.of(Map.of(Items.OAK_PLANKS, 1)),
			ConstructionPlanner.materialDemands(f.data(), f.settlement())));
		assertEquals(10, stockpile.getItem(0).getCount());
		assertEquals(Items.OAK_LOG, stockpile.getItem(0).getItem());
		assertEquals(saved, f.data().save(new CompoundTag(), null));
	}

	@Test void futureProjectDemandRemainsAvailableWhenThereIsNoSavedJob() throws Exception {
		var plan = new ConstructionPlan(ConstructionPlanner.CAMPFIRE_HEARTH, List.of(
			new ConstructionInstruction(BlockPos.ZERO, Blocks.CAMPFIRE.defaultBlockState(), Items.CAMPFIRE, true)));
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "New camp", 0);
		withPlan(plan, () -> assertEquals(List.of(Map.of(Items.CAMPFIRE, 1)),
			ConstructionPlanner.materialDemands(data, settlement)));
	}

	private static void assertStopped(Fixture f, ConstructionSystem.BuildTarget target, String reason) {
		var saved = f.data().save(new CompoundTag(), null);
		var simulation = f.data().simulationOf(f.settlement()).orElseThrow();
		var simulationBefore = simulation.save();
		f.data().setDirty(false);
		for (int i = 0; i < 3; i++) {
			assertTrue(ConstructionSystem.nextBuildTarget(null, f.data(), f.settlement()).isEmpty());
			assertTrue(ConstructionPlanner.materialDemands(f.data(), f.settlement()).isEmpty());
			var readiness = ConstructionSystem.inspect(null, f.data(), f.settlement()).orElseThrow();
			assertEquals(reason, readiness.blocker());
			assertNull(readiness.position());
			assertFalse(ConstructionSystem.buildNext(null, f.data(), f.settlement(), target, null));
			assertFalse(f.data().isDirty());
			assertEquals(saved, f.data().save(new CompoundTag(), null));
			assertEquals(simulationBefore, simulation.save());
			simulation.world().assertConservation();
		}
	}

	static ConstructionPlan plan(net.minecraft.world.level.block.Block first, net.minecraft.world.level.block.Block last) {
		return new ConstructionPlan(ID, List.of(
			new ConstructionInstruction(BlockPos.ZERO, first.defaultBlockState(), first.asItem(), true),
			new ConstructionInstruction(BlockPos.ZERO.east(), Blocks.COBBLESTONE.defaultBlockState(), Items.COBBLESTONE, true),
			new ConstructionInstruction(BlockPos.ZERO.east(2), last.defaultBlockState(), last.asItem(), true)));
	}

	private static Fixture fixture(ConstructionJob job) {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "Version protection", 0);
		data.setConstructionJob(settlement, job);
		var simulation = data.getOrCreateSimulation(settlement);
		simulation.world().importExternalBatch(ResourceType.STONE, 8, simulation.campOwner());
		return new Fixture(data, settlement);
	}

	private static void withPlan(ConstructionPlan plan, Runnable action) throws Exception {
		withPlans(Map.of(plan.id(), plan), action);
	}

	private static void withPlans(Map<ResourceLocation, ConstructionPlan> plans, Runnable action) throws Exception {
		Field field = BlueprintManager.class.getDeclaredField("plans");
		field.setAccessible(true);
		Object previous = field.get(null);
		try { field.set(null, plans); action.run(); }
		finally { field.set(null, previous); }
	}

	private record Fixture(SettlementSavedData data, Settlement settlement) { }
}
