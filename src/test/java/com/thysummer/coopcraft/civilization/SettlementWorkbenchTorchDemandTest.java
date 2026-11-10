package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Calls the production Goal's output-demand gate, without constructing a world or actor. */
class SettlementWorkbenchTorchDemandTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void eitherLastFuelAndStickCanLeaveWithoutInvalidatingPublicDemand() {
		for (var fuel : List.of(Items.COAL, Items.CHARCOAL)) {
			var store = new SimpleContainer(new ItemStack(fuel), new ItemStack(Items.STICK));
			assertTrue(needs(store, 1));
			assertEquals(1, store.removeItem(0, 1).getCount());
			assertEquals(1, store.removeItem(1, 1).getCount());
			assertTrue(needs(store, 1));
			assertTrue(store.isEmpty());
		}
	}

	@Test
	void publicOutputCompletionOrDemandRemovalStopsWork() {
		var store = new SimpleContainer(new ItemStack(Items.TORCH, 4));
		assertFalse(needs(store, 4));
		assertTrue(needs(store, 5));
		assertFalse(needs(store, 0));
		assertFalse(VillagerWorkGoal.torchDemandStillNeeded(List.of(), store));
		assertFalse(VillagerWorkGoal.torchDemandStillNeeded(List.of(Map.of(Items.TORCH, 4)), null));
		assertFalse(VillagerWorkGoal.torchDemandStillNeeded(List.of(Map.of(Items.CAMPFIRE, 4)), store));
	}

	@Test
	void namedAndCustodiedOutputsDoNotSatisfyPlainPublicDemand() {
		var named = new ItemStack(Items.TORCH, 64);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Private torch"));
		var store = new SimpleContainer(named,
			SettlementWorkbenchCargo.output(UUID.randomUUID(), UUID.randomUUID(), SettlementMaterialCrafting.Recipe.TORCH_COAL),
			SettlementWorkbenchCargo.output(UUID.randomUUID(), UUID.randomUUID(), SettlementMaterialCrafting.Recipe.TORCH_CHARCOAL));
		assertTrue(needs(store, 1));
		store.setItem(0, new ItemStack(Items.TORCH));
		assertFalse(needs(store, 1));
	}

	@Test
	void projectDemandsStayIndependentRatherThanBeingSummed() {
		var store = new SimpleContainer(new ItemStack(Items.TORCH, 4));
		assertFalse(VillagerWorkGoal.torchDemandStillNeeded(List.of(Map.of(Items.TORCH, 4), Map.of(Items.TORCH, 4)), store));
		assertTrue(VillagerWorkGoal.torchDemandStillNeeded(List.of(Map.of(Items.TORCH, 4), Map.of(Items.TORCH, 5)), store));
	}

	@Test
	void existingDemandSelectorPinsCoalThenCharcoalWithoutCrossFuelSubstitution() {
		var store = new SimpleContainer(new ItemStack(Items.COAL), new ItemStack(Items.CHARCOAL), new ItemStack(Items.STICK));
		var demand = Map.of(Items.TORCH, 1);
		assertEquals(SettlementMaterialCrafting.Recipe.TORCH_COAL,
			SettlementMaterialCrafting.chooseRecipe(store, null, demand).orElseThrow());
		store.removeItem(0, 1);
		assertEquals(SettlementMaterialCrafting.Recipe.TORCH_CHARCOAL,
			SettlementMaterialCrafting.chooseRecipe(store, null, demand).orElseThrow());
		store.removeItem(1, 1);
		assertTrue(SettlementMaterialCrafting.chooseRecipe(store, null, demand).isEmpty());
		assertTrue(needs(store, 1));
	}

	private static boolean needs(SimpleContainer store, int count) {
		return VillagerWorkGoal.torchDemandStillNeeded(List.of(Map.of(Items.TORCH, count)), store);
	}
}
