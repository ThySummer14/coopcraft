package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

@Isolated("BlueprintManager has a static reload snapshot")
class TimberHousingSelectionTest {
	private Field plans;
	private Object previous;
	private SettlementSavedData data;
	private Settlement settlement;

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@BeforeEach void setup() throws Exception {
		plans = BlueprintManager.class.getDeclaredField("plans");
		plans.setAccessible(true); previous = plans.get(null);
		new BlueprintManager().apply(Map.of(ConstructionPlanner.STONE_COTTAGE, resource("stone_cottage"),
			ConstructionPlanner.TIMBER_PORCH_CABIN, resource("timber_porch_cabin_v1")), null, null);
		data = new SettlementSavedData(); settlement = data.getOrCreate(new BlockPos(4, 64, 8), "Cabin", 0);
		data.registerMember(settlement, UUID.randomUUID(), 0);
		data.setStage(settlement, SettlementStage.AGRICULTURAL);
		// These fixtures isolate housing selection; workshop demand has its own tests.
		data.setCraftingStation(settlement, new BlockPos(6, 64, 8));
	}
	@AfterEach void restore() throws Exception { plans.set(null, previous); }

	@Test void newHousingUsesCabinDemandAndStopsWhenRegisteredBedsCoverMembers() {
		var cabin = BlueprintManager.get(ConstructionPlanner.TIMBER_PORCH_CABIN);
		assertEquals(List.of(new ConstructionPlanner.Project(cabin.id())), ConstructionPlanner.projects(data, settlement));
		assertEquals(List.of(cabin.materials()), ConstructionPlanner.materialDemands(data, settlement));
		assertTrue(data.getConstructionJob(settlement).isEmpty());
		data.addHousingBed(settlement, new BlockPos(1, 65, 4));
		assertTrue(ConstructionPlanner.projects(data, settlement).isEmpty());
		assertTrue(ConstructionPlanner.materialDemands(data, settlement).isEmpty());
	}

	@Test void savedCottageKeepsItsIdFingerprintIndexAndMaterialsAcrossWholeSaveReload() {
		var cottage = BlueprintManager.get(ConstructionPlanner.STONE_COTTAGE);
		var job = new ConstructionJob(cottage.id(), new BlockPos(20, 64, 20), 17, cottage.fingerprint());
		data.setConstructionJob(settlement, job);
		var before = data.save(new CompoundTag(), null); data.setDirty(false);
		var demand = List.of(ConstructionPlanner.remainingMaterials(cottage, 17));
		assertEquals(demand, ConstructionPlanner.materialDemands(data, settlement));
		assertTrue(ConstructionSystem.inspectNewSites(null, data, settlement).isEmpty());
		assertEquals(before, data.save(new CompoundTag(), null)); assertFalse(data.isDirty());
		var restored = SettlementSavedData.load(before, null);
		var restoredSettlement = restored.find(settlement.center()).orElseThrow();
		assertEquals(job, restored.getConstructionJob(restoredSettlement).orElseThrow());
		assertEquals("ready", job.compatibilityBlocker(cottage));
		assertEquals(demand, ConstructionPlanner.materialDemands(restored, restoredSettlement));
		assertEquals(before, restored.save(new CompoundTag(), null));
	}

	@Test void unverifiedOrChangedOldJobsNeverFallThroughToNewCabinDemand() {
		var cottage = BlueprintManager.get(ConstructionPlanner.STONE_COTTAGE);
		for (String signature : List.of("", "not-the-plan")) {
			var job = new ConstructionJob(cottage.id(), BlockPos.ZERO, 4, signature);
			data.setConstructionJob(settlement, job);
			assertTrue(ConstructionPlanner.materialDemands(data, settlement).isEmpty());
			assertEquals(job, data.getConstructionJob(settlement).orElseThrow());
			assertTrue(ConstructionSystem.inspectNewSites(null, data, settlement).isEmpty());
		}
	}

	@Test void missingNewPlanReportsUnavailableWithoutWorldReadsOrFallbackToCottage() throws Exception {
		new BlueprintManager().apply(Map.of(ConstructionPlanner.STONE_COTTAGE, resource("stone_cottage")), null, null);
		var before = data.save(new CompoundTag(), null); data.setDirty(false);
		var results = ConstructionSystem.inspectNewSites(null, data, settlement);
		assertEquals(1, results.size());
		assertEquals(ConstructionPlanner.TIMBER_PORCH_CABIN, results.getFirst().blueprintId());
		assertEquals("plan_unavailable", results.getFirst().reason());
		assertTrue(ConstructionPlanner.materialDemands(data, settlement).isEmpty());
		assertEquals(before, data.save(new CompoundTag(), null)); assertFalse(data.isDirty());
	}

	@Test void bothKnownHouseIdsRemainResidentialWhenMalformedReplacementRemovesBeds() {
		var noReads = new ConstructionSite.Probe() {
			public boolean inBounds(BlockPos p) { fail("No world access for an invalid plan"); return false; }
			public boolean ticking(BlockPos p) { fail("No world access for an invalid plan"); return false; }
			public BlockState state(BlockPos p) { fail("No world access for an invalid plan"); return null; }
		};
		for (var id : List.of(ConstructionPlanner.STONE_COTTAGE, ConstructionPlanner.TIMBER_PORCH_CABIN)) {
			var plan = new ConstructionPlan(id, List.of(new ConstructionInstruction(BlockPos.ZERO,
				Blocks.OAK_PLANKS.defaultBlockState(), Items.OAK_PLANKS, true)));
			assertTrue(ConstructionPlanner.isHousingBlueprint(id));
			assertTrue(ConstructionSite.isHousing(plan));
			assertEquals("unsupported_layout", ConstructionSite.inspect(plan, BlockPos.ZERO, noReads).reason());
		}
		assertFalse(ConstructionPlanner.isHousingBlueprint(ConstructionPlanner.CAMPFIRE_HEARTH));
	}

	private static JsonElement resource(String name) throws Exception {
		try (var input = TimberHousingSelectionTest.class.getResourceAsStream(
			"/data/coopcraft/civilization/blueprints/" + name + ".json")) {
			assertNotNull(input);
			try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
				return JsonParser.parseReader(reader);
			}
		}
	}
}
