package com.thysummer.coopcraft.civilization;

import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * One physical paid construction item, held in the resident's real inventory.
 * Callers gate arrival, living membership, stockpile binding and current plan/job identity.
 * Containers must be distinct, non-overlapping standard synchronous containers. Placement may
 * change the world, but must not mutate these containers. No arbitrary reentrancy is supported.
 */
public final class ConstructionSupply {
	public static final String KEY = "coopcraft_construction_supply";
	private static final Set<String> FIELDS = Set.of("Version", "Dimension", "Settlement", "Actor", "Job", "Index", "Plan", "Item");
	private ConstructionSupply() { }

	public static boolean supported(Item item) { return item == Items.OAK_DOOR || item == Items.WHITE_BED; }

	/** Even unknown versions and malformed markers remain quarantined. */
	public static boolean isMarked(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		return custom != null && custom.copyTag().contains(KEY);
	}

	public static boolean hasAny(Container bag) {
		if (bag == null) return false;
		for (int slot = 0; slot < bag.getContainerSize(); slot++) if (isMarked(bag.getItem(slot))) return true;
		return false;
	}

	private record Stamp(ResourceLocation dimension, UUID camp, UUID actor, UUID job, int index, String plan) { }

	private static Stamp read(ItemStack stack) {
		if (stack == null || stack.isEmpty() || stack.getCount() != 1 || !supported(stack.getItem())) return null;
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		if (custom == null) return null;
		// Nothing beyond this protocol may be discarded when returning the ordinary item.
		ItemStack expected = new ItemStack(stack.getItem());
		expected.set(DataComponents.CUSTOM_DATA, custom);
		if (!expected.getComponentsPatch().equals(stack.getComponentsPatch())) return null;
		CompoundTag root = custom.copyTag();
		if (!root.getAllKeys().equals(Set.of(KEY)) || !root.contains(KEY, Tag.TAG_COMPOUND)) return null;
		CompoundTag tag = root.getCompound(KEY);
		if (!tag.getAllKeys().equals(FIELDS)
			|| !tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1
			|| !tag.contains("Dimension", Tag.TAG_STRING)
			|| !tag.hasUUID("Settlement") || !tag.hasUUID("Actor") || !tag.hasUUID("Job")
			|| !tag.contains("Index", Tag.TAG_INT) || tag.getInt("Index") < 0
			|| !tag.contains("Plan", Tag.TAG_STRING) || tag.getString("Plan").isEmpty()
			|| !tag.contains("Item", Tag.TAG_STRING)
			|| !BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(tag.getString("Item"))) return null;
		ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
		if (dimension == null || !dimension.toString().equals(tag.getString("Dimension"))) return null;
		return new Stamp(dimension, tag.getUUID("Settlement"), tag.getUUID("Actor"), tag.getUUID("Job"),
			tag.getInt("Index"), tag.getString("Plan"));
	}

	private static boolean origin(Stamp stamp, ResourceLocation dimension, UUID camp, UUID actor) {
		return stamp != null && stamp.dimension.equals(dimension) && stamp.camp.equals(camp) && stamp.actor.equals(actor);
	}

	private static boolean current(Stamp stamp, ConstructionJob job) {
		return job != null && job.hasValidJobId() && stamp.job.equals(job.jobId())
			&& stamp.index == job.nextIndex() && stamp.plan.equals(job.planFingerprint());
	}

	private static boolean paid(ConstructionJob job, ConstructionInstruction instruction) {
		return job != null && job.hasValidJobId() && !job.planFingerprint().isEmpty()
			&& instruction != null && instruction.consume() && supported(instruction.item());
	}

	public static boolean matches(ItemStack stack, ResourceLocation dimension, UUID camp, UUID actor,
		ConstructionJob job, ConstructionInstruction instruction) {
		Stamp stamp = read(stack);
		return paid(job, instruction) && origin(stamp, dimension, camp, actor) && current(stamp, job)
			&& stack.is(instruction.item());
	}

	public static boolean hasFor(Container bag, ResourceLocation dimension, UUID camp, UUID actor,
		ConstructionJob job, ConstructionInstruction instruction) {
		if (bag == null) return false;
		for (int slot = 0; slot < bag.getContainerSize(); slot++)
			if (matches(bag.getItem(slot), dimension, camp, actor, job, instruction)) return true;
		return false;
	}

