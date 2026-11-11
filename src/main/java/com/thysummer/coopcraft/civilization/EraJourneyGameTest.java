package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;

import com.thysummer.coopcraft.automation.Automation;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class EraJourneyGameTest implements FabricGameTest {
	private static final BlockPos CENTER = new BlockPos(2, 0, 2);
	private static final BlockPos STOCKPILE = new BlockPos(3, 1, 2);
	private static final BlockPos CRAFTING_TABLE = new BlockPos(1, 0, 1);
	private static final BlockPos FURNACE = new BlockPos(0, 0, 1);
	private static final BlockPos DIRECT_MACHINE = new BlockPos(3, 0, 3);
	private static final BlockPos DIRECT_CROP = new BlockPos(3, 0, 2);
	private static final BlockPos WHEEL = new BlockPos(2, 0, 3);
	private static final BlockPos MACHINE_A = new BlockPos(5, 0, 3);
	private static final BlockPos MACHINE_B = new BlockPos(4, 0, 5);
	private static final BlockPos CROP_A = new BlockPos(5, 0, 2);
	private static final BlockPos CROP_B = new BlockPos(4, 0, 6);
	private static final BlockPos ARCHIVE_A = new BlockPos(6, 0, 1);
	private static final BlockPos ARCHIVE_B = new BlockPos(6, 0, 5);
	private static final BlockPos CORE = new BlockPos(7, 0, 3);
	private static final BlockPos LAMP = new BlockPos(6, 0, 3);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 4000, batch = "era_journey")
	public void villageProgressesThroughEveryEraWithRealProduction(GameTestHelper helper) {
		CivilizationGameTestSupport.clearAround(helper, CENTER, 10);
		VillageOriginGameTest.markVanillaVillage(helper);
		encloseWorkingArea(helper);
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		Settlement settlement = CivilizationGameTestSupport.isolatedSettlement(helper, data, "Continuous Civilization");
		List<Villager> residents = new ArrayList<>();
		residents.add(helper.spawn(EntityType.VILLAGER, new BlockPos(0, 0, 2)));
		residents.add(helper.spawn(EntityType.VILLAGER, new BlockPos(0, 0, 3)));
		Villager worker = residents.getFirst();
		data.registerLoadedResidents(helper.getLevel(), settlement, residents);
		for (int index = 0; index < 6; index++) {
			putBed(helper, new BlockPos(index * 2, 0, 0));
		}
		for (int x = 2; x <= 4; x++) {
			for (int z = 1; z <= 3; z++) {
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
			}
		}
		helper.setBlock(STOCKPILE, Blocks.CHEST);
		helper.setBlock(CRAFTING_TABLE, Blocks.CRAFTING_TABLE);
		data.setStockpile(settlement, helper.absolutePos(STOCKPILE));
		data.setCraftingStation(settlement, helper.absolutePos(CRAFTING_TABLE));
		Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(STOCKPILE));
		SettlementStockpile.insert(chest, new ItemStack(Items.CARROT, 32));
		SettlementStockpile.insert(chest, new ItemStack(Items.WHEAT_SEEDS, 12));
		helper.assertValueEqual(SettlementStage.AGRICULTURAL,
			SettlementStages.initialize(helper.getLevel(), data, settlement, residents),
			"The real village and its existing housing must establish an agricultural settlement");
		CivilizationEras.refresh(helper.getLevel(), data, settlement);
		helper.assertValueEqual(SettlementOrigin.ESTABLISHED_VILLAGE, data.originOf(settlement), "The village origin must be observed");
		helper.assertValueEqual(CivilizationEras.AGRICULTURAL, data.eraOf(settlement).id(), "The journey must start agricultural");
		helper.assertFalse(data.eraMilestonesOf(settlement).contains(CivilizationEras.HEARTH),
			"This journey deliberately leaves the primitive hearth requirement incomplete");

		try {
			completeAgriculture(helper, data, settlement, residents, chest);
		} catch (RuntimeException | Error error) {
			CivilizationGameTestSupport.clearAround(helper, CENTER, 10);
			throw error;
		}
		helper.setBlock(FURNACE, Blocks.FURNACE);
		data.setSmeltingStation(settlement, helper.absolutePos(FURNACE));
		SettlementStockpile.insert(chest, new ItemStack(Items.RAW_IRON, 3));
		SettlementStockpile.insert(chest, new ItemStack(Items.COAL, 3));
		SettlementStockpile.insert(chest, new ItemStack(Items.STICK, 2));
		worker.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE_PICKAXE));
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.setGameMode(GameType.SURVIVAL);
		player.getInventory().setItem(9, new ItemStack(Items.WHEAT, 3));
		List<Step> steps = new ArrayList<>();
		steps.add(new Step(0, () -> helper.succeedWhen(() -> {
			CivilizationGameTestSupport.arriveAtWork(helper, worker,
				helper.absolutePos(STOCKPILE), helper.absolutePos(FURNACE), helper.absolutePos(CRAFTING_TABLE));
			if (!worker.getMainHandItem().is(Items.IRON_PICKAXE)) return;
			helper.assertTrue(worker.getMainHandItem().is(Items.IRON_PICKAXE),
				"The resident must really smelt iron and equip an iron pickaxe: " + VillagerWorkGoal.statusOf(worker)
					+ ", rawIron=" + chest.countItem(Items.RAW_IRON) + ", coal=" + chest.countItem(Items.COAL)
					+ ", sticks=" + chest.countItem(Items.STICK) + ", reservation=" + new VillagerWorkGoal(worker).debugState());
			helper.assertTrue(data.eraMilestonesOf(settlement).contains(CivilizationEras.METAL_SMELTING), "Smelting must be observed");
			helper.assertTrue(data.eraMilestonesOf(settlement).contains(CivilizationEras.METAL_TOOL_PRODUCTION), "Tool production must be observed");
			helper.assertValueEqual(CivilizationEras.AGRICULTURAL, data.eraOf(settlement).id(), "Metalworking cannot skip mechanical power");
			installDirectProduction(helper);
			runSteps(helper, steps, 1);
		})));
		steps.add(new Step(10, () -> harvestMachine(helper, DIRECT_MACHINE, DIRECT_CROP, dueTick(helper, DIRECT_MACHINE,
			helper.getLevel().getGameTime(), 40))));
		steps.add(new Step(10, () -> {
			helper.assertValueEqual(CivilizationEras.INDUSTRIAL, data.eraOf(settlement).id(),
				"Real public mechanical production must unlock industry");
			helper.assertTrue(data.eraMilestonesOf(settlement).contains(CivilizationEras.MECHANICAL_POWER), "Power must come from a real harvest");
			helper.assertValueEqual(0L, data.productionStatsOf(settlement, SettlementIntelligence.POWERED_HARVEST).cycles(),
				"Industrial machinery must not create information-era learning samples early");
			installSharedProduction(helper);
		}));
		steps.add(new Step(10, () -> harvestMachine(helper, MACHINE_A, CROP_A, dueTick(helper, MACHINE_A,
			helper.getLevel().getGameTime(), 40))));
		steps.add(new Step(10, () -> harvestMachine(helper, MACHINE_B, CROP_B, dueTick(helper, MACHINE_B,
			helper.getLevel().getGameTime(), 40))));
		steps.add(new Step(10, () -> {
			helper.assertTrue(data.eraMilestonesOf(settlement).contains(CivilizationEras.AUTOMATED_PRODUCTION),
				"Two different machines must share the real waterwheel and public logistics");
			helper.assertValueEqual(helper.absolutePos(WHEEL),
				SettlementIndustry.powerSource(helper.getLevel(), helper.absolutePos(MACHINE_A)).orElseThrow(), "The first machine needs real power");
			helper.assertValueEqual(helper.absolutePos(WHEEL),
				SettlementIndustry.powerSource(helper.getLevel(), helper.absolutePos(MACHINE_B)).orElseThrow(), "The second machine needs the same power source");
			helper.assertValueEqual(CivilizationEras.INDUSTRIAL, data.eraOf(settlement).id(), "Shared production cannot skip information storage");
			installInformationNetwork(helper);
			recordArchive(helper, player, ARCHIVE_A);
			setGameTime(helper, helper.getLevel().getGameTime() + 1);
			recordArchive(helper, player, ARCHIVE_B);
			useOnCore(helper, player);
		}));
		steps.add(new Step(80, () -> {
			var core = (com.thysummer.coopcraft.automation.ComputerCoreBlockEntity) helper.getLevel()
				.getBlockEntity(helper.absolutePos(CORE));
			helper.assertTrue(helper.getBlockState(LAMP).getValue(RedstoneLampBlock.LIT),
				"The real seed catalog must light the computation receiver: target=" + core.target() + ", amount=" + core.amount()
					+ ", nodes=" + core.connectedArchives() + ", seeds=" + chest.countItem(Items.WHEAT_SEEDS)
					+ ", wheat=" + chest.countItem(Items.WHEAT));
			helper.assertTrue(data.eraMilestonesOf(settlement).contains(CivilizationEras.INFORMATION_STORAGE), "The archive must record real seeds");
			helper.assertTrue(data.eraMilestonesOf(settlement).contains(CivilizationEras.COMPUTATION_NETWORK), "The network must compute the real catalog");
			helper.assertValueEqual(CivilizationEras.INFORMATION, data.eraOf(settlement).id(), "Real information infrastructure must unlock information");
			harvestMachine(helper, MACHINE_A, CROP_A, dueTick(helper, MACHINE_A, helper.getLevel().getGameTime(), 40));
		}));
		steps.add(new Step(10, () -> {
			helper.assertValueEqual(1L, data.productionStatsOf(settlement, SettlementIntelligence.POWERED_HARVEST).cycles(),
				"The first information-era harvest must begin learning");
			harvestMachine(helper, MACHINE_B, CROP_B, dueTick(helper, MACHINE_B,
				data.productionStatsOf(settlement, SettlementIntelligence.POWERED_HARVEST).lastSuccessTick() + 80, 40));
		}));
		steps.add(new Step(10, () -> harvestMachine(helper, MACHINE_A, CROP_A, dueTick(helper, MACHINE_A,
			data.productionStatsOf(settlement, SettlementIntelligence.POWERED_HARVEST).lastSuccessTick() + 80, 40))));
		steps.add(new Step(10, () -> {
			ProductionStats learned = data.productionStatsOf(settlement, SettlementIntelligence.POWERED_HARVEST);
			helper.assertValueEqual(3L, learned.cycles(), "Three information-era harvests must drive the learning decision");
			helper.assertValueEqual(20, learned.recommendedIntervalTicks(), "Long real waits must shorten the inspection interval");
			helper.assertTrue(data.eraMilestonesOf(settlement).contains(CivilizationEras.ADAPTIVE_LEARNING), "The measured change must be recorded");
			completeRealPlan(helper, data, settlement, player);
		}));
		steps.add(new Step(10, () -> {
			long earliest = Math.max(helper.getLevel().getGameTime(),
				data.productionStatsOf(settlement, SettlementIntelligence.POWERED_HARVEST).lastSuccessTick() + 20);
			harvestMachine(helper, MACHINE_A, CROP_A, dueTick(helper, MACHINE_A, earliest, 20));
		}));
		steps.add(new Step(10, () -> {
			helper.assertValueEqual(1, data.productionStatsOf(settlement, SettlementIntelligence.POWERED_HARVEST).improvedSamples(),
				"The first post-learning production cycle must be measured");
			long earliest = Math.max(helper.getLevel().getGameTime(),
				data.productionStatsOf(settlement, SettlementIntelligence.POWERED_HARVEST).lastSuccessTick() + 20);
			harvestMachine(helper, MACHINE_B, CROP_B, dueTick(helper, MACHINE_B, earliest, 20));
		}));
		steps.add(new Step(10, () -> {
			ProductionStats improved = data.productionStatsOf(settlement, SettlementIntelligence.POWERED_HARVEST);
			helper.assertValueEqual(5L, improved.cycles(), "Every learning sample must come from a real public harvest");
			helper.assertValueEqual(2, improved.improvedSamples(), "Two real post-learning cycles must verify the improvement");
			helper.assertTrue(improved.selfImproved(), "The measured cycles must improve over the baseline: " + improved);
			helper.assertTrue(data.eraMilestonesOf(settlement).contains(CivilizationEras.SELF_IMPROVING_PRODUCTION),
				"Only measured improvement may complete the final milestone");
			helper.assertValueEqual(CivilizationEras.INTELLIGENT, data.eraOf(settlement).id(), "The same settlement must reach the intelligent era");
			helper.assertValueEqual("", CivilizationEras.nextMilestoneTranslationKey(data, settlement), "The built-in journey must be complete");
			SettlementSavedData restored = SettlementSavedData.load(
				data.save(new CompoundTag(), helper.getLevel().registryAccess()), helper.getLevel().registryAccess()
			);
			Settlement restoredSettlement = restored.find(settlement.center()).orElseThrow();
			helper.assertValueEqual(SettlementOrigin.ESTABLISHED_VILLAGE, restored.originOf(restoredSettlement), "The village origin must survive reload");
			helper.assertValueEqual(CivilizationEras.INTELLIGENT, restored.eraOf(restoredSettlement).id(), "The final era must survive reload");
			helper.assertValueEqual(improved, restored.productionStatsOf(restoredSettlement, SettlementIntelligence.POWERED_HARVEST),
				"All learning evidence must survive reload");
			helper.assertValueEqual(SettlementPlan.Status.COMPLETED, restored.latestPlanOf(restoredSettlement).orElseThrow().status(),
				"The completed real plan must survive reload");
			CivilizationGameTestSupport.clearAround(helper, CENTER, 10);
		}));
		runSteps(helper, steps, 0);
	}

	private static void encloseWorkingArea(GameTestHelper helper) {
		for (int x = -1; x <= 8; x++) {
			for (int z = -1; z <= 7; z++) {
				if (x != -1 && x != 8 && z != -1 && z != 7) {
					continue;
				}
				for (int y = 0; y <= 2; y++) {
					helper.setBlock(new BlockPos(x, y, z), Blocks.BARRIER);
				}
			}
		}
	}

	private static void completeAgriculture(
		GameTestHelper helper,
		SettlementSavedData data,
		Settlement settlement,
		List<Villager> residents,
		Container chest
	) {
		Villager worker = residents.getFirst();
		List<BlockPos> crops = new ArrayList<>();
		for (int index = 0; index < 12; index++) {
			BlockPos crop = new BlockPos(index % 4, 0, 4 + index / 4);
			crops.add(crop);
			helper.setBlock(crop.below(), Blocks.FARMLAND);
		}
		for (BlockPos crop : crops) {
			worker.setPos(helper.absolutePos(crop).getCenter());
			VillagerWorkGoal goal = new VillagerWorkGoal(worker);
			helper.assertTrue(goal.canUse(), "The resident must choose real wheat planting at " + crop + ": " + goal.debugState());
			goal.start();
			String selected = goal.debugState();
			for (int tick = 0; tick < 120 && !helper.getBlockState(crop).is(Blocks.WHEAT); tick++) {
				goal.tick();
			}
			helper.assertTrue(helper.getBlockState(crop).is(Blocks.WHEAT), "Wheat must be planted by the resident at " + crop
				+ ": state=" + helper.getBlockState(crop) + ", ground=" + helper.getBlockState(crop.below())
				+ ", seeds=" + chest.countItem(Items.WHEAT_SEEDS) + ", inventory=" + worker.getInventory().countItem(Items.WHEAT_SEEDS)
				+ ", selected=" + selected + ", goal=" + goal.debugState());
		}
		for (BlockPos crop : crops) {
			helper.getLevel().setBlock(helper.absolutePos(crop), ((CropBlock) Blocks.WHEAT).getStateForAge(7), 3);
			worker.setPos(helper.absolutePos(crop).getCenter());
			VillagerWorkGoal goal = new VillagerWorkGoal(worker);
			helper.assertTrue(goal.canUse(), "The resident must choose real wheat harvesting: " + goal.debugState());
			goal.start();
			String selected = goal.debugState();
			for (int tick = 0; tick < 120 && helper.getBlockState(crop).getValue(CropBlock.AGE) != 0; tick++) {
				goal.tick();
			}
			helper.assertValueEqual(0, helper.getBlockState(crop).getValue(CropBlock.AGE), "Every harvest must replant " + crop
				+ ": selected=" + selected + ", remaining=" + goal.debugState() + ", wheat=" + chest.countItem(Items.WHEAT));
			SettlementStockpile.depositGatheredResources(chest, worker.getInventory());
		}
		helper.assertTrue(chest.countItem(Items.WHEAT) >= 12, "The resident must harvest enough real wheat for both production plans");
		SettlementSimulationService.setSurvivalEnabled(helper.getLevel(), data, settlement, true);
		SettlementSimulationService.setProductionEnabled(data, settlement, true);
		worker.setPos(helper.absolutePos(CRAFTING_TABLE).getCenter());
		VillagerWorkGoal breadGoal = new VillagerWorkGoal(worker);
		helper.assertTrue(breadGoal.canUse(), "Three real wheat items must allow bread production");
		breadGoal.start();
		String selected = breadGoal.debugState();
		for (int tick = 0; tick < 180 && SettlementSimulationService.craftingResourceCount(data, settlement, Items.BREAD) < 1; tick++) {
			breadGoal.tick();
		}
		helper.assertValueEqual(1L, SettlementSimulationService.craftingResourceCount(data, settlement, Items.BREAD),
			"Three wheat must create exactly one real bread product: selected=" + selected + ", remaining=" + breadGoal.debugState());
		Villager child = AgriculturalEraGameTest.breed(helper, residents.get(0), residents.get(1));
		child.setPos(helper.absolutePos(new BlockPos(1, 0, 2)).getCenter());
		CivilizationEras.refresh(helper.getLevel(), data, settlement);
		helper.assertTrue(data.isMember(settlement, child.getUUID()), "The real newborn must join the same settlement");
		helper.assertTrue(data.eraMilestonesOf(settlement).contains(CivilizationEras.POPULATION_GROWTH), "The real birth must be observed");
		helper.assertTrue(CivilizationEras.agriculturalComplete(data, settlement), "All four agricultural results must be complete");
		helper.assertFalse(data.eraMilestonesOf(settlement).contains(CivilizationEras.HEARTH),
			"An established village must not need to repeat the primitive hearth");
		helper.assertValueEqual("coopcraft.milestone.metal_smelting", CivilizationEras.nextMilestoneTranslationKey(data, settlement),
			"Agriculture must lead into real metalworking");
		helper.assertValueEqual(CivilizationEras.AGRICULTURAL, data.eraOf(settlement).id(), "Agriculture alone cannot unlock industry");
	}

	private static void installDirectProduction(GameTestHelper helper) {
		helper.setBlock(DIRECT_MACHINE, Automation.FARM_STATION);
		helper.setBlock(new BlockPos(3, 1, 3), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.NORTH));
		helper.setBlock(WHEEL, Automation.WATERWHEEL.defaultBlockState().setValue(WaterwheelBlock.FACING, Direction.EAST));
		prepareFlowingWater(helper);
	}

	private static void installSharedProduction(GameTestHelper helper) {
		for (BlockPos shaft : List.of(DIRECT_MACHINE, new BlockPos(4, 0, 3))) {
			helper.setBlock(shaft, Automation.DRIVE_SHAFT.defaultBlockState().setValue(DriveShaftBlock.AXIS, Direction.Axis.X));
		}
		helper.setBlock(new BlockPos(4, 0, 4), Automation.DRIVE_SHAFT.defaultBlockState().setValue(DriveShaftBlock.AXIS, Direction.Axis.Z));
		for (BlockPos machine : List.of(MACHINE_A, MACHINE_B)) {
			helper.setBlock(machine, Automation.FARM_STATION);
		}
		helper.setBlock(new BlockPos(5, 1, 3), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.NORTH));
		helper.setBlock(new BlockPos(5, 1, 2), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.WEST));
		helper.setBlock(new BlockPos(4, 1, 2), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.WEST));
		helper.setBlock(new BlockPos(4, 1, 5), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.NORTH));
		helper.setBlock(new BlockPos(4, 1, 4), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.NORTH));
		helper.setBlock(new BlockPos(4, 1, 3), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.NORTH));
	}

	private static void installInformationNetwork(GameTestHelper helper) {
		for (BlockPos archive : List.of(ARCHIVE_A, ARCHIVE_B)) {
			helper.setBlock(archive, Automation.ARCHIVE);
		}
		helper.setBlock(CORE, Automation.COMPUTER_CORE);
		helper.setBlock(LAMP, Blocks.REDSTONE_LAMP);
		for (int z : new int[] {1, 2, 4, 5}) {
			helper.setBlock(new BlockPos(7, 0, z), Blocks.REDSTONE_WIRE);
		}
	}

	private static void completeRealPlan(GameTestHelper helper, SettlementSavedData data, Settlement settlement, ServerPlayer player) {
		Container stockpile = SettlementStockpile.getStockpile(helper.getLevel(), data, settlement);
		helper.assertValueEqual(3, player.getInventory().countItem(Items.WHEAT), "Three wheat items must remain reserved in the player inventory");
		helper.assertValueEqual(3, SettlementStockpile.depositItem(stockpile, player.getInventory(), Items.WHEAT),
			"The player must donate three reserved wheat items to the real stockpile");
		helper.assertValueEqual(0, player.getInventory().countItem(Items.WHEAT), "The donated wheat must leave the player inventory");
		long offset = settlement.id().getLeastSignificantBits();
		setGameTime(helper, nextTick(helper.getLevel().getGameTime() + offset, 100) - offset);
		SettlementIntelligence.tick(helper.getLevel(), data, settlement);
		var core = (com.thysummer.coopcraft.automation.ComputerCoreBlockEntity) helper.getLevel()
			.getBlockEntity(helper.absolutePos(CORE));
		helper.assertTrue(data.activePlanOf(settlement).isPresent(), "The real information network must create a shortage plan: wheat="
			+ stockpile.countItem(Items.WHEAT) + ", bread=" + stockpile.countItem(Items.BREAD)
			+ ", abstractBread=" + SettlementSimulationService.craftingResourceCount(data, settlement, Items.BREAD)
			+ ", nodes=" + core.connectedArchives() + ", stage=" + data.stageOf(settlement) + ", era=" + data.eraOf(settlement).id());
		SettlementPlan plan = data.activePlanOf(settlement).orElseThrow();
		Villager worker = (Villager) helper.getLevel().getEntity(plan.workerId());
		VillagerWorkGoal goal = new VillagerWorkGoal(worker);
		helper.assertTrue(goal.canUse(), "The assigned resident must accept the real production plan: " + goal.debugState());
		goal.start();
		worker.setPos(helper.absolutePos(CRAFTING_TABLE).getCenter());
		for (int tick = 0; tick < 120 && data.activePlanOf(settlement).isPresent(); tick++) {
			goal.tick();
		}
		helper.assertValueEqual(SettlementPlan.Status.COMPLETED, data.latestPlanOf(settlement).orElseThrow().status(),
			"The assigned resident must actually produce the planned bread");
		helper.assertValueEqual(1, data.latestPlanOf(settlement).orElseThrow().producedQuantity(), "The plan must record exactly one bread");
		helper.assertTrue(data.eraMilestonesOf(settlement).contains(CivilizationEras.AUTONOMOUS_PLANNING),
			"Only real plan output may complete autonomous planning");
		helper.assertValueEqual(CivilizationEras.INFORMATION, data.eraOf(settlement).id(), "A plan alone cannot skip verified improvement");
	}

	private static void harvestMachine(GameTestHelper helper, BlockPos machine, BlockPos crop, long earliest) {
		helper.setBlock(crop.below(), Blocks.FARMLAND);
		helper.setBlock(crop, ((CropBlock) Blocks.POTATOES).getStateForAge(7));
		BlockPos absolute = helper.absolutePos(machine);
		int interval = SettlementIntelligence.workInterval(helper.getLevel(), absolute);
		setGameTime(helper, dueTick(helper, machine, Math.max(earliest, helper.getLevel().getGameTime()), interval));
		FarmStationBlockEntity station = (FarmStationBlockEntity) helper.getLevel().getBlockEntity(absolute);
		FarmStationBlockEntity.tick(helper.getLevel(), absolute, station.getBlockState(), station);
		helper.assertValueEqual(0, helper.getBlockState(crop).getValue(CropBlock.AGE),
			"The machine must really harvest and replant at " + machine + "; power="
				+ SettlementIndustry.powerSource(helper.getLevel(), absolute) + ", interval=" + SettlementIntelligence.workInterval(helper.getLevel(), absolute));
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

	private static void recordArchive(GameTestHelper helper, ServerPlayer player, BlockPos archive) {
		BlockPos absolute = helper.absolutePos(archive);
		player.setPos(absolute.above().getCenter());
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WRITABLE_BOOK));
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false);
		player.gameMode.useItemOn(player, helper.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
		helper.assertValueEqual(0, player.getInventory().countItem(Items.WRITABLE_BOOK),
			"Recording the real stockpile must consume one book at " + archive);
	}

	private static void useOnCore(GameTestHelper helper, ServerPlayer player) {
		BlockPos absolute = helper.absolutePos(CORE);
		player.setPos(absolute.above().getCenter());
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT_SEEDS));
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false);
		player.gameMode.useItemOn(player, helper.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
	}

	private static void putBed(GameTestHelper helper, BlockPos pos) {
		BlockPos foot = helper.absolutePos(pos);
		BlockPos head = foot.east();
		helper.getLevel().setBlock(foot.below(), Blocks.STONE.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
		helper.getLevel().setBlock(head.below(), Blocks.STONE.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
		var state = Blocks.RED_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING, Direction.EAST);
		helper.getLevel().setBlock(foot, state.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
		helper.getLevel().setBlock(head, state.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD), net.minecraft.world.level.block.Block.UPDATE_ALL);
	}

	private static long dueTick(GameTestHelper helper, BlockPos machine, long earliest, int interval) {
		return nextTick(earliest + helper.absolutePos(machine).asLong(), interval) - helper.absolutePos(machine).asLong();
	}

	private static long nextTick(long time, int interval) {
		return Math.floorDiv(time + interval - 1, interval) * interval;
	}

	private static void setGameTime(GameTestHelper helper, long time) {
		if (!(helper.getLevel().getLevelData() instanceof PrimaryLevelData data)) {
			throw new IllegalStateException("The test world must expose its real game clock");
		}
		data.setGameTime(Math.max(helper.getLevel().getGameTime(), time));
	}

	private static void runSteps(GameTestHelper helper, List<Step> steps, int index) {
		if (index == steps.size()) {
			helper.succeed();
			return;
		}
		Step step = steps.get(index);
		helper.runAfterDelay(step.delayTicks(), () -> {
			try {
				step.action().run();
			} catch (RuntimeException | Error error) {
				CivilizationGameTestSupport.clearAround(helper, CENTER, 10);
				throw error;
			}
			runSteps(helper, steps, index + 1);
		});
	}

	private record Step(int delayTicks, Runnable action) {
	}
}
