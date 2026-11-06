package com.thysummer.coopcraft.civilization;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.behavior.VillagerMakeLove;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.properties.BedPart;

public final class AgriculturalEraGameTest implements FabricGameTest {
	private static final BlockPos BELL = new BlockPos(2, 0, 2);
	private static final BlockPos CHEST = new BlockPos(1, 0, 1);
	private static final BlockPos CRAFTING_TABLE = new BlockPos(3, 0, 2);
	private static final BlockPos HEARTH = new BlockPos(1, 0, 3);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600, batch = "agricultural_core")
	public void agriculturalCoreCanBeCompletedEndToEnd(GameTestHelper helper) {
		CivilizationGameTestSupport.clearAround(helper, new BlockPos(2, 0, 2), 8);
		helper.setBlock(CHEST, Blocks.CHEST);
		helper.setBlock(CRAFTING_TABLE, Blocks.CRAFTING_TABLE);
		helper.setBlock(HEARTH, Blocks.CAMPFIRE);
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		Settlement settlement = CivilizationGameTestSupport.isolatedSettlement(helper, data, "Agricultural Review");
		data.setStockpile(settlement, helper.absolutePos(CHEST));
		data.setCraftingStation(settlement, helper.absolutePos(CRAFTING_TABLE));
		data.setHearth(settlement, helper.absolutePos(HEARTH));
		Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
		SettlementStockpile.insert(chest, new ItemStack(Items.BREAD, 4));
		SettlementStockpile.insert(chest, new ItemStack(Items.WHEAT_SEEDS, 4));
		List<Villager> residents = new ArrayList<>();
		for (int index = 0; index < 4; index++) {
			residents.add(helper.spawn(EntityType.VILLAGER, new BlockPos(index, 0, 4)));
		}
		data.registerLoadedResidents(helper.getLevel(), settlement, residents);
		putBedFoot(helper, data, settlement, new BlockPos(0, 0, 0));
		putBedFoot(helper, data, settlement, new BlockPos(1, 0, 0));
		SettlementStages.tick(helper.getLevel(), data, settlement, -settlement.id().getLeastSignificantBits());
		helper.assertValueEqual(SettlementStage.AGRICULTURAL, data.stageOf(settlement), "Real settlement basics must allow farming");
		List<BlockPos> crops = List.of(new BlockPos(0, 0, 5), new BlockPos(1, 0, 5), new BlockPos(2, 0, 5), new BlockPos(3, 0, 5));
		for (BlockPos crop : crops) {
			helper.setBlock(crop.below(), Blocks.FARMLAND);
		}
		Villager worker = residents.getFirst();

		for (BlockPos crop : crops) {
			VillagerWorkGoal goal = new VillagerWorkGoal(worker);
			helper.assertTrue(goal.canUse(), "Wheat seeds must be plantable: " + goal.debugState());
			goal.start();
			worker.setPos(helper.absolutePos(crop).getCenter());
			for (int tick = 0; tick < 120 && !helper.getBlockState(crop).is(Blocks.WHEAT); tick++) {
				goal.tick();
			}
			helper.assertTrue(helper.getBlockState(crop).is(Blocks.WHEAT), "The worker must plant a real wheat block: " + goal.debugState());
		}
		CivilizationEras.refresh(helper.getLevel(), data, settlement);
		helper.assertValueEqual(CivilizationEras.AGRICULTURAL, data.eraOf(settlement).id(), "Real crops must unlock the agricultural era");
		for (BlockPos crop : crops) {
			helper.getLevel().setBlock(helper.absolutePos(crop), ((CropBlock) Blocks.WHEAT).getStateForAge(7), 3);
			VillagerWorkGoal goal = new VillagerWorkGoal(worker);
			helper.assertTrue(goal.canUse(), "Mature wheat must be harvestable: " + goal.debugState());
			goal.start();
			worker.setPos(helper.absolutePos(crop).getCenter());
			for (int tick = 0; tick < 120 && helper.getBlockState(crop).getValue(CropBlock.AGE) != 0; tick++) {
				goal.tick();
			}
			helper.assertValueEqual(0, helper.getBlockState(crop).getValue(CropBlock.AGE), "Harvesting must replant wheat at age zero");
			SettlementStockpile.depositGatheredResources(chest, worker.getInventory());
		}
		helper.assertTrue(chest.countItem(Items.WHEAT) >= 4, "Four mature wheat blocks must yield at least four wheat");
		helper.assertTrue(chest.countItem(Items.WHEAT_SEEDS) >= 4, "Harvesting must preserve replanting seeds");
		SettlementSimulationService.setSurvivalEnabled(helper.getLevel(), data, settlement, true);
		SettlementSimulationService.setProductionEnabled(data, settlement, true);
		VillagerWorkGoal breadGoal = new VillagerWorkGoal(worker);
		helper.assertTrue(breadGoal.canUse(), "Three wheat must allow bread crafting");
		breadGoal.start();
		for (int tick = 0; tick < 180 && SettlementSimulationService.craftingResourceCount(data, settlement, Items.BREAD) < 1; tick++) {
			breadGoal.tick();
		}

		helper.assertValueEqual(1L, SettlementSimulationService.craftingResourceCount(data, settlement, Items.BREAD),
			"Three wheat must create exactly one abstract bread batch");
		helper.assertTrue(data.eraMilestonesOf(settlement).contains(CivilizationEras.BREAD_BAKED), "Bread production must complete its milestone");
		CivilizationEras.refresh(helper.getLevel(), data, settlement);
		helper.assertTrue(data.eraMilestonesOf(settlement).contains(CivilizationEras.STAPLE_CROP), "The real wheat field must complete staple cultivation");
		helper.assertTrue(data.eraMilestonesOf(settlement).contains(CivilizationEras.HOUSING_COMPLETE), "Real beds must complete housing");

		Villager child = breed(helper, residents.get(0), residents.get(1));
		CivilizationEras.refresh(helper.getLevel(), data, settlement);
		helper.assertTrue(data.isMember(settlement, child.getUUID()), "The real newborn must join the settlement");
		helper.assertTrue(data.eraMilestonesOf(settlement).contains(CivilizationEras.POPULATION_GROWTH), "A real birth must complete population growth");
		helper.assertTrue(CivilizationEras.agriculturalComplete(data, settlement), "All agricultural core milestones must be complete");
		helper.assertValueEqual("coopcraft.milestone.metal_smelting", CivilizationEras.nextMilestoneTranslationKey(data, settlement),
			"Completing agriculture must expose the next industrial milestone");
		helper.assertValueEqual(CivilizationEras.AGRICULTURAL, data.eraOf(settlement).id(),
			"Agricultural completion alone cannot skip metalworking and mechanical power");
		residents.forEach(Villager::kill);
		child.kill();
		CivilizationGameTestSupport.clearAround(helper, new BlockPos(2, 0, 2), 8);
		helper.succeed();
	}

	private static void putBedFoot(GameTestHelper helper, SettlementSavedData data, Settlement settlement, BlockPos pos) {
		helper.setBlock(pos, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.FOOT));
		data.addHousingBed(settlement, helper.absolutePos(pos));
	}

	static Villager breed(GameTestHelper helper, Villager first, Villager second) {
		try {
			Method breed = VillagerMakeLove.class.getDeclaredMethod("breed", ServerLevel.class, Villager.class, Villager.class);
			breed.setAccessible(true);
			Object result = breed.invoke(new VillagerMakeLove(), helper.getLevel(), first, second);
			return (Villager) ((Optional<?>) result).orElseThrow();
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Could not invoke vanilla villager breeding", exception);
		}
	}
}
