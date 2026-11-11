package com.thysummer.coopcraft.automation;

import java.util.List;

import com.mojang.serialization.MapCodec;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

public class MechanicalDrillBlock extends BaseEntityBlock {
	public static final MapCodec<MechanicalDrillBlock> CODEC = simpleCodec(MechanicalDrillBlock::new);
	public static final DirectionProperty FACING = DirectionalBlock.FACING;
	public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

	public MechanicalDrillBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH).setValue(POWERED, false));
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
		return new MechanicalDrillBlockEntity(pos, state);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState()
			.setValue(FACING, context.getNearestLookingDirection().getOpposite())
			.setValue(POWERED, context.getLevel().hasNeighborSignal(context.getClickedPos()));
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		if (!level.isClientSide && state.getValue(POWERED)) {
			level.scheduleTick(pos, this, 2);
		}
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof MechanicalDrillBlockEntity drill) {
			Containers.dropContents(level, pos, drill);
		}
		super.onRemove(state, level, pos, newState, movedByPiston);
	}

	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
		if (level.isClientSide) {
			return;
		}
		boolean powered = level.hasNeighborSignal(pos);
		if (powered != state.getValue(POWERED)) {
			level.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS);
			if (powered) {
				level.scheduleTick(pos, this, 2);
			}
		}
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (level.getBlockEntity(pos) instanceof MechanicalDrillBlockEntity drill) {
			drill.drill();
		}
	}

	@Override
	protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		if (!(level.getBlockEntity(pos) instanceof MechanicalDrillBlockEntity drill)) {
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		}
		if (!level.isClientSide) {
			if (player.isSecondaryUseActive()) {
				drill.ejectTool(player);
			} else if (drill.isEmpty() && ToolContainer.isTool(stack)) {
				drill.setItem(0, stack.copyWithCount(1));
				stack.shrink(1);
			} else {
				player.displayClientMessage(drill.getStatusMessage(), true);
			}
		}
		return ItemInteractionResult.SUCCESS;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!level.isClientSide && level.getBlockEntity(pos) instanceof MechanicalDrillBlockEntity drill) {
			if (player.isSecondaryUseActive()) {
				drill.ejectTool(player);
			} else {
				player.displayClientMessage(drill.getStatusMessage(), true);
			}
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, POWERED);
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(Component.translatable("block.coopcraft.mechanical_drill.tooltip").withStyle(ChatFormatting.GRAY));
	}
}
