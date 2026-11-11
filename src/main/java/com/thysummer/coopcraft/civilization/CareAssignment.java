package com.thysummer.coopcraft.civilization;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;

public record CareAssignment(long day, UUID caregiverId, UUID dependentId, int elapsedTicks, boolean completed) {
	public static final int DURATION_TICKS = 200;
	public static final int LABOR_HOURS = 1;

	public CareAssignment {
		Objects.requireNonNull(caregiverId, "caregiverId");
		Objects.requireNonNull(dependentId, "dependentId");
		if (day < 0 || caregiverId.equals(dependentId) || elapsedTicks < 0 || elapsedTicks > DURATION_TICKS
			|| completed != (elapsedTicks == DURATION_TICKS)) {
			throw new IllegalArgumentException("Invalid care assignment");
		}
	}

	public static CareAssignment start(long day, UUID caregiverId, UUID dependentId) {
		return new CareAssignment(day, caregiverId, dependentId, 0, false);
	}

	public CareAssignment advance() {
		int advancedTicks = Math.min(DURATION_TICKS, elapsedTicks + 1);
		return new CareAssignment(day, caregiverId, dependentId, advancedTicks, advancedTicks == DURATION_TICKS);
	}

	CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putLong("Day", day);
		tag.putUUID("CaregiverId", caregiverId);
		tag.putUUID("DependentId", dependentId);
		tag.putInt("ElapsedTicks", elapsedTicks);
		tag.putBoolean("Completed", completed);
		return tag;
	}

	static CareAssignment load(CompoundTag tag) {
		return new CareAssignment(
			tag.getLong("Day"), tag.getUUID("CaregiverId"), tag.getUUID("DependentId"),
			tag.getInt("ElapsedTicks"), tag.getBoolean("Completed")
		);
	}
}
