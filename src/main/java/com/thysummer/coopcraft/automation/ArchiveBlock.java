package com.thysummer.coopcraft.automation;

import java.util.List;

import com.mojang.serialization.MapCodec;

import com.thysummer.coopcraft.civilization.CivilizationEras;
import com.thysummer.coopcraft.civilization.Settlement;
import com.thysummer.coopcraft.civilization.SettlementSavedData;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class ArchiveBlock extends BaseEntityBlock {
	public static final MapCodec<ArchiveBlock> CODEC = simpleCodec(ArchiveBlock::new);

	public ArchiveBlock(Properties properties) {
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
		return new ArchiveBlockEntity(pos, state);
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
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)
			|| !(level.getBlockEntity(pos) instanceof ArchiveBlockEntity archive)) {
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		}
		if (!stack.is(Items.WRITABLE_BOOK)) {
			serverPlayer.displayClientMessage(archive.describe(stack.getItem()), true);
			return ItemInteractionResult.SUCCESS;
		}
		SettlementSavedData data = SettlementSavedData.get(serverLevel);
		Settlement settlement = data.find(pos).orElse(null);
		Container stockpile = settlement == null ? null : data.getStockpile(settlement)
			.map(serverLevel::getBlockEntity)
			.filter(Container.class::isInstance)
			.map(Container.class::cast)
			.orElse(null);
		if (settlement == null || stockpile == null) {
			serverPlayer.displayClientMessage(Component.translatable("coopcraft.archive.no_stockpile"), true);
			return ItemInteractionResult.SUCCESS;
		}
		archive.record(stockpile, settlement.id(), serverLevel.getGameTime());
		data.registerArchive(settlement, pos);
		data.completeEraMilestone(settlement, CivilizationEras.INFORMATION_STORAGE);
		CivilizationEras.refresh(serverLevel, data, settlement);
		if (!serverPlayer.getAbilities().instabuild) {
			stack.shrink(1);
		}
		serverPlayer.displayClientMessage(Component.translatable("coopcraft.archive.recorded", archive.entries(), archive.totalItems()), true);
		return ItemInteractionResult.SUCCESS;
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		super.onPlace(state, level, pos, oldState, movedByPiston);
		if (level instanceof ServerLevel serverLevel) {
			SettlementSavedData data = SettlementSavedData.get(serverLevel);
			data.find(pos).ifPresent(settlement -> data.registerArchive(settlement, pos));
		}
	}

	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
			SettlementSavedData data = SettlementSavedData.get(serverLevel);
			for (Settlement settlement : data.all()) {
				data.unregisterArchive(settlement, pos);
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
		return level.getBlockEntity(pos) instanceof ArchiveBlockEntity archive && archive.hasRecord() ? 15 : 0;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(Component.translatable("block.coopcraft.archive.tooltip").withStyle(ChatFormatting.GRAY));
	}
}
