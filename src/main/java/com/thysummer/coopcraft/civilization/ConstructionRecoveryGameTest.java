package com.thysummer.coopcraft.civilization;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.resources.ResourceLocation;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Real block/stockpile regression coverage. Compile with build; run explicitly with runGameTest. */
public final class ConstructionRecoveryGameTest implements FabricGameTest {
	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
	public void obstructionRetainsPrefixAndResumesAfterNormalSaveReload(GameTestHelper helper) {
		var level = helper.getLevel();
		BlockPos center = helper.absolutePos(new BlockPos(1, 0, 2));
		BlockPos origin = helper.absolutePos(new BlockPos(0, 3, 0));
		BlockPos chestPosition = helper.absolutePos(new BlockPos(0, 0, 0));
		helper.setBlock(new BlockPos(0, 0, 0), Blocks.CHEST);
		Container chest = (Container) level.getBlockEntity(chestPosition);
		chest.setItem(0, new ItemStack(Items.COBBLESTONE, 32));
		chest.setItem(1, new ItemStack(Items.CAMPFIRE, 2));
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(center, "Construction recovery", 0);
		data.setStockpile(settlement, chestPosition);
		var plan = BlueprintManager.get(ConstructionPlanner.CAMPFIRE_HEARTH);
		helper.assertTrue(plan != null && plan.instructions().size() > 2, "Loaded hearth blueprint must have a real prefix");
		data.setConstructionJob(settlement, new ConstructionJob(plan.id(), origin, 0));
		Villager builder = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 0, 2));
		Villager secondBuilder = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 0, 2));
		builder.setNoAi(true);
		secondBuilder.setNoAi(true);

		var first = ConstructionSystem.nextBuildTarget(level, data, settlement).orElseThrow();
		helper.assertTrue(ConstructionSystem.buildNext(level, data, settlement, first, builder), "Build the actual prefix");
		helper.assertTrue(level.getBlockState(first.position()).equals(first.instruction().state()), "Prefix must exist in the world");
		var pending = ConstructionSystem.nextBuildTarget(level, data, settlement).orElseThrow();
		int remaining = chest.countItem(pending.instruction().item());
		level.setBlockAndUpdate(pending.position(), Blocks.DIRT.defaultBlockState());
		var beforeInspection = data.save(new CompoundTag(), level.registryAccess());
		data.setDirty(false);
		for (int i = 0; i < 3; i++) {
			helper.assertValueEqual("target_occupied", ConstructionSystem.inspect(level, data, settlement).orElseThrow().blocker(), "Explain player obstruction");
			helper.assertTrue(ConstructionSystem.nextBuildTarget(level, data, settlement).isEmpty(), "Let builders choose other work");
			helper.assertTrue(!ConstructionSystem.buildNext(level, data, settlement, pending, builder), "Revalidate already claimed target");
		}
		helper.assertTrue(!data.isDirty(), "Inspection and blocked attempts must not dirty saved data");
		helper.assertValueEqual(beforeInspection, data.save(new CompoundTag(), level.registryAccess()), "No job, simulation or ledger changes while blocked");
		helper.assertValueEqual(remaining, chest.countItem(pending.instruction().item()), "No material consumption or refunds while blocked");
		helper.assertTrue(level.getBlockState(pending.position()).is(Blocks.DIRT), "Keep the player's obstacle intact");
		helper.assertTrue(level.getBlockState(first.position()).equals(first.instruction().state()), "Do not dismantle completed prefix");

		// Normal serialized save/load only: this does not claim crash-atomic persistence with chunks.
		var restored = SettlementSavedData.load(beforeInspection, level.registryAccess());
		var restoredSettlement = restored.find(center).orElseThrow();
		helper.assertValueEqual(pending.job(), restored.getConstructionJob(restoredSettlement).orElseThrow(), "Paused job identity and next index survive reload");
		helper.assertValueEqual("target_occupied", ConstructionSystem.inspect(level, restored, restoredSettlement).orElseThrow().blocker(), "Reload preserves pause");
		level.setBlockAndUpdate(pending.position(), Blocks.AIR.defaultBlockState());
		var resumed = ConstructionSystem.nextBuildTarget(level, restored, restoredSettlement).orElseThrow();
		helper.assertValueEqual(pending, resumed, "Resume at the same position and instruction");
		helper.assertTrue(ConstructionSystem.buildNext(level, restored, restoredSettlement, resumed, builder), "Build after the player clears the obstacle");
		helper.assertValueEqual(remaining - (pending.instruction().consume() ? 1 : 0), chest.countItem(pending.instruction().item()), "Resume consumes exactly once");
		helper.assertTrue(!ConstructionSystem.buildNext(level, restored, restoredSettlement, resumed, secondBuilder), "Second villager's stale target must not build twice");
		helper.assertValueEqual(remaining - (pending.instruction().consume() ? 1 : 0), chest.countItem(pending.instruction().item()), "Stale target cannot double-charge");
		helper.assertValueEqual(pending.job().nextIndex() + 1, restored.getConstructionJob(restoredSettlement).orElseThrow().nextIndex(), "Advance only once");
		builder.kill();
		secondBuilder.kill();
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
	public void identicalPlayerBlockStillPausesAndBuilderCanClaimWhileStandingInTarget(GameTestHelper helper) {
		var level = helper.getLevel();
		BlockPos origin = helper.absolutePos(new BlockPos(0, 3, 0));
		BlockPos chestPosition = helper.absolutePos(new BlockPos(0, 0, 0));
		helper.setBlock(new BlockPos(0, 0, 0), Blocks.CHEST);
		Container chest = (Container) level.getBlockEntity(chestPosition);
		chest.setItem(0, new ItemStack(Items.COBBLESTONE, 32));
		chest.setItem(1, new ItemStack(Items.CAMPFIRE, 2));
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(helper.absolutePos(new BlockPos(1, 0, 2)), "Identical obstacle", 0);
		data.setStockpile(settlement, chestPosition);
		var plan = BlueprintManager.get(ConstructionPlanner.CAMPFIRE_HEARTH);
		data.setConstructionJob(settlement, new ConstructionJob(plan.id(), origin, 0));
		var target = ConstructionSystem.nextBuildTarget(level, data, settlement).orElseThrow();
		Villager builder = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 0, 2));
		builder.setNoAi(true);
		int remaining = chest.countItem(target.instruction().item());
		level.setBlockAndUpdate(target.position(), target.instruction().state());
		helper.assertTrue(ConstructionSystem.nextBuildTarget(level, data, settlement).isEmpty(), "Identical player block must not be adopted");
		helper.assertTrue(!ConstructionSystem.buildNext(level, data, settlement, target, builder), "Identical block must not advance the job");
		helper.assertValueEqual(target.job(), data.getConstructionJob(settlement).orElseThrow(), "Keep index unchanged");
		helper.assertValueEqual(remaining, chest.countItem(target.instruction().item()), "Do not charge for player blocks");
		level.setBlockAndUpdate(target.position(), Blocks.AIR.defaultBlockState());
		builder.moveTo(target.position().getX() + 0.5, target.position().getY(), target.position().getZ() + 0.5, 0, 0);
		helper.assertValueEqual("entity_obstructed", ConstructionSystem.inspect(level, data, settlement).orElseThrow().blocker(), "Inspection includes the builder itself");
		helper.assertTrue(ConstructionSystem.nextBuildTarget(level, data, settlement).isPresent(), "Builder must claim then use existing retreat behavior");
		helper.assertTrue(!ConstructionSystem.buildNext(level, data, settlement, target, builder), "Never place through the builder");
		builder.moveTo(chestPosition.getX() + 2.5, chestPosition.getY(), chestPosition.getZ() + 2.5, 0, 0);
		helper.assertTrue(ConstructionSystem.buildNext(level, data, settlement, target, builder), "Build after the builder moves away");
		helper.assertValueEqual(remaining - (target.instruction().consume() ? 1 : 0), chest.countItem(target.instruction().item()), "Only the actual placement consumes material");
		builder.kill();
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
	public void missingPlanRetainsSavedJobAndResumesWithSamePlan(GameTestHelper helper) throws Exception {
		var level = helper.getLevel();
		BlockPos center = helper.absolutePos(new BlockPos(1, 0, 2));
		BlockPos origin = helper.absolutePos(new BlockPos(0, 3, 0));
		BlockPos chestPosition = helper.absolutePos(new BlockPos(0, 0, 0));
		helper.setBlock(new BlockPos(0, 0, 0), Blocks.CHEST);
		Container chest = (Container) level.getBlockEntity(chestPosition);
		chest.setItem(0, new ItemStack(Items.COBBLESTONE, 32));
		chest.setItem(1, new ItemStack(Items.CAMPFIRE, 2));
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(center, "Missing plan recovery", 0);
		data.setStockpile(settlement, chestPosition);
		var plan = BlueprintManager.get(ConstructionPlanner.CAMPFIRE_HEARTH);
		helper.assertTrue(plan != null && plan.instructions().size() > 2, "Hearth must have a prefix and pending steps");
		data.setConstructionJob(settlement, new ConstructionJob(plan.id(), origin, 0));
		Villager builder = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 0, 2));
		builder.setNoAi(true);
		Field field = BlueprintManager.class.getDeclaredField("plans");
		field.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<ResourceLocation, ConstructionPlan> snapshot = (Map<ResourceLocation, ConstructionPlan>) field.get(null);
		try {
			var first = ConstructionSystem.nextBuildTarget(level, data, settlement).orElseThrow();
			helper.assertTrue(ConstructionSystem.buildNext(level, data, settlement, first, builder), "Build a real prefix before saving");
			var pending = ConstructionSystem.nextBuildTarget(level, data, settlement).orElseThrow();
			int remaining = chest.countItem(pending.instruction().item());
			// Normal save/load, not crash-atomic persistence or blueprint migration.
			var restored = SettlementSavedData.load(data.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
			var restoredSettlement = restored.find(center).orElseThrow();
			var beforePause = restored.save(new CompoundTag(), level.registryAccess());
			restored.setDirty(false);
			var missing = new HashMap<>(snapshot);
			missing.remove(plan.id());
			field.set(null, Map.copyOf(missing));
			for (int attempt = 0; attempt < 3; attempt++) {
				helper.assertValueEqual("plan_unavailable", ConstructionSystem.inspect(level, restored, restoredSettlement).orElseThrow().blocker(), "Explain the missing plan");
				helper.assertTrue(ConstructionSystem.nextBuildTarget(level, restored, restoredSettlement).isEmpty(), "Missing plan pauses builders");
				helper.assertTrue(!ConstructionSystem.buildNext(level, restored, restoredSettlement, pending, builder), "Reject a previously claimed target while the plan is absent");
				helper.assertTrue(!restored.isDirty(), "Missing plan must not dirty the save");
				helper.assertValueEqual(pending.job(), restored.getConstructionJob(restoredSettlement).orElseThrow(), "Keep blueprint, origin and index");
				helper.assertValueEqual(beforePause, restored.save(new CompoundTag(), level.registryAccess()), "Retain every saved field while paused");
				helper.assertValueEqual(remaining, chest.countItem(pending.instruction().item()), "Missing plan cannot charge or refund stock");
				helper.assertTrue(level.getBlockState(first.position()).equals(first.instruction().state()), "Keep the completed prefix");
				helper.assertTrue(level.getBlockState(pending.position()).isAir(), "No placement while the plan is missing");
			}
			field.set(null, snapshot);
			helper.assertTrue(BlueprintManager.get(plan.id()) == plan, "Restore the exact original plan");
			var resumed = ConstructionSystem.nextBuildTarget(level, restored, restoredSettlement).orElseThrow();
			helper.assertValueEqual(pending, resumed, "Resume at the original origin and index");
			helper.assertTrue(ConstructionSystem.buildNext(level, restored, restoredSettlement, resumed, builder), "Resume after restoring the same plan");
			helper.assertTrue(!ConstructionSystem.buildNext(level, restored, restoredSettlement, resumed, builder), "A stale claim cannot build twice");
			helper.assertValueEqual(remaining - (resumed.instruction().consume() ? 1 : 0), chest.countItem(resumed.instruction().item()), "Charge exactly once");
			helper.assertValueEqual(pending.job().nextIndex() + 1, restored.getConstructionJob(restoredSettlement).orElseThrow().nextIndex(), "Advance exactly once");
			helper.assertTrue(level.getBlockState(resumed.position()).equals(resumed.instruction().state()), "Place the original pending instruction");
		} finally {
			field.set(null, snapshot);
			builder.kill();
		}
		helper.succeed();
	}
}
