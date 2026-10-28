package com.thysummer.coopcraft.civilization;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

public record ConstructionJob(ResourceLocation blueprintId, BlockPos origin, int nextIndex) {
	public ConstructionJob {
		Objects.requireNonNull(blueprintId, "blueprintId");
		Objects.requireNonNull(origin, "origin");
		if (nextIndex < 0) {
			throw new IllegalArgumentException("nextIndex cannot be negative");
		}
	}

	CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putString("Blueprint", blueprintId.toString());
		tag.putLong("Origin", origin.asLong());
		tag.putInt("NextIndex", nextIndex);
		return tag;
	}

	static ConstructionJob load(CompoundTag tag) {
		return new ConstructionJob(
			ResourceLocation.parse(tag.getString("Blueprint")),
			BlockPos.of(tag.getLong("Origin")),
			tag.getInt("NextIndex")
		);
	}
}
