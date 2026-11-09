package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Controlled probes around production services, not a running ServerLevel or AI navigation test. */
class ConstructionWorkshopFlowTest {
	private static final BlockPos ORIGIN = new BlockPos(6, 64, 0);
	private static ConstructionPlan workshop, cabin;

	@BeforeAll static void bootstrap() throws Exception {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		workshop = resource("starter_workshop_v1"); cabin = resource("timber_porch_cabin_v1");
	}

	private static ConstructionPlan resource(String name) throws Exception {
		try (var reader = new InputStreamReader(ConstructionWorkshopFlowTest.class.getResourceAsStream(
			"/data/coopcraft/civilization/blueprints/" + name + ".json"), StandardCharsets.UTF_8)) {
			return ConstructionPlan.fromJson(ResourceLocation.parse("coopcraft:" + name), JsonParser.parseReader(reader).getAsJsonObject());
		}
	}

	private static final class World implements ConstructionReadiness.Probe {
		final Map<BlockPos, BlockState> blocks = new HashMap<>();
		final Set<BlockPos> unknown = new HashSet<>();
		final SimpleContainer inventory = new SimpleContainer(9);
		int reads, inventoryReads;
		boolean unloaded, entity;
		public boolean inBounds(BlockPos position) { return position.getY() >= -64 && position.getY() < 320; }
		public boolean ticking(BlockPos position) { return !unloaded && !unknown.contains(position); }
		public BlockState state(BlockPos position) {
			assertTrue(inBounds(position)); assertTrue(ticking(position)); reads++;
			return blocks.getOrDefault(position, position.getY() == 63 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
		}
		public boolean entityObstructed(BlockPos position) { return entity; }
		public SettlementHousing.BedCheck inspectBed(BlockPos position) { throw new AssertionError("No beds in workshop"); }
		public Container stockpile() { inventoryReads++; return inventory; }
		public SettlementSimulationState simulation() { return null; }
	}

	@Test void changedGroundPausesBeforeTransactionAndReloadCanFinishThenProduceHousingPlanks() {
		var data = new SettlementSavedData(); var settlement = data.getOrCreate(new BlockPos(0, 64, 0), "Work", 0);
		data.registerMember(settlement, UUID.randomUUID(), 0);
		var job = ConstructionJob.start(workshop, ORIGIN); data.setConstructionJob(settlement, job);
		var world = new World(); world.inventory.setItem(0, new ItemStack(Items.CRAFTING_TABLE));
		world.inventory.setItem(1, new ItemStack(Items.OAK_LOG));
		assertTrue(ConstructionStepReadiness.inspect(job, workshop, world, false).ready());
		world.blocks.put(ORIGIN.below().east(), Blocks.AIR.defaultBlockState());
		var before = data.save(new CompoundTag(), null); data.setDirty(false);
		assertEquals("workshop_foundation_unsupported", ConstructionReadiness.inspect(job, workshop, world).blocker());
		assertFalse(ConstructionSystem.placeIfReady(job, workshop, world, () -> fail("No transaction while support is lost")));
		assertEquals(1, world.inventory.countItem(Items.CRAFTING_TABLE)); assertEquals(0, world.inventoryReads);
		assertEquals(before, data.save(new CompoundTag(), null)); assertFalse(data.isDirty());
		var restored = SettlementSavedData.load(before, null); var resumed = restored.getConstructionJob(settlement).orElseThrow();
		assertEquals(job, resumed); world.blocks.clear();
		assertTrue(place(resumed, world, restored, true));
		assertEquals(0, world.inventory.countItem(Items.CRAFTING_TABLE));
		assertTrue(ConstructionWorkshop.registerCompleted(restored, settlement, workshop, ORIGIN, world));
		restored.completeConstruction(settlement);
		assertEquals(ORIGIN, restored.getCraftingStation(settlement).orElseThrow());
		assertTrue(restored.getConstructionJob(settlement).isEmpty());
		assertFalse(ConstructionPlanner.projects(restored, settlement).stream().anyMatch(p -> p.blueprintId().equals(workshop.id())));
		var demand = ConstructionPlanner.remainingMaterials(cabin, 0);
		var recipe = SettlementMaterialCrafting.chooseRecipe(world.inventory, null, demand).orElseThrow();
		assertEquals(SettlementMaterialCrafting.Recipe.PLANKS, recipe);
		assertTrue(SettlementMaterialCrafting.craft(world.inventory, null, recipe, UUID.randomUUID(), restored::setDirty));
		assertEquals(0, world.inventory.countItem(Items.OAK_LOG)); assertEquals(4, world.inventory.countItem(Items.OAK_PLANKS));
		assertEquals(0, restored.housingCapacity(settlement), "A workbench is not housing");
	}

	private static boolean place(ConstructionJob job, World world, SettlementSavedData data, boolean succeeds) {
		return ConstructionSystem.placeIfReady(job, workshop, world, () -> ConstructionMaterials.place(
			world.inventory, null, Items.CRAFTING_TABLE, true, job, UUID.randomUUID(), data::setDirty,
			() -> { if (succeeds) world.blocks.put(ORIGIN, Blocks.CRAFTING_TABLE.defaultBlockState()); return succeeds; }));
	}

