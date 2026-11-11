package com.thysummer.coopcraft.automation;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MechanicalDrillTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void requiresARealTool() {
		assertFalse(ToolContainer.isTool(ItemStack.EMPTY));
		assertFalse(ToolContainer.isTool(new ItemStack(Items.STICK)));
		assertTrue(ToolContainer.isTool(new ItemStack(Items.IRON_PICKAXE)));
		assertTrue(ToolContainer.isTool(new ItemStack(Items.SHEARS)));
	}

	@Test
	void rejectsAirFluidsAndIndestructibleBlocks() {
		ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
		assertFalse(MechanicalDrillBlockEntity.canHarvest(Blocks.AIR.defaultBlockState(), pickaxe));
		assertFalse(MechanicalDrillBlockEntity.canHarvest(Blocks.WATER.defaultBlockState(), pickaxe));
		assertFalse(MechanicalDrillBlockEntity.canHarvest(Blocks.BEDROCK.defaultBlockState(), pickaxe));
	}

	@Test
	void enforcesToolComponentRules() {
		ItemStack obsidianTool = toolThatMines(Blocks.OBSIDIAN);
		assertFalse(MechanicalDrillBlockEntity.canHarvest(Blocks.STONE.defaultBlockState(), obsidianTool));
		assertTrue(MechanicalDrillBlockEntity.canHarvest(Blocks.OBSIDIAN.defaultBlockState(), obsidianTool));
	}

	@Test
	void harvestsOrdinaryBlocksWithASuitableTool() {
		assertTrue(MechanicalDrillBlockEntity.canHarvest(Blocks.STONE.defaultBlockState(), toolThatMines(Blocks.STONE)));
		assertTrue(MechanicalDrillBlockEntity.canHarvest(Blocks.OAK_LOG.defaultBlockState(), new ItemStack(Items.IRON_AXE)));
		assertTrue(MechanicalDrillBlockEntity.canHarvest(Blocks.DIRT.defaultBlockState(), new ItemStack(Items.IRON_SHOVEL)));
	}

	private static ItemStack toolThatMines(Block block) {
		ItemStack tool = new ItemStack(Items.IRON_PICKAXE);
		Tool.Rule rule = Tool.Rule.minesAndDrops(List.of(block), 4.0f);
		tool.set(DataComponents.TOOL, new Tool(List.of(rule), 1.0f, 1));
		return tool;
	}

	@Test
	void toolSlotHoldsOneToolAndReportsChanges() {
		AtomicInteger changes = new AtomicInteger();
		ToolContainer container = new ToolContainer(changes::incrementAndGet);
		ItemStack tool = new ItemStack(Items.IRON_PICKAXE);
		tool.setDamageValue(17);

		assertTrue(container.canPlaceItem(0, tool));
		assertFalse(container.canPlaceItem(0, new ItemStack(Items.COBBLESTONE)));
		assertEquals(1, container.getMaxStackSize());
		container.setItem(0, tool);

		assertEquals(tool, container.getItem(0));
		assertEquals(17, container.getItem(0).getDamageValue());
		assertEquals(1, changes.get());
	}
}
