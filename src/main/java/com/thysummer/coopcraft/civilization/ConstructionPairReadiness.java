package com.thysummer.coopcraft.civilization;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Bounded current-step guard. Checks at most the target and its door/bed companion, never the built prefix. */
final class ConstructionPairReadiness {
	private ConstructionPairReadiness() {}

	interface Probe {
		boolean inBounds(BlockPos position);
		boolean ticking(BlockPos position);
		BlockState state(BlockPos position);
		boolean entityObstructed(BlockPos position);
	}

	record Result(BlockPos position, String blocker) {
		boolean ready() { return blocker.equals("ready"); }
	}

	static Result inspect(ConstructionJob job, ConstructionPlan plan, Probe probe, boolean checkEntities) {
		String compatibility = job.compatibilityBlocker(plan);
		if (!compatibility.equals("ready")) return new Result(null, compatibility);
		var instruction = plan.instructions().get(job.nextIndex());
		boolean paired = instruction.state().getBlock() instanceof DoorBlock
			|| instruction.state().getBlock() instanceof BedBlock;
		ConstructionInstruction companion = null;
		BlockPos position;
		BlockPos companionPosition = null;
		try {
			// Validate the pair's instruction shape before touching the world, including synthetic plans.
			if (paired) {
				int paidIndex = instruction.consume() ? job.nextIndex() : job.nextIndex() - 1;
				if (paidIndex < 0 || paidIndex >= plan.instructions().size() - 1)
					return new Result(null, "pair_invalid");
				var paid = plan.instructions().get(paidIndex);
				var free = plan.instructions().get(paidIndex + 1);
				if (!paid.consume() || free.consume() || paid.state().getBlock() != free.state().getBlock()
					|| paid.state().getBlock() != instruction.state().getBlock()
					|| paid.item() != free.item() || paid.item() != paid.state().getBlock().asItem())
					return new Result(null, "pair_invalid");
				BlockPos delta;
				BlockState expected;
				if (paid.state().getBlock() instanceof DoorBlock) {
					if (paid.state().getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER)
						return new Result(null, "pair_invalid");
					delta = new BlockPos(0, 1, 0);
					expected = paid.state().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
				} else {
					if (paid.state().getValue(BedBlock.PART) != BedPart.FOOT)
						return new Result(null, "pair_invalid");
					Direction direction = paid.state().getValue(BedBlock.FACING);
					delta = new BlockPos(direction.getStepX(), 0, direction.getStepZ());
					expected = paid.state().setValue(BedBlock.PART, BedPart.HEAD);
				}
				if (!offset(paid.offset(), delta).equals(free.offset()) || !expected.equals(free.state()))
					return new Result(null, "pair_invalid");
				companion = instruction.consume() ? free : paid;
			}
			position = offset(job.origin(), instruction.offset());
			if (companion != null) companionPosition = offset(job.origin(), companion.offset());
		} catch (ArithmeticException exception) {
			return new Result(null, "target_out_of_bounds");
		}
		// Preflight the entire two-cell envelope before ANY block/entity reads.
		if (!probe.inBounds(position)) return new Result(position, "target_out_of_bounds");
		if (companionPosition != null && !probe.inBounds(companionPosition))
			return new Result(position, "pair_out_of_bounds");
		if (!probe.ticking(position)) return new Result(position, "target_not_ticking");
		if (companionPosition != null && !probe.ticking(companionPosition))
			return new Result(position, "pair_not_ticking");
		if (!probe.state(position).isAir()) return new Result(position, "target_occupied");
		if (checkEntities && probe.entityObstructed(position)) return new Result(position, "entity_obstructed");
		if (companionPosition != null) {
			BlockState actual = probe.state(companionPosition);
			if (instruction.consume() && !actual.isAir()) return new Result(position, "pair_occupied");
			if (!instruction.consume() && !actual.equals(companion.state()))
				return new Result(position, "pair_changed");
		}
		return new Result(position, "ready");
	}

	private static BlockPos offset(BlockPos origin, BlockPos offset) {
		return new BlockPos(Math.addExact(origin.getX(), offset.getX()), Math.addExact(origin.getY(), offset.getY()),
			Math.addExact(origin.getZ(), offset.getZ()));
	}
}
