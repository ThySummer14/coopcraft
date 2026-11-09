package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.StorageOwner;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static org.junit.jupiter.api.Assertions.*;

@Isolated("BlueprintManager has a static reload snapshot")
class ConstructionLogDemandTest {
	private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void completedCabinProductsStillRequireAllTwentyFourStructuralLogsWithoutWorkshop() throws Exception {
		var plan = cabin();
		assertEquals(24, plan.materials().get(Items.OAK_LOG).intValue());
		for (int logs : new int[] {0, 23, 24}) {
			var stock = products(plan);
			SettlementStockpile.insert(stock, new ItemStack(Items.OAK_LOG, logs));
			for (boolean station : new boolean[] {false, true}) {
				assertEquals(logs < 24, needs(stock, new SimpleContainer(8), null, plan.materials(), station));
			}
			assertEquals(logs == 24, ConstructionMaterials.hasAll(stock, null, plan));
		}
	}

	@Test void constructionAcceptsNamedLogsButProcessingStillRequiresPlainIngredientsAndWorkshop() {
		var stock = new SimpleContainer(8);
		var actor = new SimpleContainer(8);
		stock.setItem(0, named(Items.OAK_LOG, 24));
		assertFalse(needs(stock, actor, null, Map.of(Items.OAK_LOG, 24), false));
		assertFalse(needs(stock, actor, null, Map.of(Items.OAK_PLANKS, 4), false));
		assertTrue(needs(stock, actor, null, Map.of(Items.OAK_PLANKS, 4), true));
		actor.setItem(0, named(Items.OAK_LOG, 1));
		assertTrue(needs(stock, actor, null, Map.of(Items.OAK_PLANKS, 4), true));
		actor.setItem(0, new ItemStack(Items.OAK_LOG));
		assertFalse(needs(stock, actor, null, Map.of(Items.OAK_PLANKS, 4), true));
		actor.clearContent();
		stock.setItem(1, new ItemStack(Items.OAK_LOG));
		assertFalse(needs(stock, actor, null, Map.of(Items.OAK_PLANKS, 4), true));
		stock.clearContent();
		stock.setItem(0, named(Items.OAK_PLANKS, 6));
		assertTrue(needs(stock, actor, null, Map.of(Items.OAK_DOOR, 1), true));
		stock.setItem(0, new ItemStack(Items.OAK_PLANKS, 6));
		assertFalse(needs(stock, actor, null, Map.of(Items.OAK_DOOR, 1), true));
	}

	@Test void birchAndSpruceNeverCoverOakButUnrelatedDemandDoesNotRequestOak() {
		var stock = new SimpleContainer(8);
		stock.setItem(0, new ItemStack(Items.BIRCH_LOG, 64));
		stock.setItem(1, new ItemStack(Items.SPRUCE_LOG, 64));
		var actor = new SimpleContainer(new ItemStack(Items.BIRCH_LOG, 64));
		assertTrue(needs(stock, actor, null, Map.of(Items.OAK_LOG, 1), false));
		assertTrue(needs(stock, actor, null, Map.of(Items.OAK_PLANKS, 4), true));
		assertFalse(needs(stock, actor, null, Map.of(Items.BIRCH_LOG, 65, Items.COBBLESTONE, 50), true));
	}

	@Test void carriedStructuralLogsOnlyCountAfterExistingDepositAndFullStockRemainsShort() {
		var stock = new SimpleContainer(1);
		var actor = new SimpleContainer(new ItemStack(Items.OAK_LOG, 24));
		var other = new SimpleContainer(new ItemStack(Items.OAK_LOG, 64));
		stock.setItem(0, new ItemStack(Items.STONE, 64));
		assertTrue(needs(stock, actor, null, Map.of(Items.OAK_LOG, 24), false));
		assertEquals(0, SettlementStockpile.depositItem(stock, actor, Items.OAK_LOG));
		assertTrue(needs(stock, actor, null, Map.of(Items.OAK_LOG, 24), false));
		stock.clearContent();
		assertEquals(24, SettlementStockpile.depositItem(stock, actor, Items.OAK_LOG));
		assertFalse(needs(stock, actor, null, Map.of(Items.OAK_LOG, 24), false));
		assertTrue(actor.isEmpty());
		assertEquals(64, other.countItem(Items.OAK_LOG));
	}

