package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.thysummer.coopcraft.CoopCraft;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static org.junit.jupiter.api.Assertions.*;

/** Planner-only regressions: no world, server, crafting simulation or automatic marker cleanup. */
@Isolated("BlueprintManager has a static reload snapshot")
class ConstructionWorkshopDemandTest {
	private Field plans;
	private Object previous;
	private SettlementSavedData data;
	private Settlement settlement;

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@BeforeEach void setup() throws Exception {
		plans = BlueprintManager.class.getDeclaredField("plans");
		plans.setAccessible(true);
		previous = plans.get(null);
		new BlueprintManager().apply(Map.of(
			ConstructionPlanner.CAMPFIRE_HEARTH, resource("campfire_hearth"),
			ConstructionPlanner.STARTER_WORKSHOP, resource("starter_workshop_v1"),
			ConstructionPlanner.STONE_COTTAGE, resource("stone_cottage"),
			ConstructionPlanner.TIMBER_PORCH_CABIN, resource("timber_porch_cabin_v1"),
			ConstructionPlanner.TIMBER_SIDE_PORCH_CABIN, resource("timber_side_porch_cabin_v1")), null, null);
		data = new SettlementSavedData();
		settlement = data.getOrCreate(new BlockPos(4, 64, 8), "Workshop demand", 0);
	}
	@AfterEach void restore() throws Exception { plans.set(null, previous); }

	@Test void noMembersOrEnoughBedsNeverRequestsWorkshopAtAnyStage() {
		for (SettlementStage stage : SettlementStage.values()) {
			data.setStage(settlement, stage);
			assertEquals(stage == SettlementStage.PRIMITIVE ? List.of(ConstructionPlanner.CAMPFIRE_HEARTH) : List.of(), ids());
			assertFalse(ConstructionPlanner.materialDemands(data, settlement).stream()
				.anyMatch(demand -> demand.containsKey(Items.CRAFTING_TABLE)));
		}
		data.registerMember(settlement, UUID.randomUUID(), 0);
		data.addHousingBed(settlement, new BlockPos(2, 65, 3));
		for (SettlementStage stage : SettlementStage.values()) {
			data.setStage(settlement, stage);
			assertEquals(stage == SettlementStage.PRIMITIVE ? List.of(ConstructionPlanner.CAMPFIRE_HEARTH) : List.of(), ids());
		}
		data.setHearth(settlement, settlement.center());
		data.setStage(settlement, SettlementStage.PRIMITIVE);
		assertTrue(ids().isEmpty());
		assertTrue(ConstructionPlanner.materialDemands(data, settlement).isEmpty());
	}

	@Test void deficitOrdersHearthThenWorkshopThenSelectedHouseAtEveryStage() {
		for (int i = 0; i < 5; i++) data.registerMember(settlement, new UUID(0, i + 1), 0);
		for (int capacity = 0; capacity < 5; capacity++) {
			for (SettlementStage stage : SettlementStage.values()) {
				data.setStage(settlement, stage);
				List<ResourceLocation> expected = new ArrayList<>();
				if (stage == SettlementStage.PRIMITIVE) expected.add(ConstructionPlanner.CAMPFIRE_HEARTH);
				expected.add(ConstructionPlanner.STARTER_WORKSHOP);
				expected.add(ConstructionPlanner.housingBlueprint(capacity));
				assertEquals(expected, ids());
				assertEquals(expected.stream().map(id -> BlueprintManager.get(id).materials()).toList(),
					ConstructionPlanner.materialDemands(data, settlement));
			}
			data.addHousingBed(settlement, new BlockPos(capacity * 2, 65, 3));
		}
	}

	@Test void registeredMarkerSuppressesWorkshopWithoutWorldValidationAndSurvivesReload() {
		data.registerMember(settlement, UUID.randomUUID(), 0);
		// Planner deliberately has no world access: both near and distant (potentially unloaded
		// or stale) markers remain authoritative until explicit maintenance outside planning.
		for (BlockPos marker : List.of(settlement.center().east(), new BlockPos(12000, 64, -12000))) {
			data.setCraftingStation(settlement, marker);
			for (SettlementStage stage : SettlementStage.values()) {
				data.setStage(settlement, stage);
				var before = data.save(new CompoundTag(), null);
				data.setDirty(false);
				assertEquals(stage == SettlementStage.PRIMITIVE
					? List.of(ConstructionPlanner.CAMPFIRE_HEARTH, ConstructionPlanner.TIMBER_PORCH_CABIN)
					: List.of(ConstructionPlanner.TIMBER_PORCH_CABIN), ids());
				assertFalse(ConstructionPlanner.materialDemands(data, settlement).stream()
					.anyMatch(demand -> demand.containsKey(Items.CRAFTING_TABLE)));
				assertEquals(marker, data.getCraftingStation(settlement).orElseThrow());
				assertUnchanged(before);
				var loaded = SettlementSavedData.load(before, null);
				var restored = loaded.find(settlement.center()).orElseThrow();
				assertEquals(marker, loaded.getCraftingStation(restored).orElseThrow());
				assertEquals(ConstructionPlanner.projects(data, settlement), ConstructionPlanner.projects(loaded, restored));
			}
		}
	}

