package com.thysummer.coopcraft.civilization;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

public record ConstructionJob(ResourceLocation blueprintId, BlockPos origin, int nextIndex, String planFingerprint,
	UUID jobId, Tag malformedJobId) {
	/** Legacy jobs remain readable but cannot resume until explicitly adopted. */
	public ConstructionJob(ResourceLocation blueprintId, BlockPos origin, int nextIndex) {
		this(blueprintId, origin, nextIndex, "");
	}

	public ConstructionJob(ResourceLocation blueprintId, BlockPos origin, int nextIndex, String planFingerprint) {
		this(blueprintId, origin, nextIndex, planFingerprint, UUID.randomUUID());
	}

	public ConstructionJob(ResourceLocation blueprintId, BlockPos origin, int nextIndex, String planFingerprint, UUID jobId) {
		this(blueprintId, origin, nextIndex, planFingerprint, Objects.requireNonNull(jobId, "jobId"), null);
	}

	public static ConstructionJob start(ConstructionPlan plan, BlockPos origin) {
		return new ConstructionJob(plan.id(), origin, 0, plan.fingerprint());
	}

	public ConstructionJob {
		if ((jobId == null) == (malformedJobId == null)) {
			throw new IllegalArgumentException("Exactly one valid or malformed job identity is required");
		}
		if (malformedJobId != null) malformedJobId = malformedJobId.copy();
		Objects.requireNonNull(blueprintId, "blueprintId");
		origin = Objects.requireNonNull(origin, "origin").immutable();
		Objects.requireNonNull(planFingerprint, "planFingerprint");
		if (nextIndex < 0) {
			throw new IllegalArgumentException("nextIndex cannot be negative");
		}
	}

	/** A malformed persisted ID must never gain a new delivery authorization. */
	public boolean hasValidJobId() {
		return jobId != null;
	}

	@Override
	public Tag malformedJobId() {
		return malformedJobId == null ? null : malformedJobId.copy();
	}

	/** Plan adoption changes the pinned plan, not the construction instance. */
	public ConstructionJob withPlanFingerprint(String fingerprint) {
		return new ConstructionJob(blueprintId, origin, nextIndex, fingerprint, jobId, malformedJobId);
	}

	/** The same fail-closed guard is used for target selection, execution, and diagnostics. */
	public String compatibilityBlocker(ConstructionPlan plan) {
		if (!hasValidJobId()) return "invalid_job_id";
		if (plan == null) return "plan_unavailable";
		if (nextIndex >= plan.instructions().size()) return "invalid_index";
		if (planFingerprint.isEmpty()) return "plan_unverified";
		return blueprintId.equals(plan.id()) && planFingerprint.equals(plan.fingerprint()) ? "ready" : "plan_changed";
	}

	ConstructionJob advance() {
		return new ConstructionJob(blueprintId, origin, Math.incrementExact(nextIndex), planFingerprint, jobId, malformedJobId);
	}

	CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		if (hasValidJobId()) tag.putUUID("JobId", jobId);
		else tag.put("JobId", malformedJobId.copy());
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
			tag.getString("PlanFingerprint"),
			!tag.contains("JobId") ? UUID.randomUUID() : tag.hasUUID("JobId") ? tag.getUUID("JobId") : null,
			tag.contains("JobId") && !tag.hasUUID("JobId") ? tag.get("JobId") : null
		);
	}
}
