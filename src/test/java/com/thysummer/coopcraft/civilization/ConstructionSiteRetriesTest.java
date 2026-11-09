package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Controlled Probe unit tests, not server, chunk-loading, villager scheduling or TPS measurements. */
class ConstructionSiteRetriesTest {
	private static final BlockPos CENTER = new BlockPos(0, 64, 0);
	private static ConstructionPlan cottage;
	private static ConstructionPlan cabin;

	@BeforeAll static void bootstrap() throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		cottage = resource("stone_cottage");
		cabin = resource("timber_porch_cabin_v1");
	}

	private static ConstructionPlan resource(String name) throws Exception {
		try (var reader = new InputStreamReader(ConstructionSiteRetriesTest.class.getResourceAsStream(
			"/data/coopcraft/civilization/blueprints/" + name + ".json"), StandardCharsets.UTF_8)) {
			return ConstructionPlan.fromJson(ResourceLocation.parse("coopcraft:" + name),
				JsonParser.parseReader(reader).getAsJsonObject());
		}
	}

	private static Settlement settlement(int index) {
		return new Settlement(new UUID(0, index), CENTER.offset(index * 100, 0, 0), "Camp " + index, 0);
	}

	private static BlockPos choose(SettlementSavedData data, Settlement settlement, ConstructionPlan plan,
		long tick, CountingProbe probe) {
		return ConstructionSystem.chooseHousingOrigin(data, settlement, plan, tick, probe);
	}

	@Test void manyCallersShareOneFailureUntilExactTwentiethTickWithoutRenewingIt() {
		for (ConstructionPlan plan : List.of(cottage, cabin)) {
			var data = new SettlementSavedData();
			var camp = settlement(0);
			var probe = new CountingProbe(false);
			assertNull(choose(data, camp, plan, 100, probe));
			Counts once = probe.counts();
			assertEquals(new Counts(36, 36, 0), once);
			for (long tick : List.of(100L, 119L)) for (int caller = 0; caller < 20; caller++) {
				assertNull(choose(data, camp, plan, tick, probe));
				assertEquals(once, probe.counts(), "hit must perform zero Probe calls");
			}
			assertNull(choose(data, camp, plan, 120, probe));
			assertEquals(once.times(2), probe.counts());
			assertEquals(1, data.constructionSiteRetries().size());
		}
	}

	@Test void worldChangeIsSeenAfterCooldownAndSuccessIsNeverCached() {
		for (ConstructionPlan plan : List.of(cottage, cabin)) {
			var data = new SettlementSavedData();
			var camp = settlement(0);
			var probe = new CountingProbe(false);
			assertNull(choose(data, camp, plan, 0, probe));
			probe.loaded = true;
			assertNull(choose(data, camp, plan, 19, probe));
			assertNotNull(choose(data, camp, plan, 20, probe));
			assertEquals(0, data.constructionSiteRetries().size());
			Counts success = probe.counts();
			assertNotNull(choose(data, camp, plan, 20, probe));
			assertTrue(probe.reads > success.state());
			probe.loaded = false;
			assertNull(choose(data, camp, plan, 20, probe), "must not reuse ready after world change");
		}
	}

	@Test void diagnosticAndDirectInspectionStayFreshAndDoNotRenewProductionCooldown() {
		var data = new SettlementSavedData();
		var camp = settlement(0);
		var probe = new CountingProbe(false);
		assertNull(choose(data, camp, cabin, 100, probe));
		for (int i = 0; i < 3; i++) {
			Counts before = probe.counts();
			assertEquals(36, ConstructionSystem.inspectSites(data, camp, cabin, probe).size());
			assertEquals(before.plus(new Counts(36, 36, 0)), probe.counts());
		}
		probe.loaded = true;
		assertTrue(ConstructionSite.inspect(cabin, ConstructionSite.candidates(camp.center()).getFirst(), probe).ready());
		Counts beforeHit = probe.counts();
		assertNull(choose(data, camp, cabin, 119, probe));
		assertEquals(beforeHit, probe.counts());
		assertNotNull(choose(data, camp, cabin, 120, probe));
	}

	@Test void centerBlueprintAndFingerprintChangesRetryImmediately() {
		var data = new SettlementSavedData();
		var camp = settlement(0);
		var probe = new CountingProbe(false);
		assertNull(choose(data, camp, cottage, 0, probe));
		var moved = new Settlement(camp.id(), camp.center().east(), camp.name(), 0);
		assertNull(choose(data, moved, cottage, 0, probe));
		assertEquals(new Counts(72, 72, 0), probe.counts());
		assertNull(choose(data, moved, cabin, 0, probe));
		assertEquals(new Counts(108, 108, 0), probe.counts());
		var parts = new ArrayList<>(cottage.instructions());
		var first = parts.getFirst();
		parts.set(0, new ConstructionInstruction(first.offset(), Blocks.STONE.defaultBlockState(),
			Blocks.STONE.asItem(), true));
		var changed = new ConstructionPlan(cottage.id(), parts);
		assertNotEquals(cottage.fingerprint(), changed.fingerprint());
		assertNull(choose(data, moved, changed, 0, probe));
		assertEquals(new Counts(144, 144, 0), probe.counts());
	}

	@Test void identicalPlanReloadKeepsCooldownAndThrownSurveyDoesNotRecordFailure() {
		var data = new SettlementSavedData();
		var camp = settlement(0);
		var probe = new CountingProbe(false);
		assertNull(choose(data, camp, cabin, 0, probe));
		var reloaded = new ConstructionPlan(cabin.id(), cabin.instructions());
		assertNull(choose(data, camp, reloaded, 19, probe));
		assertEquals(new Counts(36, 36, 0), probe.counts());
		var other = new SettlementSavedData();
		var failingProbe = new ConstructionSite.Probe() {
			public boolean inBounds(BlockPos position) { throw new IllegalStateException("Probe failed"); }
			public boolean ticking(BlockPos position) { throw new AssertionError(); }
			public BlockState state(BlockPos position) { throw new AssertionError(); }
		};
		assertThrows(IllegalStateException.class,
			() -> ConstructionSystem.chooseHousingOrigin(other, camp, cabin, 0, failingProbe));
		assertEquals(0, other.constructionSiteRetries().size());
		assertNull(choose(other, camp, cabin, 0, probe));
		assertEquals(new Counts(72, 72, 0), probe.counts());
	}

	@Test void storedCenterIsImmutableAndDataAndSettlementIdentitiesAreIndependent() {
		var data = new SettlementSavedData();
		var center = new BlockPos.MutableBlockPos(0, 64, 0);
		var camp = new Settlement(new UUID(0, 10), center, "Mutable input", 0);
		var probe = new CountingProbe(false);
		assertNull(choose(data, camp, cabin, 0, probe));
		center.set(1, 64, 0);
		assertNull(choose(data, camp, cabin, 0, probe));
		assertNull(choose(data, new Settlement(new UUID(0, 11), center, "Other", 0), cabin, 0, probe));
		assertNull(choose(new SettlementSavedData(), camp, cabin, 0, probe));
		assertEquals(new Counts(144, 144, 0), probe.counts());
	}

	@Test void rollbackAndSubtractionOverflowRetryConservatively() {
		for (long[] times : List.of(new long[] {100, 99}, new long[] {Long.MAX_VALUE, Long.MIN_VALUE},
			new long[] {Long.MIN_VALUE, Long.MAX_VALUE}, new long[] {-1, Long.MAX_VALUE})) {
			var data = new SettlementSavedData();
			var probe = new CountingProbe(false);
			assertNull(choose(data, settlement(0), cabin, times[0], probe));
			assertNull(choose(data, settlement(0), cabin, times[1], probe));
			assertEquals(new Counts(72, 72, 0), probe.counts());
		}
		var data = new SettlementSavedData();
		var probe = new CountingProbe(false);
		assertNull(choose(data, settlement(0), cabin, Long.MAX_VALUE - 19, probe));
		assertNull(choose(data, settlement(0), cabin, Long.MAX_VALUE, probe));
		assertEquals(new Counts(36, 36, 0), probe.counts());
	}

	@Test void fifoIsBoundedAndHitsDoNotPromoteEntries() {
		var data = new SettlementSavedData();
		var probe = new CountingProbe(false);
		for (int i = 0; i < ConstructionSiteRetries.MAX_ENTRIES; i++) {
			assertNull(choose(data, settlement(i), cabin, 0, probe));
		}
		Counts full = probe.counts();
		assertNull(choose(data, settlement(0), cabin, 19, probe));
		assertEquals(full, probe.counts());
		assertNull(choose(data, settlement(ConstructionSiteRetries.MAX_ENTRIES), cabin, 19, probe));
		assertEquals(ConstructionSiteRetries.MAX_ENTRIES, data.constructionSiteRetries().size());
		assertNull(choose(data, settlement(1), cabin, 19, probe));
		assertEquals(full.plus(new Counts(36, 36, 0)), probe.counts());
		// Eviction permits an extra scan; there is no hard rate guarantee beyond the retained capacity.
		assertNull(choose(data, settlement(0), cabin, 19, probe));
		assertEquals(full.plus(new Counts(72, 72, 0)), probe.counts());
		assertEquals(ConstructionSiteRetries.MAX_ENTRIES, data.constructionSiteRetries().size());
	}

	@Test void retryTimingDoesNotDirtyOrSerializeOrChangePlansAndMaterials() {
		var data = new SettlementSavedData();
		var camp = data.getOrCreate(CENTER, "Saved camp", 0);
		data.setDirty(false);
		var before = data.save(new CompoundTag(), null);
		var materials = cabin.materials();
		var instructions = List.copyOf(cabin.instructions());
		var projects = ConstructionPlanner.projects(data, camp);
		var probe = new CountingProbe(false);
		assertNull(choose(data, camp, cabin, 0, probe));
		assertNull(choose(data, camp, cabin, 19, probe));
		assertFalse(data.isDirty());
		assertEquals(before, data.save(new CompoundTag(), null));
		assertEquals(materials, cabin.materials());
		assertEquals(instructions, cabin.instructions());
		assertEquals(projects, ConstructionPlanner.projects(data, camp));
		var loaded = SettlementSavedData.load(before, null);
		assertEquals(0, loaded.constructionSiteRetries().size());
		assertNull(choose(loaded, camp, cabin, 19, probe));
		assertEquals(new Counts(72, 72, 0), probe.counts());
		probe.loaded = true;
		assertNotNull(choose(data, camp, cabin, 20, probe));
		assertFalse(data.isDirty());
		assertEquals(before, data.save(new CompoundTag(), null));
	}

	@Test void existingUnverifiedJobNeverEntersNewSiteScanEvenWithNullLevel() {
		var data = new SettlementSavedData();
		var camp = settlement(0);
		var job = new ConstructionJob(cabin.id(), CENTER, 7);
		data.setConstructionJob(camp, job);
		data.setDirty(false);
		assertTrue(ConstructionSystem.nextBuildTarget(null, data, camp).isEmpty());
		assertTrue(ConstructionSystem.inspectSites(null, data, camp, cabin).isEmpty());
		assertTrue(ConstructionSystem.inspectNewHousingSites(null, data, camp).isEmpty());
		assertEquals(job, data.getConstructionJob(camp).orElseThrow());
		assertEquals(0, data.constructionSiteRetries().size());
		assertFalse(data.isDirty());
	}

	@Test void tenCampsTwentyCallersHaveOneTwentiethOfBaselineProbeOperations() {
		for (ConstructionPlan plan : List.of(cottage, cabin)) for (boolean loaded : List.of(false, true)) {
			var baseline = new CountingProbe(loaded);
			var throttled = new CountingProbe(loaded);
			var data = new SettlementSavedData();
			for (int camp = 0; camp < 10; camp++) {
				baseline.blockLate(plan, settlement(camp).center());
				throttled.blockLate(plan, settlement(camp).center());
			}
			for (int caller = 0; caller < 20; caller++) for (int camp = 0; camp < 10; camp++) {
				var settlement = settlement(camp);
				assertTrue(ConstructionSite.firstReady(plan, settlement.center(), baseline).isEmpty());
				assertNull(choose(data, settlement, plan, 100, throttled));
			}
			assertEquals(throttled.counts().times(20), baseline.counts());
			if (!loaded) {
				assertEquals(new Counts(7200, 7200, 0), baseline.counts());
				assertEquals(new Counts(360, 360, 0), throttled.counts());
			} else {
				Counts expected = plan == cottage ? new Counts(61680, 61680, 15240)
					: new Counts(139920, 139920, 32640);
				assertEquals(expected, throttled.counts());
			}
			System.out.println("Site retry operations " + plan.id() + " loaded=" + loaded
				+ " baseline=" + baseline.counts() + " throttled=" + throttled.counts());
		}
	}

	private record Counts(int inBounds, int ticking, int state) {
		Counts times(int factor) { return new Counts(inBounds * factor, ticking * factor, state * factor); }
		Counts plus(Counts other) { return new Counts(inBounds + other.inBounds, ticking + other.ticking, state + other.state); }
	}

	/** Static flat-ground world with optional solid late-volume cells; never pretends unknown is air. */
	private static final class CountingProbe implements ConstructionSite.Probe {
		boolean loaded;
		int bounds, ticks, reads;
		final Set<BlockPos> blockers = new HashSet<>();
		CountingProbe(boolean loaded) { this.loaded = loaded; }
		public boolean inBounds(BlockPos position) { bounds++; return true; }
		public boolean ticking(BlockPos position) { ticks++; return loaded; }
		public BlockState state(BlockPos position) {
			assertTrue(loaded, "unknown terrain must never be read");
			reads++;
			return (position.getY() == 63 || blockers.contains(position) ? Blocks.STONE : Blocks.AIR).defaultBlockState();
		}
		Counts counts() { return new Counts(bounds, ticks, reads); }
		void blockLate(ConstructionPlan plan, BlockPos center) {
			int x = plan.instructions().stream().mapToInt(p -> p.offset().getX()).max().orElseThrow();
			int y = plan.instructions().stream().mapToInt(p -> p.offset().getY()).max().orElseThrow();
			int z = plan.instructions().stream().mapToInt(p -> p.offset().getZ()).max().orElseThrow();
			// Original-height sites reach late volume blockers; +/-1 sites fail foundation/volume early.
			for (BlockPos origin : ConstructionSite.candidates(center)) blockers.add(origin.offset(x, y, z));
		}
	}
}