	@Test void workshopBlueprintChargesExactlyOneFinishedCraftingTable() {
		var plan = BlueprintManager.get(ConstructionPlanner.STARTER_WORKSHOP);
		assertNotNull(plan);
		assertEquals(List.of(new ConstructionInstruction(BlockPos.ZERO, Blocks.CRAFTING_TABLE.defaultBlockState(),
			Items.CRAFTING_TABLE, true)), plan.instructions());
		assertEquals(Map.of(Items.CRAFTING_TABLE, 1), plan.materials());
		assertEquals(Map.of(Items.CRAFTING_TABLE, 1), ConstructionPlanner.remainingMaterials(plan, 0));
		assertTrue(ConstructionPlanner.remainingMaterials(plan, 1).isEmpty());
		assertFalse(ConstructionPlanner.isHousingBlueprint(plan.id()));
	}

	@Test void unavailableWorkshopPlanLeavesHousingDemandAvailableWithoutMutatingRegistration() throws Exception {
		data.registerMember(settlement, UUID.randomUUID(), 0);
		data.setStage(settlement, SettlementStage.AGRICULTURAL);
		new BlueprintManager().apply(Map.of(ConstructionPlanner.TIMBER_PORCH_CABIN,
			resource("timber_porch_cabin_v1")), null, null);
		var before = data.save(new CompoundTag(), null);
		data.setDirty(false);
		assertEquals(List.of(ConstructionPlanner.STARTER_WORKSHOP, ConstructionPlanner.TIMBER_PORCH_CABIN), ids());
		assertEquals(List.of(BlueprintManager.get(ConstructionPlanner.TIMBER_PORCH_CABIN).materials()),
			ConstructionPlanner.materialDemands(data, settlement));
		assertTrue(data.getCraftingStation(settlement).isEmpty());
		assertTrue(data.getConstructionJob(settlement).isEmpty());
		assertUnchanged(before);
	}

	@Test void planningIsReadOnlyAndDoesNotStartJobsOrInventStationMarkers() {
		data.registerMember(settlement, UUID.randomUUID(), 0);
		var before = data.save(new CompoundTag(), null);
		data.setDirty(false);
		for (int i = 0; i < 3; i++) {
			assertEquals(3, ConstructionPlanner.projects(data, settlement).size());
			assertEquals(3, ConstructionPlanner.materialDemands(data, settlement).size());
			assertTrue(data.getConstructionJob(settlement).isEmpty());
			assertTrue(data.getCraftingStation(settlement).isEmpty());
			assertUnchanged(before);
		}
	}

	@Test void existingCompatibleJobsKeepExclusiveDemandAndPersistWithoutWorkshopFallback() {
		data.registerMember(settlement, UUID.randomUUID(), 0);
		for (var id : List.of(ConstructionPlanner.STONE_COTTAGE, ConstructionPlanner.TIMBER_PORCH_CABIN,
			ConstructionPlanner.TIMBER_SIDE_PORCH_CABIN, ConstructionPlanner.CAMPFIRE_HEARTH,
			ConstructionPlanner.STARTER_WORKSHOP)) {
			var plan = BlueprintManager.get(id);
			for (int index : new int[] {0, plan.instructions().size() - 1}) {
				var job = new ConstructionJob(id, new BlockPos(20, 64, 20), index, plan.fingerprint());
				data.setConstructionJob(settlement, job);
				var before = data.save(new CompoundTag(), null);
				data.setDirty(false);
				var expected = List.of(ConstructionPlanner.remainingMaterials(plan, index));
				assertEquals(expected, ConstructionPlanner.materialDemands(data, settlement));
				assertEquals(job, data.getConstructionJob(settlement).orElseThrow());
				assertUnchanged(before);
				var loaded = SettlementSavedData.load(before, null);
				var restored = loaded.find(settlement.center()).orElseThrow();
				assertEquals(job, loaded.getConstructionJob(restored).orElseThrow());
				assertEquals(expected, ConstructionPlanner.materialDemands(loaded, restored));
			}
		}
	}

