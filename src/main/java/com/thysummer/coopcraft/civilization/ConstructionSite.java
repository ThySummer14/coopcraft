package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Conservative, read-only new-job siting. This is not a pathfinding or structure repair check. */
public final class ConstructionSite {
	private static final int[] DISTANCES = {6, 12, 18};
	private static final int MAX_INSTRUCTIONS = 4096;
	private static final int MAX_VOLUME = 16384;

	private ConstructionSite() {}

	public interface Probe {
		/** Must not read blocks or load chunks. Includes build height and world border. */
		boolean inBounds(BlockPos position);
		/** Must not load chunks. Called only for an in-bounds position. */
		boolean ticking(BlockPos position);
		/** Called only after bounds and ticking checks for this exact position. */
		BlockState state(BlockPos position);
	}

	public record Result(ResourceLocation blueprintId, BlockPos origin, String reason, BlockPos position) {
		public Result {
			origin = origin.immutable();
			position = position == null ? null : position.immutable();
		}
		public boolean ready() { return reason.equals("ready"); }
	}

	public static List<BlockPos> candidates(BlockPos center) {
		List<BlockPos> result = new ArrayList<>();
		for (int distance : DISTANCES) {
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				result.add(center.relative(direction, distance).immutable());
			}
		}
		return List.copyOf(result);
	}

	/** All twelve candidates, in the same order used for selection. No job or inventory access. */
	public static List<Result> inspectCandidates(ConstructionPlan plan, BlockPos center, Probe probe) {
		return candidates(center).stream().map(origin -> inspect(plan, origin, probe)).toList();
	}

	/** Known residential IDs remain residential even when a malformed reload removes their beds. */
	public static boolean isHousing(ConstructionPlan plan) {
		return plan != null && (ConstructionPlanner.isHousingBlueprint(plan.id())
			|| plan.instructions().stream().anyMatch(part -> part.state().getBlock() instanceof BedBlock));
	}

	public static Result inspect(ConstructionPlan plan, BlockPos origin, Probe probe) {
		Result result = inspectSite(plan, origin, probe);
		return new Result(plan == null ? null : plan.id(), result.origin(), result.reason(), result.position());
	}

	private static Result inspectSite(ConstructionPlan plan, BlockPos origin, Probe probe) {
		if (plan == null) return fail(origin, "plan_unavailable", null);
		if (plan.instructions().isEmpty() || plan.instructions().size() > MAX_INSTRUCTIONS) {
			return fail(origin, "unsupported_layout", origin);
		}
		boolean housing = isHousing(plan);
		if (housing && plan.instructions().stream().noneMatch(part -> part.state().getBlock() instanceof BedBlock)) {
			return fail(origin, "unsupported_layout", origin);
		}
		try {
			if (!housing) {
				// Nonresidential plans retain target-only air checks; no new foundation/entrance rules.
				for (ConstructionInstruction part : plan.instructions()) {
					Result result = air(origin, offset(origin, part.offset()), probe, "target_occupied");
					if (result != null) return result;
				}
				return fail(origin, "ready", null);
			}
			return inspectHousing(plan, origin, probe);
		} catch (ArithmeticException exception) {
			return fail(origin, "out_of_bounds", origin);
		}
	}

	private static Result inspectHousing(ConstructionPlan plan, BlockPos origin, Probe probe) {
		Map<BlockPos, BlockState> layout = new HashMap<>();
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (ConstructionInstruction part : plan.instructions()) {
			BlockPos p = part.offset();
			if (layout.put(p, part.state()) != null) return fail(origin, "unsupported_layout", offset(origin, p));
			minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
			minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
			minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
		}
		long width = (long) maxX - minX + 1, height = (long) maxY - minY + 1, depth = (long) maxZ - minZ + 1;
		if (width < 3 || depth < 3 || height < 3 || width > 64 || height > 64 || depth > 64
			|| width * height * depth > MAX_VOLUME) return fail(origin, "unsupported_layout", origin);
		// Normalize loops to bounded counts, avoiding integer wrap at extreme synthetic offsets.
		List<BlockPos> entrances = new ArrayList<>();
		for (ConstructionInstruction part : plan.instructions()) {
			BlockPos p = part.offset();
			if (!(part.state().getBlock() instanceof DoorBlock)) continue;
			if (part.state().getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
				BlockState lower = layout.get(offset(p, new BlockPos(0, -1, 0)));
				if (lower == null || !lower.equals(part.state().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER))) {
					return fail(origin, "unsupported_layout", offset(origin, p));
				}
				continue;
			}
			boolean xEdge = p.getX() == minX || p.getX() == maxX;
			boolean zEdge = p.getZ() == minZ || p.getZ() == maxZ;
			if ((xEdge && zEdge) || (long) p.getY() != (long) minY + 1
				|| !part.state().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)
					.equals(layout.get(offset(p, new BlockPos(0, 1, 0))))) {
				return fail(origin, "unsupported_layout", offset(origin, p));
			}
			Direction facing = part.state().getValue(DoorBlock.FACING);
			Direction outward;
			int distance = 1;
			if (xEdge || zEdge) {
				// Perimeter doors retain the original noncorner, outward-axis rule, for either facing sign.
				outward = xEdge ? (p.getX() == minX ? Direction.WEST : Direction.EAST)
					: (p.getZ() == minZ ? Direction.NORTH : Direction.SOUTH);
				if (facing.getAxis() != outward.getAxis()) {
					return fail(origin, "unsupported_layout", offset(origin, p));
				}
			} else {
				// A recessed door needs a planned straight, supported two-high corridor to an edge.
				// Resolve both candidates using only the blueprint; world obstacles never select a detour.
				int forward = corridorDistance(layout, p, facing, minX, maxX, minZ, maxZ);
				int backward = corridorDistance(layout, p, facing.getOpposite(), minX, maxX, minZ, maxZ);
				if (forward == 0 && backward == 0) return fail(origin, "unsupported_layout", offset(origin, p));
				boolean useForward = forward != 0 && (backward == 0 || forward <= backward);
				outward = useForward ? facing : facing.getOpposite();
				distance = useForward ? forward : backward;
			}
			entrances.add(offset(offset(origin, p), new BlockPos(outward.getStepX() * distance, 0,
				outward.getStepZ() * distance)));
		}
		if (entrances.isEmpty()) return fail(origin, "unsupported_layout", origin);
		for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) {
			BlockPos floor = new BlockPos(minX + x, minY, minZ + z);
			BlockState planned = layout.get(floor);
			if (planned == null || !safeSupport(planned)) return fail(origin, "unsupported_layout", offset(origin, floor));
		}
		// Preflight the complete read envelope before reading any world block, including both
		// possible entrance support levels. Unknown data is never interpreted as air or support.
		for (int y = -1; y < height; y++) for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) {
			Result result = accessible(origin, offset(origin, new BlockPos(minX + x, Math.addExact(minY, y), minZ + z)), probe);
			if (result != null) return result;
		}
		for (BlockPos feet : entrances) for (int y = -2; y <= 1; y++) {
			Result result = accessible(origin, offset(feet, new BlockPos(0, y, 0)), probe);
			if (result != null) return result;
		}
		for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) {
			BlockPos below = offset(origin, new BlockPos(minX + x, Math.subtractExact(minY, 1), minZ + z));
			Result result = support(origin, below, probe, "foundation_unsupported");
			if (result != null) return result;
		}
		for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) {
			Result result = air(origin, offset(origin, new BlockPos(minX + x, minY + y, minZ + z)), probe, "volume_occupied");
			if (result != null) return result;
		}
		for (BlockPos feet : entrances) {
			Result result = air(origin, feet, probe, "entrance_obstructed");
			if (result != null) return result;
			result = air(origin, offset(feet, new BlockPos(0, 1, 0)), probe, "entrance_obstructed");
			if (result != null) return result;
			BlockPos floor = offset(feet, new BlockPos(0, -1, 0));
			result = accessible(origin, floor, probe);
			if (result != null) return result;
			BlockState state = probe.state(floor);
			if (safeSupport(state)) continue;
			// One-block descent: floor cell becomes feet space; the already checked cell above is headroom.
			if (!state.isAir()) return fail(origin, "entrance_unsupported", floor);
			result = support(origin, offset(floor, new BlockPos(0, -1, 0)), probe, "entrance_unsupported");
			if (result != null) return result;
		}
		return fail(origin, "ready", null);
	}

	/** Returns distance to the exterior feet cell, or zero for a blocked planned corridor.
	 * The footprint has already been bounded to 64 cells per axis before this method is called. */
	private static int corridorDistance(Map<BlockPos, BlockState> layout, BlockPos door, Direction direction,
		int minX, int maxX, int minZ, int maxZ) {
		int distance = switch (direction) {
			case WEST -> door.getX() - minX + 1;
			case EAST -> maxX - door.getX() + 1;
			case NORTH -> door.getZ() - minZ + 1;
			case SOUTH -> maxZ - door.getZ() + 1;
			default -> 0;
		};
		for (int step = 1; step < distance; step++) {
			BlockPos feet = offset(door, new BlockPos(direction.getStepX() * step, 0, direction.getStepZ() * step));
			BlockState floor = layout.get(offset(feet, new BlockPos(0, -1, 0)));
			if (floor == null || !safeSupport(floor)) return 0;
			for (int y = 0; y <= 1; y++) {
				BlockState obstruction = layout.get(offset(feet, new BlockPos(0, y, 0)));
				if (obstruction != null && !obstruction.isAir()) return 0;
			}
		}
		return distance;
	}

	/** Intentionally conservative full-cube support, queried against an empty read-only block view. */
	static boolean safeSupport(BlockState state) {
		return !state.isAir() && state.getFluidState().isEmpty() && !state.hasBlockEntity()
			&& !(state.getBlock() instanceof FallingBlock) && !(state.getBlock() instanceof LeavesBlock)
			&& !state.is(Blocks.FROSTED_ICE)
			&& !state.is(Blocks.MAGMA_BLOCK) && !state.is(Blocks.CACTUS) && !state.is(Blocks.POWDER_SNOW)
			&& !state.is(Blocks.SLIME_BLOCK) && !state.is(Blocks.HONEY_BLOCK)
			&& !state.is(Blocks.ICE) && !state.is(Blocks.PACKED_ICE) && !state.is(Blocks.BLUE_ICE)
			&& Block.isShapeFullBlock(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
	}

	private static Result air(BlockPos origin, BlockPos position, Probe probe, String reason) {
		Result result = accessible(origin, position, probe);
		return result != null ? result : probe.state(position).isAir() ? null : fail(origin, reason, position);
	}

	private static Result support(BlockPos origin, BlockPos position, Probe probe, String reason) {
		Result result = accessible(origin, position, probe);
		return result != null ? result : safeSupport(probe.state(position)) ? null : fail(origin, reason, position);
	}

	private static Result accessible(BlockPos origin, BlockPos position, Probe probe) {
		if (!probe.inBounds(position)) return fail(origin, "out_of_bounds", position);
		if (!probe.ticking(position)) return fail(origin, "not_ticking", position);
		return null;
	}

	private static BlockPos offset(BlockPos origin, net.minecraft.core.Vec3i delta) {
		return new BlockPos(Math.addExact(origin.getX(), delta.getX()), Math.addExact(origin.getY(), delta.getY()),
			Math.addExact(origin.getZ(), delta.getZ()));
	}

	private static Result fail(BlockPos origin, String reason, BlockPos position) {
		return new Result(null, origin, reason, position);
	}
}
