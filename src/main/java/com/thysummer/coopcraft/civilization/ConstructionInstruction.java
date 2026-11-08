package com.thysummer.coopcraft.civilization;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

public record ConstructionInstruction(BlockPos offset, BlockState state, Item item, boolean consume) {
	public ConstructionInstruction {
		offset = Objects.requireNonNull(offset, "offset").immutable();
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(item, "item");
	}
}
