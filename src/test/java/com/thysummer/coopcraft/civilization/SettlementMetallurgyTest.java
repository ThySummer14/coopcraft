package com.thysummer.coopcraft.civilization;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementMetallurgyTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void fullInventoryReturnsIronToOutputSlot() {
		FurnaceBlockEntity furnace = new FurnaceBlockEntity(BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
		furnace.setItem(SettlementMetallurgy.OUTPUT_SLOT, new ItemStack(Items.IRON_INGOT, 3));
		SimpleContainer inventory = new SimpleContainer(1);
		inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 64));

		assertEquals(0, SettlementMetallurgy.collectIron(furnace, inventory));
		assertEquals(3, furnace.getItem(SettlementMetallurgy.OUTPUT_SLOT).getCount());

		inventory.setItem(0, ItemStack.EMPTY);
		assertEquals(3, SettlementMetallurgy.collectIron(furnace, inventory));
		assertEquals(3, inventory.countItem(Items.IRON_INGOT));
		assertTrue(furnace.getItem(SettlementMetallurgy.OUTPUT_SLOT).isEmpty());
	}
}
