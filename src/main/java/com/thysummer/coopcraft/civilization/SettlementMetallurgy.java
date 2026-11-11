package com.thysummer.coopcraft.civilization;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class SettlementMetallurgy {
	static final int INPUT_SLOT = 0;
	static final int FUEL_SLOT = 1;
	static final int OUTPUT_SLOT = 2;

	private SettlementMetallurgy() {
	}

	static boolean isIronOre(BlockState state) {
		return state.is(Blocks.IRON_ORE) || state.is(Blocks.DEEPSLATE_IRON_ORE);
	}

	static boolean isCoalOre(BlockState state) {
		return state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE);
	}

	static boolean isOre(BlockState state) {
		return isIronOre(state) || isCoalOre(state);
	}

	static boolean isSmeltingStation(BlockState state) {
		return state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE);
	}

	static AbstractFurnaceBlockEntity furnaceAt(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		return data.getSmeltingStation(settlement)
			.map(pos -> level.getBlockEntity(pos))
			.filter(AbstractFurnaceBlockEntity.class::isInstance)
			.map(AbstractFurnaceBlockEntity.class::cast)
			.filter(furnace -> isSmeltingStation(furnace.getBlockState()))
			.orElse(null);
	}

	static boolean canLoad(AbstractFurnaceBlockEntity furnace) {
		if (furnace == null || !canAcceptOutput(furnace)) {
			return false;
		}
		return canAccept(furnace, INPUT_SLOT, new ItemStack(Items.RAW_IRON))
			&& (!needsFuel(furnace) || canAccept(furnace, FUEL_SLOT, new ItemStack(Items.COAL)));
	}

	static boolean needsFuel(AbstractFurnaceBlockEntity furnace) {
		return furnace != null && !furnace.getBlockState().getValue(AbstractFurnaceBlock.LIT)
			&& furnace.getItem(FUEL_SLOT).isEmpty();
	}

	static boolean load(AbstractFurnaceBlockEntity furnace, Container inventory) {
		if (!canLoad(furnace)) {
			return false;
		}
		if (VillagerWorkGoal.removeItems(inventory, Items.RAW_IRON, 1) != 1) {
			return false;
		}
		if (needsFuel(furnace) && VillagerWorkGoal.removeItems(inventory, Items.COAL, 1) != 1) {
			SettlementStockpile.insert(inventory, new ItemStack(Items.RAW_IRON));
			return false;
		}
		addToSlot(furnace, INPUT_SLOT, new ItemStack(Items.RAW_IRON));
		if (needsFuel(furnace)) {
			addToSlot(furnace, FUEL_SLOT, new ItemStack(Items.COAL));
		}
		furnace.setChanged();
		return true;
	}

	static boolean hasIronOutput(AbstractFurnaceBlockEntity furnace) {
		return furnace != null && furnace.getItem(OUTPUT_SLOT).is(Items.IRON_INGOT);
	}

	static int collectIron(AbstractFurnaceBlockEntity furnace, Container inventory) {
		if (!hasIronOutput(furnace)) {
			return 0;
		}
		ItemStack output = furnace.removeItem(OUTPUT_SLOT, furnace.getItem(OUTPUT_SLOT).getCount());
		int inserted = SettlementStockpile.insert(inventory, output);
		if (inserted < output.getCount()) {
			furnace.setItem(OUTPUT_SLOT, new ItemStack(Items.IRON_INGOT, output.getCount() - inserted));
		}
		furnace.setChanged();
		return inserted;
	}

	static int count(AbstractFurnaceBlockEntity furnace, Item item) {
		return furnace == null ? 0 : furnace.countItem(item);
	}

	private static boolean canAccept(AbstractFurnaceBlockEntity furnace, int slot, ItemStack input) {
		if (!furnace.canPlaceItem(slot, input)) {
			return false;
		}
		ItemStack existing = furnace.getItem(slot);
		return existing.isEmpty()
			|| ItemStack.isSameItemSameComponents(existing, input) && existing.getCount() < furnace.getMaxStackSize(existing);
	}

	private static boolean canAcceptOutput(AbstractFurnaceBlockEntity furnace) {
		ItemStack output = furnace.getItem(OUTPUT_SLOT);
		return output.isEmpty()
			|| output.is(Items.IRON_INGOT) && output.getCount() < furnace.getMaxStackSize(output);
	}

	private static void addToSlot(AbstractFurnaceBlockEntity furnace, int slot, ItemStack input) {
		ItemStack existing = furnace.getItem(slot);
		if (existing.isEmpty()) {
			furnace.setItem(slot, input);
			return;
		}
		existing.grow(input.getCount());
		furnace.setItem(slot, existing);
	}
}
