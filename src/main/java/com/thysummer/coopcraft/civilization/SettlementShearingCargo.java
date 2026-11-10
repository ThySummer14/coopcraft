package com.thysummer.coopcraft.civilization;

import java.util.Set;
import java.util.UUID;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/** Physical custody of actual shearing drops, separate from recipes and abstract resources. */
public final class SettlementShearingCargo {
	public static final String KEY = "coopcraft_shearing_cargo";
	private static final Set<String> V1_FIELDS = Set.of("Version", "Settlement", "Batch", "Sheep", "Permit");
	private SettlementShearingCargo() { }

	private record Stamp(UUID camp, UUID batch, UUID sheep, UUID permit) { }

	/** Presence, not validity: malformed and future markers must remain outside ordinary item handling. */
	public static boolean isMarked(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		return custom != null && custom.copyTag().contains(KEY);
	}

	/**
	 * Marks a detached copy of an actual drop, preserving its count. This does not create production or
	 * debit the drop: the capture caller may deduct only what was successfully inserted into custody.
	 */
	public static ItemStack mark(ItemStack actualWhiteWool, UUID camp, UUID batch, UUID sheep, UUID permit) {
		if (!whiteWool(actualWhiteWool) || !actualWhiteWool.getComponentsPatch().isEmpty()
			|| camp == null || batch == null || sheep == null || permit == null)
			throw new IllegalArgumentException("Shearing cargo requires actual plain white wool and complete custody identity");
		CompoundTag stamp = new CompoundTag();
		stamp.putInt("Version", 1);
		stamp.putUUID("Settlement", camp); stamp.putUUID("Batch", batch);
		stamp.putUUID("Sheep", sheep); stamp.putUUID("Permit", permit);
		CompoundTag root = new CompoundTag(); root.put(KEY, stamp);
		ItemStack marked = actualWhiteWool.copy();
		marked.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		return marked;
	}

	private static boolean whiteWool(ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.is(Items.WHITE_WOOL)
			&& stack.getCount() >= 1 && stack.getCount() <= 64;
	}

	private static Stamp read(ItemStack stack) {
		if (!whiteWool(stack) || !isMarked(stack)) return null;
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		CompoundTag root = custom.copyTag();
		if (!root.getAllKeys().equals(Set.of(KEY)) || !root.contains(KEY, Tag.TAG_COMPOUND)) return null;
		CompoundTag stamp = root.getCompound(KEY);
		if (!stamp.getAllKeys().equals(V1_FIELDS) || !stamp.contains("Version", Tag.TAG_INT)
			|| stamp.getInt("Version") != 1 || !stamp.hasUUID("Settlement") || !stamp.hasUUID("Batch")
			|| !stamp.hasUUID("Sheep") || !stamp.hasUUID("Permit")) return null;
		// After removing only our marker, even an explicit removal of a default must fail closed.
		ItemStack ordinary = stack.copy();
		ordinary.remove(DataComponents.CUSTOM_DATA);
		if (!ordinary.getComponentsPatch().isEmpty()) return null;
		return new Stamp(stamp.getUUID("Settlement"), stamp.getUUID("Batch"), stamp.getUUID("Sheep"),
			stamp.getUUID("Permit"));
	}

	private static boolean returnable(ItemStack stack, UUID camp) {
		Stamp stamp = read(stack);
		return stamp != null && stamp.camp().equals(camp);
	}

	/** Real custody outlives a permit, sheep, job or chunk; none of them is consulted during return. */
	public static boolean hasReturnable(Container inventory, UUID camp) {
		if (inventory == null || camp == null) return false;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++)
			if (returnable(inventory.getItem(slot), camp)) return true;
		return false;
	}

	/**
	 * Caller must verify actual arrival and current member/store identity. Containers must be distinct,
	 * non-overlapping and synchronous/non-reentrant. Only accepted copies lose the custody marker;
	 * every remainder retains its full identity and actual remaining count, including across saves.
	 */
	public static int deposit(Container inventory, Container stockpile, UUID camp) {
		if (inventory == null || stockpile == null || inventory == stockpile || camp == null) return 0;
		int moved = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack original = inventory.getItem(slot);
			if (!returnable(original, camp)) continue;
			ItemStack offered = original.copy();
			// Strict V1 has no other root keys or component customizations to erase.
			offered.remove(DataComponents.CUSTOM_DATA);
			int accepted = SettlementStockpile.insert(stockpile, offered);
			if (accepted <= 0) continue;
			original.shrink(accepted);
			inventory.setItem(slot, original.isEmpty() ? ItemStack.EMPTY : original);
			moved += accepted;
		}
		if (moved > 0) inventory.setChanged();
		return moved;
	}
}
