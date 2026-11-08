package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Container tests only. The Goal's runtime log tag and world/station checks are not executed here. */
class SettlementPersonalCraftingTest {

	private static final Predicate<ItemStack> LOG = stack -> stack.is(Items.OAK_LOG) || stack.is(Items.BIRCH_LOG);
	private static final Predicate<ItemStack> COBBLE = stack -> stack.is(Items.COBBLESTONE);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void legacyPrecheckRejectsAFullBackpackWhoseOnlyLogWouldFreeTheOutputSlot() {
		SimpleContainer inventory = fullBackpack(new ItemStack(Items.OAK_LOG));
		assertFalse(inventory.canAddItem(sticks()));
		assertTrue(SettlementPersonalCrafting.canCraft(inventory, LOG, ItemStack.EMPTY, sticks()));
		assertTrue(SettlementPersonalCrafting.craft(inventory, LOG, sticks()));
		assertEquals(8, inventory.countItem(Items.STICK));
		assertEquals(0, inventory.countItem(Items.OAK_LOG));
		assertEquals(7 * 64, inventory.countItem(Items.DIRT));
	}

	@Test
	void grindingAlsoUsesTheSlotFreedByItsLastCobblestone() {
		SimpleContainer inventory = fullBackpack(new ItemStack(Items.COBBLESTONE));
		ItemStack output = new ItemStack(Items.SMOOTH_STONE);
		assertFalse(inventory.canAddItem(output));
		assertTrue(SettlementPersonalCrafting.canCraft(inventory, COBBLE, ItemStack.EMPTY, output));
		assertTrue(SettlementPersonalCrafting.craft(inventory, COBBLE, output));
		assertEquals(1, inventory.countItem(Items.SMOOTH_STONE));
		assertEquals(0, inventory.countItem(Items.COBBLESTONE));
		assertEquals(7 * 64, inventory.countItem(Items.DIRT));
	}

	@Test
	void aRemainingIngredientStackDoesNotFreeASlot() {
		for (var input : List.of(new ItemStack(Items.OAK_LOG, 2), new ItemStack(Items.COBBLESTONE, 2))) {
			SimpleContainer inventory = fullBackpack(input);
			Predicate<ItemStack> ingredient = input.is(Items.OAK_LOG) ? LOG : COBBLE;
			ItemStack output = input.is(Items.OAK_LOG) ? sticks() : new ItemStack(Items.SMOOTH_STONE);
			List<ItemStack> before = snapshot(inventory);
			assertFalse(SettlementPersonalCrafting.canCraft(inventory, ingredient, ItemStack.EMPTY, output));
			assertFalse(SettlementPersonalCrafting.craft(inventory, ingredient, output));
			assertUnchanged(before, inventory);
		}
	}

	@Test
	void partialOutputCapacityCannotConsumeAnIngredientOrProduceAPartialBatch() {
		SimpleContainer inventory = fullBackpack(new ItemStack(Items.OAK_LOG, 2));
		inventory.setItem(1, new ItemStack(Items.STICK, 60));
		// The old canAddItem check only promises room for some of the stack.
		assertTrue(inventory.canAddItem(sticks()));
		List<ItemStack> before = snapshot(inventory);
		assertFalse(SettlementPersonalCrafting.canCraft(inventory, LOG, ItemStack.EMPTY, sticks()));
		assertFalse(SettlementPersonalCrafting.craft(inventory, LOG, sticks()));
		assertUnchanged(before, inventory);
	}

	@Test
	void fullOutputCanMergeAcrossSeveralExistingStacks() {
		SimpleContainer inventory = fullBackpack(new ItemStack(Items.OAK_LOG, 2));
		inventory.setItem(1, new ItemStack(Items.STICK, 60));
		inventory.setItem(2, new ItemStack(Items.STICK, 60));
		assertTrue(SettlementPersonalCrafting.craft(inventory, LOG, sticks()));
		assertEquals(1, inventory.countItem(Items.OAK_LOG));
		assertEquals(128, inventory.countItem(Items.STICK));
		assertEquals(64, inventory.getItem(1).getCount());
		assertEquals(64, inventory.getItem(2).getCount());
	}

	@Test
	void previewsAreReadOnlyAndDoNotNotifyInventoryListeners() {
		SimpleContainer inventory = fullBackpack(named(new ItemStack(Items.BIRCH_LOG), "Input"));
		AtomicInteger notifications = new AtomicInteger();
		inventory.addListener(ignored -> notifications.incrementAndGet());
		List<ItemStack> before = snapshot(inventory);
		ItemStack external = named(new ItemStack(Items.OAK_LOG, 30), "Unused external input");
		ItemStack output = sticks();
		for (int i = 0; i < 3; i++) {
			assertTrue(SettlementPersonalCrafting.canCraft(inventory, LOG, external, output));
		}
		assertUnchanged(before, inventory);
		assertEquals(0, notifications.get());
		assertEquals(30, external.getCount());
		assertEquals(Component.literal("Unused external input"), external.get(DataComponents.CUSTOM_NAME));
		assertEquals(8, output.getCount());
	}

