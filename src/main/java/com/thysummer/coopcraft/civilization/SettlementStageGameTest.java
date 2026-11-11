package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;

import com.thysummer.coopcraft.civilization.simulation.EventType;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;

public final class SettlementStageGameTest implements FabricGameTest {
	private static final BlockPos BELL = new BlockPos(2, 0, 2);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void detectsVillageInfrastructureAndRegistersIt(GameTestHelper helper) {
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		Settlement settlement = CivilizationGameTestSupport.isolatedSettlement(helper, data, "Stage Settlement");
		Villager first = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 0, 4));
		Villager second = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 0, 4));
		data.registerLoadedResidents(helper.getLevel(), settlement, List.of(first, second));

		putBed(helper, new BlockPos(0, 0, 0));
		putBed(helper, new BlockPos(1, 0, 0));
		putBed(helper, new BlockPos(2, 0, 0));
		helper.setBlock(new BlockPos(0, 0, 2), Blocks.CRAFTING_TABLE);
		helper.setBlock(new BlockPos(1, 0, 2), Blocks.FURNACE);
		helper.setBlock(new BlockPos(0, 0, 3), Blocks.STONECUTTER);
		helper.setBlock(new BlockPos(1, 0, 3), Blocks.CAMPFIRE);

		SettlementStage stage = SettlementStages.initialize(helper.getLevel(), data, settlement, List.of(first, second), 6);

		helper.assertValueEqual(SettlementStage.AGRICULTURAL, stage, "Beds and workstations must classify as an agricultural settlement");
		helper.assertValueEqual(SettlementStage.AGRICULTURAL, data.stageOf(settlement), "The detected stage must be persisted");
		helper.assertValueEqual(3, data.housingCapacity(settlement), "Detected beds must count as housing");
		helper.assertValueEqual(
			helper.absolutePos(new BlockPos(0, 0, 2)),
			data.getCraftingStation(settlement).orElseThrow(),
			"The nearest crafting table must become the knapping station"
		);
		helper.assertValueEqual(
			helper.absolutePos(new BlockPos(0, 0, 3)),
			data.getGrindingStation(settlement).orElseThrow(),
			"The nearest stonecutter must become the grinding station"
		);
		helper.assertValueEqual(
			helper.absolutePos(new BlockPos(1, 0, 2)),
			data.getSmeltingStation(settlement).orElseThrow(),
			"The nearest furnace must become the smelting station"
		);
		helper.assertValueEqual(
			helper.absolutePos(new BlockPos(1, 0, 3)),
			data.getHearth(settlement).orElseThrow(),
			"A nearby campfire must become the hearth"
		);
		String generatedName = first.getCustomName().getString();
		helper.assertFalse(generatedName.isBlank(), "Generated names cannot be blank");
		helper.assertValueEqual(VillagerNames.fullName(first.getUUID()), generatedName, "Residents must receive Chinese generated names");
		first.setCustomName(Component.literal(VillagerNames.legacyFullName(first.getUUID())));
		second.setCustomName(Component.literal("玩家自定义"));
		data.registerLoadedResidents(helper.getLevel(), settlement, List.of(first, second));
		helper.assertValueEqual(generatedName, first.getCustomName().getString(), "Legacy generated names must migrate to Chinese");
		helper.assertValueEqual("玩家自定义", second.getCustomName().getString(), "Player-chosen names must be preserved");

		first.kill();
		second.kill();
		helper.succeed();
	}

	private static void putBed(GameTestHelper helper, BlockPos pos) {
		var foot = helper.absolutePos(pos);
		var head = foot.south();
		var level = helper.getLevel();
		level.setBlock(foot.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(head.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		var state = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
		level.setBlock(foot, state.setValue(BedBlock.PART, BedPart.FOOT), Block.UPDATE_CLIENTS);
		level.setBlock(head, state.setValue(BedBlock.PART, BedPart.HEAD), Block.UPDATE_ALL);
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void advancesToAgriculturalEraUsingRealInfrastructure(GameTestHelper helper) {
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		Settlement settlement = CivilizationGameTestSupport.isolatedSettlement(helper, data, "Era Settlement");
		List<Villager> residents = new ArrayList<>();
		for (int index = 0; index < 4; index++) {
			residents.add(helper.spawn(EntityType.VILLAGER, new BlockPos(index, 0, 4)));
		}
		data.registerLoadedResidents(helper.getLevel(), settlement, residents);
		putBedFoot(helper, new BlockPos(0, 0, 0));
		putBedFoot(helper, new BlockPos(2, 0, 0));
		helper.setBlock(new BlockPos(1, 0, 3), Blocks.CAMPFIRE);
		helper.setBlock(new BlockPos(4, 0, 2), Blocks.CHEST);
		data.setStockpile(settlement, helper.absolutePos(new BlockPos(4, 0, 2)));
		Container stockpile = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(4, 0, 2)));
		SettlementStockpile.insert(stockpile, new ItemStack(Items.BREAD, 4));
		SettlementStages.initialize(helper.getLevel(), data, settlement, residents, 6);

		CivilizationEras.refresh(helper.getLevel(), data, settlement);
		helper.assertValueEqual(CivilizationEras.PRIMITIVE, data.eraOf(settlement).id(), "Crops are required for the agricultural era");
		helper.assertValueEqual(
			"coopcraft.milestone.cultivation",
			CivilizationEras.nextMilestoneTranslationKey(data, settlement),
			"All other agricultural milestones must come from real infrastructure"
		);
		for (int index = 0; index < 4; index++) {
			BlockPos crop = new BlockPos(index, 0, 6);
			helper.setBlock(crop.below(), Blocks.FARMLAND);
			helper.setBlock(crop, Blocks.POTATOES);
		}

		CivilizationEras.refresh(helper.getLevel(), data, settlement);

		helper.assertValueEqual(CivilizationEras.AGRICULTURAL, data.eraOf(settlement).id(), "Real crops must complete the agricultural era");
		helper.assertTrue(data.eraMilestonesOf(settlement).contains(CivilizationEras.CULTIVATION), "Cultivation must persist as completed");
		helper.assertValueEqual(
			EventType.ERA_ADVANCED,
			data.getOrCreateSimulation(settlement).world().ledger().events().getLast().type(),
			"Era advancement must leave an observable event"
		);
		residents.forEach(Villager::kill);
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void aiObservationHonorsLocationAndRateLimit(GameTestHelper helper) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		BlockPos center = helper.absolutePos(BELL);
		CivilizationGameTestSupport.isolatedSettlement(helper, data, "AI Observation");
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.moveTo(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
		var commands = player.getServer().getCommands().getDispatcher();

		helper.assertValueEqual(1, commands.execute("coop agent observe", player.createCommandSourceStack()),
			"A nearby ordinary player must receive an AI observation");
		helper.assertValueEqual(0, commands.execute("coop agent observe", player.createCommandSourceStack()),
			"Immediate repeated observations must be rate limited");
		player.moveTo(center.getX() + 0.5, center.getY() + 256, center.getZ() + 0.5);
		helper.assertValueEqual(0, commands.execute("coop agent observe", player.createCommandSourceStack()),
			"A distant player must not read settlement data");
		helper.succeed();
	}

	private static void putBedFoot(GameTestHelper helper, BlockPos pos) {
		BlockPos foot = helper.absolutePos(pos);
		BlockPos head = foot.east();
		helper.getLevel().setBlock(foot.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		helper.getLevel().setBlock(head.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		var state = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
		helper.getLevel().setBlock(foot, state.setValue(BedBlock.PART, BedPart.FOOT), Block.UPDATE_CLIENTS);
		helper.getLevel().setBlock(head, state.setValue(BedBlock.PART, BedPart.HEAD), Block.UPDATE_ALL);
	}
}
