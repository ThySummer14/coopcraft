package com.thysummer.coopcraft.civilization;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class CivilizationGameTestSupport {
	private CivilizationGameTestSupport() {
	}

	static void loadWorkArea(GameTestHelper helper) {
		BlockPos center = helper.absolutePos(new BlockPos(2, 0, 2));
		for (int dx = -5; dx <= 5; dx++) {
			for (int dz = -5; dz <= 5; dz++) {
				helper.getLevel().setChunkForced((center.getX() >> 4) + dx, (center.getZ() >> 4) + dz, true);
			}
		}
	}

	/** Deterministic safe arrival for fixtures that exercise production, not pathfinding. */
	static void arriveAtWork(GameTestHelper helper, net.minecraft.world.entity.npc.Villager villager, BlockPos... allowedTargets) {
		BlockPos target = VillagerWorkGoal.statusOf(villager).target();
		if (target == null || !java.util.Set.of(allowedTargets).contains(target)) return;
		for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos feet = target.relative(direction);
			if (helper.getLevel().isLoaded(feet) && helper.getLevel().isPositionEntityTicking(feet)
				&& helper.getLevel().getBlockState(feet).isAir() && helper.getLevel().getBlockState(feet.above()).isAir()
				&& ConstructionSite.safeSupport(helper.getLevel().getBlockState(feet.below()))) {
				villager.setPos(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
				return;
			}
		}
	}

	static Settlement isolatedSettlement(GameTestHelper helper, SettlementSavedData data, String name) {
		loadWorkArea(helper);
		Settlement settlement = new Settlement(UUID.randomUUID(), helper.absolutePos(new BlockPos(2, 0, 2)), name, 0);
		try {
			Field field = SettlementSavedData.class.getDeclaredField("settlements");
			field.setAccessible(true);
			@SuppressWarnings("unchecked")
			Map<BlockPos, Settlement> settlements = (Map<BlockPos, Settlement>) field.get(data);
			for (Settlement other : data.all()) {
				for (BlockPos bed : data.housingBeds(other)) {
					if (bed.distSqr(settlement.center()) <= 24 * 24) data.removeHousingBed(other, bed);
				}
			}
			settlements.put(settlement.center(), settlement);
			var index = SettlementSavedData.class.getDeclaredMethod("indexSettlement", Settlement.class);
			index.setAccessible(true);
			index.invoke(data, settlement);
			return settlement;
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Could not create an isolated test settlement", exception);
		}
	}

	static void advanceOneTick(GameTestHelper helper) {
		var level = helper.getLevel();
		level.setDayTime(level.getDayTime() + 1);
		if (level.getLevelData() instanceof net.minecraft.world.level.storage.PrimaryLevelData data) {
			data.setGameTime(level.getGameTime() + 1);
		}
	}

	static void resetSettlements(SettlementSavedData data) {
		try {
			for (Field field : SettlementSavedData.class.getDeclaredFields()) {
				if (!Map.class.isAssignableFrom(field.getType())) {
					continue;
				}
				field.setAccessible(true);
				((Map<?, ?>) field.get(data)).clear();
			}
			data.setDirty();
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Could not reset test settlement data", exception);
		}
	}

	static void clearAround(GameTestHelper helper, BlockPos relativeCenter, int radius) {
		BlockPos center = helper.absolutePos(relativeCenter);
		for (var villager : helper.getLevel().getEntities(
			net.minecraft.world.entity.EntityType.VILLAGER, new net.minecraft.world.phys.AABB(center).inflate(radius * 2), ignored -> true
		)) {
			villager.discard();
		}
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				cursor.set(center.getX() + dx, center.getY() - 1, center.getZ() + dz);
				if (helper.getLevel().getBlockState(cursor).is(Blocks.FARMLAND)) {
					helper.getLevel().setBlock(cursor, Blocks.DIRT.defaultBlockState(), Block.UPDATE_NONE);
				}
				for (int dy = 0; dy <= 24; dy++) {
					cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					BlockState state = helper.getLevel().getBlockState(cursor);
					if (!state.isAir() && !state.is(Blocks.STRUCTURE_BLOCK) && !state.is(Blocks.STRUCTURE_VOID)) {
						helper.getLevel().setBlock(cursor, Blocks.AIR.defaultBlockState(), Block.UPDATE_NONE);
					}
				}
			}
		}
	}
}