	@Test
	void externalInputMustPhysicallyFitBeforeConsumptionEvenWhenOutputWouldMerge() {
		SimpleContainer inventory = fullBackpack(new ItemStack(Items.STICK, 1));
		List<ItemStack> before = snapshot(inventory);
		assertTrue(inventory.canAddItem(sticks()));
		assertFalse(SettlementPersonalCrafting.canCraft(inventory, LOG, new ItemStack(Items.OAK_LOG), sticks()));
		assertFalse(SettlementPersonalCrafting.craft(inventory, LOG, sticks()));
		assertUnchanged(before, inventory);
	}

	@Test
	void oneEmptySlotIsEnoughForExternalInputThenItsEntireOutput() {
		SimpleContainer inventory = fullBackpack(ItemStack.EMPTY);
		SimpleContainer common = new SimpleContainer(named(new ItemStack(Items.BIRCH_LOG, 5), "Common logs"));
		List<ItemStack> before = snapshot(inventory);
		List<ItemStack> commonBefore = snapshot(common);
		assertTrue(SettlementPersonalCrafting.canCraft(inventory, LOG, common.getItem(0), sticks()));
		assertUnchanged(before, inventory);
		assertUnchanged(commonBefore, common);
		assertEquals(1, SettlementStockpile.transferItem(common, inventory, Items.BIRCH_LOG, 1));
		assertTrue(SettlementPersonalCrafting.craft(inventory, LOG, sticks()));
		assertEquals(4, common.countItem(Items.BIRCH_LOG));
		assertEquals(Component.literal("Common logs"), common.getItem(0).get(DataComponents.CUSTOM_NAME));
		assertEquals(8, inventory.countItem(Items.STICK));
		assertEquals(0, inventory.countItem(Items.BIRCH_LOG));
	}

	@Test
	void missingOrWrongExternalInputCannotCreateAnIngredient() {
		SimpleContainer inventory = new SimpleContainer(2);
		for (ItemStack external : List.of(ItemStack.EMPTY, new ItemStack(Items.DIRT))) {
			assertFalse(SettlementPersonalCrafting.canCraft(inventory, LOG, external, sticks()));
		}
		assertFalse(SettlementPersonalCrafting.craft(inventory, LOG, sticks()));
		assertTrue(inventory.isEmpty());
	}

	@Test
	void rechecksCapacityAfterAnEarlierSuccessfulPreview() {
		SimpleContainer inventory = fullBackpack(new ItemStack(Items.OAK_LOG));
		assertTrue(SettlementPersonalCrafting.canCraft(inventory, LOG, ItemStack.EMPTY, sticks()));
		inventory.setItem(0, new ItemStack(Items.OAK_LOG, 2));
		List<ItemStack> before = snapshot(inventory);
		assertFalse(SettlementPersonalCrafting.craft(inventory, LOG, sticks()));
		assertUnchanged(before, inventory);
		inventory.setItem(1, ItemStack.EMPTY);
		assertTrue(SettlementPersonalCrafting.craft(inventory, LOG, sticks()));
		assertEquals(1, inventory.countItem(Items.OAK_LOG));
		assertEquals(8, inventory.countItem(Items.STICK));
	}

	@Test
	void preservesUnconsumedComponentsAndFirstMatchingInputOrder() {
		SimpleContainer inventory = new SimpleContainer(4);
		ItemStack first = named(new ItemStack(Items.BIRCH_LOG, 2), "First input");
		ItemStack second = named(new ItemStack(Items.OAK_LOG), "Later input");
		ItemStack unrelated = named(new ItemStack(Items.STONE_PICKAXE), "Leave alone");
		unrelated.setDamageValue(71);
		inventory.setItem(0, first);
		inventory.setItem(1, second);
		inventory.setItem(2, unrelated);
		assertTrue(SettlementPersonalCrafting.craft(inventory, LOG, sticks()));
		assertEquals(1, inventory.getItem(0).getCount());
		assertEquals(Component.literal("First input"), inventory.getItem(0).get(DataComponents.CUSTOM_NAME));
		assertSame(second, inventory.getItem(1));
		assertSame(unrelated, inventory.getItem(2));
		assertEquals(71, inventory.getItem(2).getDamageValue());
		assertEquals(2, first.getCount(), "A detached source object must not be mutated by planning");
		assertEquals(8, inventory.countItem(Items.STICK));
	}

