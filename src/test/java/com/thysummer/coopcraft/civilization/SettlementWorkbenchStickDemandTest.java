package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementWorkbenchStickDemandTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void constructionDemandSurvivesLastLogAndStopsWhenDirectOutputIsEnough() {
		var store = new SimpleContainer(3);
		var demand = List.of(Map.of(Items.STICK, 2));
		assertTrue(VillagerWorkGoal.stickDemandStillNeeded(demand, store));
		store.setItem(0, new ItemStack(Items.STICK, 2));
		assertFalse(VillagerWorkGoal.stickDemandStillNeeded(demand, store));
		assertFalse(VillagerWorkGoal.stickDemandStillNeeded(List.of(), store));
		assertFalse(VillagerWorkGoal.stickDemandStillNeeded(demand, null));
	}

	@Test void constructionPrerequisitesUseOneBatchThresholdAndIgnoreFirstToolRecipe() {
		var store = new SimpleContainer(3);
		assertTrue(VillagerWorkGoal.stickDemandStillNeeded(List.of(Map.of(Items.TORCH, 32)), store));
		store.setItem(0, new ItemStack(Items.STICK));
		assertFalse(VillagerWorkGoal.stickDemandStillNeeded(List.of(Map.of(Items.TORCH, 32)), store));
		assertTrue(VillagerWorkGoal.stickDemandStillNeeded(List.of(Map.of(Items.CAMPFIRE, 2)), store));
		store.setItem(0, new ItemStack(Items.STICK, 3));
		assertFalse(VillagerWorkGoal.stickDemandStillNeeded(List.of(Map.of(Items.CAMPFIRE, 2)), store));
		store.clearContent();
		assertFalse(VillagerWorkGoal.stickDemandStillNeeded(List.of(Map.of(Items.STONE_PICKAXE, 1)), store));
		store.setItem(0, new ItemStack(Items.TORCH, 32));
		assertFalse(VillagerWorkGoal.stickDemandStillNeeded(List.of(Map.of(Items.TORCH, 32)), store));
	}

	@Test void toolDemandNeedsNoConstructionAndRetainsTwoStickThreshold() {
		var bag = new SimpleContainer(3);
		assertTrue(VillagerWorkGoal.toolStickDemandStillNeeded(bag, ItemStack.EMPTY, ItemStack.EMPTY, false, 0, s -> false));
		assertTrue(VillagerWorkGoal.toolStickDemandStillNeeded(bag, ItemStack.EMPTY, ItemStack.EMPTY, false, 1, s -> false));
		assertFalse(VillagerWorkGoal.toolStickDemandStillNeeded(bag, ItemStack.EMPTY, ItemStack.EMPTY, false, 2, s -> false));
		assertFalse(VillagerWorkGoal.toolStickDemandStillNeeded(bag, new ItemStack(Items.DIRT), ItemStack.EMPTY, true, 0, s -> false));
	}

	@Test void readOnlyToolDemandDoesNotEquipOrMutateExistingStonePickaxe() {
		var bag = new SimpleContainer(3);
		bag.setItem(0, new ItemStack(Items.STONE_PICKAXE));
		var actual = bag.getItem(0);
		assertFalse(VillagerWorkGoal.toolStickDemandStillNeeded(bag, ItemStack.EMPTY, ItemStack.EMPTY, false, 0, s -> false));
		assertTrue(VillagerWorkGoal.toolStickDemandStillNeeded(bag, ItemStack.EMPTY, ItemStack.EMPTY, true, 0, s -> false));
		assertFalse(VillagerWorkGoal.toolStickDemandStillNeeded(bag, ItemStack.EMPTY, ItemStack.EMPTY, true, 0, s -> true));
		assertSame(actual, bag.getItem(0));
		assertEquals(1, actual.getCount());
	}

	@Test void otherOrPurposeMarkedPickaxesPreventRedundantFirstToolIngredients() {
		var bag = new SimpleContainer(3);
		bag.setItem(0, new ItemStack(Items.IRON_PICKAXE));
		assertFalse(VillagerWorkGoal.toolStickDemandStillNeeded(bag, ItemStack.EMPTY, ItemStack.EMPTY, true, 0, s -> false));
		bag.clearContent();
		assertFalse(VillagerWorkGoal.toolStickDemandStillNeeded(bag, ItemStack.EMPTY, new ItemStack(Items.IRON_PICKAXE), true, 0, s -> false));
		bag.setItem(0, SettlementWorkbenchCargo.output(UUID.randomUUID(), UUID.randomUUID(), SettlementMaterialCrafting.Recipe.STONE_PICKAXE));
		assertFalse(VillagerWorkGoal.toolStickDemandStillNeeded(bag, ItemStack.EMPTY, ItemStack.EMPTY, true, 0, s -> false));
	}
}
