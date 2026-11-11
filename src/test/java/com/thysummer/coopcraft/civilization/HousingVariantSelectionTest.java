package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import static org.junit.jupiter.api.Assertions.*;

/** Pure selection, persistence and controlled-site probes; no Minecraft server or villager simulation. */
@Isolated("BlueprintManager has a static reload snapshot")
class HousingVariantSelectionTest {
	private static final ResourceLocation NORTH = ConstructionPlanner.TIMBER_PORCH_CABIN;
	private static final ResourceLocation SIDE = ConstructionPlanner.TIMBER_SIDE_PORCH_CABIN;
	private Field plans;
	private Object previous;
	private SettlementSavedData data;
	private Settlement settlement;

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@BeforeEach void setup() throws Exception {
		plans = BlueprintManager.class.getDeclaredField("plans");
		plans.setAccessible(true);
		previous = plans.get(null);
		new BlueprintManager().apply(Map.of(ConstructionPlanner.STONE_COTTAGE, resource("stone_cottage"),
			NORTH, resource("timber_porch_cabin_v1"), SIDE, resource("timber_side_porch_cabin_v1"),
			ConstructionPlanner.CAMPFIRE_HEARTH, resource("campfire_hearth")), null, null);
		data = new SettlementSavedData();
		settlement = data.getOrCreate(new BlockPos(4, 64, 8), "Variants", 0);
		data.setStage(settlement, SettlementStage.AGRICULTURAL);
		// These fixtures isolate housing selection; workshop demand has its own tests.
		data.setCraftingStation(settlement, new BlockPos(6, 64, 8));
	}
	@AfterEach void restore() throws Exception { plans.set(null, previous); }

	@Test void selectorAlternatesEveryTwoBedsIncludingLargeBoundaries() {
		for (int capacity : new int[] {0, 1, 4, 5, 8, 9, Integer.MAX_VALUE - 3, Integer.MAX_VALUE - 2}) {
			assertEquals(NORTH, ConstructionPlanner.housingBlueprint(capacity));
		}
		for (int capacity : new int[] {2, 3, 6, 7, Integer.MAX_VALUE - 1, Integer.MAX_VALUE}) {
			assertEquals(SIDE, ConstructionPlanner.housingBlueprint(capacity));
		}
		assertThrows(IllegalArgumentException.class, () -> ConstructionPlanner.housingBlueprint(-1));
		assertThrows(IllegalArgumentException.class, () -> ConstructionPlanner.housingBlueprint(Integer.MIN_VALUE));
	}

	@Test void noPopulationOrEnoughBedsProducesNoHousingButAnyDeficitProducesOnlyOne() {
		assertTrue(ConstructionPlanner.projects(data, settlement).isEmpty());
		for (int i = 0; i < 10; i++) data.registerMember(settlement, new UUID(0, i + 1), 0);
		for (int capacity = 0; capacity <= 10; capacity++) {
			var expected = capacity == 10 ? List.of() : List.of(
				new ConstructionPlanner.Project(ConstructionPlanner.housingBlueprint(capacity)));
			assertEquals(expected, ConstructionPlanner.projects(data, settlement));
			if (capacity < 10) data.addHousingBed(settlement, bed(capacity));
		}
		data.addHousingBed(settlement, bed(10));
		assertTrue(ConstructionPlanner.projects(data, settlement).isEmpty());
	}

	@Test void hearthRemainsFirstWithAtMostOneHousingProject() {
		data.setStage(settlement, SettlementStage.PRIMITIVE);
		for (int i = 0; i < 6; i++) data.registerMember(settlement, new UUID(0, i + 1), 0);
		for (int capacity = 0; capacity < 6; capacity++) {
			assertEquals(List.of(new ConstructionPlanner.Project(ConstructionPlanner.CAMPFIRE_HEARTH),
				new ConstructionPlanner.Project(ConstructionPlanner.housingBlueprint(capacity))),
				ConstructionPlanner.projects(data, settlement));
			assertEquals(2, ConstructionPlanner.materialDemands(data, settlement).size());
			data.addHousingBed(settlement, bed(capacity));
		}
	}

