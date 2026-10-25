package com.thysummer.coopcraft.automation;

import com.thysummer.coopcraft.CoopCraft;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class Automation {
	public static final Block FARM_STATION = Registry.register(
		BuiltInRegistries.BLOCK,
		CoopCraft.id("farm_station"),
		new FarmStationBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).requiresCorrectToolForDrops())
	);
	public static final BlockEntityType<FarmStationBlockEntity> FARM_STATION_ENTITY = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE,
		CoopCraft.id("farm_station"),
		BlockEntityType.Builder.of(FarmStationBlockEntity::new, FARM_STATION).build(null)
	);
	public static final Item FARM_STATION_ITEM = Registry.register(
		BuiltInRegistries.ITEM,
		CoopCraft.id("farm_station"),
		new BlockItem(FARM_STATION, new Item.Properties())
	);

	private Automation() {
	}

	public static void register() {
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.REDSTONE_BLOCKS).register(entries -> entries.accept(FARM_STATION_ITEM));
	}
}
