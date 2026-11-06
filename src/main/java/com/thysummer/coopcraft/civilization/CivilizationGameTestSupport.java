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

	static Settlement isolatedSettlement(GameTestHelper helper, SettlementSavedData data, String name) {
		Settlement settlement = new Settlement(UUID.randomUUID(), helper.absolutePos(new BlockPos(2, 0, 2)), name, 0);
		try {
			Field field = SettlementSavedData.class.getDeclaredField("settlements");
			field.setAccessible(true);
			@SuppressWarnings("unchecked")
			Map<BlockPos, Settlement> settlements = (Map<BlockPos, Settlement>) field.get(data);
			settlements.put(settlement.center(), settlement);
			return settlement;
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Could not create an isolated test settlement", exception);
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