	@Test void manualBedChangesSelectNextSingleEqualCostDemandWithoutWritingSaveOrStartingJob() {
		for (int i = 0; i < 8; i++) data.registerMember(settlement, new UUID(0, i + 1), 0);
		var north = BlueprintManager.get(NORTH);
		var side = BlueprintManager.get(SIDE);
		assertEquals(north.materials(), side.materials());
		for (int i = 0; i < north.instructions().size(); i++) {
			assertEquals(north.instructions().get(i).item(), side.instructions().get(i).item());
			assertEquals(north.instructions().get(i).consume(), side.instructions().get(i).consume());
		}
		for (int capacity : new int[] {0, 1, 2, 3, 4, 5, 2, 0}) {
			setBeds(capacity);
			var before = data.save(new CompoundTag(), null);
			data.setDirty(false);
			assertEquals(List.of(new ConstructionPlanner.Project(ConstructionPlanner.housingBlueprint(capacity))),
				ConstructionPlanner.projects(data, settlement));
			assertEquals(List.of(north.materials()), ConstructionPlanner.materialDemands(data, settlement));
			assertTrue(data.getConstructionJob(settlement).isEmpty());
			assertFalse(data.isDirty());
			assertEquals(before, data.save(new CompoundTag(), null));
		}
	}

	@Test void allSavedHouseJobsKeepIdOriginFingerprintIndexAndRemainingDemandAcrossBedChangesAndWholeSaveReload() {
		for (var id : List.of(ConstructionPlanner.STONE_COTTAGE, NORTH, SIDE)) {
			var plan = BlueprintManager.get(id);
			for (int index : new int[] {0, 17, plan.instructions().size() - 1}) {
				var job = new ConstructionJob(id, new BlockPos(20, 64, 20), index, plan.fingerprint());
				data.setConstructionJob(settlement, job);
				for (int capacity : new int[] {0, 2, 4, 1}) {
					setBeds(capacity);
					var before = data.save(new CompoundTag(), null);
					data.setDirty(false);
					var demand = List.of(ConstructionPlanner.remainingMaterials(plan, index));
					assertEquals(demand, ConstructionPlanner.materialDemands(data, settlement));
					assertTrue(ConstructionSystem.inspectNewSites(null, data, settlement).isEmpty());
					assertEquals(job, data.getConstructionJob(settlement).orElseThrow());
					assertFalse(data.isDirty());
					assertEquals(before, data.save(new CompoundTag(), null));
					var restored = SettlementSavedData.load(before, null);
					var camp = restored.find(settlement.center()).orElseThrow();
					assertEquals(job, restored.getConstructionJob(camp).orElseThrow());
					assertEquals("ready", restored.getConstructionJob(camp).orElseThrow().compatibilityBlocker(plan));
					assertEquals(demand, ConstructionPlanner.materialDemands(restored, camp));
					assertEquals(before, restored.save(new CompoundTag(), null));
				}
			}
		}
	}

	@Test void unverifiedOrChangedSavedJobsBlockBeforeWorldReadsWithoutVariantFallback() {
		for (int i = 0; i < 8; i++) data.registerMember(settlement, new UUID(0, i + 1), 0);
		for (var id : List.of(ConstructionPlanner.STONE_COTTAGE, NORTH, SIDE)) {
			for (String fingerprint : List.of("", "changed-plan")) {
				var job = new ConstructionJob(id, new BlockPos(20, 64, 20), 17, fingerprint);
				data.setConstructionJob(settlement, job);
				for (int capacity : new int[] {0, 2, 4}) {
					setBeds(capacity);
					var before = data.save(new CompoundTag(), null);
					data.setDirty(false);
					assertTrue(ConstructionPlanner.materialDemands(data, settlement).isEmpty());
					assertTrue(ConstructionSystem.nextBuildTarget(null, data, settlement).isEmpty());
					assertTrue(ConstructionSystem.inspectNewSites(null, data, settlement).isEmpty());
					assertEquals(job, data.getConstructionJob(settlement).orElseThrow());
					assertEquals(before, data.save(new CompoundTag(), null));
					assertFalse(data.isDirty());
				}
			}
		}
	}

