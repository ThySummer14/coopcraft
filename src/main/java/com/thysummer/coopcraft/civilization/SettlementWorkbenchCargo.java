package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.SettlementMaterialCrafting.Recipe;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Physical custody for supported plain-item workbench batches, never an abstract resource credit. */
public final class SettlementWorkbenchCargo {
	public static final String KEY = "coopcraft_workbench_cargo";
	private static final Set<String> V1_FIELDS = Set.of("Version", "Settlement", "Batch", "Phase");
	private static final Set<String> V2_FIELDS = Set.of("Version", "Settlement", "Batch", "Phase", "Recipe");
	private SettlementWorkbenchCargo() { }

	public enum Phase { INPUT, OUTPUT, UNKNOWN }
	private record Stamp(UUID camp, UUID batch, Phase phase, Recipe recipe) { }

	/** Presence, not validity: future and malformed cargo must never pass ordinary material handling. */
	public static boolean isMarked(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		return custom != null && custom.copyTag().contains(KEY);
	}

	public static boolean supported(Recipe recipe) { return recipe == Recipe.POLISH_PICKAXE || recipe == Recipe.SMOOTH_STONE || recipe == Recipe.PLANKS || recipe == Recipe.STICKS || recipe == Recipe.DOOR || recipe == Recipe.BED || recipe == Recipe.WOOL
		|| recipe == Recipe.STONE_PICKAXE || recipe == Recipe.TORCH_COAL || recipe == Recipe.TORCH_CHARCOAL; }

	public static ItemStack input(UUID camp, UUID batch) { return input(camp, batch, Recipe.PLANKS); }
	public static ItemStack output(UUID camp, UUID batch) { return output(camp, batch, Recipe.PLANKS); }
	/** Single-input compatibility API; multi-input batches must use inputs(). */
	public static ItemStack input(UUID camp, UUID batch, Recipe recipe) {
		validate(camp, batch, recipe);
		if (recipe.ingredients().size() != 1)
			throw new IllegalArgumentException("Multi-input workbench recipe requires inputs(): " + recipe);
		return inputs(camp, batch, recipe).getFirst();
	}

	/** Complete physical inputs for one batch; each stack carries the same batch identity. */
	public static List<ItemStack> inputs(UUID camp, UUID batch, Recipe recipe) {
		validate(camp, batch, recipe);
		return recipe.ingredients().entrySet().stream()
			.map(ingredient -> stamp(camp, batch, Phase.INPUT, recipe,
				new ItemStack(ingredient.getKey(), ingredient.getValue())))
			.toList();
	}

	public static ItemStack output(UUID camp, UUID batch, Recipe recipe) {
		if (recipe == Recipe.POLISH_PICKAXE) throw new IllegalArgumentException("Polishing inputs never produce public tool cargo");
		validate(camp, batch, recipe);
		return stamp(camp, batch, Phase.OUTPUT, recipe, new ItemStack(recipe.output(), recipe.count()));
	}

	private static void validate(UUID camp, UUID batch, Recipe recipe) {
		Objects.requireNonNull(camp); Objects.requireNonNull(batch);
		if (!supported(recipe)) throw new IllegalArgumentException("Unsupported workbench recipe: " + recipe);
	}

	private static ItemStack stamp(UUID camp, UUID batch, Phase phase, Recipe recipe, ItemStack stack) {
		CompoundTag tag = new CompoundTag();
		// Preserve V1 planks and existing V2 recipes. Grinding uses its own strict V3 encoding.
		tag.putInt("Version", recipe == Recipe.POLISH_PICKAXE ? 4 : recipe == Recipe.PLANKS ? 1 : recipe == Recipe.SMOOTH_STONE ? 3 : 2);
		if (recipe != Recipe.PLANKS) tag.putString("Recipe", recipe.name());
		tag.putUUID("Settlement", camp); tag.putUUID("Batch", batch);
		tag.putString("Phase", phase.name());
		CompoundTag root = new CompoundTag(); root.put(KEY, tag);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		return stack;
	}

