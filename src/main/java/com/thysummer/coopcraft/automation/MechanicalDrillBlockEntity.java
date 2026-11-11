package com.thysummer.coopcraft.automation;

import java.util.List;

import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class MechanicalDrillBlockEntity extends BlockEntity implements Container {
	private final ToolContainer tools = new ToolContainer(this::setChanged);
	private Status status = Status.IDLE;

	public MechanicalDrillBlockEntity(BlockPos pos, BlockState state) {
		super(Automation.MECHANICAL_DRILL_ENTITY, pos, state);
	}

	void drill() {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		ItemStack tool = tools.getItem(0);
		if (!ToolContainer.isTool(tool)) {
			setStatus(Status.NO_TOOL);
			return;
		}

		Direction facing = getBlockState().getValue(MechanicalDrillBlock.FACING);
		BlockPos targetPos = worldPosition.relative(facing);
		if (!serverLevel.isLoaded(targetPos)) {
			return;
		}
		BlockState targetState = serverLevel.getBlockState(targetPos);
		if (!isValidTarget(targetState)) {
			setStatus(Status.IDLE);
			return;
		}
		if (serverLevel.getBlockEntity(targetPos) != null) {
			setStatus(Status.PROTECTED);
			return;
		}
		if (!canHarvest(targetState, tool)) {
			setStatus(Status.WRONG_TOOL);
			return;
		}

		List<ItemStack> drops = Block.getDrops(targetState, serverLevel, targetPos, null, null, tool.copy());
		BlockPos outputPos = worldPosition.relative(facing.getOpposite());
		Storage<ItemVariant> storage = ItemStorage.SIDED.find(serverLevel, outputPos, facing);
		if (storage != null && !MachineTransfer.insertAll(storage, drops)) {
			setStatus(Status.OUTPUT_FULL);
			return;
		}
		if (!serverLevel.destroyBlock(targetPos, false)) {
			setStatus(Status.IDLE);
			return;
		}
		if (storage == null) {
			Vec3 dropPos = targetPos.getCenter();
			for (ItemStack drop : drops) {
				ItemEntity itemEntity = new ItemEntity(serverLevel, dropPos.x, dropPos.y, dropPos.z, drop);
				itemEntity.setDeltaMovement(Vec3.ZERO);
				serverLevel.addFreshEntity(itemEntity);
			}
		}

		tool.hurtAndBreak(1, serverLevel, null, item -> { });
		tools.setChanged();
		serverLevel.levelEvent(null, 2001, targetPos, Block.getId(targetState));
		setStatus(Status.RUNNING);
	}

	static boolean canHarvest(BlockState state, ItemStack tool) {
		return isValidTarget(state)
			&& ToolContainer.isTool(tool)
			&& (!state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state));
	}

	private static boolean isValidTarget(BlockState state) {
		return !state.isAir() && state.getFluidState().isEmpty() && state.getBlock().defaultDestroyTime() >= 0;
	}

	private void setStatus(Status newStatus) {
		status = newStatus;
	}

	Component getStatusMessage() {
		MutableComponent message = (tools.isEmpty() ? Status.NO_TOOL : status).message();
		ItemStack tool = tools.getItem(0);
		if (!tool.isEmpty()) {
			message.append(Component.literal(" · "))
				.append(tool.getHoverName())
				.append(Component.literal(" (" + (tool.getMaxDamage() - tool.getDamageValue()) + "/" + tool.getMaxDamage() + ")"));
		}
		return message;
	}

	void ejectTool(Player player) {
		ItemStack tool = tools.removeItemNoUpdate(0);
		setChanged();
		if (!tool.isEmpty() && !player.addItem(tool)) {
			player.drop(tool, false);
		}
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		tools.fromTag(tag.getList("Items", Tag.TAG_COMPOUND), registries);
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.put("Items", tools.createTag(registries));
	}

	@Override
	public int getContainerSize() {
		return tools.getContainerSize();
	}

	@Override
	public boolean isEmpty() {
		return tools.isEmpty();
	}

	@Override
	public ItemStack getItem(int slot) {
		return tools.getItem(slot);
	}

	@Override
	public ItemStack removeItem(int slot, int amount) {
		return tools.removeItem(slot, amount);
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		return tools.removeItemNoUpdate(slot);
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		tools.setItem(slot, stack);
	}

	@Override
	public int getMaxStackSize() {
		return tools.getMaxStackSize();
	}

	@Override
	public void setChanged() {
		super.setChanged();
	}

	@Override
	public boolean stillValid(Player player) {
		return Container.stillValidBlockEntity(this, player);
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return tools.canPlaceItem(slot, stack);
	}

	@Override
	public void clearContent() {
		tools.clearContent();
	}

	private enum Status {
		IDLE("idle"),
		RUNNING("running"),
		NO_TOOL("no_tool"),
		WRONG_TOOL("wrong_tool"),
		PROTECTED("protected"),
		OUTPUT_FULL("output_full");

		private final String key;

		Status(String key) {
			this.key = key;
		}

		private MutableComponent message() {
			return Component.translatable("coopcraft.mechanical_drill.status." + key);
		}
	}
}
