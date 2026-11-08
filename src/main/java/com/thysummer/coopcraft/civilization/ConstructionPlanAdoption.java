package com.thysummer.coopcraft.civilization;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Explicit adoption checks cannot prove the historical plan, and never rewrite its completed prefix. */
public final class ConstructionPlanAdoption {
	private ConstructionPlanAdoption() { }

	static Result inspect(ConstructionJob job, ConstructionPlan plan, String expectedFingerprint, Probe probe) {
		if (job == null) return new Result("no_job", null);
		if (!job.planFingerprint().isEmpty()) return new Result("not_legacy", null);
		if (plan == null) return new Result("plan_unavailable", null);
		if (job.nextIndex() >= plan.instructions().size()) return new Result("invalid_index", null);
		if (!job.blueprintId().equals(plan.id()) || !plan.fingerprint().equals(expectedFingerprint)) {
			return new Result("fingerprint_mismatch", null);
		}
		for (int index = 0; index < job.nextIndex(); index++) {
			var instruction = plan.instructions().get(index);
			BlockPos position = job.origin().offset(instruction.offset());
			if (!probe.loaded(position)) return new Result("prefix_unloaded", position);
			if (!instruction.state().equals(probe.state(position))) return new Result("prefix_mismatch", position);
		}
		return new Result("adopted", null);
	}

	public record Result(String status, BlockPos position) { }

	interface Probe {
		boolean loaded(BlockPos position);
		BlockState state(BlockPos position);
	}
}