	@Test void abstractPublicWoodRequiresBothSwitchesAndPrivateBatchesNeverCount() {
		for (int flags = 0; flags < 4; flags++) {
			var state = state();
			state.setSurvivalEnabled((flags & 1) != 0, 0);
			state.setProductionEnabled((flags & 2) != 0);
			state.world().importExternalBatch(ResourceType.WOOD, 24, state.campOwner());
			var stock = new SimpleContainer(8);
			var actor = new SimpleContainer(8);
			assertEquals(flags != 3, needs(stock, actor, state, Map.of(Items.OAK_LOG, 24), false));
			assertEquals(flags != 3, needs(stock, actor, state, Map.of(Items.OAK_PLANKS, 4), true));
			stock.setItem(0, named(Items.OAK_LOG, 24));
			assertFalse(needs(stock, actor, state, Map.of(Items.OAK_LOG, 24), false));
		}
		var state = state();
		state.setSurvivalEnabled(true, 0); state.setProductionEnabled(true);
		UUID other = UUID.randomUUID();
		state.mapResident(ACTOR, false, 50); state.mapResident(other, false, 50);
		String actorId = state.residentId(ACTOR).orElseThrow();
		state.world().importExternalBatch(ResourceType.WOOD, 64, StorageOwner.person(actorId));
		state.world().importExternalBatch(ResourceType.WOOD, 64, StorageOwner.person(state.residentId(other).orElseThrow()));
		state.world().importExternalBatch(ResourceType.WOOD, 64,
			StorageOwner.household(state.world().person(actorId).householdId()));
		assertTrue(needs(new SimpleContainer(8), new SimpleContainer(8), state, Map.of(Items.OAK_LOG, 24), false));
		assertTrue(needs(new SimpleContainer(8), new SimpleContainer(8), state, Map.of(Items.OAK_PLANKS, 4), true));
	}

	@Test void processingKeepsBoundedPrerequisiteAndCarriedPlainStickAccounting() {
		var stock = new SimpleContainer(8);
		var actor = new SimpleContainer(8);
		stock.setItem(0, new ItemStack(Items.OAK_LOG, 3));
		assertTrue(needs(stock, actor, null, Map.of(Items.CAMPFIRE, 100), true));
		actor.setItem(0, named(Items.STICK, 3));
		assertTrue(needs(stock, actor, null, Map.of(Items.CAMPFIRE, 100), true));
		actor.setItem(0, new ItemStack(Items.STICK, 3));
		assertFalse(needs(stock, actor, null, Map.of(Items.CAMPFIRE, 100), true));
		assertTrue(SettlementMaterialCrafting.needsConstructionLogs(stock, actor, null,
			List.of(Map.of(Items.CAMPFIRE, 1), Map.of(Items.OAK_LOG, 4)), true));
	}

	@Test void noStockpileNoDemandAndRepeatedInspectionsNeverMutateInventoriesOrState() {
		var state = state();
		state.setSurvivalEnabled(true, 0); state.setProductionEnabled(true);
		state.world().importExternalBatch(ResourceType.WOOD, 1, state.campOwner());
		var stock = new SimpleContainer(named(Items.OAK_LOG, 22), named(Items.OAK_PLANKS, 6));
		var actor = new SimpleContainer(named(Items.OAK_LOG, 24));
		var before = state.save();
		ItemStack[] copies = {stock.getItem(0).copy(), stock.getItem(1).copy(), actor.getItem(0).copy()};
		for (int attempt = 0; attempt < 3; attempt++) {
			assertFalse(needs(null, actor, state, Map.of(Items.OAK_LOG, 24), true));
			assertFalse(SettlementMaterialCrafting.needsConstructionLogs(stock, actor, state, List.of(), true));
			assertFalse(needs(stock, actor, state, Map.of(), true));
			assertTrue(needs(stock, actor, state, Map.of(Items.OAK_LOG, 24), false));
			assertTrue(needs(stock, actor, state, Map.of(Items.OAK_DOOR, 1), true));
			assertTrue(ItemStack.matches(copies[0], stock.getItem(0)));
			assertTrue(ItemStack.matches(copies[1], stock.getItem(1)));
			assertTrue(ItemStack.matches(copies[2], actor.getItem(0)));
			assertEquals(before, state.save());
		}
		state.world().assertConservation();
	}

