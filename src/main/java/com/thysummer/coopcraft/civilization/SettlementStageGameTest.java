package com.thysummer.coopcraft.civilization;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;

public final class SettlementStageGameTest implements FabricGameTest {
	private static final BlockPos BELL = new BlockPos(2, 0, 2);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void detectsVillageInfrastructureAndRegistersIt(GameTestHelper helper) {
		SettlementSavedData data = SettlementSavedData.load(new CompoundTag(), helper.getLevel().registryAccess());
		Settlement settlement = data.getOrCreate(helper.absolutePos(BELL), "Stage Settlement", 0);
		Villager first = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 0, 4));
		Villager second = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 0, 4));
		data.registerLoadedResidents(helper.getLevel(), settlement, List.of(first, second));

		putBedFoot(helper, new BlockPos(0, 0, 0));
		putBedFoot(helper, new BlockPos(1, 0, 0));
		putBedFoot(helper, new BlockPos(2, 0, 0));
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
			helper.absolutePos(new BlockPos(1, 0, 3)),
			data.getHearth(settlement).orElseThrow(),
			"A nearby campfire must become the hearth"
		);
		helper.assertFalse(first.getCustomName() == null, "Loaded residents must receive generated names");
		helper.assertFalse(first.getCustomName().getString().isBlank(), "Generated names cannot be blank");

		first.kill();
		second.kill();
		helper.succeed();
	}

	private static void putBedFoot(GameTestHelper helper, BlockPos pos) {
		helper.setBlock(pos, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.FOOT));
	}
}
