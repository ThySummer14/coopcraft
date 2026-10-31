package com.thysummer.coopcraft.civilization;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementStockpileTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void insertsIntoExistingAndEmptySlots() {
		SimpleContainer stockpile = new SimpleContainer(2);

		assertEquals(64, SettlementStockpile.insert(stockpile, new ItemStack(Items.BREAD, 64)));
		assertEquals(5, SettlementStockpile.insert(stockpile, new ItemStack(Items.BREAD, 5)));
		assertEquals(64, stockpile.getItem(0).getCount());
		assertEquals(5, stockpile.getItem(1).getCount());
	}

	@Test
	void leavesOverflowInSource() {
		SimpleContainer stockpile = new SimpleContainer(1);
		stockpile.setItem(0, new ItemStack(Items.BREAD, 64));

		assertEquals(0, SettlementStockpile.insert(stockpile, new ItemStack(Items.BREAD, 1)));
		assertEquals(64, stockpile.getItem(0).getCount());
	}

	@Test
	void depositsOnlyExcessFood() {
		SimpleContainer villagerInventory = new SimpleContainer(1);
		villagerInventory.setItem(0, new ItemStack(Items.BREAD, 16));
		SimpleContainer stockpile = new SimpleContainer(1);

		assertEquals(13, SettlementStockpile.depositExcessFood(stockpile, villagerInventory));
		assertEquals(3, villagerInventory.getItem(0).getCount());
		assertEquals(13, stockpile.getItem(0).getCount());
	}

	@Test
	void letsSelfishMemberRetainExtraConsumerFood() {
		SimpleContainer villagerInventory = new SimpleContainer(1);
		villagerInventory.setItem(0, new ItemStack(Items.BREAD, 16));
		SimpleContainer stockpile = new SimpleContainer(1);

		assertEquals(10, SettlementStockpile.depositExcessFood(stockpile, villagerInventory, 24));
		assertEquals(6, villagerInventory.getItem(0).getCount());
		assertEquals(10, stockpile.getItem(0).getCount());
		assertEquals(16, villagerInventory.countItem(Items.BREAD) + stockpile.countItem(Items.BREAD));
	}

	@Test
	void keepsFoodNeededByVillager() {
		SimpleContainer villagerInventory = new SimpleContainer(1);
		villagerInventory.setItem(0, new ItemStack(Items.BREAD, 3));
		SimpleContainer stockpile = new SimpleContainer(1);

		assertEquals(0, SettlementStockpile.depositExcessFood(stockpile, villagerInventory));
		assertEquals(3, villagerInventory.getItem(0).getCount());
		assertTrue(stockpile.isEmpty());
	}

	@Test
	void suppliesMissingBasicRations() {
		SimpleContainer villagerInventory = new SimpleContainer(2);
		villagerInventory.setItem(0, new ItemStack(Items.BREAD));
		SimpleContainer stockpile = new SimpleContainer(2);
		stockpile.setItem(0, new ItemStack(Items.BREAD, 10));

		assertEquals(2, SettlementStockpile.distributeBasicFood(stockpile, villagerInventory));
		assertEquals(3, villagerInventory.getItem(0).getCount());
		assertEquals(8, stockpile.getItem(0).getCount());
	}

	@Test
	void suppliesIndividualBasicFoodNeeds() {
		SimpleContainer villagerInventory = new SimpleContainer(2);
		villagerInventory.setItem(0, new ItemStack(Items.BREAD));
		SimpleContainer stockpile = new SimpleContainer(2);
		stockpile.setItem(0, new ItemStack(Items.BREAD, 10));

		assertEquals(3, SettlementStockpile.distributeBasicFood(stockpile, villagerInventory, 16));
		assertEquals(4, villagerInventory.getItem(0).getCount());
		assertEquals(7, stockpile.getItem(0).getCount());
		assertEquals(11, villagerInventory.countItem(Items.BREAD) + stockpile.countItem(Items.BREAD));
	}

	@Test
	void distributesOnlyAvailableFoodDuringShortage() {
		SimpleContainer villagerInventory = new SimpleContainer(1);
		villagerInventory.setItem(0, new ItemStack(Items.BREAD));
		SimpleContainer stockpile = new SimpleContainer(1);
		stockpile.setItem(0, new ItemStack(Items.BREAD));

		assertEquals(1, SettlementStockpile.distributeBasicFood(stockpile, villagerInventory));
		assertEquals(2, villagerInventory.getItem(0).getCount());
		assertTrue(stockpile.isEmpty());
	}

	@Test
	void leavesRationsInStockpileWhenInventoryIsFull() {
		SimpleContainer villagerInventory = new SimpleContainer(1);
		villagerInventory.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
		SimpleContainer stockpile = new SimpleContainer(1);
		stockpile.setItem(0, new ItemStack(Items.BREAD, 3));

		assertEquals(0, SettlementStockpile.distributeBasicFood(stockpile, villagerInventory));
		assertEquals(3, stockpile.countItem(Items.BREAD));
		assertEquals(64, villagerInventory.countItem(Items.COBBLESTONE));
	}

	@Test
	void depositsCommunalResourcesButKeepsPersonalTool() {
		SimpleContainer villagerInventory = new SimpleContainer(4);
		villagerInventory.setItem(0, new ItemStack(Items.STICK, 2));
		villagerInventory.setItem(1, new ItemStack(Items.COBBLESTONE, 3));
		villagerInventory.setItem(2, new ItemStack(Items.SMOOTH_STONE, 2));
		villagerInventory.setItem(3, new ItemStack(Items.STONE_PICKAXE));
		SimpleContainer stockpile = new SimpleContainer(4);

		assertEquals(7, SettlementStockpile.depositGatheredResources(stockpile, villagerInventory));
		assertEquals(2, stockpile.countItem(Items.STICK));
		assertEquals(3, stockpile.countItem(Items.COBBLESTONE));
		assertEquals(2, stockpile.countItem(Items.SMOOTH_STONE));
		assertEquals(1, villagerInventory.countItem(Items.STONE_PICKAXE));
		assertEquals(7, stockpile.countItem(Items.STICK) + stockpile.countItem(Items.COBBLESTONE)
			+ stockpile.countItem(Items.SMOOTH_STONE));
	}

	@Test
	void readsPublicSurplusFromRealContents() {
		SimpleContainer stockpile = new SimpleContainer(3);
		stockpile.setItem(0, new ItemStack(Items.BREAD, 2));
		stockpile.setItem(1, new ItemStack(Items.COBBLESTONE, 4));
		stockpile.setItem(2, new ItemStack(Items.STICK, 3));

		assertEquals(8, SettlementStockpile.communalFoodPoints(stockpile));
		assertEquals(7, SettlementStockpile.communalResourceCount(stockpile));
		assertEquals(0, SettlementStockpile.communalFoodPoints(null));
		assertEquals(0, SettlementStockpile.communalResourceCount(null));
	}
}
