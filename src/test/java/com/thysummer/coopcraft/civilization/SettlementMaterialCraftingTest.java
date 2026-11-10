package com.thysummer.coopcraft.civilization;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettlementMaterialCraftingTest {
	private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static SettlementSimulationState state() {
		return SettlementSimulationState.create(new Settlement(UUID.randomUUID(), BlockPos.ZERO, "Workshop", 0));
	}

	@Test
	void producesExactlyOneBatchOfEveryStockpileSupportedRecipe() {
		for (SettlementMaterialCrafting.Recipe recipe : SettlementMaterialCrafting.Recipe.values()) {
			// Pickaxes and grinding require physical transport to their registered station.
			if (recipe == SettlementMaterialCrafting.Recipe.STONE_PICKAXE
				|| recipe == SettlementMaterialCrafting.Recipe.SMOOTH_STONE) continue;
			SimpleContainer stock = new SimpleContainer(9);
			recipe.ingredients().forEach((item, count) -> SettlementStockpile.insert(stock, new ItemStack(item, count)));
			AtomicInteger dirty = new AtomicInteger();
			assertTrue(SettlementMaterialCrafting.craft(stock, state(), recipe, ACTOR, dirty::incrementAndGet));
			assertEquals(recipe.count(), stock.countItem(recipe.output()));
			recipe.ingredients().keySet().forEach(item -> assertEquals(0, stock.countItem(item)));
			assertEquals(1, dirty.get());
		}
	}

	@Test
	void usesSlotFreedByConsumptionButDoesNotConsumeWhenOutputCannotFit() {
		SimpleContainer stock = new SimpleContainer(1);
		stock.setItem(0, new ItemStack(Items.OAK_LOG));
		assertTrue(SettlementMaterialCrafting.craft(stock, state(), SettlementMaterialCrafting.Recipe.PLANKS, ACTOR, () -> {}));
		assertEquals(4, stock.countItem(Items.OAK_PLANKS));
		stock.setItem(0, new ItemStack(Items.OAK_LOG, 2));
		assertFalse(SettlementMaterialCrafting.craft(stock, state(), SettlementMaterialCrafting.Recipe.PLANKS, ACTOR, () -> fail("No mutation expected")));
		assertEquals(2, stock.countItem(Items.OAK_LOG));
	}

	@Test
	void neverConsumesNamedIngredientsOrConvertsOtherWood() {
		for (Item item : new Item[] { Items.OAK_LOG, Items.BIRCH_LOG }) {
			SimpleContainer stock = new SimpleContainer(3);
			ItemStack input = new ItemStack(item, 5);
			if (item == Items.OAK_LOG) { input.set(DataComponents.CUSTOM_NAME, Component.literal("Keep me")); }
			stock.setItem(0, input);
			assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, state(), Map.of(Items.OAK_PLANKS, 4)).isEmpty());
			assertFalse(SettlementMaterialCrafting.craft(stock, state(), SettlementMaterialCrafting.Recipe.PLANKS, ACTOR, () -> {}));
			assertEquals(5, stock.getItem(0).getCount());
		}
	}

	@Test
	void exportsOnlyMissingRawMaterialsAndKeepsOneAuthority() {
		SettlementSimulationState state = state();
		state.setSurvivalEnabled(true, 0);
		state.setProductionEnabled(true);
		state.world().importExternalBatch(ResourceType.WOOD, 8, state.campOwner());
		state.world().importExternalBatch(ResourceType.STICK, 9, state.campOwner());
		SimpleContainer stock = new SimpleContainer(5);
		stock.setItem(0, new ItemStack(Items.OAK_LOG));
		stock.setItem(1, new ItemStack(Items.COAL));
		AtomicInteger dirty = new AtomicInteger();
		assertTrue(SettlementMaterialCrafting.craft(stock, state, SettlementMaterialCrafting.Recipe.CAMPFIRE_COAL, ACTOR, dirty::incrementAndGet));
		assertEquals(1, stock.countItem(Items.CAMPFIRE));
		assertEquals(0, stock.countItem(Items.OAK_LOG));
		assertEquals(0, stock.countItem(Items.STICK));
		assertEquals(6, quantity(state, ResourceType.WOOD));
		assertEquals(6, quantity(state, ResourceType.STICK));
		assertEquals(3, dirty.get());
		state.world().assertConservation();
	}

	@Test
	void cannotExportIntoAFullContainerEvenIfAbstractResourcesExist() {
		SettlementSimulationState state = state();
		state.setSurvivalEnabled(true, 0);
		state.setProductionEnabled(true);
		state.world().importExternalBatch(ResourceType.WOOD, 4, state.campOwner());
		SimpleContainer stock = new SimpleContainer(1);
		stock.setItem(0, new ItemStack(Items.STONE, 64));
		assertFalse(SettlementMaterialCrafting.craft(stock, state, SettlementMaterialCrafting.Recipe.PLANKS, ACTOR, () -> fail("No mutation expected")));
		assertEquals(4, quantity(state, ResourceType.WOOD));
		assertEquals(64, stock.countItem(Items.STONE));
		state.world().assertConservation();
	}

	@Test
	void interruptedExportLeavesTransferredIngredientsWithoutConsumingRecipe() {
		SettlementSimulationState state = state();
		state.setSurvivalEnabled(true, 0);
		state.setProductionEnabled(true);
		state.world().importExternalBatch(ResourceType.WOOD, 3, state.campOwner());
		state.world().importExternalBatch(ResourceType.STICK, 3, state.campOwner());
		AtomicBoolean allow = new AtomicBoolean(true);
		SimpleContainer stock = new SimpleContainer(5) {
			@Override
			public boolean canPlaceItem(int slot, ItemStack stack) { return allow.get(); }
		};
		stock.setItem(0, new ItemStack(Items.COAL));
		assertFalse(SettlementMaterialCrafting.craft(stock, state, SettlementMaterialCrafting.Recipe.CAMPFIRE_COAL, ACTOR, () -> allow.set(false)));
		assertEquals(0, quantity(state, ResourceType.WOOD));
		assertEquals(3, stock.countItem(Items.OAK_LOG));
		assertEquals(3, quantity(state, ResourceType.STICK));
		assertEquals(1, stock.countItem(Items.COAL));
		assertEquals(0, stock.countItem(Items.CAMPFIRE));
		state.world().assertConservation();
	}

	@Test
	void respectsOutputSlotRestrictionsAndCustomStackLimits() {
		SimpleContainer stock = new SimpleContainer(1) {
			@Override
			public int getMaxStackSize() { return 2; }
		};
		stock.setItem(0, new ItemStack(Items.OAK_LOG));
		assertFalse(SettlementMaterialCrafting.craft(stock, state(), SettlementMaterialCrafting.Recipe.PLANKS, ACTOR, () -> {}));
		assertEquals(1, stock.countItem(Items.OAK_LOG));
		SimpleContainer restricted = new SimpleContainer(2) {
			@Override
			public boolean canPlaceItem(int slot, ItemStack stack) { return !stack.is(Items.OAK_PLANKS); }
		};
		restricted.setItem(0, new ItemStack(Items.OAK_LOG));
		assertFalse(SettlementMaterialCrafting.craft(restricted, state(), SettlementMaterialCrafting.Recipe.PLANKS, ACTOR, () -> {}));
		assertEquals(1, restricted.countItem(Items.OAK_LOG));
	}

	@Test
	void selectsBoundedPrerequisiteThenDoorAndStopsAtDemand() {
		SettlementSimulationState state = state();
		SimpleContainer stock = new SimpleContainer(4);
		stock.setItem(0, new ItemStack(Items.OAK_LOG, 20));
		Map<Item, Integer> demand = Map.of(Items.OAK_DOOR, 1);
		for (int i = 0; i < 2; i++) {
			var recipe = SettlementMaterialCrafting.chooseRecipe(stock, state, demand).orElseThrow();
			assertEquals(SettlementMaterialCrafting.Recipe.PLANKS, recipe);
			assertTrue(SettlementMaterialCrafting.craft(stock, state, recipe, ACTOR, () -> {}));
		}
		var recipe = SettlementMaterialCrafting.chooseRecipe(stock, state, demand).orElseThrow();
		assertEquals(SettlementMaterialCrafting.Recipe.DOOR, recipe);
		assertTrue(SettlementMaterialCrafting.craft(stock, state, recipe, ACTOR, () -> {}));
		assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, state, demand).isEmpty());
		assertEquals(3, stock.countItem(Items.OAK_DOOR));
		assertEquals(2, stock.countItem(Items.OAK_PLANKS));
		assertEquals(18, stock.countItem(Items.OAK_LOG));
	}

	@Test
	void skipsUnavailableDemandAndSelectsCharcoalAlternative() {
		SimpleContainer stock = new SimpleContainer(4);
		stock.setItem(0, new ItemStack(Items.CHARCOAL));
		stock.setItem(1, new ItemStack(Items.STICK));
		Map<Item, Integer> demand = new LinkedHashMap<>();
		demand.put(Items.WHITE_BED, 1);
		demand.put(Items.TORCH, 4);
		assertEquals(SettlementMaterialCrafting.Recipe.TORCH_CHARCOAL,
			SettlementMaterialCrafting.chooseRecipe(stock, state(), demand).orElseThrow());
		assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, state(), Map.of()).isEmpty());
	}

	@Test
	void stopsPrerequisiteProductionWhenOtherBedIngredientsRemainUnavailable() {
		SimpleContainer stock = new SimpleContainer(4);
		stock.setItem(0, new ItemStack(Items.OAK_LOG, 20));
		SettlementSimulationState state = state();
		var recipe = SettlementMaterialCrafting.chooseRecipe(stock, state, Map.of(Items.WHITE_BED, 1)).orElseThrow();
		assertTrue(SettlementMaterialCrafting.craft(stock, state, recipe, ACTOR, () -> {}));
		assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, state, Map.of(Items.WHITE_BED, 1)).isEmpty());
		assertEquals(4, stock.countItem(Items.OAK_PLANKS));
	}

	@Test
	void makesSticksBeforeCampfireThenStopsAtDemand() {
		SimpleContainer stock = new SimpleContainer(5);
		stock.setItem(0, new ItemStack(Items.OAK_LOG, 10));
		stock.setItem(1, new ItemStack(Items.COAL, 10));
		SettlementSimulationState state = state();
		Map<Item, Integer> demand = Map.of(Items.CAMPFIRE, 1);
		var prerequisite = SettlementMaterialCrafting.chooseRecipe(stock, state, demand).orElseThrow();
		assertEquals(SettlementMaterialCrafting.Recipe.STICKS, prerequisite);
		assertTrue(SettlementMaterialCrafting.craft(stock, state, prerequisite, ACTOR, () -> {}));
		var recipe = SettlementMaterialCrafting.chooseRecipe(stock, state, demand).orElseThrow();
		assertEquals(SettlementMaterialCrafting.Recipe.CAMPFIRE_COAL, recipe);
		assertTrue(SettlementMaterialCrafting.craft(stock, state, recipe, ACTOR, () -> {}));
		assertEquals(1, stock.countItem(Items.CAMPFIRE));
		assertEquals(5, stock.countItem(Items.STICK));
		assertEquals(6, stock.countItem(Items.OAK_LOG));
		assertEquals(9, stock.countItem(Items.COAL));
		assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, state, demand).isEmpty());
	}

	@Test
	void missingFuelDoesNotProduceUnboundedSticks() {
		for (Item output : new Item[] { Items.CAMPFIRE, Items.TORCH }) {
			SimpleContainer stock = new SimpleContainer(4);
			stock.setItem(0, new ItemStack(Items.OAK_LOG, 20));
			SettlementSimulationState state = state();
			Map<Item, Integer> demand = Map.of(output, 100);
			var recipe = SettlementMaterialCrafting.chooseRecipe(stock, state, demand).orElseThrow();
			assertEquals(SettlementMaterialCrafting.Recipe.STICKS, recipe);
			assertTrue(SettlementMaterialCrafting.craft(stock, state, recipe, ACTOR, () -> {}));
			assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, state, demand).isEmpty());
			assertEquals(8, stock.countItem(Items.STICK));
			assertEquals(19, stock.countItem(Items.OAK_LOG));
		}
	}

	@Test
	void missingStockpileAndDefaultNullStateAreSafe() {
		assertTrue(SettlementMaterialCrafting.chooseRecipe(null, null, Map.of(Items.OAK_PLANKS, 4)).isEmpty());
		assertFalse(SettlementMaterialCrafting.craft(null, null, SettlementMaterialCrafting.Recipe.PLANKS, ACTOR, () -> fail("No mutation expected")));
		SimpleContainer stock = new SimpleContainer(2);
		assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, null, Map.of(Items.OAK_PLANKS, 4)).isEmpty());
		assertFalse(SettlementMaterialCrafting.craft(stock, null, SettlementMaterialCrafting.Recipe.PLANKS, ACTOR, () -> fail("No mutation expected")));
		stock.setItem(0, new ItemStack(Items.OAK_LOG));
		assertEquals(SettlementMaterialCrafting.Recipe.PLANKS,
			SettlementMaterialCrafting.chooseRecipe(stock, null, Map.of(Items.OAK_PLANKS, 4)).orElseThrow());
		assertTrue(SettlementMaterialCrafting.craft(stock, null, SettlementMaterialCrafting.Recipe.PLANKS, ACTOR, () -> {}));
		assertEquals(4, stock.countItem(Items.OAK_PLANKS));
	}

	@Test
	void abstractSupplyRequiresBothExperimentalSwitchesButRealSupplyDoesNot() {
		for (int flags = 0; flags < 3; flags++) {
			SettlementSimulationState state = state();
			state.setSurvivalEnabled((flags & 1) != 0, 0);
			state.setProductionEnabled((flags & 2) != 0);
			state.world().importExternalBatch(ResourceType.WOOD, 10, state.campOwner());
			int eventsBefore = state.world().ledger().events().size();
			SimpleContainer stock = new SimpleContainer(2);
			assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, state, Map.of(Items.OAK_PLANKS, 4)).isEmpty());
			assertFalse(SettlementMaterialCrafting.craft(stock, state, SettlementMaterialCrafting.Recipe.PLANKS, ACTOR, () -> fail("No mutation expected")));
			assertTrue(stock.isEmpty());
			assertEquals(10, quantity(state, ResourceType.WOOD));
			assertEquals(eventsBefore, state.world().ledger().events().size());
			stock.setItem(0, new ItemStack(Items.OAK_LOG));
			assertTrue(SettlementMaterialCrafting.craft(stock, state, SettlementMaterialCrafting.Recipe.PLANKS, ACTOR, () -> {}));
			assertEquals(4, stock.countItem(Items.OAK_PLANKS));
			assertEquals(10, quantity(state, ResourceType.WOOD));
			assertEquals(eventsBefore, state.world().ledger().events().size());
			state.world().assertConservation();
		}
	}

	@Test
	void choosingRecipeDoesNotExportOrMutateEitherInventory() {
		SettlementSimulationState state = state();
		state.setSurvivalEnabled(true, 0);
		state.setProductionEnabled(true);
		state.world().importExternalBatch(ResourceType.WOOD, 10, state.campOwner());
		int eventsBefore = state.world().ledger().events().size();
		SimpleContainer stock = new SimpleContainer(2);
		assertEquals(SettlementMaterialCrafting.Recipe.PLANKS,
			SettlementMaterialCrafting.chooseRecipe(stock, state, Map.of(Items.OAK_PLANKS, 4)).orElseThrow());
		assertTrue(stock.isEmpty());
		assertEquals(10, quantity(state, ResourceType.WOOD));
		assertEquals(eventsBefore, state.world().ledger().events().size());
		state.world().assertConservation();
	}

	@Test
	void disablingProductionDuringHandoffStopsFurtherExportsWithoutConsumingRecipe() {
		SettlementSimulationState state = state();
		state.setSurvivalEnabled(true, 0);
		state.setProductionEnabled(true);
		state.world().importExternalBatch(ResourceType.WOOD, 3, state.campOwner());
		state.world().importExternalBatch(ResourceType.STICK, 3, state.campOwner());
		SimpleContainer stock = new SimpleContainer(5);
		stock.setItem(0, new ItemStack(Items.COAL));
		assertFalse(SettlementMaterialCrafting.craft(stock, state, SettlementMaterialCrafting.Recipe.CAMPFIRE_COAL,
			ACTOR, () -> state.setProductionEnabled(false)));
		assertEquals(3, stock.countItem(Items.OAK_LOG));
		assertEquals(0, quantity(state, ResourceType.WOOD));
		assertEquals(3, quantity(state, ResourceType.STICK));
		assertEquals(0, stock.countItem(Items.STICK));
		assertEquals(1, stock.countItem(Items.COAL));
		assertEquals(0, stock.countItem(Items.CAMPFIRE));
		state.world().assertConservation();
	}

	@Test
	void namedResourcesDoNotHideTheGatheringShortage() {
		SimpleContainer stock = new SimpleContainer(3);
		ItemStack named = new ItemStack(Items.OAK_LOG, 64);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Do not use"));
		stock.setItem(0, named);
		stock.setItem(1, new ItemStack(Items.OAK_LOG, 2));
		assertEquals(2, SettlementMaterialCrafting.availablePlain(stock, null, Items.OAK_LOG));
		SettlementSimulationState state = state();
		state.world().importExternalBatch(ResourceType.WOOD, 3, state.campOwner());
		assertEquals(2, SettlementMaterialCrafting.availablePlain(stock, state, Items.OAK_LOG));
		state.setSurvivalEnabled(true, 0);
		state.setProductionEnabled(true);
		assertEquals(5, SettlementMaterialCrafting.availablePlain(stock, state, Items.OAK_LOG));
		assertEquals(64, stock.getItem(0).getCount());
		state.world().assertConservation();
	}

	@Test
	void completesAndStopsTheWholeHousingSupplyChain() {
		SimpleContainer stock = new SimpleContainer(9);
		SettlementStockpile.insert(stock, new ItemStack(Items.OAK_LOG, 10));
		SettlementStockpile.insert(stock, new ItemStack(Items.WHITE_WOOL, 3));
		SettlementStockpile.insert(stock, new ItemStack(Items.COAL));
		SettlementStockpile.insert(stock, new ItemStack(Items.STICK));
		Map<Item, Integer> demand = new LinkedHashMap<>();
		demand.put(Items.COBBLESTONE, 50);
		demand.put(Items.OAK_PLANKS, 29);
		demand.put(Items.OAK_DOOR, 1);
		demand.put(Items.WHITE_BED, 1);
		demand.put(Items.TORCH, 2);
		int batches = 0;
		while (batches < 30) {
			var recipe = SettlementMaterialCrafting.chooseRecipe(stock, null, demand);
			if (recipe.isEmpty()) { break; }
			assertTrue(SettlementMaterialCrafting.craft(stock, null, recipe.get(), ACTOR, () -> {}));
			batches++;
		}
		assertEquals(13, batches);
		assertEquals(31, stock.countItem(Items.OAK_PLANKS));
		assertEquals(3, stock.countItem(Items.OAK_DOOR));
		assertEquals(1, stock.countItem(Items.WHITE_BED));
		assertEquals(4, stock.countItem(Items.TORCH));
		assertEquals(0, stock.countItem(Items.OAK_LOG));
		assertEquals(0, stock.countItem(Items.WHITE_WOOL));
		assertEquals(0, stock.countItem(Items.COAL));
		assertEquals(0, stock.countItem(Items.STICK));
		assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, null, demand).isEmpty());
	}

	private static long quantity(SettlementSimulationState state, ResourceType type) {
		return state.world().batchesAt(state.campOwner()).stream().filter(batch -> batch.resourceType() == type)
			.mapToLong(batch -> batch.quantity()).sum();
	}
}
