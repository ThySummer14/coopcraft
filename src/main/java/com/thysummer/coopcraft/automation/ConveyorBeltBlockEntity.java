package com.thysummer.coopcraft.automation;

import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public class ConveyorBeltBlockEntity extends BlockEntity {
	private static final double SPEED = 0.12;
	private static final double OUTPUT_THRESHOLD = 0.32;

	public ConveyorBeltBlockEntity(BlockPos pos, BlockState state) {
		super(Automation.CONVEYOR_BELT_ENTITY, pos, state);
	}

	public static void tick(Level level, BlockPos pos, BlockState state, ConveyorBeltBlockEntity belt) {
		if (level.isClientSide || state.getValue(ConveyorBeltBlock.POWERED)) {
			return;
		}
		Direction facing = state.getValue(ConveyorBeltBlock.FACING);
		AABB collectionArea = new AABB(
			pos.getX(), pos.getY() + 0.2, pos.getZ(),
			pos.getX() + 1, pos.getY() + 1.5, pos.getZ() + 1
		);
		for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, collectionArea)) {
			if (item.getItem().isEmpty()) {
				continue;
			}
			if (tryOutput(level, pos, facing, item)) {
				item.setDeltaMovement(0, item.getDeltaMovement().y, 0);
			} else {
				item.setDeltaMovement(facing.getStepX() * SPEED, item.getDeltaMovement().y, facing.getStepZ() * SPEED);
			}
		}
	}

	private static boolean tryOutput(Level level, BlockPos pos, Direction facing, ItemEntity item) {
		double progress = (item.getX() - pos.getX() - 0.5) * facing.getStepX()
			+ (item.getZ() - pos.getZ() - 0.5) * facing.getStepZ();
		if (progress < OUTPUT_THRESHOLD) {
			return false;
		}

		BlockPos outputPos = pos.relative(facing);
		if (!level.isLoaded(outputPos)) {
			return true;
		}
		Storage<ItemVariant> storage = ItemStorage.SIDED.find(level, outputPos, facing.getOpposite());
		if (storage == null) {
			return false;
		}

		ItemStack stack = item.getItem();
		long inserted = MachineTransfer.insert(storage, stack);
		if (inserted > 0) {
			ItemStack remainder = stack.copy();
			remainder.shrink((int) inserted);
			item.setItem(remainder);
			if (remainder.isEmpty()) {
				item.discard();
			}
		}
		return true;
	}
}
