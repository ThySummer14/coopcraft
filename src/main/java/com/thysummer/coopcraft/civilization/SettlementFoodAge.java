package com.thysummer.coopcraft.civilization;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.ResourceType;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/** Bounded physical-food identity; this class neither imports stock nor advances a clock. */
public final class SettlementFoodAge {
	private static final String KEY = "coopcraft_food_age";
	private static final Set<String> FIELDS = Set.of("Format", "ClockId", "HarvestDay", "ExpiryDay", "Resource");
	private static final Map<Item, ResourceType> FOOD_TYPES = Map.of(
		Items.SWEET_BERRIES, ResourceType.WILD_FRUIT,
		Items.POTATO, ResourceType.TUBERS,
		Items.COD, ResourceType.FISH,
		Items.BEEF, ResourceType.GAME
	);

	private SettlementFoodAge() {
	}

	public record Stamp(UUID clockId, ResourceType resourceType, long harvestDay, long expiryDay) {
	}

	/** Returns a detached marked copy. Existing customizations and age markers are never replaced. */
	public static ItemStack stamp(ItemStack ordinary, UUID clockId, long harvestDay) {
		if (clockId == null || harvestDay < 0) {
			throw new IllegalArgumentException("Food age requires a clock and a nonnegative harvest day");
		}
		ResourceType type = ordinaryFoodType(ordinary)
			.orElseThrow(() -> new IllegalArgumentException("Only plain supported food can be age-stamped"));
		CompoundTag age = new CompoundTag();
		age.putInt("Format", 1);
		age.putUUID("ClockId", clockId);
		age.putLong("HarvestDay", harvestDay);
		age.putLong("ExpiryDay", expiryDay(type, harvestDay));
		age.putString("Resource", type.name());
		CompoundTag data = new CompoundTag();
		data.put(KEY, age);
		ItemStack result = ordinary.copy();
		result.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
		return result;
	}

	/** Marker presence is independent of validity, so malformed age data cannot become ordinary food. */
	public static boolean isMarked(ItemStack stack) {
		if (stack == null) return false;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().contains(KEY);
	}

	/** Only this exact format and component patch are trusted; unknown data fails closed. */
	public static Optional<Stamp> read(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return Optional.empty();
		ResourceType type = FOOD_TYPES.get(stack.getItem());
		if (type == null) return Optional.empty();
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		if (custom == null) return Optional.empty();
		ItemStack expected = new ItemStack(stack.getItem());
		expected.set(DataComponents.CUSTOM_DATA, custom);
		if (!stack.getComponentsPatch().equals(expected.getComponentsPatch())) return Optional.empty();
		CompoundTag data = custom.copyTag();
		if (!data.getAllKeys().equals(Set.of(KEY)) || !data.contains(KEY, Tag.TAG_COMPOUND)) {
			return Optional.empty();
		}
		CompoundTag age = data.getCompound(KEY);
		if (!age.getAllKeys().equals(FIELDS) || !age.contains("Format", Tag.TAG_INT)
			|| age.getInt("Format") != 1 || !age.hasUUID("ClockId")
			|| !age.contains("HarvestDay", Tag.TAG_LONG) || !age.contains("ExpiryDay", Tag.TAG_LONG)
			|| !age.contains("Resource", Tag.TAG_STRING) || !type.name().equals(age.getString("Resource"))) {
			return Optional.empty();
		}
		long harvestDay = age.getLong("HarvestDay");
		long expiryDay = age.getLong("ExpiryDay");
		if (harvestDay < 0 || expiryDay != expiryDay(type, harvestDay)) return Optional.empty();
		return Optional.of(new Stamp(age.getUUID("ClockId"), type, harvestDay, expiryDay));
	}

	public static Optional<ResourceType> ordinaryFoodType(ItemStack stack) {
		if (stack == null || stack.isEmpty() || !stack.getComponentsPatch().isEmpty()) return Optional.empty();
		return Optional.ofNullable(FOOD_TYPES.get(stack.getItem()));
	}

	private static long expiryDay(ResourceType type, long harvestDay) {
		return harvestDay > Long.MAX_VALUE - type.shelfLifeDays()
			? Long.MAX_VALUE : harvestDay + type.shelfLifeDays();
	}
}