	@Test void pausedUnavailableUnverifiedChangedOrInvalidJobsNeverFallThroughToWorkshop() {
		data.registerMember(settlement, UUID.randomUUID(), 0);
		var cottage = BlueprintManager.get(ConstructionPlanner.STONE_COTTAGE);
		var workshop = BlueprintManager.get(ConstructionPlanner.STARTER_WORKSHOP);
		for (var plan : List.of(cottage, workshop)) {
			for (var job : List.of(
				new ConstructionJob(plan.id(), BlockPos.ZERO, 0),
				new ConstructionJob(plan.id(), BlockPos.ZERO, 0, "changed"),
				new ConstructionJob(plan.id(), BlockPos.ZERO, plan.instructions().size(), plan.fingerprint()),
				new ConstructionJob(CoopCraft.id("missing_plan"), BlockPos.ZERO, 0, plan.fingerprint()))) {
				data.setConstructionJob(settlement, job);
				var before = data.save(new CompoundTag(), null);
				data.setDirty(false);
				assertTrue(ConstructionPlanner.materialDemands(data, settlement).isEmpty());
				assertEquals(job, data.getConstructionJob(settlement).orElseThrow());
				assertUnchanged(before);
			}
		}
	}

	@Test void missingFinishedTableSkipsWorkshopAndSelectsFullySuppliedHouse() {
		prepareAgriculturalDeficit();
		var house = BlueprintManager.get(ConstructionPlanner.TIMBER_PORCH_CABIN);
		var workshop = BlueprintManager.get(ConstructionPlanner.STARTER_WORKSHOP);
		var stockpile = supplied(house);
		assertTrue(ConstructionMaterials.hasAll(stockpile, null, house));
		assertFalse(ConstructionMaterials.hasAll(stockpile, null, workshop));
		var inventoryBefore = snapshot(stockpile);
		var before = data.save(new CompoundTag(), null);
		data.setDirty(false);
		List<ResourceLocation> surveyed = new ArrayList<>();
		BlockPos origin = new BlockPos(20, 64, 20);
		var selected = ConstructionSystem.chooseProject(data, settlement, stockpile, plan -> {
			surveyed.add(plan.id());
			return origin;
		});
		assertNewProject(house, origin, selected);
		assertEquals(List.of(house.id()), surveyed);
		assertSelectionReadOnly(before, stockpile, inventoryBefore);
	}

	@Test void unavailableWorkshopSiteContinuesToFullySuppliedHouse() {
		prepareAgriculturalDeficit();
		var house = BlueprintManager.get(ConstructionPlanner.TIMBER_PORCH_CABIN);
		var workshop = BlueprintManager.get(ConstructionPlanner.STARTER_WORKSHOP);
		var stockpile = supplied(house, workshop);
		assertTrue(ConstructionMaterials.hasAll(stockpile, null, house));
		assertTrue(ConstructionMaterials.hasAll(stockpile, null, workshop));
		var inventoryBefore = snapshot(stockpile);
		var before = data.save(new CompoundTag(), null);
		data.setDirty(false);
		List<ResourceLocation> surveyed = new ArrayList<>();
		BlockPos origin = new BlockPos(20, 64, 20);
		var selected = ConstructionSystem.chooseProject(data, settlement, stockpile, plan -> {
			surveyed.add(plan.id());
			return plan.id().equals(workshop.id()) ? null : origin;
		});
		assertNewProject(house, origin, selected);
		assertEquals(List.of(workshop.id(), house.id()), surveyed);
		assertSelectionReadOnly(before, stockpile, inventoryBefore);
	}

	@Test void fullySuppliedBuildableWorkshopTakesPriorityWithoutSurveyingHouse() {
		prepareAgriculturalDeficit();
		var house = BlueprintManager.get(ConstructionPlanner.TIMBER_PORCH_CABIN);
		var workshop = BlueprintManager.get(ConstructionPlanner.STARTER_WORKSHOP);
		var stockpile = supplied(house, workshop);
		assertTrue(ConstructionMaterials.hasAll(stockpile, null, house));
		assertTrue(ConstructionMaterials.hasAll(stockpile, null, workshop));
		var inventoryBefore = snapshot(stockpile);
		var before = data.save(new CompoundTag(), null);
		data.setDirty(false);
		List<ResourceLocation> surveyed = new ArrayList<>();
		BlockPos origin = settlement.center().east(3);
		var selected = ConstructionSystem.chooseProject(data, settlement, stockpile, plan -> {
			surveyed.add(plan.id());
			return origin;
		});
		assertNewProject(workshop, origin, selected);
		assertEquals(List.of(workshop.id()), surveyed);
		assertSelectionReadOnly(before, stockpile, inventoryBefore);
	}

