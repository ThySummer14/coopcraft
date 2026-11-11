package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
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

class SettlementWorkbenchWoolDemandTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void lastFourStringCanLeaveTheStoreWithoutInvalidatingDemand() {
		var store = new SimpleContainer(3);
		store.setItem(0, new ItemStack(Items.STRING, 4));
		store.setItem(1, new ItemStack(Items.WHITE_WOOL, 2));
		assertTrue(needs(store, Items.WHITE_BED, 1));
		assertEquals(4, store.removeItem(0, 4).getCount());
		assertTrue(needs(store, Items.WHITE_BED, 1));
		assertEquals(0, store.countItem(Items.STRING));
		assertEquals(2, store.countItem(Items.WHITE_WOOL));
	}

	@Test
	void nextBedLimitStopsAtThreeEvenWhenTheTotalBudgetStillHasAShortage() {
		var store = new SimpleContainer(2);
		store.setItem(0, new ItemStack(Items.WHITE_WOOL, 3));
		store.setItem(1, new ItemStack(Items.STRING, 64));
		assertEquals(3, SettlementWoolSupply.inspect(Map.of(Items.WHITE_BED, 2), store).orElseThrow().missingWool());
		assertFalse(needs(store, Items.WHITE_BED, 2));
		store.removeItem(0, 1);
		assertTrue(needs(store, Items.WHITE_BED, 2));
	}

	@Test
	void publicBedCompletionOrDemandRemovalStopsWork() {
		var store = new SimpleContainer(2);
		assertTrue(needs(store, Items.WHITE_BED, 1));
		store.setItem(0, new ItemStack(Items.WHITE_BED));
		assertFalse(needs(store, Items.WHITE_BED, 1));
		assertTrue(needs(store, Items.WHITE_BED, 2));
		assertFalse(needs(store, Items.WHITE_BED, 0));
		assertFalse(needs(store, Items.WHITE_WOOL, 64));
		assertFalse(needs(store, Items.RED_BED, 2));
		assertFalse(SettlementWoolSupply.nextBedNeedsWool(List.of(), store, null));
		assertFalse(SettlementWoolSupply.nextBedNeedsWool(List.of(Map.of(Items.WHITE_BED, 1)), null, null));
	}

	@Test
	void namedColoredAndCustodiedStockCannotSatisfyPlainPublicDemand() {
		var store = new SimpleContainer(6);
		var namedBed = new ItemStack(Items.WHITE_BED);
		namedBed.set(DataComponents.CUSTOM_NAME, Component.literal("Private bed"));
		store.setItem(0, namedBed);
		var namedWool = new ItemStack(Items.WHITE_WOOL, 64);
		namedWool.set(DataComponents.CUSTOM_NAME, Component.literal("Private wool"));
		store.setItem(1, namedWool);
		store.setItem(2, new ItemStack(Items.RED_WOOL, 64));
		store.setItem(3, new ItemStack(Items.RED_BED));
		store.setItem(4, SettlementWorkbenchCargo.output(UUID.randomUUID(), UUID.randomUUID(), SettlementMaterialCrafting.Recipe.BED));
		store.setItem(5, SettlementWorkbenchCargo.output(UUID.randomUUID(), UUID.randomUUID(), SettlementMaterialCrafting.Recipe.WOOL));
		assertTrue(needs(store, Items.WHITE_BED, 1));
		store.setItem(5, new ItemStack(Items.WHITE_WOOL, 3));
		assertFalse(needs(store, Items.WHITE_BED, 1));
	}

	@Test
	void projectDemandsRemainIndependentRatherThanBeingSummed() {
		var store = new SimpleContainer(new ItemStack(Items.WHITE_BED));
		assertFalse(SettlementWoolSupply.nextBedNeedsWool(
			List.of(Map.of(Items.WHITE_BED, 1), Map.of(Items.WHITE_BED, 1)), store, null));
		assertTrue(SettlementWoolSupply.nextBedNeedsWool(
			List.of(Map.of(Items.WHITE_BED, 1), Map.of(Items.WHITE_BED, 2)), store, null));
	}

	private static boolean needs(SimpleContainer store, Item item, int amount) {
		return SettlementWoolSupply.nextBedNeedsWool(List.of(Map.of(item, amount)), store, null);
	}
}
