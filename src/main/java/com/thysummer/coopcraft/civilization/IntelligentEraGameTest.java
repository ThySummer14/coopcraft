package com.thysummer.coopcraft.civilization;

import java.util.List;

import com.thysummer.coopcraft.automation.Automation;
import com.thysummer.coopcraft.automation.ComputerCoreBlockEntity;
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
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class IntelligentEraGameTest implements FabricGameTest {
	private static final BlockPos STOCKPILE = new BlockPos(3, 1, 4);
	private static final BlockPos CRAFTING_TABLE = new BlockPos(1, 0, 1);
	private static final BlockPos ARCHIVE = new BlockPos(6, 0, 1);
	private static final BlockPos CORE = new BlockPos(6, 0, 5);
	private static final BlockPos MACHINE = new BlockPos(3, 0, 4);
	private static final BlockPos WHEEL = new BlockPos(3, 0, 5);
	private static final BlockPos CHANNEL = new BlockPos(3, 0, 6);
	private static final BlockPos WATER_SOURCE = new BlockPos(3, 0, 7);
	private static final BlockPos CROP = new BlockPos(3, 0, 3);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1400, batch = "intelligent_core")
	public void planningLearningAndMeasuredImprovementUnlockIntelligence(GameTestHelper helper) {
		CivilizationGameTestSupport.clearAround(helper, new BlockPos(2, 0, 2), 8);
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		Settlement settlement = CivilizationGameTestSupport.isolatedSettlement(helper, data, "Intelligent Review");
		prepareInformationSettlement(helper, data, settlement);
		helper.setBlock(CRAFTING_TABLE, Blocks.CRAFTING_TABLE);
		data.setCraftingStation(settlement, helper.absolutePos(CRAFTING_TABLE));
		helper.setBlock(ARCHIVE, Automation.ARCHIVE);
		helper.setBlock(CORE, Automation.COMPUTER_CORE);
		for (int z = 2; z <= 4; z++) {
			helper.setBlock(new BlockPos(6, 0, z), Blocks.REDSTONE_WIRE);
		}
		Villager worker = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 0, 2));
		data.registerLoadedResidents(helper.getLevel(), settlement, List.of(worker));
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.setGameMode(GameType.SURVIVAL);
		recordWithBook(helper, player);
		helper.getLevel().updateNeighborsAt(helper.absolutePos(ARCHIVE), Automation.ARCHIVE);
		tickCore(helper);
		tickPlanner(helper, settlement);
		helper.assertTrue(data.activePlanOf(settlement).isPresent(), "The real information network must allow a production plan");

		VillagerWorkGoal goal = new VillagerWorkGoal(worker);
		helper.assertTrue(goal.canUse(), "The assigned resident must accept the real bread plan: " + goal.debugState());
		goal.start();
		worker.setPos(helper.absolutePos(CRAFTING_TABLE).getCenter());
		for (int tick = 0; tick < 120 && data.activePlanOf(settlement).isPresent(); tick++) {
			goal.tick();
		}
		SettlementPlan plan = data.latestPlanOf(settlement).orElseThrow();
		Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(STOCKPILE));
		helper.assertValueEqual(SettlementPlan.Status.COMPLETED, plan.status(),
			"The assigned resident must actually complete the autonomous bread plan: " + plan);
		helper.assertValueEqual(1, plan.producedQuantity(), "The plan must record exactly one real bread output");
		helper.assertValueEqual(2, chest.countItem(Items.BREAD) + worker.getInventory().countItem(Items.BREAD),
			"The initial bread and the planned bread must remain real items across the stockpile and worker inventory");
		helper.assertTrue(data.eraMilestonesOf(settlement).contains(CivilizationEras.AUTONOMOUS_PLANNING),
			"Only actual output may complete autonomous planning");
		helper.assertValueEqual(CivilizationEras.INFORMATION, data.eraOf(settlement).id(),
			"Planning alone cannot skip learning and measured improvement");
		worker.kill();

		helper.setBlock(MACHINE, Automation.FARM_STATION);
		helper.setBlock(WHEEL, Automation.WATERWHEEL.defaultBlockState().setValue(WaterwheelBlock.FACING, Direction.NORTH));
		prepareWaterChannel(helper);
		setMatureCrop(helper);
		tickMachine(helper, nextTick(helper.getLevel().getGameTime() + helper.absolutePos(MACHINE).asLong(), 40)
			- helper.absolutePos(MACHINE).asLong());
		ProductionStats first = data.productionStatsOf(settlement, SettlementIntelligence.POWERED_HARVEST);
		helper.assertValueEqual(1L, first.cycles(), "The first real harvest must create the first learning sample");
		helper.assertValueEqual(40, data.productionIntervalOf(settlement, SettlementIntelligence.POWERED_HARVEST),
			"An untrained machine must retain its original interval");

		advanceMachine(helper, data, settlement, 80);
		helper.assertValueEqual(2L, data.productionStatsOf(settlement, SettlementIntelligence.POWERED_HARVEST).cycles(),
			"The second spaced harvest must provide another real interval");
		advanceMachine(helper, data, settlement, 80);
		ProductionStats learned = data.productionStatsOf(settlement, SettlementIntelligence.POWERED_HARVEST);
		helper.assertValueEqual(3L, learned.cycles(), "Three real harvests must drive the learning decision");
		helper.assertValueEqual(20, learned.recommendedIntervalTicks(), "Long waits must shorten the next inspection interval");
		helper.assertValueEqual(80L, learned.baselineCycleTicks(), "The learned baseline must come from measured cycles");
		helper.assertTrue(data.eraMilestonesOf(settlement).contains(CivilizationEras.ADAPTIVE_LEARNING),
			"A data-driven interval change must complete adaptive learning");

		advanceMachine(helper, data, settlement, 20);
		ProductionStats fourth = data.productionStatsOf(settlement, SettlementIntelligence.POWERED_HARVEST);
		helper.assertValueEqual(4L, fourth.cycles(), "The shortened interval must execute a real fourth harvest");
		helper.assertValueEqual(1, fourth.improvedSamples(), "The first post-learning cycle must be measured");
		advanceMachine(helper, data, settlement, 20);
		ProductionStats improved = data.productionStatsOf(settlement, SettlementIntelligence.POWERED_HARVEST);
		helper.assertValueEqual(5L, improved.cycles(), "Two post-learning harvests must verify the changed interval");
		helper.assertValueEqual(2, improved.improvedSamples(), "Both improved cycles must be recorded");
		helper.assertTrue(improved.selfImproved(), "The measured post-learning cycles must improve on the baseline");
		helper.assertTrue(data.eraMilestonesOf(settlement).contains(CivilizationEras.SELF_IMPROVING_PRODUCTION),
			"Only verified production improvement may complete the final milestone");
		helper.assertValueEqual(CivilizationEras.INTELLIGENT, data.eraOf(settlement).id(),
			"Planning, learning and verified improvement must unlock the intelligent era");
		helper.assertValueEqual("", CivilizationEras.nextMilestoneTranslationKey(data, settlement),
			"The intelligent era is the final built-in era");

		SettlementSavedData restored = SettlementSavedData.load(
			data.save(new CompoundTag(), helper.getLevel().registryAccess()), helper.getLevel().registryAccess()
		);
		Settlement restoredSettlement = restored.find(settlement.center()).orElseThrow();
		helper.assertValueEqual(CivilizationEras.INTELLIGENT, restored.eraOf(restoredSettlement).id(),
			"The final era must survive settlement reload");
		helper.assertValueEqual(improved, restored.productionStatsOf(restoredSettlement, SettlementIntelligence.POWERED_HARVEST),
			"All learning evidence must survive settlement reload");
		helper.assertValueEqual(plan, restored.latestPlanOf(restoredSettlement).orElseThrow(),
			"The completed autonomous plan must survive settlement reload");
		helper.assertValueEqual(20, restored.productionIntervalOf(restoredSettlement, SettlementIntelligence.POWERED_HARVEST),
			"The learned machine interval must survive settlement reload");
		CivilizationGameTestSupport.clearAround(helper, new BlockPos(2, 0, 2), 8);
		helper.succeed();
	}

	private static void prepareInformationSettlement(GameTestHelper helper, SettlementSavedData data, Settlement settlement) {
		data.setStage(settlement, SettlementStage.AGRICULTURAL);
		data.setEra(settlement, CivilizationEras.byId(CivilizationEras.INFORMATION));
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
			CivilizationEras.MECHANICAL_POWER,
			CivilizationEras.AUTOMATED_PRODUCTION,
			CivilizationEras.COMPUTATION_NETWORK
		)) {
			data.completeEraMilestone(settlement, milestone);
		}
		helper.setBlock(STOCKPILE, Blocks.CHEST);
		data.setStockpile(settlement, helper.absolutePos(STOCKPILE));
		Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(STOCKPILE));
		SettlementStockpile.insert(chest, new ItemStack(Items.WHEAT, 3));
		SettlementStockpile.insert(chest, new ItemStack(Items.BREAD, 1));
	}

	private static void prepareWaterChannel(GameTestHelper helper) {
		for (int z = 6; z <= 7; z++) {
			for (int x = 2; x <= 4; x++) {
				helper.setBlock(new BlockPos(x, -1, z), Blocks.STONE);
			}
			helper.setBlock(new BlockPos(2, 0, z), Blocks.STONE);
			helper.setBlock(new BlockPos(4, 0, z), Blocks.STONE);
		}
		helper.setBlock(WATER_SOURCE.south(), Blocks.STONE);
		helper.setBlock(CHANNEL, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1));
		helper.setBlock(WATER_SOURCE, Blocks.WATER);
	}

	private static void setMatureCrop(GameTestHelper helper) {
		helper.setBlock(CROP.below(), Blocks.FARMLAND);
		CropBlock potatoes = (CropBlock) Blocks.POTATOES;
		helper.setBlock(CROP, potatoes.getStateForAge(potatoes.getMaxAge()));
	}

	private static void recordWithBook(GameTestHelper helper, ServerPlayer player) {
		BlockPos absolute = helper.absolutePos(ARCHIVE);
		player.setPos(absolute.above().getCenter());
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WRITABLE_BOOK));
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false);
		player.gameMode.useItemOn(player, helper.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
		helper.assertValueEqual(0, player.getInventory().countItem(Items.WRITABLE_BOOK),
			"The information infrastructure must be created by consuming a real book");
	}

	private static void tickCore(GameTestHelper helper) {
		BlockPos pos = helper.absolutePos(CORE);
		setGameTime(helper, nextTick(helper.getLevel().getGameTime() + pos.asLong(), 20) - pos.asLong());
		ComputerCoreBlockEntity core = (ComputerCoreBlockEntity) helper.getLevel().getBlockEntity(pos);
		ComputerCoreBlockEntity.tick(helper.getLevel(), pos, core.getBlockState(), core);
	}

	private static void tickPlanner(GameTestHelper helper, Settlement settlement) {
		long offset = settlement.id().getLeastSignificantBits();
		setGameTime(helper, nextTick(helper.getLevel().getGameTime() + offset, 100) - offset);
		SettlementIntelligence.tick(helper.getLevel(), SettlementSavedData.get(helper.getLevel()), settlement);
	}

	private static void advanceMachine(GameTestHelper helper, SettlementSavedData data, Settlement settlement, int interval) {
		long time = data.productionStatsOf(settlement, SettlementIntelligence.POWERED_HARVEST).lastSuccessTick() + interval;
		setMatureCrop(helper);
		tickMachine(helper, time);
	}

	private static void tickMachine(GameTestHelper helper, long time) {
		setGameTime(helper, time);
		BlockPos pos = helper.absolutePos(MACHINE);
		FarmStationBlockEntity station = (FarmStationBlockEntity) helper.getLevel().getBlockEntity(pos);
		FarmStationBlockEntity.tick(helper.getLevel(), pos, station.getBlockState(), station);
	}

	private static long nextTick(long time, int interval) {
		return Math.floorDiv(time + interval - 1, interval) * interval;
	}

	private static void setGameTime(GameTestHelper helper, long time) {
		if (!(helper.getLevel().getLevelData() instanceof PrimaryLevelData data)) {
			throw new IllegalStateException("The test world must expose its real game clock");
		}
		data.setGameTime(time);
	}
}
