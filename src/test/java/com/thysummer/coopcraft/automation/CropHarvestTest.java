package com.thysummer.coopcraft.automation;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CropHarvestTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void recognizesMatureVanillaCrops() {
		List<BlockState> matureCrops = List.of(
			mature((CropBlock) Blocks.WHEAT),
			mature((CropBlock) Blocks.CARROTS),
			mature((CropBlock) Blocks.POTATOES),
			mature((CropBlock) Blocks.BEETROOTS),
			Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, NetherWartBlock.MAX_AGE)
		);

		for (BlockState crop : matureCrops) {
			assertTrue(CropHarvest.isMature(crop));
			BlockState replanted = CropHarvest.getReplantState(crop);
			assertFalse(CropHarvest.isMature(replanted));
		}
	}

	private static BlockState mature(CropBlock crop) {
		return crop.getStateForAge(crop.getMaxAge());
	}

	@Test
	void ignoresImmatureAndUnsupportedBlocks() {
		assertFalse(CropHarvest.isMature(Blocks.WHEAT.defaultBlockState()));
		assertFalse(CropHarvest.isMature(Blocks.NETHER_WART.defaultBlockState()));
		assertFalse(CropHarvest.isMature(Blocks.STONE.defaultBlockState()));
	}

	@Test
	void reservesExactlyOneSeedAndKeepsRemainingDrops() {
		List<ItemStack> drops = new ArrayList<>(List.of(
			new ItemStack(Items.WHEAT),
			new ItemStack(Items.WHEAT_SEEDS, 3)
		));

		assertTrue(CropHarvest.removeOneSeed(drops, new ItemStack(Items.WHEAT_SEEDS)));
		assertEquals(2, drops.size());
		assertEquals(1, drops.get(0).getCount());
		assertEquals(2, drops.get(1).getCount());
	}

	@Test
	void removesEmptySeedStackAfterReservingSeed() {
		List<ItemStack> drops = new ArrayList<>(List.of(new ItemStack(Items.CARROT)));

		assertTrue(CropHarvest.removeOneSeed(drops, new ItemStack(Items.CARROT)));
		assertTrue(drops.isEmpty());
	}

	@Test
	void refusesHarvestWhenDropsContainNoSeed() {
		List<ItemStack> drops = new ArrayList<>(List.of(new ItemStack(Items.WHEAT)));

		assertFalse(CropHarvest.removeOneSeed(drops, new ItemStack(Items.WHEAT_SEEDS)));
		assertEquals(1, drops.size());
		assertEquals(Items.WHEAT, drops.getFirst().getItem());
		assertEquals(1, drops.getFirst().getCount());
	}
}
