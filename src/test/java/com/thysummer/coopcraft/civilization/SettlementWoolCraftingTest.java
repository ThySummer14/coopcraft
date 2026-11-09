package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.StorageOwner;

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

/** Real container transactions; the existing work goal remains responsible for the workshop gate. */
class SettlementWoolCraftingTest {
	private static final UUID ACTOR = new UUID(71, 1);
	private static final Map<Item, Integer> BED_DEMAND = Map.of(Items.WHITE_BED, 1);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static SettlementSimulationState state() {
		return SettlementSimulationState.create(new Settlement(UUID.randomUUID(), BlockPos.ZERO, "Wool", 0));
	}

	private static SimpleContainer stock(int string, int wool, int planks) {
		SimpleContainer stock = new SimpleContainer(9);
		if (string > 0) { stock.setItem(0, new ItemStack(Items.STRING, string)); }
		if (wool > 0) { stock.setItem(1, new ItemStack(Items.WHITE_WOOL, wool)); }
		if (planks > 0) { stock.setItem(2, new ItemStack(Items.OAK_PLANKS, planks)); }
		return stock;
	}

	private static ItemStack named(Item item, int count) {
		ItemStack stack = new ItemStack(item, count);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Keep this"));
		return stack;
	}

	private static void craftNext(SimpleContainer stock, SettlementSimulationState state,
		Map<Item, Integer> demand, SettlementMaterialCrafting.Recipe expected) {
		assertEquals(expected, SettlementMaterialCrafting.chooseRecipe(stock, state, demand).orElseThrow());
		assertTrue(SettlementMaterialCrafting.craft(stock, state, expected, ACTOR, () -> {}));
	}

