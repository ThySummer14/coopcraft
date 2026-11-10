package com.thysummer.coopcraft.civilization;

import net.minecraft.core.BlockPos;

/** Read-only whole-plan veto. It grants no material, world, membership or work permission. */
final class ConstructionProtection {
	private ConstructionProtection() { }

	@FunctionalInterface
	interface Probe {
		boolean blocked(int minX, int minZ, int maxX, int maxZ);
	}

	/** Inclusive X/Z bounds include the built prefix, future steps and empty space between them. */
	static boolean isBlocked(ConstructionPlan plan, BlockPos origin, Probe probe) {
		if (plan == null || origin == null || probe == null || plan.instructions().isEmpty()) return true;
		int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		try {
			for (ConstructionInstruction instruction : plan.instructions()) {
				int x = Math.addExact(origin.getX(), instruction.offset().getX());
				int z = Math.addExact(origin.getZ(), instruction.offset().getZ());
				minX = Math.min(minX, x); maxX = Math.max(maxX, x);
				minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
			}
		} catch (ArithmeticException overflow) {
			// Never wrap a corrupt footprint into an unrelated unprotected location.
			return true;
		}
		return probe.blocked(minX, minZ, maxX, maxZ);
	}
}
