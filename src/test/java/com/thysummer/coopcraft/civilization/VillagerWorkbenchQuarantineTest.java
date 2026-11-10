package com.thysummer.coopcraft.civilization;

import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Container-only quarantine checks, without creating a world, server or villager. */
class VillagerWorkbenchQuarantineTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	private static ItemStack marked(Item item) {
		ItemStack stack = new ItemStack(item, 3);
		CompoundTag root = new CompoundTag();
		root.putString(SettlementWorkbenchCargo.KEY, "unknown_future_format");
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		return stack;
	}

	@Test void countingAndConsumptionSkipMalformedMarkersButPreserveLegacyCustomItems() {
		for (Item item : List.of(Items.COBBLESTONE, Items.STICK, Items.SMOOTH_STONE, Items.OAK_LOG)) {
			ItemStack quarantined = marked(item);
			ItemStack named = new ItemStack(item, 2);
			named.set(DataComponents.CUSTOM_NAME, Component.literal("Legacy custom ingredient"));
			SimpleContainer bag = new SimpleContainer(quarantined.copy(), named, new ItemStack(item, 4));
			assertEquals(6, VillagerWorkGoal.countCraftingItems(bag, item));
			assertEquals(5, VillagerWorkGoal.removeItems(bag, item, 5));
			assertTrue(ItemStack.matches(quarantined, bag.getItem(0)));
			assertEquals(1, VillagerWorkGoal.countCraftingItems(bag, item));
			assertEquals(1, VillagerWorkGoal.removeItems(bag, item, 99));
			assertTrue(ItemStack.matches(quarantined, bag.getItem(0)));
		}
	}

	@Test void warehouseTransferSkipsMarkersAndKeepsComponentsAndPartialRemainders() {
		ItemStack quarantined = marked(Items.COBBLESTONE);
		ItemStack named = new ItemStack(Items.COBBLESTONE, 4);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Legacy"));
		SimpleContainer store = new SimpleContainer(quarantined.copy(), named.copy());
		SimpleContainer bag = new SimpleContainer(1) {
			@Override public int getMaxStackSize() { return 2; }
		};
		assertEquals(2, VillagerWorkGoal.transferCraftingItems(store, bag, Items.COBBLESTONE, 4));
		assertTrue(ItemStack.matches(quarantined, store.getItem(0)));
		assertEquals(2, store.getItem(1).getCount());
		assertEquals(2, bag.getItem(0).getCount());
		assertTrue(ItemStack.isSameItemSameComponents(named, bag.getItem(0)));
		assertEquals(0, VillagerWorkGoal.transferCraftingItems(store, store, Items.COBBLESTONE, 4));
	}
}
