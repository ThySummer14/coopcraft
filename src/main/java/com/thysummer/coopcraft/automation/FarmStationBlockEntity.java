package com.thysummer.coopcraft.automation;

import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class FarmStationBlockEntity extends BlockEntity {
	private static final long WORK_INTERVAL_TICKS = 40;
	private static final double ALERT_RANGE_SQUARED = 16 * 16;

	private Status status = Status.IDLE;

	public FarmStationBlockEntity(BlockPos pos, BlockState state) {
		this(Automation.FARM_STATION_ENTITY, pos, state);
	}

	public FarmStationBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	public static void tick(Level level, BlockPos pos, BlockState state, FarmStationBlockEntity station) {
		if (!(level instanceof ServerLevel serverLevel) || Math.floorMod(level.getGameTime() + pos.asLong(), WORK_INTERVAL_TICKS) != 0) {
			return;
		}

		Storage<ItemVariant> storage = ItemStorage.SIDED.find(serverLevel, pos.above(), Direction.DOWN);
		if (storage == null) {
			station.setStatus(Status.NO_OUTPUT, serverLevel);
			return;
		}

		BlockPos.MutableBlockPos cropPos = new BlockPos.MutableBlockPos();
		for (int x = -1; x <= 1; x++) {
			for (int z = -1; z <= 1; z++) {
				cropPos.set(pos.getX() + x, pos.getY(), pos.getZ() + z);
				BlockState cropState = serverLevel.getBlockState(cropPos);
				if (!CropHarvest.isMature(cropState)) {
					continue;
				}

				CropHarvest.Result result = CropHarvest.harvest(serverLevel, cropPos, cropState, storage);
				switch (result) {
					case HARVESTED -> station.setStatus(Status.RUNNING, serverLevel);
					case OUTPUT_FULL -> station.setStatus(Status.OUTPUT_FULL, serverLevel);
					case MISSING_SEED -> station.setStatus(Status.MISSING_SEED, serverLevel);
				}
				return;
			}
		}

		station.setStatus(Status.IDLE, serverLevel);
	}

	Component getStatusMessage() {
		return status.message();
	}

	private void setStatus(Status newStatus, ServerLevel level) {
		if (status == newStatus) {
			return;
		}
		status = newStatus;
		if (!newStatus.alertNearby) {
			return;
		}

		Component message = Component.translatable("coopcraft.farm_station.alert", worldPosition.toShortString(), newStatus.message());
		for (ServerPlayer player : level.players()) {
			if (player.distanceToSqr(worldPosition.getCenter()) <= ALERT_RANGE_SQUARED) {
				player.displayClientMessage(message, true);
			}
		}
	}

	private enum Status {
		RUNNING("running", false),
		IDLE("idle", false),
		NO_OUTPUT("no_output", true),
		OUTPUT_FULL("output_full", true),
		MISSING_SEED("missing_seed", true);

		private final String key;
		private final boolean alertNearby;

		Status(String key, boolean alertNearby) {
			this.key = key;
			this.alertNearby = alertNearby;
		}

		private Component message() {
			return Component.translatable("coopcraft.farm_station.status." + key);
		}
	}
}
