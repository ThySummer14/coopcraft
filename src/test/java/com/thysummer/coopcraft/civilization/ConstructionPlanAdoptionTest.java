package com.thysummer.coopcraft.civilization;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import static org.junit.jupiter.api.Assertions.*;

@Isolated("BlueprintManager has a static reload snapshot")
class ConstructionPlanAdoptionTest {
	private static final ResourceLocation ID = ResourceLocation.parse("coopcraft:adoption_fixture");
	private static final BlockPos ORIGIN = new BlockPos(6, 64, 0);

	@BeforeAll static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test void unavailableIneligibleAndStaleRequestsNeverProbeTheWorld() {
		var plan = plan();
		var legacy = new ConstructionJob(ID, ORIGIN, 1);
		assertEquals("no_job", ConstructionPlanAdoption.inspect(null, plan, plan.fingerprint(), null).status());
		assertEquals("not_legacy", ConstructionPlanAdoption.inspect(ConstructionJob.start(plan, ORIGIN), plan, plan.fingerprint(), null).status());
		assertEquals("not_legacy", ConstructionPlanAdoption.inspect(new ConstructionJob(ID, ORIGIN, 1, "v2-unknown"), plan, plan.fingerprint(), null).status());
		assertEquals("plan_unavailable", ConstructionPlanAdoption.inspect(legacy, null, plan.fingerprint(), null).status());
		assertEquals("invalid_index", ConstructionPlanAdoption.inspect(new ConstructionJob(ID, ORIGIN, 3), plan, plan.fingerprint(), null).status());
		assertEquals("fingerprint_mismatch", ConstructionPlanAdoption.inspect(legacy, plan, "v1-" + "0".repeat(64), null).status());
		var differentId = new ConstructionPlan(ResourceLocation.parse("coopcraft:other_adoption_fixture"), plan.instructions());
		assertEquals("fingerprint_mismatch", ConstructionPlanAdoption.inspect(legacy, differentId, differentId.fingerprint(), null).status());
	}

	@Test void unloadedCompletedPrefixNeverReadsUnknownBlockState() {
		var plan = plan();
		var probe = new Probe();
		probe.states.put(ORIGIN, Blocks.COBBLESTONE.defaultBlockState());
		probe.unloaded = ORIGIN.east();
		var result = ConstructionPlanAdoption.inspect(new ConstructionJob(ID, ORIGIN, 2), plan, plan.fingerprint(), probe);
		assertEquals("prefix_unloaded", result.status());
		assertEquals(ORIGIN.east(), result.position());
		assertEquals(List.of(ORIGIN), probe.reads);
		assertEquals(List.of(ORIGIN, ORIGIN.east()), probe.loadedChecks);
	}

	@Test void mismatchingCompletedPrefixReportsPositionAndStopsBeforeNextInstruction() {
		var plan = plan();
		var probe = matchingPrefix();
		probe.states.put(ORIGIN.east(), Blocks.STONE.defaultBlockState());
		var before = Map.copyOf(probe.states);
		var result = ConstructionPlanAdoption.inspect(new ConstructionJob(ID, ORIGIN, 2), plan, plan.fingerprint(), probe);
		assertEquals("prefix_mismatch", result.status());
		assertEquals(ORIGIN.east(), result.position());
		assertEquals(List.of(ORIGIN, ORIGIN.east()), probe.reads);
		assertEquals(before, probe.states);
	}

	@Test void sameBlockWithDifferentStatePropertiesStillRejectsLegacyPrefix() {
		var bottom = Blocks.OAK_SLAB.defaultBlockState();
		var slabPlan = new ConstructionPlan(ID, List.of(
			new ConstructionInstruction(BlockPos.ZERO, bottom, Items.OAK_SLAB, true),
			new ConstructionInstruction(BlockPos.ZERO.east(), bottom, Items.OAK_SLAB, true)));
		var probe = new Probe();
		probe.states.put(ORIGIN, bottom.setValue(net.minecraft.world.level.block.SlabBlock.TYPE,
			net.minecraft.world.level.block.state.properties.SlabType.TOP));
		var result = ConstructionPlanAdoption.inspect(new ConstructionJob(ID, ORIGIN, 1), slabPlan,
			slabPlan.fingerprint(), probe);
		assertEquals("prefix_mismatch", result.status());
		assertEquals(ORIGIN, result.position());
		assertEquals(List.of(ORIGIN), probe.reads);
	}

