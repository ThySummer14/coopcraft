package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Real components and read-only inventory views; these tests do not apply mixins or run a world. */
class SettlementVanillaFoodGuardTest {
	private static final String KEY = "coopcraft_food_age";
	private static final UUID CLOCK = new UUID(430, 1);
	private static HolderLookup.Provider registries;

	@BeforeAll static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		registries = VanillaRegistries.createLookup();
	}

	@Test void allFourFoodsAreQuarantinedWithoutReadingOrAdvancingAnyClock() {
		for (Item item : List.of(Items.SWEET_BERRIES, Items.POTATO, Items.COD, Items.BEEF)) {
			for (long day : List.of(0L, 7L, Long.MAX_VALUE)) {
				ItemStack marked = SettlementFoodAge.stamp(new ItemStack(item, 17), CLOCK, day);
				ReadOnlyContainer inventory = new ReadOnlyContainer(marked);
				Tag before = marked.save(registries);
				assertTrue(SettlementVanillaFoodGuard.containsMarked(inventory));
				assertSame(ItemStack.EMPTY, SettlementVanillaFoodGuard.usableFoodView(inventory, 0));
				assertEquals(0, SettlementVanillaFoodGuard.foodPoints(inventory, Map.of(item, 3)));
				assertSame(marked, inventory.getItem(0));
				assertEquals(before, marked.save(registries));
			}
		}
	}

	@Test void unknownCorruptAndUnsupportedMarkersNeverFallBackToOrdinaryFood() {
		List<ItemStack> marked = new ArrayList<>();
		marked.add(withAge(age -> age.putInt("Format", 999)));
		marked.add(withAge(age -> age.remove("ClockId")));
		marked.add(withAge(age -> age.putString("HarvestDay", "yesterday")));
		marked.add(withAge(age -> age.putLong("ExpiryDay", -1)));
		marked.add(withAge(age -> age.putString("Extra", "unknown")));
		CompoundTag malformed = new CompoundTag();
		malformed.putString(KEY, "not a compound");
		ItemStack wrongShape = new ItemStack(Items.POTATO, 8);
		wrongShape.set(DataComponents.CUSTOM_DATA, CustomData.of(malformed));
		marked.add(wrongShape);
		ItemStack unsupported = new ItemStack(Items.WHEAT, 12);
		unsupported.set(DataComponents.CUSTOM_DATA, withAge(age -> { }).get(DataComponents.CUSTOM_DATA));
		marked.add(unsupported);
		for (ItemStack stack : marked) {
			assertTrue(SettlementFoodAge.isMarked(stack));
			assertTrue(SettlementFoodAge.read(stack).isEmpty());
			ReadOnlyContainer inventory = new ReadOnlyContainer(stack);
			Tag before = stack.save(registries);
			assertTrue(SettlementVanillaFoodGuard.containsMarked(inventory));
			assertSame(ItemStack.EMPTY, SettlementVanillaFoodGuard.usableFoodView(inventory, 0));
			assertEquals(0, SettlementVanillaFoodGuard.foodPoints(inventory, Map.of(stack.getItem(), 1)));
			assertEquals(before, stack.save(registries));
		}
	}

	@Test void copiedAndNbtRestoredMarkersRemainQuarantinedWithExactMetadata() {
		ItemStack original = SettlementFoodAge.stamp(new ItemStack(Items.POTATO, 9), CLOCK, 7);
		ItemStack copied = original.copy();
		ItemStack restored = ItemStack.parse(registries, original.save(registries)).orElseThrow();
		ReadOnlyContainer inventory = new ReadOnlyContainer(original, copied, restored);
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			assertSame(ItemStack.EMPTY, SettlementVanillaFoodGuard.usableFoodView(inventory, slot));
			assertEquals(original.save(registries), inventory.getItem(slot).save(registries));
		}
		assertEquals(0, SettlementVanillaFoodGuard.foodPoints(inventory, Villager.FOOD_POINTS));
	}

	@Test void ordinaryAndCustomizedStacksKeepTheirReferencesAndVanillaValues() {
		ItemStack named = new ItemStack(Items.POTATO, 5);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep this name"));
		CompoundTag unrelated = new CompoundTag();
		unrelated.putString("OtherMod", "keep this data");
		ItemStack custom = new ItemStack(Items.CARROT, 6);
		custom.set(DataComponents.CUSTOM_DATA, CustomData.of(unrelated));
		ItemStack emptyCustom = new ItemStack(Items.POTATO, 4);
		emptyCustom.set(DataComponents.CUSTOM_DATA, CustomData.of(new CompoundTag()));
		ItemStack[] stacks = {new ItemStack(Items.BREAD, 2), new ItemStack(Items.POTATO, 3),
			new ItemStack(Items.SWEET_BERRIES, 7), named, custom, emptyCustom, ItemStack.EMPTY};
		ReadOnlyContainer inventory = new ReadOnlyContainer(stacks);
		assertFalse(SettlementVanillaFoodGuard.containsMarked(inventory));
		for (int slot = 0; slot < stacks.length; slot++) {
			assertSame(stacks[slot], SettlementVanillaFoodGuard.usableFoodView(inventory, slot));
		}
		// Vanilla counts bread at four and potatoes/carrots at one, while berries add no breeding points.
		assertEquals(26, SettlementVanillaFoodGuard.foodPoints(inventory, Villager.FOOD_POINTS));
		assertEquals(5, named.getCount());
		assertEquals(Component.literal("Keep this name"), named.get(DataComponents.CUSTOM_NAME));
		assertEquals(unrelated, custom.get(DataComponents.CUSTOM_DATA).copyTag());
	}

	@Test void mixedSameItemInventorySkipsMarkersButKeepsLaterOrdinarySlots() {
		ItemStack markedFirst = SettlementFoodAge.stamp(new ItemStack(Items.POTATO, 60), CLOCK, 0);
		ItemStack ordinary = new ItemStack(Items.POTATO, 7);
		ItemStack markedLast = withAge(age -> age.putInt("Format", 2));
		ReadOnlyContainer inventory = new ReadOnlyContainer(markedFirst, ordinary, markedLast);
		List<Tag> before = List.of(markedFirst.save(registries), ordinary.save(registries), markedLast.save(registries));
		for (int repeat = 0; repeat < 3; repeat++) {
			assertTrue(SettlementVanillaFoodGuard.containsMarked(inventory));
			assertSame(ItemStack.EMPTY, SettlementVanillaFoodGuard.usableFoodView(inventory, 0));
			assertSame(ordinary, SettlementVanillaFoodGuard.usableFoodView(inventory, 1));
			assertSame(ItemStack.EMPTY, SettlementVanillaFoodGuard.usableFoodView(inventory, 2));
			assertEquals(7, SettlementVanillaFoodGuard.foodPoints(inventory, Villager.FOOD_POINTS));
		}
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			assertEquals(before.get(slot), inventory.getItem(slot).save(registries));
		}
	}

	@Test void emptyInventoryHasNoMarkersAndNoFoodPoints() {
		ReadOnlyContainer inventory = new ReadOnlyContainer(ItemStack.EMPTY, ItemStack.EMPTY);
		assertFalse(SettlementVanillaFoodGuard.containsMarked(inventory));
		assertEquals(0, SettlementVanillaFoodGuard.foodPoints(inventory, Villager.FOOD_POINTS));
		assertSame(ItemStack.EMPTY, SettlementVanillaFoodGuard.usableFoodView(inventory, 0));
	}

	@Test void cookingInputsRejectEverySupportedMarkerWithoutChangingTheIngredient() {
		for (Item item : List.of(Items.SWEET_BERRIES, Items.POTATO, Items.COD, Items.BEEF)) {
			ItemStack marked = SettlementFoodAge.stamp(new ItemStack(item, 4), CLOCK, 7);
			SingleRecipeInput input = new SingleRecipeInput(marked);
			Tag before = marked.save(registries);
			assertTrue(SettlementVanillaFoodGuard.containsMarked(input));
			assertSame(marked, input.getItem(0));
			assertEquals(before, input.getItem(0).save(registries));
			assertFalse(SettlementVanillaFoodGuard.containsMarked(new SingleRecipeInput(new ItemStack(item, 4))));
		}
		assertTrue(SettlementVanillaFoodGuard.containsMarked(new SingleRecipeInput(withAge(age -> age.putInt("Format", 99)))));
		assertFalse(SettlementVanillaFoodGuard.containsMarked(new SingleRecipeInput(ItemStack.EMPTY)));
	}

	@Test void mixedCraftingInputRejectsMarkerInAnySlotWhileOrdinaryCustomizationsRemainUsable() {
		ItemStack ordinary = new ItemStack(Items.POTATO, 3);
		ordinary.set(DataComponents.CUSTOM_NAME, Component.literal("Ordinary named potato"));
		CompoundTag unrelated = new CompoundTag();
		unrelated.putString("OtherMod", "keep");
		ordinary.set(DataComponents.CUSTOM_DATA, CustomData.of(unrelated));
		ItemStack marked = withAge(age -> age.putInt("Format", 99));
		Tag before = marked.save(registries);
		for (int markedSlot = 0; markedSlot < 4; markedSlot++) {
			List<ItemStack> items = new ArrayList<>(List.of(ordinary, ordinary, ordinary, ordinary));
			items.set(markedSlot, marked);
			CraftingInput input = CraftingInput.of(2, 2, items);
			assertTrue(SettlementVanillaFoodGuard.containsMarked(input));
			assertEquals(before, marked.save(registries));
			assertEquals(3, ordinary.getCount());
		}
		assertFalse(SettlementVanillaFoodGuard.containsMarked(CraftingInput.of(2, 2,
			List.of(ordinary, ordinary, ordinary, ordinary))));
		assertFalse(SettlementVanillaFoodGuard.containsMarked(new SingleRecipeInput(ordinary)));
		assertFalse(SettlementVanillaFoodGuard.containsMarked(CraftingInput.of(2, 2,
			List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY))));
		assertEquals(unrelated, ordinary.get(DataComponents.CUSTOM_DATA).copyTag());
	}

	private static ItemStack withAge(Consumer<CompoundTag> change) {
		ItemStack stack = SettlementFoodAge.stamp(new ItemStack(Items.POTATO, 8), CLOCK, 7);
		CompoundTag data = stack.get(DataComponents.CUSTOM_DATA).copyTag();
		change.accept(data.getCompound(KEY));
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
		return stack;
	}

	/** A helper that attempts a container write must fail even if it restores the original contents. */
	private static final class ReadOnlyContainer extends SimpleContainer {
		ReadOnlyContainer(ItemStack... stacks) { super(stacks); }
		@Override public void setItem(int slot, ItemStack stack) { throw new AssertionError("Unexpected inventory write"); }
		@Override public ItemStack removeItem(int slot, int count) { throw new AssertionError("Unexpected inventory removal"); }
		@Override public ItemStack removeItemNoUpdate(int slot) { throw new AssertionError("Unexpected inventory removal"); }
		@Override public void clearContent() { throw new AssertionError("Unexpected inventory clear"); }
		@Override public void setChanged() { throw new AssertionError("Unexpected dirty notification"); }
	}
}
