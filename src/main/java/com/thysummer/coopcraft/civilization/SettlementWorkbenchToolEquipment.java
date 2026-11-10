package com.thysummer.coopcraft.civilization;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.component.CustomData;

/**
 * Moves the actual output of one successful workbench batch into an empty hand, never issues a tool.
 * Requires distinct storage/hand, synchronous non-reentrant mutation, side-effect-free live reads and
 * reference-retaining setters, as in SettlementShearsLoan. Exceptions before/after a setter are checked
 * against actual custody. Cloning/discarding setters, failing rollback, reentry and crash atomicity
 * are outside this contract. No compensating item is manufactured.
 */
public final class SettlementWorkbenchToolEquipment {
	private SettlementWorkbenchToolEquipment() { }

	/** Any pickaxe, including marked or customized ones, prevents starting another first-tool job. */
	public static boolean needsFirstPickaxe(Container backpack, ItemStack main, ItemStack off) {
		if (backpack == null || main == null || !main.isEmpty() || off == null || pickaxe(off)) return false;
		for (int slot = 0; slot < backpack.getContainerSize(); slot++)
			if (pickaxe(backpack.getItem(slot))) return false;
		return true;
	}

	private static boolean pickaxe(ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.getItem() instanceof PickaxeItem;
	}

	/** One attempt only; an unsuccessful transfer leaves the marked product for ordinary cargo return. */
	static boolean equipFinishedPickaxe(SettlementWorkbenchCrafting.Ticket ticket, SettlementSavedData data,
		Settlement camp, UUID actor, Container backpack, SettlementShearsLoan.Hand mainHand,
		Supplier<ItemStack> offHand, BooleanSupplier atStation) {
		if (backpack == null || mainHand == null || offHand == null || atStation == null
			|| !SettlementWorkbenchCrafting.claimFinishedPickaxe(ticket, data, camp, actor, backpack)) return false;
		int source = -1;
		for (int slot = 0; slot < backpack.getContainerSize(); slot++) {
			if (!SettlementWorkbenchCrafting.matchesFinishedPickaxe(ticket, backpack.getItem(slot))) continue;
			if (source != -1) return false;
			source = slot;
		}
		if (source == -1) return false;
		ItemStack actual = backpack.getItem(source), before = actual.copy();
		ItemStack plain = SettlementWorkbenchCargo.unmarkOutput(actual);
		if (plain.isEmpty()) return false;
		// All callbacks precede the final live custody checks. A callback filling a hand or replacing
		// the source must leave the bag untouched. No authorization callbacks run during transfer.
		if (!atStation.getAsBoolean()
			|| !SettlementWorkbenchCrafting.isFinishedPickaxeCurrent(ticket, data, camp, actor, backpack)
			|| !available(backpack, source, actual, before, mainHand, offHand)) return false;
		try { backpack.removeItemNoUpdate(source); }
		catch (RuntimeException mutationFailure) { /* Inspect the actual source, never repeat removal. */ }
		if (backpack.getItem(source) == actual && ItemStack.matches(before, actual)) return false;
		if (!backpack.getItem(source).isEmpty())
			throw new IllegalStateException("Workbench output source did not detach its actual stack");
		boolean delivered = false;
		CustomData marker = actual.get(DataComponents.CUSTOM_DATA);
		try {
			setCustomData(actual, plain.get(DataComponents.CUSTOM_DATA));
			try { mainHand.set(actual); }
			catch (RuntimeException mutationFailure) { /* A setter may have committed before throwing. */ }
			delivered = mainHand.get() == actual;
			return delivered;
		} finally {
			if (!delivered) {
				setCustomData(actual, marker);
				if (!backpack.getItem(source).isEmpty())
					throw new IllegalStateException("Cannot restore output to occupied workbench cargo slot");
				try { backpack.setItem(source, actual); }
				catch (RuntimeException mutationFailure) { /* Check restoration rather than duplicate it. */ }
				if (backpack.getItem(source) != actual)
					throw new IllegalStateException("Cannot restore actual workbench output");
			}
			try { backpack.setChanged(); }
			catch (RuntimeException notificationFailure) { /* Custody has already committed. */ }
		}
	}

	private static boolean available(Container backpack, int source, ItemStack actual, ItemStack before,
		SettlementShearsLoan.Hand mainHand, Supplier<ItemStack> offHand) {
		ItemStack off = offHand.get(), main = mainHand.get();
		if (main == null || !main.isEmpty() || off == null || pickaxe(off)
			|| backpack.getItem(source) != actual || !ItemStack.matches(before, actual)) return false;
		for (int slot = 0; slot < backpack.getContainerSize(); slot++)
			if (slot != source && pickaxe(backpack.getItem(slot))) return false;
		return true;
	}

	private static void setCustomData(ItemStack actual, CustomData data) {
		if (data == null) actual.remove(DataComponents.CUSTOM_DATA);
		else actual.set(DataComponents.CUSTOM_DATA, data);
	}
}
