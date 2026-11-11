package com.thysummer.coopcraft.civilization;

import java.util.List;

import com.thysummer.coopcraft.automation.ArchiveBlockEntity;
import com.thysummer.coopcraft.automation.Automation;
import com.thysummer.coopcraft.automation.ComputerCoreBlockEntity;
import com.thysummer.coopcraft.automation.CropHarvest;
import com.thysummer.coopcraft.automation.DriveShaftBlock;
import com.thysummer.coopcraft.automation.FarmStationBlockEntity;
import com.thysummer.coopcraft.automation.WaterwheelBlock;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.GameType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class InformationEraGameTest implements FabricGameTest {
	private static final BlockPos STOCKPILE = new BlockPos(3, 1, 2);
	private static final BlockPos WHEEL = new BlockPos(2, 0, 3);
	private static final BlockPos SHAFT_A = new BlockPos(3, 0, 3);
	private static final BlockPos SHAFT_B = new BlockPos(4, 0, 3);
	private static final BlockPos SHAFT_C = new BlockPos(4, 0, 4);
	private static final BlockPos MACHINE_A = new BlockPos(5, 0, 3);
	private static final BlockPos MACHINE_B = new BlockPos(4, 0, 5);
	private static final BlockPos CROP_A = new BlockPos(5, 0, 2);
	private static final BlockPos CROP_B = new BlockPos(4, 0, 6);
	private static final BlockPos HOPPER_A = new BlockPos(5, 1, 3);
	private static final BlockPos HOPPER_B = new BlockPos(4, 1, 5);
	private static final BlockPos ARCHIVE_A = new BlockPos(6, 0, 1);
	private static final BlockPos ARCHIVE_B = new BlockPos(6, 0, 5);
	private static final BlockPos CORE = new BlockPos(7, 0, 3);
	private static final BlockPos LAMP = new BlockPos(6, 0, 3);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 700, batch = "information_production")
	public void sharedShaftAndLogisticsProduceForOneStockpile(GameTestHelper helper) {
		Settlement settlement = industrialSettlement(helper);
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		helper.setBlock(WHEEL, Automation.WATERWHEEL.defaultBlockState().setValue(WaterwheelBlock.FACING, Direction.EAST));
		prepareFlowingWater(helper);
		for (BlockPos machine : List.of(MACHINE_A, MACHINE_B)) {
			helper.setBlock(machine, Automation.FARM_STATION);
		}
		helper.setBlock(HOPPER_A, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.NORTH));
		helper.setBlock(new BlockPos(5, 1, 2), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.WEST));
		helper.setBlock(new BlockPos(4, 1, 2), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.WEST));
		helper.setBlock(HOPPER_B, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.NORTH));
		helper.setBlock(new BlockPos(4, 1, 4), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.NORTH));
		helper.setBlock(new BlockPos(4, 1, 3), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.NORTH));
		setMatureCrop(helper, CROP_A);
		setMatureCrop(helper, CROP_B);
		FarmStationBlockEntity first = (FarmStationBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(MACHINE_A));
		FarmStationBlockEntity second = (FarmStationBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(MACHINE_B));
		Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(STOCKPILE));

		helper.runAfterDelay(60, () -> {
			helper.assertTrue(CropHarvest.isMature(helper.getBlockState(CROP_A)), "A missing shaft cannot transmit power to the first machine: crop="
				+ helper.getBlockState(CROP_A) + ", source=" + SettlementIndustry.powerSource(helper.getLevel(), helper.absolutePos(MACHINE_A))
				+ ", harvests=" + first.poweredHarvests() + ", ground=" + helper.getBlockState(CROP_A.below()));
			helper.assertTrue(CropHarvest.isMature(helper.getBlockState(CROP_B)), "A missing shaft cannot transmit power to the second machine");
			helper.assertFalse(data.eraMilestonesOf(settlement).contains(CivilizationEras.AUTOMATED_PRODUCTION),
				"Unconnected machines cannot complete the production network");
			for (BlockPos shaft : List.of(SHAFT_A, SHAFT_B)) {
				helper.setBlock(shaft, Automation.DRIVE_SHAFT.defaultBlockState().setValue(DriveShaftBlock.AXIS, Direction.Axis.X));
			}
			helper.setBlock(SHAFT_C, Automation.DRIVE_SHAFT.defaultBlockState().setValue(DriveShaftBlock.AXIS, Direction.Axis.Z));
			helper.runAfterDelay(140, () -> {
				helper.assertValueEqual(0, helper.getBlockState(CROP_A).getValue(CropBlock.AGE),
					"The first machine must harvest and replant through the shared shaft");
				for (BlockPos shaft : List.of(SHAFT_A, SHAFT_B, SHAFT_C)) {
					helper.assertTrue(SettlementIndustry.powerSource(helper.getLevel(), helper.absolutePos(shaft)).isPresent(),
						"Power search stopped at " + shaft + ": source=" + SettlementIndustry.powerSource(helper.getLevel(), helper.absolutePos(shaft))
							+ ", wheel=" + helper.getBlockState(WHEEL) + ", driven=" + WaterwheelBlock.isDriven(
								helper.getLevel(), helper.absolutePos(WHEEL), helper.getBlockState(WHEEL)));
				}
				helper.assertTrue(SettlementIndustry.isPowered(helper.getLevel(), helper.absolutePos(MACHINE_B)),
					"The second machine must have a powered shaft path: source=" + SettlementIndustry.powerSource(helper.getLevel(), helper.absolutePos(MACHINE_B))
						+ ", crop=" + helper.getBlockState(CROP_B) + ", harvests=" + second.poweredHarvests()
						+ ", branch=" + helper.getBlockState(SHAFT_C) + ", joint=" + helper.getBlockState(SHAFT_B)
						+ ", root=" + helper.getBlockState(SHAFT_A) + ", water=" + helper.getLevel().getFluidState(helper.absolutePos(WHEEL.west())));
				helper.assertValueEqual(0, helper.getBlockState(CROP_B).getValue(CropBlock.AGE),
					"The second machine must harvest and replant through the same shaft");
				helper.assertValueEqual(helper.absolutePos(WHEEL),
					SettlementIndustry.powerSource(helper.getLevel(), helper.absolutePos(MACHINE_A)).orElseThrow(),
					"The first machine must identify the actual shared waterwheel");
				helper.assertValueEqual(helper.absolutePos(WHEEL),
					SettlementIndustry.powerSource(helper.getLevel(), helper.absolutePos(MACHINE_B)).orElseThrow(),
					"The second machine must identify the same actual waterwheel");
				helper.assertTrue(chest.countItem(Items.POTATO) >= 2,
					"Both real harvests must travel through hoppers into the one public stockpile");
				helper.assertValueEqual(1L, first.poweredHarvests(), "The first real machine must record exactly one harvest");
				helper.assertValueEqual(1L, second.poweredHarvests(), "The second real machine must record exactly one harvest");
				helper.assertTrue(data.eraMilestonesOf(settlement).contains(CivilizationEras.AUTOMATED_PRODUCTION),
					"Two producers sharing one power source and public logistics must complete automated production");
				helper.assertValueEqual(CivilizationEras.INDUSTRIAL, data.eraOf(settlement).id(),
					"A production network alone cannot skip information storage and computation");
				SettlementSavedData restored = SettlementSavedData.load(
					data.save(new CompoundTag(), helper.getLevel().registryAccess()), helper.getLevel().registryAccess()
				);
				Settlement restoredSettlement = restored.find(settlement.center()).orElseThrow();
				helper.assertTrue(restored.eraMilestonesOf(restoredSettlement).contains(CivilizationEras.AUTOMATED_PRODUCTION),
					"The shared production evidence must survive settlement reload");
				CivilizationGameTestSupport.clearAround(helper, new BlockPos(2, 0, 2), 8);
				helper.succeed();
			});
		});
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 800, batch = "information_network")
	public void archiveReplicationAndRedstoneComputationUnlockInformation(GameTestHelper helper) {
		Settlement settlement = industrialSettlement(helper);
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		data.completeEraMilestone(settlement, CivilizationEras.AUTOMATED_PRODUCTION);
		for (BlockPos archive : List.of(ARCHIVE_A, ARCHIVE_B)) {
			helper.setBlock(archive, Automation.ARCHIVE);
		}
		helper.setBlock(CORE, Automation.COMPUTER_CORE);
		helper.setBlock(LAMP, Blocks.REDSTONE_LAMP);
		for (int z : new int[] {1, 2, 4, 5}) {
			helper.setBlock(new BlockPos(7, 0, z), Blocks.REDSTONE_WIRE);
		}
		ArchiveBlockEntity first = (ArchiveBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(ARCHIVE_A));
		ArchiveBlockEntity second = (ArchiveBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(ARCHIVE_B));
		ComputerCoreBlockEntity core = (ComputerCoreBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(CORE));
		Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(STOCKPILE));
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.setGameMode(GameType.SURVIVAL);

		recordWithBook(helper, player, ARCHIVE_A);
		helper.assertValueEqual(0, player.getInventory().countItem(Items.WRITABLE_BOOK), "Recording a catalog must consume exactly one book");
		helper.assertValueEqual(4L, first.count(Items.BREAD), "The archive must record the real bread count");
		helper.assertTrue(data.eraMilestonesOf(settlement).contains(CivilizationEras.INFORMATION_STORAGE),
			"Writing the real stockpile catalog must complete searchable storage");
		helper.assertValueEqual(15, helper.getBlockState(ARCHIVE_A).getSignal(helper.getLevel(), helper.absolutePos(ARCHIVE_A), Direction.NORTH),
			"A recorded archive must emit a real redstone signal");
		helper.assertValueEqual(CivilizationEras.INDUSTRIAL, data.eraOf(settlement).id(),
			"Storage alone cannot skip the production and computation requirements");

		helper.runAfterDelay(20, () -> {
			chest.clearContent();
			recordWithBook(helper, player, ARCHIVE_B);
			SettlementStockpile.insert(chest, new ItemStack(Items.BREAD, 4));
			helper.assertTrue(second.hasRecord(), "An empty but real catalog is still an active information node");
			helper.assertValueEqual(0L, second.count(Items.BREAD), "The newer empty catalog must not inherit stock without synchronization");
			helper.runAfterDelay(40, () -> {
				helper.assertFalse(helper.getBlockState(LAMP).getValue(RedstoneLampBlock.LIT),
					"The newest empty catalog must not satisfy the four-bread computation: signal=" + core.signal()
						+ ", nodes=" + core.connectedArchives() + ", amount=" + core.amount()
						+ ", latestTick=" + second.catalog().orElseThrow().recordedTick()
						+ ", lampPower=" + helper.getLevel().getBestNeighborSignal(helper.absolutePos(LAMP))
						+ ", north=" + helper.getBlockState(LAMP.north()) + "/" + helper.getLevel().getDirectSignal(helper.absolutePos(LAMP), Direction.NORTH)
						+ ", south=" + helper.getBlockState(LAMP.south()) + "/" + helper.getLevel().getDirectSignal(helper.absolutePos(LAMP), Direction.SOUTH)
						+ ", east=" + helper.getBlockState(LAMP.east()) + "/" + helper.getLevel().getDirectSignal(helper.absolutePos(LAMP), Direction.EAST)
						+ ", west=" + helper.getBlockState(LAMP.west()) + "/" + helper.getLevel().getDirectSignal(helper.absolutePos(LAMP), Direction.WEST)
						+ ", up=" + helper.getBlockState(LAMP.above()) + "/" + helper.getLevel().getDirectSignal(helper.absolutePos(LAMP), Direction.UP)
						+ ", down=" + helper.getBlockState(LAMP.below()) + "/" + helper.getLevel().getDirectSignal(helper.absolutePos(LAMP), Direction.DOWN)
						+ ", coreConductor=" + helper.getBlockState(CORE).isRedstoneConductor(helper.getLevel(), helper.absolutePos(CORE)));
				helper.assertFalse(data.eraMilestonesOf(settlement).contains(CivilizationEras.COMPUTATION_NETWORK),
					"A connected network with insufficient items cannot complete computation");
				recordWithBook(helper, player, ARCHIVE_A);
				helper.runAfterDelay(80, () -> {
					helper.assertValueEqual(4L, second.count(Items.BREAD),
						"The newer four-bread catalog must replicate from the first archive to the second");
					helper.assertValueEqual(2, core.connectedArchives(), "Both archive nodes must be linked by real powered redstone");
					helper.assertValueEqual(4L, core.amount(), "The core must compute from the latest replicated catalog");
					helper.assertValueEqual(15, core.signal(), "Four bread must produce the computed redstone output");
					helper.assertTrue(helper.getBlockState(LAMP).getValue(RedstoneLampBlock.LIT),
						"The computed result must light a real adjacent redstone lamp");
					for (int z : new int[] {1, 2, 4, 5}) {
						helper.assertTrue(helper.getBlockState(new BlockPos(7, 0, z)).getValue(RedStoneWireBlock.POWER) > 0,
							"The archive network must use actually powered redstone wire at " + z);
					}
					helper.assertTrue(data.eraMilestonesOf(settlement).contains(CivilizationEras.COMPUTATION_NETWORK),
						"Real linked computation and receiver output must complete the network milestone");
					helper.assertValueEqual(CivilizationEras.INFORMATION, data.eraOf(settlement).id(),
						"Shared production, searchable records and real computation must unlock the information era");
					helper.assertValueEqual("coopcraft.milestone.autonomous_planning",
						CivilizationEras.nextMilestoneTranslationKey(data, settlement),
						"Information completion must not skip the intelligent-era requirements");

					CompoundTag archiveSaved = second.saveWithFullMetadata(helper.getLevel().registryAccess());
					second.loadAdditional(archiveSaved, helper.getLevel().registryAccess());
					helper.assertValueEqual(4L, second.count(Items.BREAD), "The replicated archive must survive block-entity reload");
					CompoundTag coreSaved = core.saveWithFullMetadata(helper.getLevel().registryAccess());
					core.loadAdditional(coreSaved, helper.getLevel().registryAccess());
					helper.assertValueEqual(15, core.signal(), "The computation result must survive block-entity reload");
					SettlementSavedData restored = SettlementSavedData.load(
						data.save(new CompoundTag(), helper.getLevel().registryAccess()), helper.getLevel().registryAccess()
					);
					Settlement restoredSettlement = restored.find(settlement.center()).orElseThrow();
					helper.assertValueEqual(CivilizationEras.INFORMATION, restored.eraOf(restoredSettlement).id(),
						"The information era must survive settlement reload");
					helper.assertValueEqual(2, restored.archiveStationsOf(restoredSettlement).size(),
						"Both registered archive nodes must survive settlement reload");
					CivilizationGameTestSupport.clearAround(helper, new BlockPos(2, 0, 2), 8);
					helper.succeed();
				});
			});
		});
	}

	private static Settlement industrialSettlement(GameTestHelper helper) {
		CivilizationGameTestSupport.clearAround(helper, new BlockPos(2, 0, 2), 8);
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		Settlement settlement = CivilizationGameTestSupport.isolatedSettlement(helper, data, "Information Review");
		data.setEra(settlement, CivilizationEras.byId(CivilizationEras.INDUSTRIAL));
		for (var milestone : CivilizationEras.byId(CivilizationEras.AGRICULTURAL).milestones()) {
			data.completeEraMilestone(settlement, milestone);
		}
		for (var milestone : List.of(
			CivilizationEras.STAPLE_CROP,
			CivilizationEras.BREAD_BAKED,
			CivilizationEras.HOUSING_COMPLETE,
			CivilizationEras.POPULATION_GROWTH,
			CivilizationEras.METAL_SMELTING,
			CivilizationEras.METAL_TOOL_PRODUCTION,
			CivilizationEras.MECHANICAL_POWER
		)) {
			data.completeEraMilestone(settlement, milestone);
		}
		helper.setBlock(STOCKPILE, Blocks.CHEST);
		data.setStockpile(settlement, helper.absolutePos(STOCKPILE));
		Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(STOCKPILE));
		SettlementStockpile.insert(chest, new ItemStack(Items.BREAD, 4));
		return settlement;
	}

	private static void prepareFlowingWater(GameTestHelper helper) {
		helper.setBlock(new BlockPos(1, -1, 3), Blocks.STONE);
		for (int z : new int[] {2, 4}) {
			helper.setBlock(new BlockPos(1, 0, z), Blocks.STONE);
			helper.setBlock(new BlockPos(1, 1, z), Blocks.STONE);
		}
		for (int x : new int[] {0, 2}) {
			helper.setBlock(new BlockPos(x, 1, 3), Blocks.STONE);
		}
		helper.setBlock(new BlockPos(0, 0, 3), Blocks.STONE);
		helper.setBlock(new BlockPos(1, 2, 3), Blocks.STONE);
		helper.setBlock(WHEEL.west(), Blocks.AIR);
		helper.setBlock(new BlockPos(1, 1, 3), Blocks.WATER);
	}

	private static void setMatureCrop(GameTestHelper helper, BlockPos pos) {
		helper.setBlock(pos.below(), Blocks.FARMLAND);
		CropBlock potatoes = (CropBlock) Blocks.POTATOES;
		helper.setBlock(pos, potatoes.getStateForAge(potatoes.getMaxAge()));
	}

	private static void recordWithBook(GameTestHelper helper, ServerPlayer player, BlockPos archive) {
		BlockPos absolute = helper.absolutePos(archive);
		player.setPos(absolute.above().getCenter());
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WRITABLE_BOOK));
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false);
		player.gameMode.useItemOn(player, helper.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
		helper.assertValueEqual(0, player.getInventory().countItem(Items.WRITABLE_BOOK),
			"The real block interaction must consume one book at " + archive);
	}
}
