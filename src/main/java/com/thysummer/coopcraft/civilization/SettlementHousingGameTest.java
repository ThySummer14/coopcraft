package com.thysummer.coopcraft.civilization;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;

/** Real block feedback and the same explicit registration handler used by player interaction. */
@SuppressWarnings("removal")
public final class SettlementHousingGameTest implements FabricGameTest {
	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
	public void destroyedBedRestoresHousingPlanAndExplicitRegistrationClosesGap(GameTestHelper helper) {
		var level = helper.getLevel();
		SettlementSavedData data = SettlementSavedData.get(level);
		Settlement settlement = CivilizationGameTestSupport.isolatedSettlement(helper, data, "Housing Feedback");
		int settlementCount = data.all().size();
		Villager resident = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 3));
		resident.setNoAi(true);
		data.registerLoadedResidents(level, settlement, List.of(resident));
		BlockPos foot = helper.absolutePos(new BlockPos(0, 1, 0));
		BlockPos head = foot.east();
		placeBed(helper, foot);
		helper.assertValueEqual(SettlementHousing.RegistrationResult.ADDED,
			SettlementHousing.registerBed(level, data, settlement, head), "Registering the head must normalize to the foot");
		helper.assertValueEqual(1, data.housingCapacity(settlement), "One complete bed provides one registered place");
		helper.assertFalse(hasHousingProject(data, settlement), "Enough registered beds must suppress new housing plans");

		// Break the real block, not a saved-data entry. Vanilla may also remove its counterpart.
		level.destroyBlock(head, false);
		for (long tick = 0; tick < 100; tick++) {
			SettlementHousing.tick(level, data, settlement, tick);
		}
		helper.assertValueEqual(0, data.housingCapacity(settlement), "Maintenance must discard a destroyed bed");
		helper.assertValueEqual(1, ConstructionPlanner.housingPlan(data, settlement).missingBeds(), "The resident now lacks housing");
		helper.assertTrue(hasHousingProject(data, settlement), "A missing registered bed must restore the cottage plan");

		placeBed(helper, foot);
		for (long tick = 100; tick < 200; tick++) {
			SettlementHousing.tick(level, data, settlement, tick);
		}
		helper.assertValueEqual(0, data.housingCapacity(settlement), "Maintenance must not discover a player's unregistered bed");
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		var hit = new BlockHitResult(head.getCenter(), Direction.UP, head, false);
		player.setShiftKeyDown(true);
		helper.assertValueEqual(InteractionResult.PASS,
			Civilization.onUseBed(player, level, InteractionHand.OFF_HAND, hit), "Off-hand callbacks must not register beds");
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
		helper.assertValueEqual(InteractionResult.PASS,
			Civilization.onUseBed(player, level, InteractionHand.MAIN_HAND, hit), "Holding an item must preserve normal bed use");
		player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		player.setShiftKeyDown(false);
		helper.assertValueEqual(InteractionResult.PASS,
			Civilization.onUseBed(player, level, InteractionHand.MAIN_HAND, hit), "Normal bed use must not register housing");
		player.setShiftKeyDown(true);
		helper.assertValueEqual(InteractionResult.SUCCESS,
			Civilization.registerHousingBed(player, level, data, head), "Explicit player registration must succeed");
		Civilization.registerHousingBed(player, level, data, foot);
		helper.assertValueEqual(1, data.housingCapacity(settlement), "Registering either half again must be idempotent");
		helper.assertValueEqual(0, ConstructionPlanner.housingPlan(data, settlement).missingBeds(), "Manual complete-bed registration closes the gap");
		helper.assertFalse(hasHousingProject(data, settlement), "Manual registration must suppress new housing plans");
		helper.assertValueEqual(settlementCount, data.all().size(), "Manual registration must not create settlements");
		resident.discard();
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
	public void incompleteBedIsNotSurveyedAndRegistrationDoesNotFoundSettlement(GameTestHelper helper) {
		var level = helper.getLevel();
		BlockPos foot = helper.absolutePos(new BlockPos(0, 1, 0));
		placeBed(helper, foot);
		SettlementSavedData data = SettlementSavedData.load(new CompoundTag(), level.registryAccess());
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		Civilization.registerHousingBed(player, level, data, foot);
		helper.assertValueEqual(0, data.all().size(), "A bed interaction must not found a settlement");
		level.setBlock(foot.east(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
		helper.assertValueEqual(0, SettlementStages.survey(level, foot, 0, 2).beds(),
			"A lone foot must not count as housing in the founding survey");
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
	public void eitherBedHalfSelectsFootSettlementAtBoundaries(GameTestHelper helper) {
		var level = helper.getLevel();
		BlockPos foot = helper.absolutePos(new BlockPos(0, 1, 0));
		placeBed(helper, foot);
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		SettlementSavedData radiusData = SettlementSavedData.load(new CompoundTag(), level.registryAccess());
		Settlement edge = radiusData.getOrCreate(foot.west(48), "Radius Boundary", 0);
		helper.assertTrue(radiusData.find(foot.east()).isEmpty(), "The head must be outside the 48-block radius for this regression");
		Civilization.registerHousingBed(player, level, radiusData, foot.east());
		Civilization.registerHousingBed(player, level, radiusData, foot);
		helper.assertValueEqual(1, radiusData.housingCapacity(edge), "Both halves must register once using the in-range foot");

		SettlementSavedData boundaryData = SettlementSavedData.load(new CompoundTag(), level.registryAccess());
		Settlement west = boundaryData.getOrCreate(foot.west(24), "West Boundary", 0);
		Settlement east = boundaryData.getOrCreate(foot.east(25), "East Boundary", 0);
		helper.assertValueEqual(east, boundaryData.find(foot.east()).orElseThrow(), "Raw head lookup must prefer the other settlement for this regression");
		Civilization.registerHousingBed(player, level, boundaryData, foot.east());
		Civilization.registerHousingBed(player, level, boundaryData, foot);
		helper.assertValueEqual(1, boundaryData.housingCapacity(west), "Both halves must choose the settlement nearest the foot");
		helper.assertValueEqual(0, boundaryData.housingCapacity(east), "Clicking the head must not claim the bed for a different settlement");
		helper.succeed();
	}

	private static boolean hasHousingProject(SettlementSavedData data, Settlement settlement) {
		return ConstructionPlanner.projects(data, settlement).stream()
			.anyMatch(project -> ConstructionPlanner.isHousingBlueprint(project.blueprintId()));
	}

	private static void placeBed(GameTestHelper helper, BlockPos foot) {
		var level = helper.getLevel();
		level.setBlock(foot.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(foot.east().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		var state = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
		level.setBlock(foot, state.setValue(BedBlock.PART, BedPart.FOOT), Block.UPDATE_CLIENTS);
		level.setBlock(foot.east(), state.setValue(BedBlock.PART, BedPart.HEAD), Block.UPDATE_ALL);
	}
}
