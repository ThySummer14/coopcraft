package com.thysummer.coopcraft.civilization;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementStockpileTransferTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void fullInventoryLeavesEverySourceComponentUntouched() {
		SimpleContainer source = new SimpleContainer(decorated(5, "A"));
		SimpleContainer target = new SimpleContainer(new ItemStack(Items.DIRT, 64));
		ItemStack before = source.getItem(0).copy();
		ItemStack targetBefore = target.getItem(0).copy();

		assertEquals(0, SettlementStockpile.transferItem(source, target, Items.COBBLESTONE, 3));
		assertStack(before, source.getItem(0));
		assertStack(targetBefore, target.getItem(0));
	}

	@Test
	void partialCapacityDeductsOnlyActuallyAcceptedItems() {
		SimpleContainer source = new SimpleContainer(decorated(5, "A"));
		SimpleContainer target = new SimpleContainer(decorated(63, "A"));

		assertEquals(1, SettlementStockpile.transferItem(source, target, Items.COBBLESTONE, 3));
		assertStack(decorated(4, "A"), source.getItem(0));
		assertStack(decorated(64, "A"), target.getItem(0));
		assertEquals(68, source.countItem(Items.COBBLESTONE) + target.countItem(Items.COBBLESTONE));
		// A failed craft may retry later; the already moved item is still in the inventory.
		assertEquals(0, SettlementStockpile.transferItem(source, target, Items.COBBLESTONE, 2));
		assertStack(decorated(4, "A"), source.getItem(0));
		assertStack(decorated(64, "A"), target.getItem(0));
	}

	@Test
	void differentComponentsNeverMergeIntoExistingStack() {
		SimpleContainer source = new SimpleContainer(decorated(3, "A"));
		SimpleContainer target = new SimpleContainer(decorated(1, "B"));

		assertEquals(0, SettlementStockpile.transferItem(source, target, Items.COBBLESTONE, 3));
		assertStack(decorated(3, "A"), source.getItem(0));
		assertStack(decorated(1, "B"), target.getItem(0));
	}

	@Test
	void movesMultipleMetadataStacksOnceAndHonorsRequestedTotal() {
		SimpleContainer source = new SimpleContainer(decorated(2, "A"), decorated(3, "B"), decorated(4, "C"));
		SimpleContainer target = new SimpleContainer(3);

		assertEquals(4, SettlementStockpile.transferItem(source, target, Items.COBBLESTONE, 4));
		assertTrue(source.getItem(0).isEmpty());
		assertStack(decorated(1, "B"), source.getItem(1));
		assertStack(decorated(4, "C"), source.getItem(2));
		assertStack(decorated(2, "A"), target.getItem(0));
		assertStack(decorated(2, "B"), target.getItem(1));
		assertTrue(target.getItem(2).isEmpty());
		assertEquals(9, source.countItem(Items.COBBLESTONE) + target.countItem(Items.COBBLESTONE));
	}

	@Test
	void skipsUnacceptedMetadataButCanMoveLaterCompatibleStack() {
		SimpleContainer source = new SimpleContainer(decorated(2, "A"), decorated(3, "B"));
		SimpleContainer target = new SimpleContainer(decorated(63, "B"));

		assertEquals(1, SettlementStockpile.transferItem(source, target, Items.COBBLESTONE, 4));
		assertStack(decorated(2, "A"), source.getItem(0));
		assertStack(decorated(2, "B"), source.getItem(1));
		assertStack(decorated(64, "B"), target.getItem(0));
	}

	@Test
	void plainStacksStillMoveAndUnrelatedItemsStayPut() {
		SimpleContainer source = new SimpleContainer(new ItemStack(Items.STICK, 4), new ItemStack(Items.DIRT, 8));
		SimpleContainer target = new SimpleContainer(2);

		assertEquals(3, SettlementStockpile.transferItem(source, target, Items.STICK, 3));
		assertStack(new ItemStack(Items.STICK), source.getItem(0));
		assertStack(new ItemStack(Items.STICK, 3), target.getItem(0));
		assertStack(new ItemStack(Items.DIRT, 8), source.getItem(1));
		assertEquals(0, SettlementStockpile.transferItem(source, target, Items.SMOOTH_STONE, 2));
		assertStack(new ItemStack(Items.STICK, 3), target.getItem(0));
	}

	@Test
	void sourceShortageReturnsActualMovedAmountWithoutDuplication() {
		SimpleContainer source = new SimpleContainer(decorated(2, "A"));
		SimpleContainer target = new SimpleContainer(2);

		assertEquals(2, SettlementStockpile.transferItem(source, target, Items.COBBLESTONE, 5));
		assertTrue(source.isEmpty());
		assertStack(decorated(2, "A"), target.getItem(0));
		assertEquals(0, SettlementStockpile.transferItem(source, target, Items.COBBLESTONE, 3));
		assertStack(decorated(2, "A"), target.getItem(0));
	}

	@Test
	void sameContainerAndNonPositiveRequestsDoNothing() {
		SimpleContainer source = new SimpleContainer(decorated(5, "A"));
		SimpleContainer target = new SimpleContainer(1);

		assertEquals(0, SettlementStockpile.transferItem(source, source, Items.COBBLESTONE, 3));
		assertEquals(0, SettlementStockpile.transferItem(source, target, Items.COBBLESTONE, 0));
		assertEquals(0, SettlementStockpile.transferItem(source, target, Items.COBBLESTONE, -1));
		assertStack(decorated(5, "A"), source.getItem(0));
		assertTrue(target.isEmpty());
	}

	@Test
	void insertionUsesComponentStackLimit() {
		ItemStack input = decorated(4, "A");
		input.set(DataComponents.MAX_STACK_SIZE, 4);
		ItemStack existing = input.copy();
		existing.setCount(3);
		SimpleContainer source = new SimpleContainer(input);
		SimpleContainer target = new SimpleContainer(existing);

		assertEquals(1, SettlementStockpile.transferItem(source, target, Items.COBBLESTONE, 4));
		ItemStack expected = input.copy();
		expected.setCount(4);
		assertStack(expected, target.getItem(0));
		expected.setCount(3);
		assertStack(expected, source.getItem(0));
	}

	@Test
	void respectsTargetSlotPlacementRules() {
		SimpleContainer source = new SimpleContainer(decorated(5, "A"));
		SimpleContainer target = new SimpleContainer(2) {
			@Override
			public boolean canPlaceItem(int slot, ItemStack stack) {
				return slot == 1;
			}
		};

		assertEquals(3, SettlementStockpile.transferItem(source, target, Items.COBBLESTONE, 3));
		assertTrue(target.getItem(0).isEmpty());
		assertStack(decorated(3, "A"), target.getItem(1));
		assertStack(decorated(2, "A"), source.getItem(0));
	}

	private static ItemStack decorated(int count, String marker) {
		ItemStack stack = new ItemStack(Items.COBBLESTONE, count);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Named " + marker));
		CompoundTag data = new CompoundTag();
		data.putString("transfer_marker", marker);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
		return stack;
	}

	private static void assertStack(ItemStack expected, ItemStack actual) {
		assertEquals(expected.getCount(), actual.getCount());
		assertTrue(ItemStack.isSameItemSameComponents(expected, actual));
	}
}
