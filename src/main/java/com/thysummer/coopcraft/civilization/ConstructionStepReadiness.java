package com.thysummer.coopcraft.civilization;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BedBlock;

/** Shared claim, execution and diagnostic guard; only completion inspects planned paid beds. */
final class ConstructionStepReadiness {
	private ConstructionStepReadiness() {}

	interface Probe extends ConstructionPairReadiness.Probe, ConstructionSite.Probe {
		SettlementHousing.BedCheck inspectBed(BlockPos position);
	}

	static ConstructionPairReadiness.Result inspect(ConstructionJob job, ConstructionPlan plan,
		Probe probe, boolean checkEntities) {
		String compatibility = job.compatibilityBlocker(plan);
		if (!compatibility.equals("ready")) return new ConstructionPairReadiness.Result(null, compatibility);
		if (plan.id().equals(ConstructionPlanner.STARTER_WORKSHOP)) {
			var site = ConstructionWorkshop.inspect(plan, job.origin(), probe);
			if (!site.ready()) return new ConstructionPairReadiness.Result(site.position(), "workshop_" + site.reason());
		}
		var target = ConstructionPairReadiness.inspect(job, plan, probe, checkEntities);
		if (!target.ready() || job.nextIndex() != plan.instructions().size() - 1) return target;
		for (ConstructionInstruction part : plan.instructions()) {
			if (!part.consume() || !(part.state().getBlock() instanceof BedBlock)) continue;
			BlockPos bed;
			try {
				bed = new BlockPos(Math.addExact(job.origin().getX(), part.offset().getX()),
					Math.addExact(job.origin().getY(), part.offset().getY()),
					Math.addExact(job.origin().getZ(), part.offset().getZ()));
			} catch (ArithmeticException overflow) {
				return new ConstructionPairReadiness.Result(target.position(), "completion_beds_out_of_bounds");
			}
			if (!probe.inBounds(bed)) {
				return new ConstructionPairReadiness.Result(target.position(), "completion_beds_out_of_bounds");
			}
			if (probe.inspectBed(bed).status() == SettlementHousing.BedStatus.UNLOADED) {
				return new ConstructionPairReadiness.Result(target.position(), "completion_beds_unloaded");
			}
		}
		// INVALID is allowed: the current step may finish a bed, or an earlier bed may be damaged.
		// Actual registration after placement remains responsible for validity and ownership.
		return target;
	}
}
