package com.thysummer.coopcraft.civilization;

import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * Custody of one actual warehouse tool, never a newly manufactured replacement. Callers authorize
 * arrival, living membership and the current warehouse binding. Returns do not require an active
 * sheep permit, work role or wool demand. This helper neither discovers nor loads a warehouse.
 *
 * Transfers require distinct, non-overlapping storage and hand, synchronous server-thread mutation,
 * side-effect-free reads and no reentrant inventory edits. Setters retain the supplied stack reference;
 * a notification throwing after the mutation is supported and checked against the actual contents.
 * Arbitrary setters that clone/discard items, fail during rollback, or concurrent/process-crash
 * atomicity are outside this contract. No compensating stack is ever manufactured.
 */
public final class SettlementShearsLoan {
	public static final String KEY = "coopcraft_shears_loan";
	private static final Set<String> V1_FIELDS = Set.of(
		"Version", "Settlement", "Actor", "Dimension", "Loan", "HadCustomData");

	private SettlementShearsLoan() { }

	/** A normal equipment slot: reads return its live stack; writes retain the supplied reference. */
	public interface Hand {
		ItemStack get();
		void set(ItemStack stack);
	}

	private record Stamp(ResourceLocation dimension, UUID camp, UUID actor, UUID loan, boolean hadCustomData) { }

	/** Presence, not recognition: wrong items, corrupt formats and future versions stay quarantined. */
	public static boolean isMarked(ItemStack stack) {
		if (stack == null) return false;
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		return custom != null && custom.copyTag().contains(KEY);
	}

	private static boolean singleShears(ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.is(Items.SHEARS) && stack.getCount() == 1;
	}

	private static boolean otherPurpose(ItemStack stack) {
		return ConstructionSupply.isMarked(stack) || SettlementWorkbenchCargo.isMarked(stack)
			|| SettlementShearingCargo.isMarked(stack);
	}

	/** Names, enchantments, damage, explicit component removals and unrelated custom data are retained. */
	public static boolean borrowable(ItemStack stack) {
		return singleShears(stack) && !isMarked(stack) && !otherPurpose(stack);
	}

	/** Detached preview only. The transfer methods debit actual custody before using this metadata. */
	public static ItemStack mark(ItemStack actual, ResourceLocation dimension, UUID camp, UUID actor, UUID loan) {
		if (!borrowable(actual) || dimension == null || camp == null || actor == null || loan == null)
			throw new IllegalArgumentException("Shears loan requires one unassigned actual tool and complete identity");
		CustomData custom = actual.get(DataComponents.CUSTOM_DATA);
		CompoundTag root = custom == null ? new CompoundTag() : custom.copyTag();
		CompoundTag stamp = new CompoundTag();
		stamp.putInt("Version", 1);
		stamp.putUUID("Settlement", camp); stamp.putUUID("Actor", actor); stamp.putUUID("Loan", loan);
		stamp.putString("Dimension", dimension.toString());
		// Without this bit, removing our only key would erase a pre-existing empty CUSTOM_DATA component.
		stamp.putBoolean("HadCustomData", custom != null);
		root.put(KEY, stamp);
		ItemStack marked = actual.copy();
		marked.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		return marked;
	}

	private static Stamp read(ItemStack stack) {
		if (!singleShears(stack) || otherPurpose(stack)) return null;
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		if (custom == null) return null;
		CompoundTag root = custom.copyTag();
		if (!root.contains(KEY, Tag.TAG_COMPOUND)) return null;
		CompoundTag stamp = root.getCompound(KEY);
		if (!stamp.getAllKeys().equals(V1_FIELDS)
			|| !stamp.contains("Version", Tag.TAG_INT) || stamp.getInt("Version") != 1
			|| !stamp.hasUUID("Settlement") || !stamp.hasUUID("Actor") || !stamp.hasUUID("Loan")
			|| !stamp.contains("Dimension", Tag.TAG_STRING)
			|| !stamp.contains("HadCustomData", Tag.TAG_BYTE)
			|| (stamp.getByte("HadCustomData") != 0 && stamp.getByte("HadCustomData") != 1)) return null;
		ResourceLocation dimension = ResourceLocation.tryParse(stamp.getString("Dimension"));
		if (dimension == null || !dimension.toString().equals(stamp.getString("Dimension"))) return null;
		return new Stamp(dimension, stamp.getUUID("Settlement"), stamp.getUUID("Actor"),
			stamp.getUUID("Loan"), stamp.getBoolean("HadCustomData"));
	}

	public static boolean isRecognized(ItemStack stack) { return read(stack) != null; }

	public static boolean ownedBy(ItemStack stack, ResourceLocation dimension, UUID camp, UUID actor) {
		Stamp stamp = read(stack);
		return stamp != null && stamp.dimension().equals(dimension) && stamp.camp().equals(camp)
			&& stamp.actor().equals(actor);
	}

	/** Only recognized metadata can be removed. This is a detached preview, not proof of delivery. */
	public static ItemStack unmark(ItemStack stack) {
		Stamp stamp = read(stack);
		if (stamp == null) throw new IllegalArgumentException("Unrecognized shears loan must remain quarantined");
		ItemStack ordinary = stack.copy();
		removeMarker(ordinary, stamp);
		return ordinary;
	}

