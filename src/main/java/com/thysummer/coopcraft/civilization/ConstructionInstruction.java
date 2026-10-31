package com.thysummer.coopcraft.civilization;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

public record ConstructionInstruction(BlockPos offset, BlockState state, Item item, boolean consume) {
}