	@Test void actualPlannerUsesSavedOldJobRemainderAndSuppressesPausedJobsWithoutMutation() throws Exception {
		Field plans = BlueprintManager.class.getDeclaredField("plans");
		plans.setAccessible(true); Object previous = plans.get(null);
		try {
			new BlueprintManager().apply(Map.of(ConstructionPlanner.STONE_COTTAGE, resource("stone_cottage"),
				ConstructionPlanner.TIMBER_PORCH_CABIN, resource("timber_porch_cabin_v1")), null, null);
			var data = new SettlementSavedData();
			var settlement = data.getOrCreate(BlockPos.ZERO, "Log demand", 0);
			data.registerMember(settlement, ACTOR, 0);
			var plan = BlueprintManager.get(ConstructionPlanner.STONE_COTTAGE);
			var stock = products(plan);
			var actor = new SimpleContainer(8);
			for (String fingerprint : List.of(plan.fingerprint(), "", "changed")) {
				var job = new ConstructionJob(plan.id(), BlockPos.ZERO, 17, fingerprint);
				data.setConstructionJob(settlement, job);
				var saved = data.save(new CompoundTag(), null); data.setDirty(false);
				var demands = ConstructionPlanner.materialDemands(data, settlement);
				if (fingerprint.equals(plan.fingerprint())) {
					assertEquals(List.of(ConstructionPlanner.remainingMaterials(plan, 17)), demands);
				} else { assertTrue(demands.isEmpty()); }
				assertFalse(SettlementMaterialCrafting.needsConstructionLogs(stock, actor, null, demands, true));
				assertEquals(saved, data.save(new CompoundTag(), null));
				assertFalse(data.isDirty());
			}
		} finally { plans.set(null, previous); }
	}

	@Test void oneLogAtATimeFeedsRealCraftingThenStopsOnlyWhenCabinMaterialsAreReady() throws Exception {
		var plan = cabin();
		var demand = ConstructionPlanner.remainingMaterials(plan, 0);
		var stock = new SimpleContainer(27);
		var actor = new SimpleContainer(8);
		stock.setItem(0, new ItemStack(Items.WHITE_WOOL, 6));
		stock.setItem(1, new ItemStack(Items.COAL));
		int gathered = 0;
		int batches = 0;
		while (gathered < 80 && needs(stock, actor, null, demand, true)) {
			actor.setItem(0, new ItemStack(Items.OAK_LOG));
			assertEquals(1, SettlementStockpile.depositItem(stock, actor, Items.OAK_LOG));
			gathered++;
			for (int attempts = 0; attempts < 100; attempts++) {
				var recipe = SettlementMaterialCrafting.chooseRecipe(stock, null, demand);
				if (recipe.isEmpty()) break;
				assertTrue(SettlementMaterialCrafting.craft(stock, null, recipe.get(), ACTOR, () -> {}));
				batches++;
				assertTrue(batches < 100, "Production must remain bounded");
			}
			if (gathered == 1) assertEquals(4, stock.countItem(Items.OAK_PLANKS));
		}
		assertEquals(59, gathered);
		assertTrue(batches > 0);
		assertEquals(24, stock.countItem(Items.OAK_LOG));
		assertTrue(ConstructionMaterials.hasAll(stock, null, plan));
		assertFalse(needs(stock, actor, null, demand, true));
		assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, null, demand).isEmpty());
	}

	private static boolean needs(SimpleContainer stock, SimpleContainer actor, SettlementSimulationState state,
		Map<Item, Integer> demand, boolean station) {
		return SettlementMaterialCrafting.needsConstructionLogs(stock, actor, state, List.of(demand), station);
	}
	private static SimpleContainer products(ConstructionPlan plan) {
		var stock = new SimpleContainer(27);
		plan.materials().forEach((item, count) -> {
			if (item != Items.OAK_LOG) assertEquals(count.intValue(), SettlementStockpile.insert(stock, new ItemStack(item, count)));
		});
		return stock;
	}
	private static ItemStack named(Item item, int count) {
		var stack = new ItemStack(item, count);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Preserve components"));
		return stack;
	}
	private static SettlementSimulationState state() {
		return SettlementSimulationState.create(new Settlement(UUID.randomUUID(), BlockPos.ZERO, "Log demand", 0));
	}
	private static ConstructionPlan cabin() throws Exception {
		return ConstructionPlan.fromJson(ConstructionPlanner.TIMBER_PORCH_CABIN, resource("timber_porch_cabin_v1").getAsJsonObject());
	}
	private static JsonElement resource(String name) throws Exception {
		try (var input = ConstructionLogDemandTest.class.getResourceAsStream("/data/coopcraft/civilization/blueprints/" + name + ".json")) {
			assertNotNull(input);
			return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8));
		}
	}
}