	@Test
	void doesNotChooseALaterSingletonJustToMakeTheOldConsumptionOrderFit() {
		SimpleContainer inventory = fullBackpack(new ItemStack(Items.BIRCH_LOG, 2));
		inventory.setItem(1, new ItemStack(Items.OAK_LOG));
		List<ItemStack> before = snapshot(inventory);
		assertFalse(SettlementPersonalCrafting.canCraft(inventory, LOG, ItemStack.EMPTY, sticks()));
		assertFalse(SettlementPersonalCrafting.craft(inventory, LOG, sticks()));
		assertUnchanged(before, inventory);
	}

	@Test
	void outputCannotMergeWithAStackHavingDifferentComponents() {
		SimpleContainer inventory = fullBackpack(new ItemStack(Items.OAK_LOG, 2));
		inventory.setItem(1, named(new ItemStack(Items.STICK), "Named sticks"));
		List<ItemStack> before = snapshot(inventory);
		assertFalse(SettlementPersonalCrafting.craft(inventory, LOG, sticks()));
		assertUnchanged(before, inventory);
	}

	@Test
	void slotRestrictionsAndChangedPermissionsAreHonoredBeforeCommit() {
		AtomicBoolean acceptOutput = new AtomicBoolean(true);
		SimpleContainer inventory = new SimpleContainer(1) {
			@Override
			public boolean canPlaceItem(int slot, ItemStack stack) {
				return !stack.is(Items.STICK) || acceptOutput.get();
			}
		};
		inventory.setItem(0, new ItemStack(Items.OAK_LOG));
		assertTrue(SettlementPersonalCrafting.canCraft(inventory, LOG, ItemStack.EMPTY, sticks()));
		acceptOutput.set(false);
		assertFalse(SettlementPersonalCrafting.craft(inventory, LOG, sticks()));
		assertEquals(1, inventory.countItem(Items.OAK_LOG));
		acceptOutput.set(true);
		assertTrue(SettlementPersonalCrafting.craft(inventory, LOG, sticks()));
		assertEquals(8, inventory.countItem(Items.STICK));
	}

	@Test
	void incomingIngredientRestrictionsCannotBeSkippedByPreviewingOnlyTheProduct() {
		SimpleContainer inventory = new SimpleContainer(1) {
			@Override
			public boolean canPlaceItem(int slot, ItemStack stack) { return !LOG.test(stack); }
		};
		assertFalse(SettlementPersonalCrafting.canCraft(inventory, LOG, new ItemStack(Items.OAK_LOG), sticks()));
		assertTrue(inventory.isEmpty());
	}

	@Test
	void customStackLimitsRequireEnoughSpaceForEveryOutputItem() {
		SimpleContainer inventory = new SimpleContainer(2) {
			@Override
			public int getMaxStackSize() { return 4; }
		};
		inventory.setItem(0, new ItemStack(Items.OAK_LOG));
		inventory.setItem(1, new ItemStack(Items.DIRT));
		assertFalse(SettlementPersonalCrafting.craft(inventory, LOG, sticks()));
		assertEquals(1, inventory.countItem(Items.OAK_LOG));
		inventory.setItem(1, ItemStack.EMPTY);
		assertTrue(SettlementPersonalCrafting.craft(inventory, LOG, sticks()));
		assertEquals(4, inventory.getItem(0).getCount());
		assertEquals(4, inventory.getItem(1).getCount());
	}

	@Test
	void repeatedCallsCannotMakeMoreThanOneBatchFromTheOnlyInput() {
		SimpleContainer inventory = fullBackpack(new ItemStack(Items.OAK_LOG));
		assertTrue(SettlementPersonalCrafting.craft(inventory, LOG, sticks()));
		List<ItemStack> before = snapshot(inventory);
		for (int i = 0; i < 3; i++) {
			assertFalse(SettlementPersonalCrafting.craft(inventory, LOG, sticks()));
		}
		assertUnchanged(before, inventory);
	}

	private static ItemStack sticks() { return new ItemStack(Items.STICK, 8); }

	private static ItemStack named(ItemStack stack, String name) {
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
		return stack;
	}

	private static SimpleContainer fullBackpack(ItemStack input) {
		SimpleContainer inventory = new SimpleContainer(8);
		inventory.setItem(0, input);
		for (int slot = 1; slot < inventory.getContainerSize(); slot++) {
			inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
		}
		return inventory;
	}

	private static List<ItemStack> snapshot(Container container) {
		List<ItemStack> result = new ArrayList<>();
		for (int slot = 0; slot < container.getContainerSize(); slot++) result.add(container.getItem(slot).copy());
		return result;
	}

	private static void assertUnchanged(List<ItemStack> before, Container container) {
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			assertTrue(ItemStack.matches(before.get(slot), container.getItem(slot)), "Changed slot " + slot);
		}
	}
}
