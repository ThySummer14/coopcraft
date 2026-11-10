package com.thysummer.coopcraft.civilization;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/** Physical custody for one plain oak log -> four oak planks, never an abstract resource credit. */
public final class SettlementWorkbenchCargo {
	public static final String KEY = "coopcraft_workbench_cargo";
	private static final Set<String> FIELDS = Set.of("Version", "Settlement", "Batch", "Phase");
	private SettlementWorkbenchCargo() { }

	public enum Phase { INPUT, OUTPUT, UNKNOWN }
	private record Stamp(UUID camp, UUID batch, Phase phase) { }

	/** Presence, not validity: future and malformed cargo must never pass ordinary material handling. */
	public static boolean isMarked(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		return custom != null && custom.copyTag().contains(KEY);
	}

	public static ItemStack input(UUID camp, UUID batch) { return create(camp, batch, Phase.INPUT); }
	public static ItemStack output(UUID camp, UUID batch) { return create(camp, batch, Phase.OUTPUT); }

	private static ItemStack create(UUID camp, UUID batch, Phase phase) {
		Objects.requireNonNull(camp); Objects.requireNonNull(batch);
		CompoundTag tag = new CompoundTag();
		tag.putInt("Version", 1); tag.putUUID("Settlement", camp); tag.putUUID("Batch", batch);
		tag.putString("Phase", phase.name());
		CompoundTag root = new CompoundTag(); root.put(KEY, tag);
		ItemStack stack = new ItemStack(phase == Phase.INPUT ? Items.OAK_LOG : Items.OAK_PLANKS,
			phase == Phase.INPUT ? 1 : 4);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		return stack;
	}

	private static Stamp read(ItemStack stack) {
		if (!isMarked(stack)) return null;
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		CompoundTag root = custom.copyTag();
		if (!root.getAllKeys().equals(Set.of(KEY)) || !root.contains(KEY, Tag.TAG_COMPOUND)) return null;
		CompoundTag tag = root.getCompound(KEY);
		if (!tag.getAllKeys().equals(FIELDS) || !tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1
			|| !tag.hasUUID("Settlement") || !tag.hasUUID("Batch") || !tag.contains("Phase", Tag.TAG_STRING)) return null;
		Phase phase = switch (tag.getString("Phase")) {
			case "INPUT" -> Phase.INPUT;
			case "OUTPUT" -> Phase.OUTPUT;
			default -> Phase.UNKNOWN;
		};
		if (phase == Phase.UNKNOWN || (phase == Phase.INPUT
			? !stack.is(Items.OAK_LOG) || stack.getCount() != 1
			: !stack.is(Items.OAK_PLANKS) || stack.getCount() < 1 || stack.getCount() > 4)) return null;
		// Reject unrelated component patches, including explicit removal of an item's default component.
		ItemStack expected = new ItemStack(stack.getItem());
		expected.set(DataComponents.CUSTOM_DATA, custom);
		if (!expected.getComponentsPatch().equals(stack.getComponentsPatch())) return null;
		return new Stamp(tag.getUUID("Settlement"), tag.getUUID("Batch"), phase);
	}

	public static Phase phase(ItemStack stack) {
		Stamp stamp = read(stack);
		return stamp == null ? Phase.UNKNOWN : stamp.phase();
	}

	public static boolean matchesInput(ItemStack stack, UUID camp, UUID batch) {
		Stamp stamp = read(stack);
		return stamp != null && stamp.phase() == Phase.INPUT && stamp.camp().equals(camp) && stamp.batch().equals(batch);
	}

	private static boolean returnable(ItemStack stack, UUID camp) {
		Stamp stamp = read(stack);
		return stamp != null && stamp.camp().equals(camp);
	}

	public static boolean hasReturnable(Container inventory, UUID camp) {
		if (inventory == null || camp == null) return false;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++)
			if (returnable(inventory.getItem(slot), camp)) return true;
		return false;
	}

	/**
	 * Caller has verified real arrival and current membership/store identity. Distinct, non-overlapping,
	 * synchronous, non-reentrant standard containers only. Insert copies, deduct only actual acceptance.
	 * Failed/partial delivery keeps the original marker on every item still in custody.
	 */
	public static int deposit(Container inventory, Container stockpile, UUID camp) {
		if (inventory == null || stockpile == null || inventory == stockpile || camp == null) return 0;
		int moved = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack original = inventory.getItem(slot);
			if (!returnable(original, camp)) continue;
			ItemStack offered = original.copy();
			CompoundTag root = offered.get(DataComponents.CUSTOM_DATA).copyTag();
			root.remove(KEY);
			if (root.isEmpty()) offered.remove(DataComponents.CUSTOM_DATA);
			else offered.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
			int accepted = SettlementStockpile.insert(stockpile, offered);
			if (accepted == 0) continue;
			original.shrink(accepted);
			inventory.setItem(slot, original.isEmpty() ? ItemStack.EMPTY : original);
			moved += accepted;
		}
		if (moved > 0) inventory.setChanged();
		return moved;
	}
}