	@Test void unavailableSelectedVariantReportsOnlyThatIdAndNeverFallsBackToOtherHouse() throws Exception {
		for (int i = 0; i < 4; i++) data.registerMember(settlement, new UUID(0, i + 1), 0);
		for (var selected : List.of(NORTH, SIDE)) {
			setBeds(selected.equals(NORTH) ? 0 : 2);
			var other = selected.equals(NORTH) ? SIDE : NORTH;
			new BlueprintManager().apply(Map.of(other, resource(other.getPath()),
				ConstructionPlanner.STONE_COTTAGE, resource("stone_cottage")), null, null);
			var before = data.save(new CompoundTag(), null);
			data.setDirty(false);
			var results = ConstructionSystem.inspectNewSites(null, data, settlement);
			assertEquals(1, results.size());
			assertEquals(selected, results.getFirst().blueprintId());
			assertEquals("plan_unavailable", results.getFirst().reason());
			assertEquals(List.of(new ConstructionPlanner.Project(selected)), ConstructionPlanner.projects(data, settlement));
			assertTrue(ConstructionPlanner.materialDemands(data, settlement).isEmpty());
			assertFalse(data.isDirty());
			assertEquals(before, data.save(new CompoundTag(), null));
		}
	}

	@Test void allKnownHouseIdsStayResidentialIfReplacementRemovesBeds() {
		var noReads = new ConstructionSite.Probe() {
			public boolean inBounds(BlockPos p) { fail("Invalid plan must not read world"); return false; }
			public boolean ticking(BlockPos p) { fail("Invalid plan must not read world"); return false; }
			public BlockState state(BlockPos p) { fail("Invalid plan must not read world"); return null; }
		};
		for (var id : List.of(ConstructionPlanner.STONE_COTTAGE, NORTH, SIDE)) {
			var plan = new ConstructionPlan(id, List.of(new ConstructionInstruction(BlockPos.ZERO,
				Blocks.OAK_PLANKS.defaultBlockState(), Items.OAK_PLANKS, true)));
			assertTrue(ConstructionPlanner.isHousingBlueprint(id));
			assertTrue(ConstructionSite.isHousing(plan));
			assertEquals("unsupported_layout", ConstructionSite.inspect(plan, BlockPos.ZERO, noReads).reason());
		}
		assertFalse(ConstructionPlanner.isHousingBlueprint(ConstructionPlanner.CAMPFIRE_HEARTH));
	}

	@Test void failedSurveyCooldownIsIndependentPerVariantAndExpiresAtTwentiethTick() {
		AtomicInteger reads = new AtomicInteger();
		var unloaded = new ConstructionSite.Probe() {
			public boolean inBounds(BlockPos p) { reads.incrementAndGet(); return true; }
			public boolean ticking(BlockPos p) { reads.incrementAndGet(); return false; }
			public BlockState state(BlockPos p) { fail("Unloaded terrain must not be read"); return null; }
		};
		var north = BlueprintManager.get(NORTH);
		var side = BlueprintManager.get(SIDE);
		var before = data.save(new CompoundTag(), null);
		data.setDirty(false);
		assertNull(ConstructionSystem.chooseGuardedOrigin(data, settlement, north, 100, unloaded));
		int oneScan = reads.get();
		assertTrue(oneScan > 0);
		assertNull(ConstructionSystem.chooseGuardedOrigin(data, settlement, side, 105, unloaded));
		assertEquals(2 * oneScan, reads.get());
		for (int caller = 0; caller < 20; caller++) {
			assertNull(ConstructionSystem.chooseGuardedOrigin(data, settlement, north, 119, unloaded));
			assertNull(ConstructionSystem.chooseGuardedOrigin(data, settlement, side, 119, unloaded));
		}
		assertEquals(2 * oneScan, reads.get());
		assertNull(ConstructionSystem.chooseGuardedOrigin(data, settlement, north, 120, unloaded));
		assertNull(ConstructionSystem.chooseGuardedOrigin(data, settlement, side, 124, unloaded));
		assertEquals(3 * oneScan, reads.get());
		assertNull(ConstructionSystem.chooseGuardedOrigin(data, settlement, side, 125, unloaded));
		assertEquals(4 * oneScan, reads.get());
		assertEquals(2, data.constructionSiteRetries().size());
		assertFalse(data.isDirty());
		assertEquals(before, data.save(new CompoundTag(), null));
	}

	private static BlockPos bed(int i) { return new BlockPos(i * 2, 65, 4); }
	private void setBeds(int count) {
		for (int i = 0; i < 12; i++) data.removeHousingBed(settlement, bed(i));
		for (int i = 0; i < count; i++) data.addHousingBed(settlement, bed(i));
	}
	private static JsonElement resource(String name) throws Exception {
		try (var input = HousingVariantSelectionTest.class.getResourceAsStream(
			"/data/coopcraft/civilization/blueprints/" + name + ".json")) {
			assertNotNull(input);
			try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
				return JsonParser.parseReader(reader);
			}
		}
	}
}
