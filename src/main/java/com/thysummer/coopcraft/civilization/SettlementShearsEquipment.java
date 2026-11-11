package com.thysummer.coopcraft.civilization;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;

/** Protects actual loaned hand custody from vanilla trade/bone-meal display writes. */
public final class SettlementShearsEquipment {
	private SettlementShearsEquipment() { }
	private static final ThreadLocal<Removal> REMOVAL = new ThreadLocal<>();
	private static final class Removal {
		final Object actor;
		final ItemStack expected;
		boolean used;
		Removal(Object actor, ItemStack expected) { this.actor = actor; this.expected = expected; }
	}

	/** Called only for a villager's MAINHAND. Unknown loan markers receive the same protection. */
	public static boolean blocksMainHandChange(Object actor, ItemStack held, ItemStack replacement) {
		if (!SettlementShearsLoan.isMarked(held) || held == replacement) return false;
		Removal permit = REMOVAL.get();
		if (permit != null && !permit.used && permit.actor == actor && permit.expected == held
			&& replacement != null && replacement.isEmpty()) {
			permit.used = true;
			return false;
		}
		return true;
	}

	/** One exact empty-hand transition, never authority to install a replacement or clear another hand. */
	public static void allowClear(Villager actor, ItemStack expected, Runnable operation) {
		withRemoval(actor, expected, operation);
	}

	static void withRemoval(Object actor, ItemStack expected, Runnable operation) {
		if (actor == null || expected == null || operation == null || REMOVAL.get() != null)
			throw new IllegalStateException("Invalid or nested shears custody removal");
		REMOVAL.set(new Removal(actor, expected));
		try { operation.run(); }
		finally { REMOVAL.remove(); }
	}

	/** Return/death callers must settle only an actual removal, rather than a copy of a protected hand. */
	public static boolean clear(Villager actor, ItemStack expected) {
		if (actor == null || expected == null || expected.isEmpty() || actor.getMainHandItem() != expected) return false;
		allowClear(actor, expected, () -> actor.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY));
		return actor.getMainHandItem().isEmpty();
	}
}
