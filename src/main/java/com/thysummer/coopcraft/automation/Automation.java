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
	public static final Block WATERWHEEL = Registry.register(
		BuiltInRegistries.BLOCK,
		CoopCraft.id("waterwheel"),
		new WaterwheelBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_PLANKS).requiresCorrectToolForDrops())
	);
	public static final Item WATERWHEEL_ITEM = Registry.register(
		BuiltInRegistries.ITEM,
		CoopCraft.id("waterwheel"),
		new BlockItem(WATERWHEEL, new Item.Properties())
	);
	public static final Block DRIVE_SHAFT = Registry.register(
		BuiltInRegistries.BLOCK,
		CoopCraft.id("drive_shaft"),
		new DriveShaftBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).requiresCorrectToolForDrops())
	);
	public static final Item DRIVE_SHAFT_ITEM = Registry.register(
		BuiltInRegistries.ITEM,
		CoopCraft.id("drive_shaft"),
		new BlockItem(DRIVE_SHAFT, new Item.Properties())
	);
	public static final Block ARCHIVE = Registry.register(
		BuiltInRegistries.BLOCK,
		CoopCraft.id("archive"),
		new ArchiveBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.BOOKSHELF).requiresCorrectToolForDrops())
	);
	public static final BlockEntityType<ArchiveBlockEntity> ARCHIVE_ENTITY = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE,
		CoopCraft.id("archive"),
		BlockEntityType.Builder.of(ArchiveBlockEntity::new, ARCHIVE).build(null)
	);
	public static final Item ARCHIVE_ITEM = Registry.register(
		BuiltInRegistries.ITEM,
		CoopCraft.id("archive"),
		new BlockItem(ARCHIVE, new Item.Properties())
	);
	public static final Block COMPUTER_CORE = Registry.register(
		BuiltInRegistries.BLOCK,
		CoopCraft.id("computer_core"),
		new ComputerCoreBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).requiresCorrectToolForDrops()
				.isRedstoneConductor((state, level, pos) -> false))
	);
	public static final BlockEntityType<ComputerCoreBlockEntity> COMPUTER_CORE_ENTITY = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE,
		CoopCraft.id("computer_core"),
		BlockEntityType.Builder.of(ComputerCoreBlockEntity::new, COMPUTER_CORE).build(null)
	);
	public static final Item COMPUTER_CORE_ITEM = Registry.register(
		BuiltInRegistries.ITEM,
		CoopCraft.id("computer_core"),
		new BlockItem(COMPUTER_CORE, new Item.Properties())
	);

	private Automation() {
	}

	public static void register() {
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.REDSTONE_BLOCKS).register(entries -> {
			entries.accept(FARM_STATION_ITEM);
			entries.accept(WATERWHEEL_ITEM);
			entries.accept(DRIVE_SHAFT_ITEM);
			entries.accept(ARCHIVE_ITEM);
			entries.accept(COMPUTER_CORE_ITEM);
		});
	}
}
