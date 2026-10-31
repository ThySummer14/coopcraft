package com.thysummer.coopcraft.civilization;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

public record Settlement(UUID id, BlockPos center, String name, long foundedDay) {
	public Settlement {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(center, "center");
		Objects.requireNonNull(name, "name");
		if (name.isBlank()) {
			throw new IllegalArgumentException("Settlement name cannot be blank");
		}
	}

	CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putUUID("Id", id);
		tag.putLong("Center", center.asLong());
		tag.putString("Name", name);
		tag.putLong("FoundedDay", foundedDay);
		return tag;
	}

	static Settlement load(CompoundTag tag) {
		return new Settlement(
			tag.getUUID("Id"),
			BlockPos.of(tag.getLong("Center")),
			tag.getString("Name"),
			tag.getLong("FoundedDay")
		);
	}
}