	private static CustomData mark(Item item, ResourceLocation dimension, UUID camp, UUID actor, ConstructionJob job) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Version", 1); tag.putString("Dimension", dimension.toString());
		tag.putUUID("Settlement", camp); tag.putUUID("Actor", actor); tag.putUUID("Job", job.jobId());
		tag.putInt("Index", job.nextIndex()); tag.putString("Plan", job.planFingerprint());
		tag.putString("Item", BuiltInRegistries.ITEM.getKey(item).toString());
		CompoundTag root = new CompoundTag(); root.put(KEY, tag);
		return CustomData.of(root);
	}

	public static boolean ordinary(ItemStack stack) {
		return stack != null && !stack.isEmpty() && supported(stack.getItem()) && stack.getComponentsPatch().isEmpty();
	}

	public static int countAvailable(Container store, Item item) {
		if (store == null || !supported(item)) return 0;
		int count = 0;
		for (int slot = 0; slot < store.getContainerSize(); slot++) {
			ItemStack stack = store.getItem(slot);
			if (ordinary(stack) && stack.is(item)) count = (int) Math.min(Integer.MAX_VALUE, (long) count + stack.getCount());
		}
		return count;
	}

	private static boolean ordinary(ItemStack stack, Item item) {
		return !stack.isEmpty() && stack.is(item) && stack.getComponentsPatch().isEmpty();
	}

	/** Only an arrival-authorized pickup splits one actual source item into an empty slot. */
	public static boolean pickup(Container store, Container bag, ResourceLocation dimension, UUID camp, UUID actor,
		ConstructionJob job, ConstructionInstruction instruction, BooleanSupplier authorized) {
		if (store == null || bag == null || store == bag || dimension == null || camp == null || actor == null
			|| !paid(job, instruction) || authorized == null || !authorized.getAsBoolean() || hasAny(bag)) return false;
		ItemStack preview = new ItemStack(instruction.item());
		CustomData marker = mark(instruction.item(), dimension, camp, actor, job);
		preview.set(DataComponents.CUSTOM_DATA, marker);
		for (int target = 0; target < bag.getContainerSize(); target++) {
			if (!bag.getItem(target).isEmpty() || !bag.canPlaceItem(target, preview) || bag.getMaxStackSize(preview) < 1) continue;
			for (int source = 0; source < store.getContainerSize(); source++) {
				ItemStack original = store.getItem(source);
				if (!ordinary(original, instruction.item())) continue;
				int count = original.getCount();
				if (!authorized.getAsBoolean()) return false;
				if (hasAny(bag) || !bag.getItem(target).isEmpty() || !bag.canPlaceItem(target, preview)
					|| bag.getMaxStackSize(preview) < 1 || store.getItem(source) != original
					|| original.getCount() != count || !ordinary(original, instruction.item())) return false;
				ItemStack moved = store.removeItem(source, 1);
				moved.set(DataComponents.CUSTOM_DATA, marker);
				bag.setItem(target, moved); store.setChanged(); bag.setChanged();
				return true;
			}
		}
		return false;
	}

	/** Reserve the exact real item before world placement; failure restores that same item. */
	public static boolean place(Container bag, ResourceLocation dimension, UUID camp, UUID actor,
		ConstructionJob job, ConstructionInstruction instruction, BooleanSupplier authorized, BooleanSupplier placement) {
		if (bag == null || authorized == null || placement == null || !authorized.getAsBoolean()) return false;
		for (int slot = 0; slot < bag.getContainerSize(); slot++) {
			ItemStack original = bag.getItem(slot);
			if (!matches(original, dimension, camp, actor, job, instruction)) continue;
			if (!authorized.getAsBoolean() || bag.getItem(slot) != original
				|| !matches(original, dimension, camp, actor, job, instruction)) return false;
			ItemStack reserved = bag.removeItem(slot, 1);
			boolean placed = false;
			try {
				placed = placement.getAsBoolean();
				return placed;
			} finally {
				if (!placed) bag.setItem(slot, reserved);
				bag.setChanged();
			}
		}
		return false;
	}

	private static boolean returnable(ItemStack stack, ResourceLocation dimension, UUID camp, UUID actor, ConstructionJob job) {
		Stamp stamp = read(stack);
		return origin(stamp, dimension, camp, actor) && !current(stamp, job);
	}

	public static boolean hasReturnable(Container bag, ResourceLocation dimension, UUID camp, UUID actor, ConstructionJob currentJob) {
		if (bag == null) return false;
		for (int slot = 0; slot < bag.getContainerSize(); slot++)
			if (returnable(bag.getItem(slot), dimension, camp, actor, currentJob)) return true;
		return false;
	}

	private static boolean accepts(Container store, int slot, ItemStack plain) {
		ItemStack existing = store.getItem(slot);
		return store.canPlaceItem(slot, plain) && (existing.isEmpty()
			? store.getMaxStackSize(plain) >= 1
			: ordinary(existing, plain.getItem()) && existing.getCount() < Math.min(existing.getMaxStackSize(), store.getMaxStackSize(existing)));
	}

	/** Return only validated, same-origin obsolete cargo. Unknown metadata is never stripped. */
	public static int returnUnused(Container bag, Container store, ResourceLocation dimension, UUID camp, UUID actor,
		ConstructionJob currentJob, BooleanSupplier authorized) {
		if (bag == null || store == null || bag == store || authorized == null || !authorized.getAsBoolean()) return 0;
		int moved = 0;
		for (int source = 0; source < bag.getContainerSize(); source++) {
			ItemStack original = bag.getItem(source);
			if (!returnable(original, dimension, camp, actor, currentJob)) continue;
			ItemStack plain = new ItemStack(original.getItem());
			for (int target = 0; target < store.getContainerSize(); target++) {
				if (!accepts(store, target, plain)) continue;
				if (!authorized.getAsBoolean()) return moved;
				if (bag.getItem(source) != original || !returnable(original, dimension, camp, actor, currentJob)
					|| !accepts(store, target, plain)) break;
				ItemStack returned = bag.removeItem(source, 1);
				returned.remove(DataComponents.CUSTOM_DATA);
				ItemStack existing = store.getItem(target);
				if (existing.isEmpty()) store.setItem(target, returned);
				else existing.grow(1);
				bag.setChanged(); store.setChanged(); moved++;
				break;
			}
		}
		return moved;
	}
}
