package com.thysummer.coopcraft.automation;

import java.util.List;

import com.mojang.serialization.MapCodec;

import com.thysummer.coopcraft.civilization.Settlement;
import com.thysummer.coopcraft.civilization.SettlementIntelligence;
import com.thysummer.coopcraft.civilization.SettlementSavedData;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class ComputerCoreBlock extends BaseEntityBlock {
	public static final MapCodec<ComputerCoreBlock> CODEC = simpleCodec(ComputerCoreBlock::new);

	public ComputerCoreBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new ComputerCoreBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		return level.isClientSide ? null : createTickerHelper(type, Automation.COMPUTER_CORE_ENTITY, ComputerCoreBlockEntity::tick);
	}

	@Override
	protected ItemInteractionResult useItemOn(
		ItemStack stack,
		BlockState state,
		Level level,
		BlockPos pos,
		Player player,
		InteractionHand hand,
		BlockHitResult hit
	) {
		if (level.isClientSide) {
			return ItemInteractionResult.SUCCESS;
		}
		if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel
			&& level.getBlockEntity(pos) instanceof ComputerCoreBlockEntity core) {
			if (!stack.isEmpty()) {
				core.selectItem(stack.getItem());
			}
			serverPlayer.displayClientMessage(core.status(), true);
			serverPlayer.displayClientMessage(SettlementIntelligence.statusOf(serverLevel, pos), true);
		}
		return ItemInteractionResult.SUCCESS;
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		super.onPlace(state, level, pos, oldState, movedByPiston);
		if (level instanceof ServerLevel serverLevel) {
			SettlementSavedData data = SettlementSavedData.get(serverLevel);
			data.find(pos).ifPresent(settlement -> data.registerPlanningCore(settlement, pos));
		}
	}

	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
			SettlementSavedData data = SettlementSavedData.get(serverLevel);
			for (Settlement settlement : data.all()) {
				data.unregisterPlanningCore(settlement, pos);
			}
		}
		super.onRemove(state, level, pos, newState, movedByPiston);
	}

	@Override
	public boolean isSignalSource(BlockState state) {
		return true;
	}

	@Override
	public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return level.getBlockEntity(pos) instanceof ComputerCoreBlockEntity core ? core.signal() : 0;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(Component.translatable("block.coopcraft.computer_core.tooltip").withStyle(ChatFormatting.GRAY));
	}
}
