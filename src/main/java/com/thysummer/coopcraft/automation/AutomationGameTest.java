package com.thysummer.coopcraft.automation;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;

public final class AutomationGameTest implements FabricGameTest {
	private static final BlockPos DRILL_POS = new BlockPos(1, 1, 1);
	private static final BlockPos CONVEYOR_OUTPUT = new BlockPos(1, 1, 4);
	private static final BlockPos BRAKE_POWER = new BlockPos(2, 1, 2);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 240)
	public void drillHarvestsIntoChestAndDamagesTool(GameTestHelper helper) {
		MechanicalDrillBlockEntity drill = setupDrill(helper, Items.DIAMOND_PICKAXE);
		helper.setBlock(DRILL_POS.south(), Blocks.STONE);
		pulseDrill(helper);
		helper.runAfterDelay(4, () -> {
			ChestBlockEntity chest = helper.getBlockEntity(DRILL_POS.north());
			helper.assertBlockNotPresent(Blocks.STONE, DRILL_POS.south());
			helper.assertValueEqual(1, chest.countItem(Items.COBBLESTONE), "The drill should output one cobblestone");
			helper.assertValueEqual(1, drill.getItem(0).getDamageValue(), "The drill should consume one tool durability");
			helper.succeed();
		});
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 240)
	public void drillKeepsTargetWhenOutputIsFull(GameTestHelper helper) {
		MechanicalDrillBlockEntity drill = setupDrill(helper, Items.DIAMOND_PICKAXE);
		helper.setBlock(DRILL_POS.south(), Blocks.STONE);
		ChestBlockEntity chest = helper.getBlockEntity(DRILL_POS.north());
		fillChest(chest, Items.STONE);
		pulseDrill(helper);
		helper.runAfterDelay(4, () -> {
			helper.assertBlockPresent(Blocks.STONE, DRILL_POS.south());
			helper.assertValueEqual(0, drill.getItem(0).getDamageValue(), "A blocked drill must not consume durability");
			helper.assertValueEqual(0, chest.countItem(Items.COBBLESTONE), "A full chest must not receive partial drops");
			helper.succeed();
		});
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 240)
	public void drillRejectsWrongTool(GameTestHelper helper) {
		MechanicalDrillBlockEntity drill = setupDrill(helper, Items.WOODEN_PICKAXE);
		helper.setBlock(DRILL_POS.south(), Blocks.OBSIDIAN);
		pulseDrill(helper);
		helper.runAfterDelay(4, () -> {
			helper.assertBlockPresent(Blocks.OBSIDIAN, DRILL_POS.south());
			helper.assertValueEqual(0, drill.getItem(0).getDamageValue(), "An unsuitable tool must not lose durability");
			helper.succeed();
		});
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 240)
	public void drillProtectsContainers(GameTestHelper helper) {
		MechanicalDrillBlockEntity drill = setupDrill(helper, Items.DIAMOND_PICKAXE);
		helper.setBlock(DRILL_POS.south(), Blocks.CHEST);
		ChestBlockEntity target = helper.getBlockEntity(DRILL_POS.south());
		target.setItem(0, new ItemStack(Items.DIAMOND));
		pulseDrill(helper);
		helper.runAfterDelay(4, () -> {
			helper.assertBlockPresent(Blocks.CHEST, DRILL_POS.south());
			helper.assertValueEqual(1, target.countItem(Items.DIAMOND), "A protected container must keep its contents");
			helper.assertValueEqual(0, drill.getItem(0).getDamageValue(), "A protected block must not consume durability");
			helper.succeed();
		});
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 240)
	public void conveyorTransfersItemsIntoChest(GameTestHelper helper) {
		setupConveyor(helper);
		ChestBlockEntity chest = helper.getBlockEntity(CONVEYOR_OUTPUT);
		ItemEntity item = spawnConveyorItem(helper, Items.COBBLESTONE, 7);
		helper.runAfterDelay(60, () -> {
			helper.assertValueEqual(7, chest.countItem(Items.COBBLESTONE), "The conveyor should transfer the whole stack");
			helper.assertFalse(item.isAlive(), "A fully inserted item entity should be removed");
			helper.succeed();
		});
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 240)
	public void conveyorBrakesAndResumesWithoutLosingItems(GameTestHelper helper) {
		setupConveyor(helper);
		helper.setBlock(BRAKE_POWER, Blocks.REDSTONE_BLOCK);
		ChestBlockEntity chest = helper.getBlockEntity(CONVEYOR_OUTPUT);
		ItemEntity item = spawnConveyorItem(helper, Items.COBBLESTONE, 4);
		helper.runAfterDelay(40, () -> {
			helper.assertTrue(item.isAlive(), "The braked conveyor should retain its item");
			helper.assertValueEqual(4, item.getItem().getCount(), "Braking must not delete items");
			helper.assertContainerEmpty(CONVEYOR_OUTPUT);
			helper.setBlock(BRAKE_POWER, Blocks.AIR);
			helper.runAfterDelay(60, () -> {
				helper.assertValueEqual(4, chest.countItem(Items.COBBLESTONE), "The conveyor should resume after power is removed");
				helper.assertFalse(item.isAlive(), "The retained stack should transfer after resuming");
				helper.succeed();
			});
		});
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 240)
	public void conveyorKeepsPartialInsertionRemainder(GameTestHelper helper) {
		setupConveyor(helper);
		ChestBlockEntity chest = helper.getBlockEntity(CONVEYOR_OUTPUT);
		fillChest(chest, Items.COBBLESTONE);
		chest.setItem(0, new ItemStack(Items.COBBLESTONE, 63));
		ItemEntity item = spawnConveyorItem(helper, Items.COBBLESTONE, 7);
		helper.runAfterDelay(60, () -> {
			helper.assertTrue(item.isAlive(), "A partial insertion should leave an item entity");
			helper.assertValueEqual(6, item.getItem().getCount(), "Only the accepted item should be deducted");
			helper.assertValueEqual(64, chest.getItem(0).getCount(), "The available slot should be filled exactly");
			helper.succeed();
		});
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 240)
	public void drillDropsOntoConveyorProductionLine(GameTestHelper helper) {
		helper.setBlock(DRILL_POS, Automation.MECHANICAL_DRILL.defaultBlockState()
			.setValue(MechanicalDrillBlock.FACING, Direction.SOUTH));
		MechanicalDrillBlockEntity drill = helper.getBlockEntity(DRILL_POS);
		drill.setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
		helper.setBlock(DRILL_POS.south(), Blocks.STONE);
		for (int z = 2; z <= 4; z++) {
			helper.setBlock(new BlockPos(1, 0, z), Automation.CONVEYOR_BELT.defaultBlockState()
				.setValue(ConveyorBeltBlock.FACING, Direction.SOUTH));
		}
		BlockPos outputPos = new BlockPos(1, 0, 5);
		helper.setBlock(outputPos, Blocks.CHEST);
		pulseDrill(helper);
		helper.runAfterDelay(60, () -> {
			ChestBlockEntity chest = helper.getBlockEntity(outputPos);
			helper.assertBlockNotPresent(Blocks.STONE, DRILL_POS.south());
			helper.assertValueEqual(1, chest.countItem(Items.COBBLESTONE), "The drill drop should travel along the conveyor");
			helper.assertValueEqual(1, drill.getItem(0).getDamageValue(), "The production line should consume tool durability");
			helper.succeed();
		});
	}

	private static MechanicalDrillBlockEntity setupDrill(GameTestHelper helper, Item tool) {
		helper.setBlock(DRILL_POS, Automation.MECHANICAL_DRILL.defaultBlockState()
			.setValue(MechanicalDrillBlock.FACING, Direction.SOUTH));
		helper.setBlock(DRILL_POS.north(), Blocks.CHEST);
		MechanicalDrillBlockEntity drill = helper.getBlockEntity(DRILL_POS);
		drill.setItem(0, new ItemStack(tool));
		return drill;
	}

	private static void pulseDrill(GameTestHelper helper) {
		helper.setBlock(DRILL_POS.east(), Blocks.REDSTONE_BLOCK);
	}

	private static void setupConveyor(GameTestHelper helper) {
		for (int z = 1; z <= 3; z++) {
			helper.setBlock(new BlockPos(1, 1, z), Automation.CONVEYOR_BELT.defaultBlockState()
				.setValue(ConveyorBeltBlock.FACING, Direction.SOUTH));
		}
		helper.setBlock(CONVEYOR_OUTPUT, Blocks.CHEST);
	}

	private static ItemEntity spawnConveyorItem(GameTestHelper helper, Item item, int count) {
		ItemEntity entity = helper.spawnItem(item, new Vec3(1.5, 1.5, 1.5));
		entity.setItem(new ItemStack(item, count));
		entity.setDeltaMovement(Vec3.ZERO);
		return entity;
	}

	private static void fillChest(ChestBlockEntity chest, Item item) {
		for (int slot = 0; slot < chest.getContainerSize(); slot++) {
			chest.setItem(slot, new ItemStack(item, 64));
		}
	}
}
