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
	public static final Block MECHANICAL_DRILL = Registry.register(
		BuiltInRegistries.BLOCK,
		CoopCraft.id("mechanical_drill"),
		new MechanicalDrillBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).requiresCorrectToolForDrops())
	);
	public static final BlockEntityType<MechanicalDrillBlockEntity> MECHANICAL_DRILL_ENTITY = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE,
		CoopCraft.id("mechanical_drill"),
		BlockEntityType.Builder.of(MechanicalDrillBlockEntity::new, MECHANICAL_DRILL).build(null)
	);
	public static final Item MECHANICAL_DRILL_ITEM = Registry.register(
		BuiltInRegistries.ITEM,
		CoopCraft.id("mechanical_drill"),
		new BlockItem(MECHANICAL_DRILL, new Item.Properties())
	);
	public static final Block CONVEYOR_BELT = Registry.register(
		BuiltInRegistries.BLOCK,
		CoopCraft.id("conveyor_belt"),
		new ConveyorBeltBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).requiresCorrectToolForDrops().noOcclusion())
	);
	public static final BlockEntityType<ConveyorBeltBlockEntity> CONVEYOR_BELT_ENTITY = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE,
		CoopCraft.id("conveyor_belt"),
		BlockEntityType.Builder.of(ConveyorBeltBlockEntity::new, CONVEYOR_BELT).build(null)
	);
	public static final Item CONVEYOR_BELT_ITEM = Registry.register(
		BuiltInRegistries.ITEM,
		CoopCraft.id("conveyor_belt"),
		new BlockItem(CONVEYOR_BELT, new Item.Properties())
	);

	private Automation() {
	}

	public static void register() {
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.REDSTONE_BLOCKS).register(entries -> {
			entries.accept(FARM_STATION_ITEM);
			entries.accept(MECHANICAL_DRILL_ITEM);
			entries.accept(CONVEYOR_BELT_ITEM);
		});
	}
}
