package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** The single starter table only. Open working space is not proof of villager navigation. */
public final class ConstructionWorkshop {
	private ConstructionWorkshop() {}

	/** Same center-based radius as existing station use, independent of villager gathering range. */
	static boolean withinWorkRange(BlockPos center, BlockPos station) {
		return center.distSqr(station) <= 12 * 12;
	}

	static boolean supported(ConstructionPlan plan) {
		if (plan == null || !ConstructionPlanner.STARTER_WORKSHOP.equals(plan.id())
			|| plan.instructions().size() != 1) return false;
		ConstructionInstruction part = plan.instructions().getFirst();
		return part.offset().equals(BlockPos.ZERO) && part.state().equals(Blocks.CRAFTING_TABLE.defaultBlockState())
			&& part.item() == Items.CRAFTING_TABLE && part.consume();
	}

	/** Read-only new-job check: nine safe full-block supports and eighteen strictly empty cells. */
	public static ConstructionSite.Result inspect(ConstructionPlan plan, BlockPos origin, ConstructionSite.Probe probe) {
		if (!supported(plan)) return result(plan, origin, "unsupported_layout", origin);
		List<BlockPos> envelope = new ArrayList<>(27);
		try {
			for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
				envelope.add(new BlockPos(Math.addExact(origin.getX(), x), Math.addExact(origin.getY(), y),
					Math.addExact(origin.getZ(), z)));
			}
		} catch (ArithmeticException exception) {
			return result(plan, origin, "out_of_bounds", origin);
		}
		if (ConstructionProtection.isBlocked(plan, origin, probe::modificationBlocked)) {
			return result(plan, origin, "protected_area", origin);
		}
		// Preflight the entire envelope before any state reads, including the furthest headroom cell.
		for (BlockPos position : envelope) {
			if (!probe.inBounds(position)) return result(plan, origin, "out_of_bounds", position);
			if (!probe.ticking(position)) return result(plan, origin, "not_ticking", position);
		}
		for (BlockPos position : envelope) {
			if (position.getY() < origin.getY()) {
				if (!ConstructionSite.safeSupport(probe.state(position))) {
					return result(plan, origin, "foundation_unsupported", position);
				}
			} else if (!probe.state(position).isAir()) {
				return result(plan, origin, "volume_occupied", position);
			}
		}
		return ConstructionProtection.isBlocked(plan, origin, probe::modificationBlocked)
			? result(plan, origin, "protected_area", origin) : result(plan, origin, "ready", null);
	}

	/** Called only after this job's final successful placement, never to discover or claim existing tables. */
	public static boolean registerCompleted(SettlementSavedData data, Settlement settlement, ConstructionPlan plan,
		BlockPos origin, ConstructionSite.Probe probe) {
		if (!supported(plan)) return false;
		var existing = data.getCraftingStation(settlement);
		if (existing.isPresent() && !existing.get().equals(origin)) return false;
		// Completion needs just the exact built target; never inspect an old marker or unloaded neighbors.
		if (!probe.inBounds(origin) || !probe.ticking(origin) || !probe.state(origin).is(Blocks.CRAFTING_TABLE)) return false;
		if (existing.isEmpty()) data.setCraftingStation(settlement, origin);
		return true;
	}

	private static ConstructionSite.Result result(ConstructionPlan plan, BlockPos origin, String reason, BlockPos position) {
		return new ConstructionSite.Result(plan == null ? null : plan.id(), origin, reason, position);
	}
}
