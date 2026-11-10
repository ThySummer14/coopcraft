package com.thysummer.coopcraft.civilization;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Read-only, same-height delivery routing. Budget exhaustion is a failed route, never a partial path. */
final class SettlementFoodDeliveryRoute {
	static final int RADIUS = 8, MAX_EXPLORED = 128, MAX_STEPS = 16;
	private static final int[][] DIRECTIONS = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
	private SettlementFoodDeliveryRoute() { }

	static List<BlockPos> find(BlockPos from, BlockPos recipient, SettlementMaterialScouting.Probe probe) {
		if (probe == null || !endpoints(from, recipient)) return List.of();
		BlockPos start = from.immutable();
		Set<BlockPos> visited = new HashSet<>();
		Map<BlockPos, BlockPos> parents = new HashMap<>();
		ArrayDeque<Step> pending = new ArrayDeque<>();
		visited.add(start); // The start and rejected terrain both consume the exploration budget.
		if (!SettlementMaterialScouting.safe(start, probe)) return List.of();
		if (adjacent(start, recipient)) return List.of(start);
		pending.addLast(new Step(start, 0));
		while (!pending.isEmpty()) {
			Step step = pending.removeFirst();
			if (step.depth() == MAX_STEPS) continue;
			for (int[] direction : DIRECTIONS) {
				BlockPos next = step.position().offset(direction[0], 0, direction[1]);
				if (!within(next, start) || next.equals(recipient) || visited.contains(next)) continue;
				if (visited.size() >= MAX_EXPLORED) return List.of();
				visited.add(next);
				if (!SettlementMaterialScouting.safe(next, probe)) continue;
				parents.put(next, step.position());
				if (adjacent(next, recipient)) return reconstruct(next, parents);
				pending.addLast(new Step(next, step.depth() + 1));
			}
		}
		return List.of();
	}

	/** Recheck every node against current terrain before following a previously calculated route. */
	static boolean valid(List<BlockPos> nodes, BlockPos from, BlockPos recipient, SettlementMaterialScouting.Probe probe) {
		if (probe == null || !endpoints(from, recipient) || nodes == null || nodes.isEmpty()
			|| nodes.size() > MAX_STEPS + 1 || !from.equals(nodes.getFirst())
			|| nodes.getLast() == null || !adjacent(nodes.getLast(), recipient)) return false;
		Set<BlockPos> visited = new HashSet<>();
		BlockPos previous = null;
		for (BlockPos node : nodes) {
			if (node == null || !within(node, from) || node.equals(recipient) || !visited.add(node)
				|| previous != null && !adjacent(previous, node) || !SettlementMaterialScouting.safe(node, probe)) return false;
			previous = node;
		}
		return true;
	}

	private static boolean endpoints(BlockPos from, BlockPos recipient) {
		return from != null && recipient != null && !from.equals(recipient) && within(recipient, from);
	}

	private static boolean within(BlockPos position, BlockPos origin) {
		long dx = (long) position.getX() - origin.getX(), dz = (long) position.getZ() - origin.getZ();
		return position.getY() == origin.getY() && Math.abs(dx) <= RADIUS && Math.abs(dz) <= RADIUS
			&& dx * dx + dz * dz <= (long) RADIUS * RADIUS;
	}

	private static boolean adjacent(BlockPos a, BlockPos b) {
		return a.getY() == b.getY() && Math.abs((long) a.getX() - b.getX()) + Math.abs((long) a.getZ() - b.getZ()) == 1;
	}

	private static List<BlockPos> reconstruct(BlockPos end, Map<BlockPos, BlockPos> parents) {
		List<BlockPos> nodes = new ArrayList<>();
		for (BlockPos node = end; node != null; node = parents.get(node)) nodes.add(node);
		Collections.reverse(nodes);
		return List.copyOf(nodes);
	}

	private record Step(BlockPos position, int depth) { }
}