	private static Stamp read(ItemStack stack) {
		if (!isMarked(stack)) return null;
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		CompoundTag root = custom.copyTag();
		if (!root.getAllKeys().equals(Set.of(KEY)) || !root.contains(KEY, Tag.TAG_COMPOUND)) return null;
		CompoundTag tag = root.getCompound(KEY);
		if (!tag.contains("Version", Tag.TAG_INT)
			|| !tag.hasUUID("Settlement") || !tag.hasUUID("Batch") || !tag.contains("Phase", Tag.TAG_STRING)) return null;
		Recipe recipe;
		if (tag.getInt("Version") == 1) {
			if (!tag.getAllKeys().equals(V1_FIELDS)) return null;
			recipe = Recipe.PLANKS;
		} else if (tag.getInt("Version") == 2) {
			if (!tag.getAllKeys().equals(V2_FIELDS) || !tag.contains("Recipe", Tag.TAG_STRING)) return null;
			recipe = switch (tag.getString("Recipe")) {
				case "STONE_PICKAXE" -> Recipe.STONE_PICKAXE;
				case "STICKS" -> Recipe.STICKS;
				case "PLANKS" -> Recipe.PLANKS;
				case "DOOR" -> Recipe.DOOR;
				case "BED" -> Recipe.BED;
				case "WOOL" -> Recipe.WOOL;
				case "TORCH_COAL" -> Recipe.TORCH_COAL;
				case "TORCH_CHARCOAL" -> Recipe.TORCH_CHARCOAL;
				default -> null;
			};
			if (recipe == null) return null;
		} else if (tag.getInt("Version") == 3) {
			// A dedicated encoding must not reinterpret formerly unknown V2 recipe cargo.
			if (!tag.getAllKeys().equals(V2_FIELDS) || !tag.contains("Recipe", Tag.TAG_STRING)
				|| !tag.getString("Recipe").equals("SMOOTH_STONE")) return null;
			recipe = Recipe.SMOOTH_STONE;
		} else if (tag.getInt("Version") == 4) {
			// Input-only purpose; V2/V3 strings with this name remain quarantined.
			if (!tag.getAllKeys().equals(V2_FIELDS) || !tag.contains("Recipe", Tag.TAG_STRING)
				|| !tag.getString("Recipe").equals("POLISH_PICKAXE")
				|| !tag.getString("Phase").equals("INPUT")) return null;
			recipe = Recipe.POLISH_PICKAXE;
		} else return null;
		Phase phase = switch (tag.getString("Phase")) {
			case "INPUT" -> Phase.INPUT;
			case "OUTPUT" -> Phase.OUTPUT;
			default -> Phase.UNKNOWN;
		};
		int inputLimit = recipe.ingredients().getOrDefault(stack.getItem(), 0);
		if (phase == Phase.UNKNOWN || stack.getCount() < 1 || (phase == Phase.INPUT
			? stack.getCount() > inputLimit
			: !stack.is(recipe.output()) || stack.getCount() > recipe.count())) return null;
		// Reject unrelated component patches, including explicit removal of an item's default component.
		ItemStack expected = new ItemStack(stack.getItem());
		expected.set(DataComponents.CUSTOM_DATA, custom);
		if (!expected.getComponentsPatch().equals(stack.getComponentsPatch())) return null;
		return new Stamp(tag.getUUID("Settlement"), tag.getUUID("Batch"), phase, recipe);
	}

	public static Phase phase(ItemStack stack) {
		Stamp stamp = read(stack);
		return stamp == null ? Phase.UNKNOWN : stamp.phase();
	}

	public static Recipe recipe(ItemStack stack) {
		Stamp stamp = read(stack);
		return stamp == null ? null : stamp.recipe();
	}

	public static boolean matchesInput(ItemStack stack, UUID camp, UUID batch) {
		return matchesInput(stack, camp, batch, Recipe.PLANKS);
	}

	/** Matches identity and valid partial custody; the caller must enforce the full batch total. */
	public static boolean matchesInput(ItemStack stack, UUID camp, UUID batch, Recipe recipe) {
		Stamp stamp = read(stack);
		return stamp != null && stamp.phase() == Phase.INPUT && stamp.recipe() == recipe
			&& stamp.camp().equals(camp) && stamp.batch().equals(batch);
	}

	/** Exact output identity; malformed or component-rich tools remain quarantined. */
	public static boolean matchesOutput(ItemStack stack, UUID camp, UUID batch, Recipe recipe) {
		Stamp stamp = read(stack);
		return stamp != null && stamp.phase() == Phase.OUTPUT && stamp.recipe() == recipe
			&& stamp.camp().equals(camp) && stamp.batch().equals(batch);
	}

	/** Detached preview only. Never removes a marker from an unrecognized/custom output. */
	static ItemStack unmarkOutput(ItemStack stack) {
		Stamp stamp = read(stack);
		if (stamp == null || stamp.phase() != Phase.OUTPUT) return ItemStack.EMPTY;
		ItemStack plain = stack.copy();
		// read() requires the cargo marker to be the sole custom-data field.
		plain.remove(DataComponents.CUSTOM_DATA);
		return plain;
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