	private static void removeMarker(ItemStack stack, Stamp stamp) {
		CompoundTag root = stack.get(DataComponents.CUSTOM_DATA).copyTag();
		root.remove(KEY);
		if (root.isEmpty() && !stamp.hadCustomData()) stack.remove(DataComponents.CUSTOM_DATA);
		else stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
	}

	private static boolean emptyHand(Hand hand) { return hand.get() != null && hand.get().isEmpty(); }

	/** Borrows exactly one existing tool into an empty hand. Repeated calls cannot issue another loan. */
	public static boolean borrow(Container stockpile, Hand hand, ResourceLocation dimension, UUID camp,
		UUID actor, BooleanSupplier currentAuthorization) {
		if (stockpile == null || hand == null || dimension == null || camp == null || actor == null
			|| currentAuthorization == null || !currentAuthorization.getAsBoolean() || !emptyHand(hand)) return false;
		for (int slot = 0; slot < stockpile.getContainerSize(); slot++) {
			ItemStack actual = stockpile.getItem(slot);
			if (!borrowable(actual)) continue;
			ItemStack preview = mark(actual, dimension, camp, actor, UUID.randomUUID());
			ItemStack before = actual.copy();
			if (!currentAuthorization.getAsBoolean() || !emptyHand(hand)
				|| stockpile.getItem(slot) != actual || !ItemStack.matches(before, actual)) return false;
			// Standard removeItemNoUpdate detaches the actual reference without invoking a change listener.
			ItemStack removed = stockpile.removeItemNoUpdate(slot);
			if (removed != actual || !stockpile.getItem(slot).isEmpty())
				throw new IllegalStateException("Warehouse must detach the actual shears without replacing them");
			boolean delivered = false;
			try {
				if (!currentAuthorization.getAsBoolean() || !emptyHand(hand)) return false;
				actual.set(DataComponents.CUSTOM_DATA, preview.get(DataComponents.CUSTOM_DATA));
				delivered = writeHand(hand, actual);
				return delivered;
			} finally {
				if (!delivered) {
					// Restore only the very same detached item. Never overwrite a newly occupied source.
					if (!stockpile.getItem(slot).isEmpty() || !writeStore(stockpile, slot, actual))
						throw new IllegalStateException("Cannot restore detached warehouse shears");
					Stamp stamp = read(actual);
					if (stamp != null) removeMarker(actual, stamp);
				}
				notifyChanged(stockpile);
			}
		}
		return false;
	}

	/**
	 * Returns one recognized loan belonging to this exact dimension/camp/actor. Full or rejecting
	 * warehouses leave it in hand, still marked. Even customized stackable shears use an empty slot;
	 * loans never merge into another tool. Only an accepted insertion loses this loan's marker.
	 */
	public static boolean returnFromHand(Hand hand, Container stockpile, ResourceLocation dimension, UUID camp,
		UUID actor, BooleanSupplier currentAuthorization) {
		if (hand == null || stockpile == null || currentAuthorization == null || !currentAuthorization.getAsBoolean()) return false;
		ItemStack actual = hand.get();
		if (!ownedBy(actual, dimension, camp, actor)) return false;
		Stamp stamp = read(actual);
		ItemStack before = actual.copy();
		ItemStack offered = unmark(actual);
		for (int slot = 0; slot < stockpile.getContainerSize(); slot++) {
			if (!accepts(stockpile, slot, offered)) continue;
			if (!currentAuthorization.getAsBoolean() || hand.get() != actual
				|| !ItemStack.matches(before, actual) || !accepts(stockpile, slot, offered)) return false;
			if (!writeHand(hand, ItemStack.EMPTY)) return false;
			boolean accepted = false;
			try {
				if (!currentAuthorization.getAsBoolean() || !accepts(stockpile, slot, offered)) return false;
				// Insert the real marked reference first. A listener failure cannot cause a copied refund.
				accepted = writeStore(stockpile, slot, actual);
				if (!accepted) return false;
				removeMarker(actual, stamp);
				notifyChanged(stockpile);
				return true;
			} finally {
				if (!accepted && (!emptyHand(hand) || !writeHand(hand, actual)))
					throw new IllegalStateException("Cannot restore undelivered loan to its empty hand");
			}
		}
		return false;
	}

	private static boolean accepts(Container stockpile, int slot, ItemStack offered) {
		return stockpile.getItem(slot).isEmpty() && stockpile.canPlaceItem(slot, offered)
			&& stockpile.getMaxStackSize(offered) >= 1;
	}

	/** Notification exceptions occur after standard setters commit; inspect that commit, never replay it. */
	private static boolean writeHand(Hand hand, ItemStack stack) {
		try { hand.set(stack); }
		catch (RuntimeException notificationFailure) { /* The actual equipment state is authoritative. */ }
		return stack.isEmpty() ? emptyHand(hand) : hand.get() == stack;
	}

	private static boolean writeStore(Container stockpile, int slot, ItemStack stack) {
		try { stockpile.setItem(slot, stack); }
		catch (RuntimeException notificationFailure) { /* The actual container state is authoritative. */ }
		return stockpile.getItem(slot) == stack;
	}

	private static void notifyChanged(Container stockpile) {
		try { stockpile.setChanged(); }
		catch (RuntimeException notificationFailure) { /* Physical custody has already committed. */ }
	}
}