	@Test void existingIncompatibleJobIsReturnedWithoutReadingInventoryOrFindingSites() {
		prepareAgriculturalDeficit();
		var house = BlueprintManager.get(ConstructionPlanner.STONE_COTTAGE);
		var noReads = new SimpleContainer(1) {
			@Override public int countItem(Item item) { fail("Saved jobs must not query inventory"); return 0; }
			@Override public ItemStack getItem(int slot) { fail("Saved jobs must not read slots"); return ItemStack.EMPTY; }
		};
		for (var job : List.of(
			new ConstructionJob(house.id(), new BlockPos(20, 64, 20), 17),
			new ConstructionJob(house.id(), new BlockPos(20, 64, 20), 17, "changed"),
			new ConstructionJob(house.id(), BlockPos.ZERO, house.instructions().size(), house.fingerprint()),
			new ConstructionJob(CoopCraft.id("missing_plan"), BlockPos.ZERO, 0, "missing"))) {
			data.setConstructionJob(settlement, job);
			var before = data.save(new CompoundTag(), null);
			data.setDirty(false);
			assertNotEquals("ready", job.compatibilityBlocker(BlueprintManager.get(job.blueprintId())));
			assertSame(job, ConstructionSystem.chooseProject(data, settlement, noReads, plan -> {
				fail("Saved jobs must not survey new sites");
				return null;
			}));
			assertSame(job, data.getConstructionJob(settlement).orElseThrow());
			assertUnchanged(before);
		}
	}

	private static void assertNewProject(ConstructionPlan plan, BlockPos origin, ConstructionJob selected) {
		assertNotNull(selected);
		assertEquals(plan.id(), selected.blueprintId());
		assertEquals(origin, selected.origin());
		assertEquals(0, selected.nextIndex());
		assertEquals(plan.fingerprint(), selected.planFingerprint());
		assertTrue(selected.hasValidJobId());
		assertNotNull(selected.jobId());
		assertNull(selected.malformedJobId());
	}

	private void prepareAgriculturalDeficit() {
		data.setStage(settlement, SettlementStage.AGRICULTURAL);
		data.registerMember(settlement, UUID.randomUUID(), 0);
	}
	private static SimpleContainer supplied(ConstructionPlan... blueprints) {
		List<ItemStack> stacks = new ArrayList<>();
		for (var blueprint : blueprints) {
			for (var entry : blueprint.materials().entrySet()) {
				int remaining = entry.getValue();
				while (remaining > 0) {
					var stack = new ItemStack(entry.getKey());
					int count = Math.min(remaining, stack.getMaxStackSize());
					stack.setCount(count);
					stacks.add(stack);
					remaining -= count;
				}
			}
		}
		return new SimpleContainer(stacks.toArray(ItemStack[]::new));
	}
	private static List<ItemStack> snapshot(SimpleContainer stockpile) {
		List<ItemStack> snapshot = new ArrayList<>();
		for (int slot = 0; slot < stockpile.getContainerSize(); slot++) snapshot.add(stockpile.getItem(slot).copy());
		return snapshot;
	}
	private void assertSelectionReadOnly(CompoundTag before, SimpleContainer stockpile, List<ItemStack> inventoryBefore) {
		assertEquals(inventoryBefore.size(), stockpile.getContainerSize());
		for (int slot = 0; slot < inventoryBefore.size(); slot++) {
			assertTrue(ItemStack.matches(inventoryBefore.get(slot), stockpile.getItem(slot)), "Changed stockpile slot " + slot);
		}
		assertTrue(data.getConstructionJob(settlement).isEmpty());
		assertTrue(data.getCraftingStation(settlement).isEmpty());
		assertTrue(data.simulationOf(settlement).isEmpty());
		assertUnchanged(before);
	}

	private List<ResourceLocation> ids() {
		return ConstructionPlanner.projects(data, settlement).stream().map(ConstructionPlanner.Project::blueprintId).toList();
	}
	private void assertUnchanged(CompoundTag before) {
		assertFalse(data.isDirty());
		assertEquals(before, data.save(new CompoundTag(), null));
	}
	private static JsonElement resource(String name) throws Exception {
		try (var input = ConstructionWorkshopDemandTest.class.getResourceAsStream(
			"/data/coopcraft/civilization/blueprints/" + name + ".json")) {
			assertNotNull(input);
			try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
				return JsonParser.parseReader(reader);
			}
		}
	}
}