	@Test
	void makesThreeSingleWoolBatchesThenOneBedAndStops() {
		SimpleContainer stock = stock(32, 0, 3);
		for (int i = 1; i <= 3; i++) {
			craftNext(stock, null, BED_DEMAND, SettlementMaterialCrafting.Recipe.WOOL);
			assertEquals(i, stock.countItem(Items.WHITE_WOOL));
			assertEquals(32 - 4 * i, stock.countItem(Items.STRING));
			assertEquals(0, stock.countItem(Items.WHITE_BED));
		}
		craftNext(stock, null, BED_DEMAND, SettlementMaterialCrafting.Recipe.BED);
		assertEquals(1, stock.countItem(Items.WHITE_BED));
		assertEquals(0, stock.countItem(Items.WHITE_WOOL));
		assertEquals(0, stock.countItem(Items.OAK_PLANKS));
		assertEquals(20, stock.countItem(Items.STRING));
		assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, null, BED_DEMAND).isEmpty());
	}

	@Test
	void missingPlanksCapsWoolAtOneBedEvenWithLargeBedDemand() {
		SimpleContainer stock = stock(64, 0, 0);
		Map<Item, Integer> demand = Map.of(Items.WHITE_BED, 100);
		for (int i = 0; i < 3; i++) {
			craftNext(stock, null, demand, SettlementMaterialCrafting.Recipe.WOOL);
		}
		assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, null, demand).isEmpty());
		assertEquals(3, stock.countItem(Items.WHITE_WOOL));
		assertEquals(52, stock.countItem(Items.STRING));
	}

	@Test
	void reusesEachPossiblePartialWoolSupply() {
		for (int existing = 1; existing <= 3; existing++) {
			SimpleContainer stock = stock(20, existing, 3);
			for (int i = existing; i < 3; i++) {
				craftNext(stock, null, BED_DEMAND, SettlementMaterialCrafting.Recipe.WOOL);
			}
			craftNext(stock, null, BED_DEMAND, SettlementMaterialCrafting.Recipe.BED);
			assertEquals(20 - (3 - existing) * 4, stock.countItem(Items.STRING));
			assertEquals(0, stock.countItem(Items.WHITE_WOOL));
			assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, null, BED_DEMAND).isEmpty());
		}
	}

	@Test
	void excessWoolAndExistingFinishedBedTakePriorityOverString() {
		SimpleContainer stock = stock(64, 6, 6);
		craftNext(stock, null, BED_DEMAND, SettlementMaterialCrafting.Recipe.BED);
		assertEquals(64, stock.countItem(Items.STRING));
		assertEquals(3, stock.countItem(Items.WHITE_WOOL));
		assertEquals(3, stock.countItem(Items.OAK_PLANKS));
		assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, null, BED_DEMAND).isEmpty());
	}

	@Test
	void absentZeroNegativeAndStandaloneWoolDemandNeverStockpileWool() {
		SimpleContainer stock = stock(64, 0, 3);
		List<Map<Item, Integer>> demands = List.of(Map.of(), Map.of(Items.WHITE_BED, 0),
			Map.of(Items.WHITE_BED, -1), Map.of(Items.WHITE_WOOL, 64), Map.of(Items.RED_BED, 1));
		for (Map<Item, Integer> demand : demands) {
			assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, null, demand).isEmpty());
		}
		assertEquals(64, stock.countItem(Items.STRING));
		assertEquals(0, stock.countItem(Items.WHITE_WOOL));
	}

	@Test
	void disappearingDemandStopsAtTheAlreadyCompletedBatch() {
		SimpleContainer stock = stock(64, 0, 3);
		craftNext(stock, null, BED_DEMAND, SettlementMaterialCrafting.Recipe.WOOL);
		assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, null, Map.of()).isEmpty());
		stock.setItem(3, new ItemStack(Items.WHITE_BED));
		assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, null, BED_DEMAND).isEmpty());
		assertEquals(60, stock.countItem(Items.STRING));
		assertEquals(1, stock.countItem(Items.WHITE_WOOL));
	}

	@Test
	void fewerThanFourPlainStringCannotStartOrPartiallyConsume() {
		for (int count = 0; count < 4; count++) {
			SimpleContainer stock = stock(count, 0, 3);
			assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, null, BED_DEMAND).isEmpty());
			assertFalse(SettlementMaterialCrafting.craft(stock, null, SettlementMaterialCrafting.Recipe.WOOL,
				ACTOR, () -> fail("A failed recipe must not dirty the stockpile")));
			assertEquals(count, stock.countItem(Items.STRING));
			assertEquals(0, stock.countItem(Items.WHITE_WOOL));
		}
	}

	@Test
	void namedStringDoesNotFillAPlainStringShortage() {
		SimpleContainer stock = stock(3, 0, 3);
		stock.setItem(3, named(Items.STRING, 64));
		assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, null, BED_DEMAND).isEmpty());
		assertFalse(SettlementMaterialCrafting.craft(stock, null, SettlementMaterialCrafting.Recipe.WOOL,
			ACTOR, () -> fail("Named string must not be consumed")));
		assertEquals(3, stock.getItem(0).getCount());
		assertEquals(64, stock.getItem(3).getCount());
		assertNotNull(stock.getItem(3).get(DataComponents.CUSTOM_NAME));
	}

	@Test
	void mixedStringConsumesOnlyTheFourPlainUnitsAcrossSlots() {
		SimpleContainer stock = stock(2, 0, 3);
		stock.setItem(3, named(Items.STRING, 64));
		stock.setItem(4, new ItemStack(Items.STRING, 2));
		craftNext(stock, null, BED_DEMAND, SettlementMaterialCrafting.Recipe.WOOL);
		assertEquals(0, SettlementMaterialCrafting.countPlain(stock, Items.STRING));
		assertEquals(64, stock.getItem(3).getCount());
		assertNotNull(stock.getItem(3).get(DataComponents.CUSTOM_NAME));
		assertEquals(1, stock.countItem(Items.WHITE_WOOL));
	}

	@Test
	void namedAndColoredWoolDoNotReplacePlainWhiteWool() {
		SimpleContainer stock = stock(12, 0, 3);
		stock.setItem(3, named(Items.WHITE_WOOL, 3));
		stock.setItem(4, new ItemStack(Items.RED_WOOL, 3));
		for (int i = 0; i < 3; i++) {
			craftNext(stock, null, BED_DEMAND, SettlementMaterialCrafting.Recipe.WOOL);
		}
		craftNext(stock, null, BED_DEMAND, SettlementMaterialCrafting.Recipe.BED);
		assertEquals(3, stock.getItem(3).getCount());
		assertNotNull(stock.getItem(3).get(DataComponents.CUSTOM_NAME));
		assertEquals(3, stock.countItem(Items.RED_WOOL));
		assertEquals(1, stock.countItem(Items.WHITE_BED));
	}

	@Test
	void namedFinishedBedFullySatisfiesConstructionWithoutAnyCrafting() {
		SimpleContainer stock = stock(64, 3, 3);
		stock.setItem(3, named(Items.WHITE_BED, 1));
		assertEquals(1, ConstructionMaterials.available(stock, null, Items.WHITE_BED));
		assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, null, BED_DEMAND).isEmpty());
		assertEquals(64, stock.countItem(Items.STRING));
		assertEquals(3, stock.countItem(Items.WHITE_WOOL));
		assertEquals(3, stock.countItem(Items.OAK_PLANKS));
		assertNotNull(stock.getItem(3).get(DataComponents.CUSTOM_NAME));
		// No phantom log shortage after construction's actual bed demand is fulfilled.
		stock.setItem(2, ItemStack.EMPTY);
		assertFalse(SettlementMaterialCrafting.needsConstructionLogs(stock, new SimpleContainer(1), null,
			List.of(BED_DEMAND), true));
	}

	@Test
	void namedBedPartiallySatisfiesDemandAndOnlyOneMissingBedIsProduced() {
		SimpleContainer stock = stock(64, 2, 6);
		stock.setItem(3, named(Items.WHITE_BED, 1));
		Map<Item, Integer> demand = Map.of(Items.WHITE_BED, 2);
		craftNext(stock, null, demand, SettlementMaterialCrafting.Recipe.WOOL);
		craftNext(stock, null, demand, SettlementMaterialCrafting.Recipe.BED);
		assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, null, demand).isEmpty());
		assertEquals(2, stock.countItem(Items.WHITE_BED));
		assertEquals(1, SettlementMaterialCrafting.countPlain(stock, Items.WHITE_BED));
		assertEquals(60, stock.countItem(Items.STRING));
		assertEquals(3, stock.countItem(Items.OAK_PLANKS));
		assertNotNull(stock.getItem(3).get(DataComponents.CUSTOM_NAME));
	}

	@Test
	void fullOutputCannotConsumeButAnExactlyFreedInputSlotCanBeUsed() {
		SimpleContainer stock = new SimpleContainer(1);
		stock.setItem(0, new ItemStack(Items.STRING, 5));
		assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, null, BED_DEMAND).isEmpty());
		assertFalse(SettlementMaterialCrafting.craft(stock, null, SettlementMaterialCrafting.Recipe.WOOL,
			ACTOR, () -> fail("No room for the output")));
		assertEquals(5, stock.countItem(Items.STRING));
		stock.setItem(0, new ItemStack(Items.STRING, 4));
		AtomicInteger dirty = new AtomicInteger();
		assertTrue(SettlementMaterialCrafting.craft(stock, null, SettlementMaterialCrafting.Recipe.WOOL,
			ACTOR, dirty::incrementAndGet));
		assertEquals(1, stock.countItem(Items.WHITE_WOOL));
		assertEquals(0, stock.countItem(Items.STRING));
		assertEquals(1, dirty.get());
	}

	@Test
	void fullBedOutputDoesNotMakeMoreWoolOrConsumeIngredients() {
		SimpleContainer stock = new SimpleContainer(3);
		stock.setItem(0, new ItemStack(Items.STRING, 64));
		stock.setItem(1, new ItemStack(Items.WHITE_WOOL, 4));
		stock.setItem(2, new ItemStack(Items.OAK_PLANKS, 4));
		assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, null, BED_DEMAND).isEmpty());
		assertFalse(SettlementMaterialCrafting.craft(stock, null, SettlementMaterialCrafting.Recipe.BED,
			ACTOR, () -> fail("No room for bed")));
		assertEquals(64, stock.countItem(Items.STRING));
		assertEquals(4, stock.countItem(Items.WHITE_WOOL));
		assertEquals(4, stock.countItem(Items.OAK_PLANKS));
	}

	@Test
	void outputSlotRestrictionsAndChangedInputsFailWithoutHalfConsumption() {
		SimpleContainer restricted = new SimpleContainer(4) {
			@Override
			public boolean canPlaceItem(int slot, ItemStack stack) { return !stack.is(Items.WHITE_WOOL); }
		};
		restricted.setItem(0, new ItemStack(Items.STRING, 4));
		restricted.setItem(1, new ItemStack(Items.OAK_PLANKS, 3));
		assertTrue(SettlementMaterialCrafting.chooseRecipe(restricted, null, BED_DEMAND).isEmpty());
		assertFalse(SettlementMaterialCrafting.craft(restricted, null, SettlementMaterialCrafting.Recipe.WOOL,
			ACTOR, () -> fail("Restricted output")));
		assertEquals(4, restricted.countItem(Items.STRING));
		SimpleContainer stock = stock(4, 0, 3);
		var chosen = SettlementMaterialCrafting.chooseRecipe(stock, null, BED_DEMAND).orElseThrow();
		stock.removeItem(0, 1);
		assertFalse(SettlementMaterialCrafting.craft(stock, null, chosen, ACTOR, () -> fail("Stale ingredients")));
		assertEquals(3, stock.countItem(Items.STRING));
		assertEquals(0, stock.countItem(Items.WHITE_WOOL));
	}

	@Test
	void customStackLimitAllowsOnlyAvailableWoolCapacity() {
		SimpleContainer stock = new SimpleContainer(3) {
			@Override
			public int getMaxStackSize() { return 2; }
		};
		stock.setItem(0, new ItemStack(Items.STRING, 2));
		stock.setItem(1, new ItemStack(Items.STRING, 2));
		stock.setItem(2, new ItemStack(Items.WHITE_WOOL, 2));
		craftNext(stock, null, BED_DEMAND, SettlementMaterialCrafting.Recipe.WOOL);
		assertEquals(3, stock.countItem(Items.WHITE_WOOL));
		for (int slot = 0; slot < stock.getContainerSize(); slot++) {
			assertTrue(stock.getItem(slot).getCount() <= 2);
		}
	}

	@Test
	void realStringWorksWithEverySimulationSwitchCombinationWithoutLedgerEntries() {
		for (int flags = 0; flags < 4; flags++) {
			SettlementSimulationState state = state();
			state.setSurvivalEnabled((flags & 1) != 0, 0);
			state.setProductionEnabled((flags & 2) != 0);
			state.world().importExternalBatch(ResourceType.WOOD, 20, state.campOwner());
			state.world().importExternalBatch(ResourceType.STICK, 20, state.campOwner());
			var totals = Map.copyOf(state.world().inventoryTotals());
			int events = state.world().ledger().events().size();
			SimpleContainer stock = stock(12, 0, 3);
			for (int i = 0; i < 3; i++) {
				craftNext(stock, state, BED_DEMAND, SettlementMaterialCrafting.Recipe.WOOL);
			}
			craftNext(stock, state, BED_DEMAND, SettlementMaterialCrafting.Recipe.BED);
			assertEquals(totals, state.world().inventoryTotals());
			assertEquals(events, state.world().ledger().events().size());
			assertEquals(1, stock.countItem(Items.WHITE_BED));
			assertEquals(0, stock.countItem(Items.STRING));
			state.world().assertConservation();
		}
	}

	@Test
	void abstractResourcesCannotInventStringOrWool() {
		SettlementSimulationState state = state();
		state.setSurvivalEnabled(true, 0);
		state.setProductionEnabled(true);
		for (ResourceType type : ResourceType.values()) {
			state.world().importExternalBatch(type, 64, state.campOwner());
		}
		var totals = Map.copyOf(state.world().inventoryTotals());
		int events = state.world().ledger().events().size();
		SimpleContainer stock = stock(0, 0, 3);
		assertTrue(MinecraftStockpileAdapter.resourceType(new ItemStack(Items.STRING)).isEmpty());
		assertTrue(MinecraftStockpileAdapter.resourceType(new ItemStack(Items.WHITE_WOOL)).isEmpty());
		assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, state, BED_DEMAND).isEmpty());
		for (var recipe : List.of(SettlementMaterialCrafting.Recipe.WOOL, SettlementMaterialCrafting.Recipe.BED)) {
			assertFalse(SettlementMaterialCrafting.craft(stock, state, recipe, ACTOR, () -> fail("No real string or wool")));
		}
		assertEquals(0, SettlementMaterialCrafting.availablePlain(stock, state, Items.STRING));
		assertEquals(0, SettlementMaterialCrafting.availablePlain(stock, state, Items.WHITE_WOOL));
		assertEquals(totals, state.world().inventoryTotals());
		assertEquals(events, state.world().ledger().events().size());
		state.world().assertConservation();
	}

	@Test
	void choosingWoolIsReadOnlyAndNullStockpileIsSafe() {
		SimpleContainer stock = stock(4, 2, 3);
		SettlementSimulationState state = state();
		int events = state.world().ledger().events().size();
		assertEquals(SettlementMaterialCrafting.Recipe.WOOL,
			SettlementMaterialCrafting.chooseRecipe(stock, state, BED_DEMAND).orElseThrow());
		assertEquals(4, stock.countItem(Items.STRING));
		assertEquals(2, stock.countItem(Items.WHITE_WOOL));
		assertEquals(events, state.world().ledger().events().size());
		assertTrue(SettlementMaterialCrafting.chooseRecipe(null, state, BED_DEMAND).isEmpty());
		assertFalse(SettlementMaterialCrafting.craft(null, state, SettlementMaterialCrafting.Recipe.WOOL,
			ACTOR, () -> fail("No stockpile")));
	}

	@Test
	void publicLogExportsOnceWhilePrivateAbstractResourcesRemainUntouched() {
		SettlementSimulationState state = state();
		state.setSurvivalEnabled(true, 0);
		state.setProductionEnabled(true);
		state.mapResident(ACTOR, false, 0);
		String person = state.residentId(ACTOR).orElseThrow();
		StorageOwner privateOwner = StorageOwner.person(person);
		StorageOwner householdOwner = StorageOwner.household(state.world().person(person).householdId());
		state.world().importExternalBatch(ResourceType.WOOD, 9, privateOwner);
		state.world().importExternalBatch(ResourceType.WOOD, 7, householdOwner);
		SimpleContainer stock = stock(12, 0, 0);
		assertTrue(SettlementMaterialCrafting.needsConstructionLogs(stock, new SimpleContainer(1), state,
			List.of(BED_DEMAND), true));
		assertFalse(SettlementMaterialCrafting.needsConstructionLogs(stock, new SimpleContainer(1), state,
			List.of(BED_DEMAND), false));
		for (int i = 0; i < 3; i++) {
			craftNext(stock, state, BED_DEMAND, SettlementMaterialCrafting.Recipe.WOOL);
		}
		assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, state, BED_DEMAND).isEmpty());
		state.world().importExternalBatch(ResourceType.WOOD, 1, state.campOwner());
		craftNext(stock, state, BED_DEMAND, SettlementMaterialCrafting.Recipe.PLANKS);
		int eventsAfterExport = state.world().ledger().events().size();
		craftNext(stock, state, BED_DEMAND, SettlementMaterialCrafting.Recipe.BED);
		assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, state, BED_DEMAND).isEmpty());
		assertEquals(eventsAfterExport, state.world().ledger().events().size());
		assertEquals(0, state.world().batchesAt(state.campOwner()).stream().mapToLong(batch -> batch.quantity()).sum());
		assertEquals(9, state.world().batchesAt(privateOwner).stream().mapToLong(batch -> batch.quantity()).sum());
		assertEquals(7, state.world().batchesAt(householdOwner).stream().mapToLong(batch -> batch.quantity()).sum());
		assertEquals(1, stock.countItem(Items.WHITE_BED));
		assertEquals(1, stock.countItem(Items.OAK_PLANKS));
		state.world().assertConservation();
	}

	@Test
	void otherFinishedProductsKeepTheirExistingPlainCountingPolicy() {
		SimpleContainer stock = new SimpleContainer(4);
		stock.setItem(0, named(Items.OAK_DOOR, 1));
		stock.setItem(1, new ItemStack(Items.OAK_PLANKS, 6));
		craftNext(stock, null, Map.of(Items.OAK_DOOR, 1), SettlementMaterialCrafting.Recipe.DOOR);
		assertEquals(4, stock.countItem(Items.OAK_DOOR));
		assertNotNull(stock.getItem(0).get(DataComponents.CUSTOM_NAME));
	}
}
