package com.thysummer.coopcraft.civilization;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

public record ConstructionJob(ResourceLocation blueprintId, BlockPos origin, int nextIndex, String planFingerprint) {
	/** Legacy jobs remain readable but cannot resume until explicitly adopted. */
	public ConstructionJob(ResourceLocation blueprintId, BlockPos origin, int nextIndex) {
		this(blueprintId, origin, nextIndex, "");
	}

	public static ConstructionJob start(ConstructionPlan plan, BlockPos origin) {
		return new ConstructionJob(plan.id(), origin, 0, plan.fingerprint());
	}

	public ConstructionJob {
		Objects.requireNonNull(blueprintId, "blueprintId");
		origin = Objects.requireNonNull(origin, "origin").immutable();
		Objects.requireNonNull(planFingerprint, "planFingerprint");
		if (nextIndex < 0) {
			throw new IllegalArgumentException("nextIndex cannot be negative");
		}
	}

	/** The same fail-closed guard is used for target selection, execution, and diagnostics. */
	public String compatibilityBlocker(ConstructionPlan plan) {
		if (plan == null) return "plan_unavailable";
		if (nextIndex >= plan.instructions().size()) return "invalid_index";
		if (planFingerprint.isEmpty()) return "plan_unverified";
		return blueprintId.equals(plan.id()) && planFingerprint.equals(plan.fingerprint()) ? "ready" : "plan_changed";
	}

	ConstructionJob advance() {
		return new ConstructionJob(blueprintId, origin, Math.incrementExact(nextIndex), planFingerprint);
	}

	CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putString("Blueprint", blueprintId.toString());
		tag.putLong("Origin", origin.asLong());
		tag.putInt("NextIndex", nextIndex);
		if (!planFingerprint.isEmpty()) tag.putString("PlanFingerprint", planFingerprint);
		return tag;
	}

	static ConstructionJob load(CompoundTag tag) {
		return new ConstructionJob(
			ResourceLocation.parse(tag.getString("Blueprint")),
			BlockPos.of(tag.getLong("Origin")),
			tag.getInt("NextIndex"),
			tag.getString("PlanFingerprint")
		);
	}
}
