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
import net.minecraft.world.level.block.Blocks;

public final class SettlementHousingProductionGameTest implements FabricGameTest {
	private static final BlockPos BELL = new BlockPos(2, 0, 2);
	private static final BlockPos CHEST = new BlockPos(1, 0, 1);
	private static final BlockPos CRAFTING_TABLE = new BlockPos(3, 0, 2);
	private static final BlockPos VILLAGER = new BlockPos(2, 0, 2);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void craftedMaterialsAllowCottageToStart(GameTestHelper helper) {
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
		SettlementStockpile.insert(chest, new ItemStack(Items.OAK_LOG, 9));
		SettlementStockpile.insert(chest, new ItemStack(Items.WHITE_WOOL, 3));
		SettlementStockpile.insert(chest, new ItemStack(Items.COAL));
		SettlementStockpile.insert(chest, new ItemStack(Items.STICK));
		villager.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE_PICKAXE));
		VillagerWorkGoal goal = new VillagerWorkGoal(villager);

		for (int step = 0; step < 1000 && !materialsReady(chest, villager); step++) {
			if (!goal.canContinueToUse() && goal.canUse()) {
				goal.start();
			}
			goal.tick();
			SettlementStockpile.depositGatheredResources(chest, villager.getInventory());
		}

		helper.assertTrue(materialsReady(chest, villager), "The worker must produce a complete set of cottage materials; planks="
			+ chest.countItem(Items.OAK_PLANKS) + ", doors=" + chest.countItem(Items.OAK_DOOR)
			+ ", beds=" + chest.countItem(Items.WHITE_BED) + ", torches=" + chest.countItem(Items.TORCH) + ", " + goal.debugState());
		helper.assertValueEqual(30, chest.countItem(Items.OAK_PLANKS), "Nine logs must make 36 planks; door and bed consume six");
		helper.assertValueEqual(3, chest.countItem(Items.OAK_DOOR), "The vanilla door recipe must produce three doors");
		helper.assertValueEqual(1, chest.countItem(Items.WHITE_BED), "Three wool and three planks must make one bed");
		helper.assertValueEqual(4, chest.countItem(Items.TORCH), "Coal and a stick must make four torches");
		for (var item : List.of(Items.OAK_PLANKS, Items.OAK_DOOR, Items.WHITE_BED, Items.TORCH)) {
			helper.assertValueEqual(0L, SettlementSimulationService.craftingResourceCount(data, settlement, item),
				"Housing products must not have a second abstract inventory copy: " + item);
		}
		helper.assertTrue(ConstructionSystem.nextBuildTarget(helper.getLevel(), data, settlement).isPresent(),
			"The crafted materials must allow real cottage construction to start");
		helper.assertValueEqual(ConstructionPlanner.STONE_COTTAGE, data.getConstructionJob(settlement).orElseThrow().blueprintId(),
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
		VillagerWorkGoal goal = new VillagerWorkGoal(villager);

		helper.assertTrue(goal.canUse(), "Two iron ingots must allow shears crafting");
		goal.start();
		for (int tick = 0; tick < 180 && !villager.getMainHandItem().is(Items.SHEARS); tick++) {
			goal.tick();
		}
		helper.assertTrue(villager.getMainHandItem().is(Items.SHEARS), "The worker must equip real shears");
		helper.assertValueEqual(0, chest.countItem(Items.IRON_INGOT), "Shears must consume exactly two iron ingots");

		VillagerWorkGoal shearGoal = new VillagerWorkGoal(villager);
		helper.assertTrue(shearGoal.canUse(), "The worker must choose the white sheep");
		shearGoal.start();
		for (int tick = 0; tick < 180 && !sheep.isSheared(); tick++) {
			shearGoal.tick();
		}

		helper.assertTrue(sheep.isSheared(), "The real sheep must enter its sheared state");
		int wool = SettlementStockpile.depositGatheredResources(chest, villager.getInventory());
		helper.assertTrue(wool > 0, "Shearing must produce real wool");
		helper.assertTrue(chest.countItem(Items.WHITE_WOOL) > 0, "Wool must enter the public housing stockpile");
		helper.assertValueEqual(1, villager.getMainHandItem().getDamageValue(), "Shearing must consume one durability point");
		helper.assertValueEqual(0L, SettlementSimulationService.craftingResourceCount(data, settlement, Items.WHITE_WOOL),
			"Wool must not have an abstract duplicate");
		helper.assertFalse(new VillagerWorkGoal(villager).canUse(), "A sheared sheep cannot be sheared again for this test");
		villager.kill();
		sheep.kill();
		helper.succeed();
	}

	private static boolean materialsReady(Container chest, Villager villager) {
		return chest.countItem(Items.OAK_PLANKS) + villager.getInventory().countItem(Items.OAK_PLANKS) >= 29
			&& chest.countItem(Items.OAK_DOOR) + villager.getInventory().countItem(Items.OAK_DOOR) >= 1
			&& chest.countItem(Items.WHITE_BED) + villager.getInventory().countItem(Items.WHITE_BED) >= 1
			&& chest.countItem(Items.TORCH) + villager.getInventory().countItem(Items.TORCH) >= 2;
	}
}