	@Test void failedPlacementKeepsOneComponentAndDuplicateAttemptCannotConsumeAgain() {
		var data = new SettlementSavedData(); var world = new World(); var job = ConstructionJob.start(workshop, ORIGIN);
		world.inventory.setItem(0, new ItemStack(Items.CRAFTING_TABLE, 2));
		assertFalse(place(job, world, data, false)); assertEquals(2, world.inventory.countItem(Items.CRAFTING_TABLE));
		assertTrue(place(job, world, data, true)); assertEquals(1, world.inventory.countItem(Items.CRAFTING_TABLE));
		assertFalse(place(job, world, data, true)); assertEquals(1, world.inventory.countItem(Items.CRAFTING_TABLE));
	}

	@Test void unknownWorkingSpaceStopsBeforeAllBlockInventoryAndPlacementReads() {
		var world = new World(); var job = ConstructionJob.start(workshop, ORIGIN);
		world.unknown.add(ORIGIN.offset(1, 1, 1));
		assertEquals("workshop_not_ticking", ConstructionReadiness.inspect(job, workshop, world).blocker());
		assertFalse(ConstructionSystem.placeIfReady(job, workshop, world, () -> fail("Unknown space")));
		assertEquals(0, world.reads); assertEquals(0, world.inventoryReads);
	}

	@Test void strictSpaceAndEntityGuardsDoNotRemovePlayerBlocksOrAdvanceSavedJob() {
		var world = new World(); var job = ConstructionJob.start(workshop, ORIGIN);
		world.inventory.setItem(0, new ItemStack(Items.CRAFTING_TABLE));
		for (BlockState obstruction : List.of(Blocks.CRAFTING_TABLE.defaultBlockState(), Blocks.WATER.defaultBlockState(),
			Blocks.SHORT_GRASS.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState())) {
			world.blocks.put(ORIGIN.north(), obstruction); var before = Map.copyOf(world.blocks);
			assertEquals("workshop_volume_occupied", ConstructionReadiness.inspect(job, workshop, world).blocker());
			assertFalse(ConstructionSystem.placeIfReady(job, workshop, world, () -> fail("Occupied work space")));
			assertEquals(before, world.blocks); assertEquals(1, world.inventory.countItem(Items.CRAFTING_TABLE));
		}
		world.blocks.clear(); world.entity = true;
		assertTrue(ConstructionStepReadiness.inspect(job, workshop, world, false).ready(), "Work claims allow the builder itself");
		assertEquals("entity_obstructed", ConstructionReadiness.inspect(job, workshop, world).blocker());
		assertFalse(ConstructionSystem.placeIfReady(job, workshop, world, () -> fail("Occupied target")));
	}

	@Test void workshopSharesBoundedRetriesButReadOnlySurveyAlwaysRefreshes() {
		var data = new SettlementSavedData(); var settlement = data.getOrCreate(new BlockPos(0, 64, 0), "Sites", 0);
		var world = new World(); world.unloaded = true;
		assertNull(ConstructionSystem.chooseGuardedOrigin(data, settlement, workshop, 100, world));
		world.unloaded = false;
		assertNull(ConstructionSystem.chooseGuardedOrigin(data, settlement, workshop, 119, world));
		assertEquals(0, world.reads);
		var before = data.save(new CompoundTag(), null); data.setDirty(false);
		var survey = ConstructionSystem.inspectSites(data, settlement, workshop, world);
		assertEquals(8, survey.size()); assertTrue(survey.stream().allMatch(ConstructionSite.Result::ready));
		assertTrue(survey.stream().allMatch(r -> r.origin().getY() == 64));
		assertEquals(before, data.save(new CompoundTag(), null)); assertFalse(data.isDirty());
		assertEquals(survey.getFirst().origin(), ConstructionSystem.chooseGuardedOrigin(data, settlement, workshop, 120, world));
		data.setConstructionJob(settlement, ConstructionJob.start(workshop, ORIGIN));
		assertTrue(ConstructionSystem.inspectSites(data, settlement, workshop, world).isEmpty());
	}
	@Test void blockedUsableRadiusNeverFallsBackToAnUnusableEighteenBlockWorkstation() {
		var data = new SettlementSavedData(); var center = new BlockPos(0, 64, 0);
		var settlement = data.getOrCreate(center, "Range", 0); var world = new World();
		var candidates = ConstructionSite.candidates(workshop, center);
		assertEquals(8, candidates.size());
		assertTrue(candidates.stream().allMatch(p -> ConstructionWorkshop.withinWorkRange(center, p)));
		for (BlockPos p : candidates) world.blocks.put(p, Blocks.OAK_PLANKS.defaultBlockState());
		assertTrue(ConstructionWorkshop.inspect(workshop, center.east(18), world).ready(), "The terrain itself is clear farther out");
		assertNull(ConstructionSystem.chooseGuardedOrigin(data, settlement, workshop, 0, world));
		assertFalse(ConstructionWorkshop.withinWorkRange(center, center.east(18)));
		assertTrue(ConstructionWorkshop.withinWorkRange(center, center.east(12)));
		assertFalse(ConstructionWorkshop.withinWorkRange(center, center.east(12).above()));
		assertEquals(36, ConstructionSite.candidates(cabin, center).size(), "Housing retains all original candidates");
	}

}
