package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;

/** Exact shear-call capture. No entity scan, generic spawn interception or reconstructed loot. */
public final class SettlementShearingDrops {
	private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
	private SettlementShearingDrops() {}

	public static Scope begin(Sheep sheep) { return beginIdentity(sheep); }
	static Scope beginIdentity(Object sheep) {
		if (sheep == null || ACTIVE.get() != null) return null;
		Scope scope = new Scope(sheep);
		ACTIVE.set(scope);
		return scope;
	}

	/** Called by a try/finally method wrapper, including recursive same-sheep calls. */
	public static void enter(Object sheep) {
		Scope scope = ACTIVE.get();
		if (scope == null) return;
		scope.depth++;
		if (scope.sheep != sheep || scope.depth != 1 || ++scope.entries != 1) scope.invalid = true;
	}

	public static void exit(Object sheep, boolean completed) {
		Scope scope = ACTIVE.get();
		if (scope == null) return;
		if (scope.sheep != sheep || !completed || scope.depth <= 0) scope.invalid = true;
		scope.depth--;
	}

	/** Return the exact original value: unrelated vanilla shearing remains unchanged. */
	public static ItemEntity capture(Sheep sheep, ItemEntity drop) {
		Scope scope = ACTIVE.get();
		if (scope != null) scope.record(sheep, drop);
		return drop;
	}

	public static final class Scope implements AutoCloseable {
		private final Object sheep;
		private final List<ItemEntity> drops = new ArrayList<>(3);
		private int depth, entries;
		private boolean invalid, closed;
		private Scope(Object sheep) { this.sheep = sheep; }

		private void record(Object source, ItemEntity drop) {
			if (source != sheep || depth != 1 || drop == null || drops.size() >= 3 || drops.contains(drop)) {
				invalid = true;
				return;
			}
			drops.add(drop);
		}

		public List<ItemEntity> drops() {
			return complete() && !drops.isEmpty() ? List.copyOf(drops) : List.of();
		}
		boolean complete() { return !closed && ACTIVE.get() == this && !invalid && depth == 0 && entries == 1; }

		@Override public void close() {
			closed = true;
			if (ACTIVE.get() == this) ACTIVE.remove();
			drops.clear();
		}
	}
}
