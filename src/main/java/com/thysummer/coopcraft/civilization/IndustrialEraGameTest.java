package com.thysummer.coopcraft.civilization;

import java.util.List;

import com.thysummer.coopcraft.automation.Automation;
import com.thysummer.coopcraft.automation.CropHarvest;
import com.thysummer.coopcraft.automation.FarmStationBlockEntity;
import com.thysummer.coopcraft.automation.WaterwheelBlock;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;

public final class IndustrialEraGameTest implements FabricGameTest {
	private static final BlockPos STOCKPILE = new BlockPos(3, 1, 4);
	private static final BlockPos MACHINE = new BlockPos(3, 0, 4);
	private static final BlockPos WHEEL = new BlockPos(3, 0, 5);
	private static final BlockPos CHANNEL = new BlockPos(3, 0, 6);
	private static final BlockPos WATER_SOURCE = new BlockPos(3, 0, 7);
	private static final BlockPos CROP = new BlockPos(3, 0, 3);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000, batch = "industrial_core")
	public void poweredHarvestCompletesIndustrialCore(GameTestHelper helper) {
		CivilizationGameTestSupport.clearAround(helper, new BlockPos(2, 0, 2), 8);
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		Settlement settlement = CivilizationGameTestSupport.isolatedSettlement(helper, data, "Industrial Review");
		data.setEra(settlement, CivilizationEras.byId(CivilizationEras.AGRICULTURAL));
		for (var milestone : CivilizationEras.byId(CivilizationEras.AGRICULTURAL).milestones()) {
			data.completeEraMilestone(settlement, milestone);
		}
		for (var milestone : List.of(
			CivilizationEras.STAPLE_CROP,
			CivilizationEras.BREAD_BAKED,
			CivilizationEras.HOUSING_COMPLETE,
			CivilizationEras.POPULATION_GROWTH,
			CivilizationEras.METAL_SMELTING,
			CivilizationEras.METAL_TOOL_PRODUCTION
		)) {
			data.completeEraMilestone(settlement, milestone);
		}
		helper.assertValueEqual(CivilizationEras.AGRICULTURAL, data.eraOf(settlement).id(),
			"Metalworking without mechanical production must remain agricultural");
		helper.setBlock(MACHINE, Automation.FARM_STATION);
		helper.setBlock(STOCKPILE, Blocks.CHEST);
		data.setStockpile(settlement, helper.absolutePos(STOCKPILE));
		helper.setBlock(WHEEL, Automation.WATERWHEEL.defaultBlockState().setValue(WaterwheelBlock.FACING, Direction.NORTH));
		prepareWaterChannel(helper);
		setMatureCrop(helper);
		Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(STOCKPILE));
		FarmStationBlockEntity station = (FarmStationBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(MACHINE));

		helper.runAfterDelay(60, () -> {
			helper.assertTrue(CropHarvest.isMature(helper.getBlockState(CROP)), "A dry wheel cannot power a harvest");
			helper.assertFalse(SettlementIndustry.isPowered(helper.getLevel(), helper.absolutePos(MACHINE)),
				"A wheel without flowing water must not transmit power");
			helper.setBlock(WHEEL, Automation.WATERWHEEL.defaultBlockState().setValue(WaterwheelBlock.FACING, Direction.SOUTH));
			helper.setBlock(WATER_SOURCE, Blocks.WATER);
			helper.runAfterDelay(80, () -> {
				helper.assertTrue(CropHarvest.isMature(helper.getBlockState(CROP)), "A wheel facing away cannot drive the machine");
				helper.assertFalse(SettlementIndustry.isPowered(helper.getLevel(), helper.absolutePos(MACHINE)),
					"Water alone must not bypass directional power transmission");
				helper.setBlock(WHEEL, Automation.WATERWHEEL.defaultBlockState().setValue(WaterwheelBlock.FACING, Direction.NORTH));
				helper.runAfterDelay(100, () -> {
					helper.assertTrue(helper.getBlockState(WHEEL).getValue(WaterwheelBlock.POWERED),
						"The correctly directed wheel must visibly connect to the real flowing water");
					helper.assertValueEqual(Blocks.POTATOES.defaultBlockState(), helper.getBlockState(CROP),
						"The powered machine must harvest and replant the real potato crop");
					helper.assertTrue(chest.countItem(Items.POTATO) >= 1,
						"The real crop output must enter the public stockpile after reserving a replanting potato");
					helper.assertValueEqual(1L, station.poweredHarvests(), "Only the completed real harvest may increment the machine counter");
					helper.assertTrue(data.eraMilestonesOf(settlement).contains(CivilizationEras.MECHANICAL_POWER),
						"Public mechanical production must complete the power milestone");
					helper.assertValueEqual(CivilizationEras.INDUSTRIAL, data.eraOf(settlement).id(),
						"Agriculture, real metalworking and powered production must unlock the industrial era");
					helper.assertValueEqual("coopcraft.milestone.automated_production",
						CivilizationEras.nextMilestoneTranslationKey(data, settlement),
						"Industrial completion must not skip the information-era requirements");

					CompoundTag machineSaved = station.saveWithFullMetadata(helper.getLevel().registryAccess());
					station.loadAdditional(machineSaved, helper.getLevel().registryAccess());
					helper.assertValueEqual(1L, station.poweredHarvests(), "The powered harvest count must survive block-entity NBT reload");
					SettlementSavedData restored = SettlementSavedData.load(
						data.save(new CompoundTag(), helper.getLevel().registryAccess()), helper.getLevel().registryAccess()
					);
					Settlement restoredSettlement = restored.find(settlement.center()).orElseThrow();
					helper.assertValueEqual(CivilizationEras.INDUSTRIAL, restored.eraOf(restoredSettlement).id(),
						"The industrial era must survive settlement reload");
					helper.assertTrue(restored.eraMilestonesOf(restoredSettlement).contains(CivilizationEras.MECHANICAL_POWER),
						"The mechanical milestone must survive settlement reload");

					helper.setBlock(WATER_SOURCE, Blocks.AIR);
					helper.setBlock(CHANNEL, Blocks.AIR);
					setMatureCrop(helper);
					helper.runAfterDelay(100, () -> {
						helper.assertTrue(CropHarvest.isMature(helper.getBlockState(CROP)),
							"Removing the water must stop further mechanical harvesting");
						helper.assertValueEqual(1L, station.poweredHarvests(), "An unpowered machine must not duplicate harvest counts");
						helper.assertValueEqual(CivilizationEras.INDUSTRIAL, data.eraOf(settlement).id(),
							"Later power loss must not erase the persisted industrial achievement");
						CivilizationGameTestSupport.clearAround(helper, new BlockPos(2, 0, 2), 8);
						helper.succeed();
					});
				});
			});
		});
	}

	private static void setMatureCrop(GameTestHelper helper) {
		helper.setBlock(CROP.below(), Blocks.FARMLAND);
		CropBlock potatoes = (CropBlock) Blocks.POTATOES;
		helper.setBlock(CROP, potatoes.getStateForAge(potatoes.getMaxAge()));
	}

	private static void prepareWaterChannel(GameTestHelper helper) {
		for (int z = 6; z <= 7; z++) {
			for (int x = 2; x <= 4; x++) {
				helper.setBlock(new BlockPos(x, -1, z), Blocks.STONE);
			}
			helper.setBlock(new BlockPos(2, 0, z), Blocks.STONE);
			helper.setBlock(new BlockPos(4, 0, z), Blocks.STONE);
		}
		helper.setBlock(CHANNEL, Blocks.AIR);
		helper.setBlock(WATER_SOURCE, Blocks.AIR);
		helper.getLevel().setBlock(helper.absolutePos(WATER_SOURCE.south()), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
	}
}
