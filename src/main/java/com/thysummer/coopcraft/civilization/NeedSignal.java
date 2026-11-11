package com.thysummer.coopcraft.civilization;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

public record NeedSignal(
	UUID id,
	Type type,
	Severity severity,
	long day,
	BlockPos source,
	int affectedMembers,
	Set<UUID> knownBy
) {
	public NeedSignal {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(severity, "severity");
		Objects.requireNonNull(source, "source");
		knownBy = Set.copyOf(knownBy);
		if (day < 0 || affectedMembers <= 0) {
			throw new IllegalArgumentException("Invalid need signal");
		}
	}

	public static NeedSignal observed(
		UUID settlementId,
		Type type,
		Severity severity,
		long day,
		BlockPos source,
		int affectedMembers,
		Set<UUID> knownBy
	) {
		UUID id = UUID.nameUUIDFromBytes(
			(settlementId + ":" + type + ":" + day).getBytes(StandardCharsets.UTF_8)
		);
		return new NeedSignal(id, type, severity, day, source, affectedMembers, knownBy);
	}

	public boolean knownTo(UUID villagerId) {
		return knownBy.contains(villagerId);
	}

	CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putUUID("Id", id);
		tag.putString("Type", type.name());
		tag.putString("Severity", severity.name());
		tag.putLong("Day", day);
		tag.putLong("Source", source.asLong());
		tag.putInt("AffectedMembers", affectedMembers);
		ListTag observers = new ListTag();
		knownBy.stream().sorted(java.util.Comparator.comparing(UUID::toString)).forEach(observer -> {
			CompoundTag entry = new CompoundTag();
			entry.putUUID("Id", observer);
			observers.add(entry);
		});
		tag.put("KnownBy", observers);
		return tag;
	}

	static NeedSignal load(CompoundTag tag) {
		java.util.Set<UUID> knownBy = new java.util.HashSet<>();
		for (int index = 0; index < tag.getList("KnownBy", ListTag.TAG_COMPOUND).size(); index++) {
			knownBy.add(tag.getList("KnownBy", ListTag.TAG_COMPOUND).getCompound(index).getUUID("Id"));
		}
		return new NeedSignal(
			tag.getUUID("Id"), Type.valueOf(tag.getString("Type")), Severity.valueOf(tag.getString("Severity")),
			tag.getLong("Day"), BlockPos.of(tag.getLong("Source")), tag.getInt("AffectedMembers"), knownBy
		);
	}

	public enum Type {
		COMMON_FOOD_SHORTAGE,
		SHELTER_GAP
	}

	public enum Severity {
		WATCH,
		URGENT
	}
}
