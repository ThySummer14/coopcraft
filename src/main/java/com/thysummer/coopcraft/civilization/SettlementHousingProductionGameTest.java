package com.thysummer.coopcraft.civilization;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public final class SettlementHousingProductionGameTest implements FabricGameTest {
	private static final BlockPos BELL = new BlockPos(2, 0, 2);
	private static final BlockPos CHEST = new BlockPos(1, 0, 1);
	private static final BlockPos CRAFTING_TABLE = new BlockPos(3, 0, 2);
	private static final BlockPos VILLAGER = new BlockPos(2, 0, 2);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void craftedMaterialsAllowCottageToStart(GameTestHelper helper) {
		CivilizationGameTestSupport.clearAround(helper, BELL, 24);
		helper.setBlock(CHEST, Blocks.CHEST);
		helper.setBlock(CRAFTING_TABLE, Blocks.CRAFTING_TABLE);
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		Settlement settlement = CivilizationGameTestSupport.isolatedSettlement(helper, data, "Housing Production");
		data.setStockpile(settlement, helper.absolutePos(CHEST));
		data.setCraftingStation(settlement, helper.absolutePos(CRAFTING_TABLE));
		Villager villager = helper.spawn(EntityType.VILLAGER, VILLAGER);
		data.registerLoadedResidents(helper.getLevel(), settlement, List.of(villager));
		SettlementSimulationService.setSurvivalEnabled(helper.getLevel(), data, settlement, true);
		SettlementSimulationService.setProductionEnabled(data, settlement, true);
		Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
		SettlementStockpile.insert(chest, new ItemStack(Items.COBBLESTONE, 50));
		SettlementStockpile.insert(chest, new ItemStack(Items.OAK_LOG, 64));
		SettlementStockpile.insert(chest, new ItemStack(Items.WHITE_WOOL, 6));
		SettlementStockpile.insert(chest, new ItemStack(Items.COAL, 2));
		SettlementStockpile.insert(chest, new ItemStack(Items.STICK));
		villager.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE_PICKAXE));
		VillagerWorkGoal goal = new VillagerWorkGoal(villager);

		for (int step = 0; step < 4000 && !materialsReady(chest); step++) {
			if (!goal.canContinueToUse() && goal.canUse()) {
				arriveForWorkbench(helper, villager, goal);
				goal.start();
			}
			arriveForWorkbench(helper, villager, goal);
			goal.tick();
			SettlementStockpile.depositGatheredResources(chest, villager.getInventory());
		}

		helper.assertTrue(materialsReady(chest), "The worker must produce a complete set of cottage materials; planks="
			+ chest.countItem(Items.OAK_PLANKS) + ", doors=" + chest.countItem(Items.OAK_DOOR)
			+ ", beds=" + chest.countItem(Items.WHITE_BED) + ", torches=" + chest.countItem(Items.TORCH) + ", " + goal.debugState()
			+ ", coal=" + chest.countItem(Items.COAL) + ", sticks=" + chest.countItem(Items.STICK)
			+ ", demands=" + ConstructionPlanner.materialDemands(data, settlement)
			+ ", choices=" + ConstructionPlanner.materialDemands(data, settlement).stream()
				.map(demand -> SettlementMaterialCrafting.chooseRecipe(chest, data.simulationOf(settlement).orElse(null), demand)).toList()
			+ ", chest=" + java.util.stream.IntStream.range(0, chest.getContainerSize()).mapToObj(chest::getItem)
				.filter(stack -> !stack.isEmpty()).toList());
		helper.assertTrue(chest.countItem(Items.OAK_PLANKS) >= 122, "The timber cabin needs at least 122 public planks");
		helper.assertTrue(chest.countItem(Items.OAK_DOOR) >= 2, "The timber cabin needs at least two public doors");
		helper.assertTrue(chest.countItem(Items.WHITE_BED) >= 2, "The timber cabin needs at least two public beds");
		helper.assertTrue(chest.countItem(Items.TORCH) >= 2, "The timber cabin needs at least two public torches");
		for (var item : List.of(Items.OAK_PLANKS, Items.OAK_DOOR, Items.WHITE_BED, Items.TORCH)) {
			helper.assertValueEqual(0L, SettlementSimulationService.craftingResourceCount(data, settlement, item),
				"Housing products must not have a second abstract inventory copy: " + item);
		}
		helper.assertTrue(ConstructionSystem.nextBuildTarget(helper.getLevel(), data, settlement).isPresent(),
			"The crafted materials must allow real cottage construction to start: " + ConstructionSystem.inspect(helper.getLevel(), data, settlement)
				+ ", sites=" + ConstructionSystem.inspectSites(helper.getLevel(), data, settlement, BlueprintManager.get(ConstructionPlanner.TIMBER_PORCH_CABIN))
				+ ", demands=" + ConstructionPlanner.materialDemands(data, settlement)
				+ ", bag=" + java.util.stream.IntStream.range(0, villager.getInventory().getContainerSize())
					.mapToObj(villager.getInventory()::getItem).filter(stack -> !stack.isEmpty()).toList()
				+ ", chest=" + java.util.stream.IntStream.range(0, chest.getContainerSize()).mapToObj(chest::getItem)
					.filter(stack -> !stack.isEmpty()).toList());
		helper.assertValueEqual(ConstructionPlanner.TIMBER_PORCH_CABIN, data.getConstructionJob(settlement).orElseThrow().blueprintId(),
			"The construction job must use the cottage blueprint");
		villager.kill();
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void realShearsProduceWoolForHousing(GameTestHelper helper) {
		helper.setBlock(CHEST, Blocks.CHEST);
		helper.setBlock(CRAFTING_TABLE, Blocks.CRAFTING_TABLE);
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		Settlement settlement = CivilizationGameTestSupport.isolatedSettlement(helper, data, "Sheep Production");
		data.setStockpile(settlement, helper.absolutePos(CHEST));
		data.setCraftingStation(settlement, helper.absolutePos(CRAFTING_TABLE));
		Villager villager = helper.spawn(EntityType.VILLAGER, VILLAGER);
		data.registerLoadedResidents(helper.getLevel(), settlement, List.of(villager));
		SettlementSimulationService.setSurvivalEnabled(helper.getLevel(), data, settlement, true);
		SettlementSimulationService.setProductionEnabled(data, settlement, true);
		Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
		SettlementStockpile.insert(chest, new ItemStack(Items.IRON_INGOT, 2));
		net.minecraft.world.entity.animal.Sheep sheep = (net.minecraft.world.entity.animal.Sheep) helper.spawn(EntityType.SHEEP, new BlockPos(3, 0, 3));
		sheep.setColor(net.minecraft.world.item.DyeColor.WHITE);
		data.permitShearing(settlement, villager.getUUID(), sheep.getUUID());
		VillagerWorkGoal goal = new VillagerWorkGoal(villager);

		helper.assertTrue(goal.canUse(), "Two iron ingots must allow shears crafting");
		goal.start();
		for (int tick = 0; tick < 180 && !villager.getMainHandItem().is(Items.SHEARS); tick++) {
			arriveForWorkbench(helper, villager, goal);
			goal.tick();
		}
		helper.assertTrue(villager.getMainHandItem().is(Items.SHEARS), "The worker must equip real shears");
		helper.assertValueEqual(0, chest.countItem(Items.IRON_INGOT), "Shears must consume exactly two iron ingots");

		VillagerWorkGoal shearGoal = new VillagerWorkGoal(villager);
		helper.assertTrue(shearGoal.canUse(), "The worker must choose the white sheep");
		arriveForWorkbench(helper, villager, shearGoal);
		shearGoal.start();
		for (int tick = 0; tick < 180 && !sheep.isSheared(); tick++) {
			arriveForWorkbench(helper, villager, shearGoal);
			shearGoal.tick();
		}

		helper.assertTrue(sheep.isSheared(), "The real sheep must enter its sheared state");
		for (int tick = 0; tick < 300 && chest.countItem(Items.WHITE_WOOL) == 0; tick++) {
			if (!shearGoal.canContinueToUse() && shearGoal.canUse()) shearGoal.start();
			arriveForWorkbench(helper, villager, shearGoal);
			shearGoal.tick();
		}
		helper.assertTrue(chest.countItem(Items.WHITE_WOOL) > 0, "Wool must physically return to the public housing stockpile");
		helper.assertValueEqual(1, villager.getMainHandItem().getDamageValue(), "Shearing must consume one durability point");
		helper.assertValueEqual(0L, SettlementSimulationService.craftingResourceCount(data, settlement, Items.WHITE_WOOL),
			"Wool must not have an abstract duplicate");
		helper.assertFalse(new VillagerWorkGoal(villager).canUse(), "A sheared sheep cannot be sheared again for this test");
		villager.kill();
		sheep.kill();
		helper.succeed();
	}

	private static void arriveForWorkbench(GameTestHelper helper, Villager villager, VillagerWorkGoal goal) {
		CivilizationGameTestSupport.advanceOneTick(helper);
		String action = goal.status().action();
		BlockPos target = goal.status().target();
		if (target == null || !(action.startsWith("collect_") || action.startsWith("workbench_") || action.startsWith("return_")
			|| action.equals("craft_first_workbench") || action.equals("craft_sticks_at_workbench")
			|| action.equals("craft_shears") || action.equals("shear_sheep")
			|| action.equals("polish") || action.equals("grind"))) return;
		BlockPos feet = target.east();
		helper.getLevel().setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		helper.getLevel().setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		helper.getLevel().setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		villager.setPos(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
	}

	private static boolean materialsReady(Container chest) {
		return chest.countItem(Items.OAK_PLANKS) >= 122 && chest.countItem(Items.OAK_DOOR) >= 2
			&& chest.countItem(Items.WHITE_BED) >= 2 && chest.countItem(Items.TORCH) >= 2;
	}
}
