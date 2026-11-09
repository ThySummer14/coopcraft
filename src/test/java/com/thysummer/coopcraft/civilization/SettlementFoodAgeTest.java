package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.thysummer.coopcraft.civilization.simulation.ResourceType;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Real ItemStack components and NBT only; no world, loot, navigation, or gathering is exercised. */
class SettlementFoodAgeTest {
	private static final String KEY = "coopcraft_food_age";
	private static final UUID CLOCK = new UUID(428, 1);
	private static HolderLookup.Provider registries;

	@BeforeAll static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		registries = VanillaRegistries.createLookup();
	}

	private static Map<Item, ResourceType> foods() {
		return Map.of(Items.SWEET_BERRIES, ResourceType.WILD_FRUIT, Items.POTATO, ResourceType.TUBERS,
			Items.COD, ResourceType.FISH, Items.BEEF, ResourceType.GAME);
	}

	@Test void allFourFoodsReceiveTheExactBoundedFormatWithoutChangingTheSource() {
		for (var entry : foods().entrySet()) {
			ItemStack source = new ItemStack(entry.getKey(), 17);
			Tag before = source.save(registries);
			ItemStack result = SettlementFoodAge.stamp(source, CLOCK, 7);
			assertNotSame(source, result);
			assertEquals(entry.getKey(), result.getItem());
			assertEquals(17, result.getCount());
			assertEquals(entry.getValue(), SettlementFoodAge.ordinaryFoodType(source).orElseThrow());
			assertTrue(SettlementFoodAge.ordinaryFoodType(result).isEmpty());
			assertFalse(SettlementFoodAge.isMarked(source));
			assertTrue(SettlementFoodAge.isMarked(result));
			assertEquals(new SettlementFoodAge.Stamp(CLOCK, entry.getValue(), 7,
				7 + entry.getValue().shelfLifeDays()), SettlementFoodAge.read(result).orElseThrow());
			CompoundTag data = result.get(DataComponents.CUSTOM_DATA).copyTag();
			assertEquals(Set.of(KEY), data.getAllKeys());
			CompoundTag age = data.getCompound(KEY);
			assertEquals(Set.of("Format", "ClockId", "HarvestDay", "ExpiryDay", "Resource"), age.getAllKeys());
			assertTrue(age.contains("Format", Tag.TAG_INT));
			assertEquals(1, age.getInt("Format"));
			assertTrue(age.hasUUID("ClockId"));
			assertTrue(age.contains("HarvestDay", Tag.TAG_LONG));
			assertTrue(age.contains("ExpiryDay", Tag.TAG_LONG));
			assertEquals(entry.getValue().name(), age.getString("Resource"));
			result.shrink(5);
			result.set(DataComponents.CUSTOM_NAME, Component.literal("Changed copy"));
			assertEquals(before, source.save(registries));
			assertTrue(source.getComponentsPatch().isEmpty());
		}
	}

	@Test void matchingAgesAndClocksCanStackButDifferentIdentitiesCannot() {
		for (Item item : foods().keySet()) {
			ItemStack first = SettlementFoodAge.stamp(new ItemStack(item, 3), CLOCK, 7);
			ItemStack same = SettlementFoodAge.stamp(new ItemStack(item, 9), CLOCK, 7);
			ItemStack later = SettlementFoodAge.stamp(new ItemStack(item, 3), CLOCK, 8);
			ItemStack elsewhere = SettlementFoodAge.stamp(new ItemStack(item, 3), new UUID(428, 2), 7);
			assertTrue(ItemStack.isSameItemSameComponents(first, same));
			assertEquals(first.getComponentsPatch(), same.getComponentsPatch());
			assertFalse(ItemStack.isSameItemSameComponents(first, later));
			assertFalse(ItemStack.isSameItemSameComponents(first, elsewhere));
			assertFalse(ItemStack.isSameItemSameComponents(first, new ItemStack(item)));
		}
	}

	@Test void itemStackNbtRoundTripsKeepCountClockAndExactDatesForEveryFood() {
		for (var entry : foods().entrySet()) {
			ItemStack source = SettlementFoodAge.stamp(new ItemStack(entry.getKey(), 23), CLOCK, 11);
			ItemStack restored = ItemStack.parse(registries, source.save(registries)).orElseThrow();
			assertEquals(23, restored.getCount());
			assertTrue(ItemStack.isSameItemSameComponents(source, restored));
			assertEquals(source.getComponentsPatch(), restored.getComponentsPatch());
			assertEquals(SettlementFoodAge.read(source), SettlementFoodAge.read(restored));
			assertEquals(source.save(registries), restored.save(registries));
		}
	}

	@Test void harvestDatesRemainExactLongsAndExpirySaturatesWithoutWrapping() {
		for (var entry : foods().entrySet()) {
			long shelfLife = entry.getValue().shelfLifeDays();
			for (long day : List.of(0L, 9_007_199_254_740_993L, Long.MAX_VALUE - shelfLife,
				Long.MAX_VALUE - shelfLife + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE)) {
				ItemStack source = SettlementFoodAge.stamp(new ItemStack(entry.getKey()), CLOCK, day);
				ItemStack restored = ItemStack.parse(registries, source.save(registries)).orElseThrow();
				var stamp = SettlementFoodAge.read(restored).orElseThrow();
				assertEquals(day, stamp.harvestDay());
				assertEquals(day > Long.MAX_VALUE - shelfLife ? Long.MAX_VALUE : day + shelfLife, stamp.expiryDay());
				assertTrue(stamp.expiryDay() >= day);
			}
		}
	}

	@Test void invalidDayOrMissingClockCannotMutateAnOrdinaryStack() {
		ItemStack source = new ItemStack(Items.POTATO, 6);
		Tag before = source.save(registries);
		assertThrows(IllegalArgumentException.class, () -> SettlementFoodAge.stamp(source, CLOCK, -1));
		assertThrows(IllegalArgumentException.class, () -> SettlementFoodAge.stamp(source, CLOCK, Long.MIN_VALUE));
		assertThrows(IllegalArgumentException.class, () -> SettlementFoodAge.stamp(source, null, 0));
		assertEquals(before, source.save(registries));
	}

	@Test void emptyUnsupportedModifiedAndAlreadyMarkedInputsCannotBeStampedOrRefreshed() {
		ItemStack named = new ItemStack(Items.POTATO, 6);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep this"));
		ItemStack custom = new ItemStack(Items.COD, 4);
		CompoundTag unrelated = new CompoundTag(); unrelated.putString("Owner", "Resident");
		custom.set(DataComponents.CUSTOM_DATA, CustomData.of(unrelated));
		ItemStack removedComponent = new ItemStack(Items.BEEF, 3);
		removedComponent.remove(DataComponents.FOOD);
		ItemStack emptyCustom = new ItemStack(Items.SWEET_BERRIES, 2);
		emptyCustom.set(DataComponents.CUSTOM_DATA, CustomData.of(new CompoundTag()));
		ItemStack valid = berries();
		ItemStack malformed = withAge(age -> age.putInt("Format", 2));
		for (ItemStack stack : List.of(new ItemStack(Items.BREAD), new ItemStack(Items.OAK_LOG),
			new ItemStack(Items.SALMON), new ItemStack(Items.COOKED_BEEF), named, custom, removedComponent,
			emptyCustom, valid, malformed)) {
			Tag before = stack.save(registries);
			assertTrue(SettlementFoodAge.ordinaryFoodType(stack).isEmpty());
			assertThrows(IllegalArgumentException.class, () -> SettlementFoodAge.stamp(stack, CLOCK, 12));
			assertEquals(before, stack.save(registries));
		}
		for (ItemStack stack : List.of(ItemStack.EMPTY, new ItemStack(Items.COD, 0))) {
			assertTrue(SettlementFoodAge.ordinaryFoodType(stack).isEmpty());
			assertTrue(SettlementFoodAge.read(stack).isEmpty());
			assertThrows(IllegalArgumentException.class, () -> SettlementFoodAge.stamp(stack, CLOCK, 0));
		}
		assertTrue(SettlementFoodAge.ordinaryFoodType(null).isEmpty());
		assertTrue(SettlementFoodAge.read(null).isEmpty());
		assertFalse(SettlementFoodAge.isMarked(null));
		assertThrows(IllegalArgumentException.class, () -> SettlementFoodAge.stamp(null, CLOCK, 0));
	}

	@Test void everyRequiredFieldMustExistAndHaveTheExactTypeAndValue() {
		for (String field : List.of("Format", "ClockId", "HarvestDay", "ExpiryDay", "Resource")) {
			assertInvalid(withAge(age -> age.remove(field)));
		}
		List<Consumer<CompoundTag>> mutations = List.of(
			age -> age.putInt("Format", 0), age -> age.putInt("Format", 2),
			age -> age.putInt("Format", -1), age -> age.putLong("Format", 1),
			age -> age.putByte("Format", (byte) 1), age -> age.putString("Format", "1"),
			age -> age.putString("ClockId", CLOCK.toString()), age -> age.putIntArray("ClockId", new int[0]),
			age -> age.putIntArray("ClockId", new int[3]), age -> age.putIntArray("ClockId", new int[5]),
			age -> age.putLongArray("ClockId", new long[2]), age -> age.put("ClockId", new CompoundTag()),
			age -> age.putInt("HarvestDay", 7), age -> age.putFloat("HarvestDay", 7),
			age -> age.putDouble("HarvestDay", 7), age -> age.putString("HarvestDay", "7"),
			age -> age.putLong("HarvestDay", -1), age -> age.putLong("HarvestDay", Long.MIN_VALUE),
			age -> age.putInt("ExpiryDay", 10), age -> age.putDouble("ExpiryDay", 10),
			age -> age.putString("ExpiryDay", "10"), age -> age.putLong("ExpiryDay", 9),
			age -> age.putLong("ExpiryDay", 11), age -> age.putLong("ExpiryDay", -1),
			age -> age.putLong("ExpiryDay", Long.MAX_VALUE), age -> age.putLong("ExpiryDay", Long.MIN_VALUE),
			age -> age.putString("Resource", "FISH"), age -> age.putString("Resource", "WOOD"),
			age -> age.putString("Resource", "wild_fruit"), age -> age.putString("Resource", "FUTURE_FOOD"),
			age -> age.putInt("Resource", 1), age -> age.putString("UnknownField", "future"));
		for (Consumer<CompoundTag> mutation : mutations) assertInvalid(withAge(mutation));
	}

	@Test void markerPresenceIsNotValidationAndExtraTopLevelDataIsNotTrusted() {
		ItemStack malformed = new ItemStack(Items.SWEET_BERRIES);
		CompoundTag data = new CompoundTag(); data.putString(KEY, "not an age compound");
		malformed.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
		assertInvalid(malformed);
		data.put(KEY, new CompoundTag());
		malformed.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
		assertInvalid(malformed);
		ItemStack extra = berries();
		data = extra.get(DataComponents.CUSTOM_DATA).copyTag(); data.putString("OtherData", "not ours");
		extra.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
		assertInvalid(extra);
		ItemStack wrongItem = new ItemStack(Items.BREAD);
		wrongItem.set(DataComponents.CUSTOM_DATA, berries().get(DataComponents.CUSTOM_DATA));
		assertInvalid(wrongItem);
		ItemStack wrongFood = new ItemStack(Items.COD);
		wrongFood.set(DataComponents.CUSTOM_DATA, berries().get(DataComponents.CUSTOM_DATA));
		assertInvalid(wrongFood);
		ItemStack ordinary = new ItemStack(Items.SWEET_BERRIES);
		assertFalse(SettlementFoodAge.isMarked(ordinary));
		assertTrue(SettlementFoodAge.read(ordinary).isEmpty());
	}

	@Test void otherwiseValidAgeWithAddedOrRemovedComponentsFailsClosedWithoutMutation() {
		List<Consumer<ItemStack>> modifications = List.of(
			stack -> stack.set(DataComponents.CUSTOM_NAME, Component.literal("Named")),
			stack -> stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Lore")))),
			stack -> stack.set(DataComponents.REPAIR_COST, 7),
			stack -> stack.remove(DataComponents.FOOD),
			stack -> stack.remove(DataComponents.MAX_STACK_SIZE));
		for (Consumer<ItemStack> modify : modifications) {
			ItemStack stack = berries(); modify.accept(stack); assertInvalid(stack);
		}
	}

	private static ItemStack berries() {
		return SettlementFoodAge.stamp(new ItemStack(Items.SWEET_BERRIES, 5), CLOCK, 7);
	}

	private static ItemStack withAge(Consumer<CompoundTag> mutate) {
		ItemStack stack = berries();
		CompoundTag data = stack.get(DataComponents.CUSTOM_DATA).copyTag();
		mutate.accept(data.getCompound(KEY));
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
		return stack;
	}

	private static void assertInvalid(ItemStack stack) {
		Tag before = stack.save(registries);
		assertTrue(SettlementFoodAge.isMarked(stack));
		assertTrue(SettlementFoodAge.read(stack).isEmpty(), before::toString);
		assertTrue(SettlementFoodAge.ordinaryFoodType(stack).isEmpty());
		assertEquals(before, stack.save(registries));
	}
}