	@Test void explicitAdoptionChangesOnlyFingerprintThenSurvivesReloadAndAdvance() throws Exception {
		withPlan(plan(), () -> {
			var f = fixture(new ConstructionJob(ID, ORIGIN, 2));
			var oldJob = f.data().getConstructionJob(f.settlement()).orElseThrow();
			var staleTarget = new ConstructionSystem.BuildTarget(oldJob, plan().instructions().get(2), ORIGIN.east(2));
			var before = f.data().save(new CompoundTag(), null);
			var expected = before.copy();
			expected.getList("ConstructionJobs", 10).getCompound(0).putString("PlanFingerprint", plan().fingerprint());
			var probe = matchingPrefix();
			// The next cell deliberately contains a player block. Adoption must not read, remove, or skip it.
			probe.states.put(ORIGIN.east(2), Blocks.DIRT.defaultBlockState());
			var states = Map.copyOf(probe.states);
			f.data().setDirty(false);
			var result = ConstructionSystem.adoptLegacyPlan(f.data(), f.settlement(), plan().fingerprint(), probe);
			assertEquals("adopted", result.status());
			assertTrue(f.data().isDirty());
			assertEquals(expected, f.data().save(new CompoundTag(), null));
			assertEquals(states, probe.states);
			assertEquals(List.of(ORIGIN, ORIGIN.east()), probe.reads);
			var pinned = f.data().getConstructionJob(f.settlement()).orElseThrow();
			assertEquals(2, pinned.nextIndex());
			assertEquals(ORIGIN, pinned.origin());
			assertEquals(ID, pinned.blueprintId());
			assertEquals("ready", pinned.compatibilityBlocker(plan()));
			assertFalse(ConstructionSystem.buildNext(null, f.data(), f.settlement(), staleTarget, null));
			assertEquals(expected, f.data().save(new CompoundTag(), null));
			for (int i = 0; i < 3; i++) {
				var loaded = SettlementSavedData.load(expected, null);
				assertEquals(expected, loaded.save(new CompoundTag(), null));
				assertEquals(pinned, loaded.getConstructionJob(f.settlement()).orElseThrow());
			}
			var simulation = f.data().simulationOf(f.settlement()).orElseThrow();
			simulation.world().assertConservation();
			f.data().advanceConstruction(f.settlement());
			assertEquals(plan().fingerprint(), f.data().getConstructionJob(f.settlement()).orElseThrow().planFingerprint());
		});
	}

	@Test void everyFailedAdoptionPreservesFullSaveDirtyFlagAndMaterialLedger() throws Exception {
		withPlan(plan(), () -> {
			for (String failure : List.of("fingerprint_mismatch", "prefix_unloaded", "prefix_mismatch", "not_legacy", "invalid_index")) {
				ConstructionJob job = switch (failure) {
					case "not_legacy" -> new ConstructionJob(ID, ORIGIN, 2, "v2-unknown");
					case "invalid_index" -> new ConstructionJob(ID, ORIGIN, 3);
					default -> new ConstructionJob(ID, ORIGIN, 2);
				};
				var f = fixture(job);
				var probe = matchingPrefix();
				if (failure.equals("prefix_unloaded")) probe.unloaded = ORIGIN;
				if (failure.equals("prefix_mismatch")) probe.states.clear();
				String expected = failure.equals("fingerprint_mismatch") ? "v1-" + "0".repeat(64) : plan().fingerprint();
				var saved = f.data().save(new CompoundTag(), null);
				f.data().setDirty(false);
				var result = ConstructionSystem.adoptLegacyPlan(f.data(), f.settlement(), expected, probe);
				assertEquals(failure, result.status());
				assertFalse(f.data().isDirty());
				assertEquals(saved, f.data().save(new CompoundTag(), null));
				assertSame(job, f.data().getConstructionJob(f.settlement()).orElseThrow());
			}
		});
	}

	@Test void repeatedAdoptionCannotOverrideAKnownPlanOrRepeatAnyWorldReads() throws Exception {
		withPlan(plan(), () -> {
			var f = fixture(new ConstructionJob(ID, ORIGIN, 0));
			assertEquals("adopted", ConstructionSystem.adoptLegacyPlan(null, f.data(), f.settlement(), plan().fingerprint()).status());
			var saved = f.data().save(new CompoundTag(), null);
			f.data().setDirty(false);
			assertEquals("not_legacy", ConstructionSystem.adoptLegacyPlan(null, f.data(), f.settlement(), plan().fingerprint()).status());
			assertFalse(f.data().isDirty());
			assertEquals(saved, f.data().save(new CompoundTag(), null));
		});
	}

	private static ConstructionPlan plan() {
		return new ConstructionPlan(ID, List.of(0, 1, 2).stream().map(x -> new ConstructionInstruction(
			new BlockPos(x, 0, 0), Blocks.COBBLESTONE.defaultBlockState(), Items.COBBLESTONE, true)).toList());
	}

	private static Probe matchingPrefix() {
		var probe = new Probe();
		probe.states.put(ORIGIN, Blocks.COBBLESTONE.defaultBlockState());
		probe.states.put(ORIGIN.east(), Blocks.COBBLESTONE.defaultBlockState());
		return probe;
	}

	private static Fixture fixture(ConstructionJob job) {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "Adoption", 0);
		data.setConstructionJob(settlement, job);
		var simulation = data.getOrCreateSimulation(settlement);
		simulation.world().importExternalBatch(ResourceType.STONE, 8, simulation.campOwner());
		return new Fixture(data, settlement);
	}

	private static void withPlan(ConstructionPlan plan, Runnable action) throws Exception {
		Field field = BlueprintManager.class.getDeclaredField("plans");
		field.setAccessible(true);
		Object previous = field.get(null);
		try { field.set(null, Map.of(plan.id(), plan)); action.run(); }
		finally { field.set(null, previous); }
	}

	private static final class Probe implements ConstructionPlanAdoption.Probe {
		final Map<BlockPos, BlockState> states = new HashMap<>();
		final List<BlockPos> reads = new ArrayList<>();
		final List<BlockPos> loadedChecks = new ArrayList<>();
		BlockPos unloaded;
		public boolean loaded(BlockPos position) { loadedChecks.add(position); return !position.equals(unloaded); }
		public BlockState state(BlockPos position) {
			assertNotEquals(unloaded, position, "Do not read unloaded chunks");
			reads.add(position);
			return states.getOrDefault(position, Blocks.AIR.defaultBlockState());
		}
	}

	private record Fixture(SettlementSavedData data, Settlement settlement) { }
}
