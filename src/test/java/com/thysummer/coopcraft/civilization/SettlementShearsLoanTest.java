package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Actual ItemStacks, containers and NBT only. No entity, level, client, server or GameTest is run. */
class SettlementShearsLoanTest {
	private static final UUID CAMP = new UUID(84, 1), ACTOR = new UUID(84, 2), LOAN = new UUID(84, 3),
		FOREIGN = new UUID(84, 4);
	private static final ResourceLocation DIMENSION = Level.OVERWORLD.location();
	private static HolderLookup.Provider registries;

	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		registries = VanillaRegistries.createLookup();
	}

	private static final class TestHand implements SettlementShearsLoan.Hand {
		final SimpleContainer equipment;
		TestHand() { this(ItemStack.EMPTY); }
		TestHand(ItemStack stack) { equipment = new SimpleContainer(stack); }
		@Override public ItemStack get() { return equipment.getItem(0); }
		@Override public void set(ItemStack stack) { equipment.setItem(0, stack); }
	}

	private static ItemStack mark(ItemStack actual) {
		return SettlementShearsLoan.mark(actual, DIMENSION, CAMP, ACTOR, LOAN);
	}

	private static ItemStack marked() { return mark(new ItemStack(Items.SHEARS)); }

	private static boolean borrow(SimpleContainer store, SettlementShearsLoan.Hand hand) {
		return SettlementShearsLoan.borrow(store, hand, DIMENSION, CAMP, ACTOR, () -> true);
	}

	private static boolean giveBack(SettlementShearsLoan.Hand hand, SimpleContainer store) {
		return SettlementShearsLoan.returnFromHand(hand, store, DIMENSION, CAMP, ACTOR, () -> true);
	}

	private static ItemStack roundTrip(ItemStack source) {
		return ItemStack.parseOptional(registries, (CompoundTag) source.save(registries));
	}

	private static ItemStack decorated() {
		ItemStack stack = new ItemStack(Items.SHEARS);
		stack.setDamageValue(73);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Warehouse heirloom"));
		stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Keep its history"))));
		stack.set(DataComponents.REPAIR_COST, 7);
		stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(42));
		stack.enchant(registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.UNBREAKING), 3);
		stack.enchant(registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.MENDING), 1);
		CompoundTag history = new CompoundTag(); history.putInt("Uses", 19);
		CompoundTag custom = new CompoundTag(); custom.putString("Owner", "Warehouse"); custom.put("History", history);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
		return stack;
	}

	private static ItemStack alter(ItemStack source, Consumer<CompoundTag> edit) {
		ItemStack changed = source.copy();
		CompoundTag root = changed.get(DataComponents.CUSTOM_DATA).copyTag();
		edit.accept(root.getCompound(SettlementShearsLoan.KEY));
		changed.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		return changed;
	}

	private static ItemStack withRootKey(ItemStack source, String key) {
		ItemStack changed = source.copy();
		CustomData custom = changed.get(DataComponents.CUSTOM_DATA);
		CompoundTag root = custom == null ? new CompoundTag() : custom.copyTag();
		root.putString(key, "unknown");
		changed.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		return changed;
	}

	@Test void markerPreservesEveryComponentAndOnlyChangesItsOwnCustomDataKey() {
		ItemStack actual = decorated(), before = actual.copy();
		ItemStack marked = mark(actual);
		assertNotSame(actual, marked);
		assertTrue(ItemStack.matches(before, actual));
		assertEquals(before.getComponentsPatch(), SettlementShearsLoan.unmark(marked).getComponentsPatch());
		CompoundTag stamp = marked.get(DataComponents.CUSTOM_DATA).copyTag().getCompound(SettlementShearsLoan.KEY);
		assertEquals(Set.of("Version", "Settlement", "Actor", "Dimension", "Loan", "HadCustomData"), stamp.getAllKeys());
		assertEquals(1, stamp.getInt("Version")); assertEquals(CAMP, stamp.getUUID("Settlement"));
		assertEquals(ACTOR, stamp.getUUID("Actor")); assertEquals(LOAN, stamp.getUUID("Loan"));
		assertEquals(DIMENSION.toString(), stamp.getString("Dimension")); assertTrue(stamp.getBoolean("HadCustomData"));
		assertTrue(SettlementShearsLoan.isRecognized(marked));
		assertTrue(SettlementShearsLoan.ownedBy(marked, DIMENSION, CAMP, ACTOR));
		assertFalse(SettlementShearsLoan.ownedBy(marked, Level.NETHER.location(), CAMP, ACTOR));
		assertFalse(SettlementShearsLoan.ownedBy(marked, DIMENSION, FOREIGN, ACTOR));
		assertFalse(SettlementShearsLoan.ownedBy(marked, DIMENSION, CAMP, FOREIGN));
		ItemStack restored = SettlementShearsLoan.unmark(roundTrip(marked));
		assertTrue(ItemStack.matches(actual, restored));
		assertEquals(actual.save(registries), restored.save(registries));
	}

	@Test void explicitComponentRemovalsAndLaterDamageAreNotReset() {
		ItemStack actual = decorated(); actual.remove(DataComponents.TOOL);
		ItemStack marked = mark(actual);
		assertEquals(actual.getComponentsPatch(), SettlementShearsLoan.unmark(marked).getComponentsPatch());
		marked.setDamageValue(74);
		marked.set(DataComponents.REPAIR_COST, 8);
		ItemStack expected = actual.copy(); expected.setDamageValue(74); expected.set(DataComponents.REPAIR_COST, 8);
		assertEquals(expected.getComponentsPatch(), SettlementShearsLoan.unmark(marked).getComponentsPatch());
	}

	@Test void absentEmptyAndNonemptyCustomDataRemainDistinctAcrossSaveAndReturn() {
		ItemStack emptyCustom = new ItemStack(Items.SHEARS);
		emptyCustom.set(DataComponents.CUSTOM_DATA, CustomData.of(new CompoundTag()));
		for (ItemStack actual : List.of(new ItemStack(Items.SHEARS), emptyCustom, decorated())) {
			ItemStack before = actual.copy();
			TestHand hand = new TestHand(roundTrip(mark(actual)));
			SimpleContainer store = new SimpleContainer(1);
			assertTrue(giveBack(hand, store)); assertTrue(hand.get().isEmpty());
			assertEquals(before.getComponentsPatch(), store.getItem(0).getComponentsPatch());
			assertEquals(before.get(DataComponents.CUSTOM_DATA), store.getItem(0).get(DataComponents.CUSTOM_DATA));
			assertTrue(ItemStack.matches(before, roundTrip(store.getItem(0))));
		}
	}

	@Test void unrelatedRootDataAddedDuringLoanIsRetainedEvenWhenOriginalHadNoCustomData() {
		ItemStack marked = withRootKey(marked(), "another_mod:note");
		assertTrue(SettlementShearsLoan.isRecognized(marked));
		ItemStack ordinary = SettlementShearsLoan.unmark(marked);
		assertEquals(Set.of("another_mod:note"), ordinary.get(DataComponents.CUSTOM_DATA).copyTag().getAllKeys());
		assertEquals("unknown", ordinary.get(DataComponents.CUSTOM_DATA).copyTag().getString("another_mod:note"));
		assertTrue(SettlementShearsLoan.isMarked(marked));
	}

	@Test void rejectsWrongItemWrongCountPurposeMarkersAndMissingIdentityWithoutMutation() {
		List<ItemStack> invalids = new ArrayList<>(List.of(ItemStack.EMPTY, new ItemStack(Items.STICK),
			new ItemStack(Items.SHEARS, 0), new ItemStack(Items.SHEARS, 2), marked()));
		for (String key : List.of(SettlementShearsLoan.KEY, ConstructionSupply.KEY,
			SettlementWorkbenchCargo.KEY, SettlementShearingCargo.KEY))
			invalids.add(withRootKey(new ItemStack(Items.SHEARS), key));
		for (ItemStack invalid : invalids) {
			ItemStack before = invalid.copy();
			assertFalse(SettlementShearsLoan.borrowable(invalid));
			assertThrows(IllegalArgumentException.class, () -> mark(invalid));
			assertTrue(ItemStack.matches(before, invalid));
		}
		assertThrows(IllegalArgumentException.class, () -> mark(null));
		ItemStack actual = decorated();
		assertThrows(IllegalArgumentException.class, () -> SettlementShearsLoan.mark(actual, null, CAMP, ACTOR, LOAN));
		assertThrows(IllegalArgumentException.class, () -> SettlementShearsLoan.mark(actual, DIMENSION, null, ACTOR, LOAN));
		assertThrows(IllegalArgumentException.class, () -> SettlementShearsLoan.mark(actual, DIMENSION, CAMP, null, LOAN));
		assertThrows(IllegalArgumentException.class, () -> SettlementShearsLoan.mark(actual, DIMENSION, CAMP, ACTOR, null));
		assertFalse(SettlementShearsLoan.isMarked(null)); assertFalse(SettlementShearsLoan.isMarked(ItemStack.EMPTY));
	}

	@Test void strictNestedSchemaQuarantinesUnknownCorruptWrongItemAndConflictingMarkers() {
		ItemStack valid = marked();
		List<ItemStack> invalids = new ArrayList<>(List.of(
			alter(valid, tag -> tag.putInt("Version", 2)), alter(valid, tag -> tag.putInt("Version", 0)),
			alter(valid, tag -> tag.putByte("Version", (byte) 1)), alter(valid, tag -> tag.putInt("Extra", 1)),
			alter(valid, tag -> tag.putString("Dimension", "overworld")),
			alter(valid, tag -> tag.putString("Dimension", "BAD:world")),
			alter(valid, tag -> tag.putInt("Dimension", 1)),
			alter(valid, tag -> tag.putInt("HadCustomData", 1)),
			alter(valid, tag -> tag.putByte("HadCustomData", (byte) 2)),
			alter(valid, tag -> tag.putByte("HadCustomData", (byte) -1)),
			valid.copyWithCount(2), withRootKey(new ItemStack(Items.SHEARS), SettlementShearsLoan.KEY)));
		for (String key : List.of("Version", "Settlement", "Actor", "Dimension", "Loan", "HadCustomData"))
			invalids.add(alter(valid, tag -> tag.remove(key)));
		for (String key : List.of("Settlement", "Actor", "Loan")) {
			invalids.add(alter(valid, tag -> tag.putString(key, CAMP.toString())));
			invalids.add(alter(valid, tag -> tag.putIntArray(key, new int[] { 1, 2, 3 })));
		}
		for (Item item : List.of(Items.STICK, Items.WHITE_WOOL, Items.POTATO)) {
			ItemStack wrongItem = new ItemStack(item); wrongItem.set(DataComponents.CUSTOM_DATA, valid.get(DataComponents.CUSTOM_DATA));
			invalids.add(wrongItem);
		}
		for (String key : List.of(ConstructionSupply.KEY, SettlementWorkbenchCargo.KEY, SettlementShearingCargo.KEY))
			invalids.add(withRootKey(valid, key));
		for (ItemStack invalid : invalids) {
			ItemStack before = invalid.copy(); TestHand hand = new TestHand(invalid);
			SimpleContainer store = new SimpleContainer(1);
			assertTrue(SettlementShearsLoan.isMarked(invalid)); assertFalse(SettlementShearsLoan.isRecognized(invalid));
			assertFalse(SettlementShearsLoan.ownedBy(invalid, DIMENSION, CAMP, ACTOR));
			assertThrows(IllegalArgumentException.class, () -> SettlementShearsLoan.unmark(invalid));
			assertFalse(giveBack(hand, store)); assertTrue(store.isEmpty());
			assertTrue(ItemStack.matches(before, hand.get()));
		}
	}

	@Test void borrowsAndReturnsTheExactWarehouseObjectWithUpdatedDurabilityAndNoReplay() {
		ItemStack actual = decorated(), before = actual.copy();
		SimpleContainer store = new SimpleContainer(actual); TestHand hand = new TestHand();
		assertTrue(borrow(store, hand)); assertSame(actual, hand.get()); assertTrue(store.isEmpty());
		assertTrue(SettlementShearsLoan.ownedBy(hand.get(), DIMENSION, CAMP, ACTOR));
		CompoundTag firstStamp = hand.get().get(DataComponents.CUSTOM_DATA).copyTag().getCompound(SettlementShearsLoan.KEY);
		assertFalse(borrow(store, hand));
		hand.get().setDamageValue(74); before.setDamageValue(74);
		assertTrue(giveBack(hand, store)); assertSame(actual, store.getItem(0)); assertTrue(hand.get().isEmpty());
		assertTrue(ItemStack.matches(before, store.getItem(0))); assertFalse(giveBack(hand, store));
		assertTrue(borrow(store, hand));
		CompoundTag secondStamp = hand.get().get(DataComponents.CUSTOM_DATA).copyTag().getCompound(SettlementShearsLoan.KEY);
		assertNotEquals(firstStamp.getUUID("Loan"), secondStamp.getUUID("Loan"));
		assertTrue(giveBack(hand, store)); assertEquals(1, store.countItem(Items.SHEARS));
	}

	@Test void occupiedHandsIncludingPersonalAndForeignToolsNeverGetOverwrittenOrReturned() {
		for (ItemStack occupied : List.of(decorated(), new ItemStack(Items.STICK), marked(),
			SettlementShearsLoan.mark(decorated(), DIMENSION, CAMP, FOREIGN, LOAN),
			withRootKey(new ItemStack(Items.SHEARS), SettlementShearsLoan.KEY))) {
			ItemStack before = occupied.copy(); TestHand hand = new TestHand(occupied);
			SimpleContainer store = new SimpleContainer(decorated()); ItemStack available = store.getItem(0);
			assertFalse(borrow(store, hand)); assertSame(available, store.getItem(0)); assertSame(occupied, hand.get());
			assertTrue(ItemStack.matches(before, hand.get()));
			if (!SettlementShearsLoan.ownedBy(occupied, DIMENSION, CAMP, ACTOR)) {
				SimpleContainer destination = new SimpleContainer(1);
				assertFalse(giveBack(hand, destination)); assertTrue(destination.isEmpty());
				assertTrue(ItemStack.matches(before, hand.get()));
			}
		}
	}

	@Test void loanCannotReturnToAnotherActorSettlementOrDimension() {
		TestHand hand = new TestHand(marked()); ItemStack actual = hand.get();
		SimpleContainer store = new SimpleContainer(1);
		assertFalse(SettlementShearsLoan.returnFromHand(hand, store, DIMENSION, FOREIGN, ACTOR, () -> true));
		assertFalse(SettlementShearsLoan.returnFromHand(hand, store, DIMENSION, CAMP, FOREIGN, () -> true));
		assertFalse(SettlementShearsLoan.returnFromHand(hand, store, Level.NETHER.location(), CAMP, ACTOR, () -> true));
		assertSame(actual, hand.get()); assertTrue(store.isEmpty());
	}

	@Test void scansPastUnborrowableStockWithoutChangingIt() {
		ItemStack unknown = withRootKey(new ItemStack(Items.SHEARS), SettlementShearsLoan.KEY);
		ItemStack workbench = withRootKey(new ItemStack(Items.SHEARS), SettlementWorkbenchCargo.KEY);
		ItemStack actual = decorated();
		SimpleContainer store = new SimpleContainer(unknown, workbench, new ItemStack(Items.STICK), actual);
		TestHand hand = new TestHand();
		assertTrue(borrow(store, hand)); assertSame(actual, hand.get());
		assertSame(unknown, store.getItem(0)); assertSame(workbench, store.getItem(1));
		assertEquals(Items.STICK, store.getItem(2).getItem()); assertTrue(store.getItem(3).isEmpty());
	}

	@Test void fullPartialRejectingAndZeroCapacityWarehousesKeepUndeliveredLoanMarked() {
		ItemStack actual = mark(decorated()), before = actual.copy(); TestHand hand = new TestHand(actual);
		SimpleContainer full = new SimpleContainer(new ItemStack(Items.STICK, 64));
		SimpleContainer rejecting = new SimpleContainer(1) {
			@Override public boolean canPlaceItem(int slot, ItemStack stack) { return false; }
		};
		SimpleContainer zero = new SimpleContainer(1) {
			@Override public int getMaxStackSize() { return 0; }
		};
		for (SimpleContainer store : List.of(full, rejecting, zero, new SimpleContainer(0))) {
			assertFalse(giveBack(hand, store)); assertSame(actual, hand.get());
			assertTrue(ItemStack.matches(before, hand.get()));
		}
		SimpleContainer partial = new SimpleContainer(3) {
			@Override public boolean canPlaceItem(int slot, ItemStack stack) {
				return slot == 2 && !SettlementShearsLoan.isMarked(stack);
			}
		};
		partial.setItem(0, new ItemStack(Items.STICK, 64));
		assertTrue(giveBack(hand, partial)); assertTrue(hand.get().isEmpty());
		assertTrue(partial.getItem(1).isEmpty()); assertSame(actual, partial.getItem(2));
		assertTrue(ItemStack.matches(SettlementShearsLoan.unmark(before), partial.getItem(2)));
	}

	@Test void customizedStackableToolsNeverMergeLoansOrOverwriteOtherTools() {
		ItemStack stackable = new ItemStack(Items.SHEARS);
		stackable.remove(DataComponents.MAX_DAMAGE); stackable.remove(DataComponents.DAMAGE);
		stackable.set(DataComponents.MAX_STACK_SIZE, 64);
		TestHand hand = new TestHand(mark(stackable));
		SimpleContainer full = new SimpleContainer(stackable.copy());
		assertFalse(giveBack(hand, full)); assertEquals(1, hand.get().getCount()); assertEquals(1, full.getItem(0).getCount());
		SimpleContainer partial = new SimpleContainer(stackable.copy(), ItemStack.EMPTY);
		assertTrue(giveBack(hand, partial)); assertEquals(1, partial.getItem(0).getCount());
		assertEquals(stackable.getComponentsPatch(), partial.getItem(1).getComponentsPatch());
	}

	@Test void revokedBorrowAuthorizationAtEveryBoundaryRestoresTheSameSource() {
		for (int revokeAt = 1; revokeAt <= 3; revokeAt++) {
			int boundary = revokeAt; AtomicInteger calls = new AtomicInteger();
			ItemStack actual = decorated(), before = actual.copy();
			SimpleContainer store = new SimpleContainer(actual); TestHand hand = new TestHand();
			assertFalse(SettlementShearsLoan.borrow(store, hand, DIMENSION, CAMP, ACTOR,
				() -> calls.incrementAndGet() < boundary));
			assertSame(actual, store.getItem(0)); assertTrue(ItemStack.matches(before, actual)); assertTrue(hand.get().isEmpty());
		}
	}

	@Test void revokedReturnAuthorizationAtEveryBoundaryRetainsTheOriginalLoan() {
		for (int revokeAt = 1; revokeAt <= 3; revokeAt++) {
			int boundary = revokeAt; AtomicInteger calls = new AtomicInteger();
			ItemStack actual = mark(decorated()), before = actual.copy();
			TestHand hand = new TestHand(actual); SimpleContainer store = new SimpleContainer(1);
			assertFalse(SettlementShearsLoan.returnFromHand(hand, store, DIMENSION, CAMP, ACTOR,
				() -> calls.incrementAndGet() < boundary));
			assertSame(actual, hand.get()); assertTrue(ItemStack.matches(before, actual)); assertTrue(store.isEmpty());
		}
	}

	@Test void authorizationChangesCannotOverwriteNewEquipmentOrWithdrawAMutatedSource() {
		ItemStack actual = decorated(); SimpleContainer store = new SimpleContainer(actual);
		TestHand hand = new TestHand(); ItemStack personal = new ItemStack(Items.STICK);
		AtomicInteger calls = new AtomicInteger();
		assertFalse(SettlementShearsLoan.borrow(store, hand, DIMENSION, CAMP, ACTOR, () -> {
			if (calls.incrementAndGet() == 2) hand.set(personal);
			return true;
		}));
		assertSame(personal, hand.get()); assertSame(actual, store.getItem(0)); assertFalse(SettlementShearsLoan.isMarked(actual));
		hand.set(ItemStack.EMPTY); calls.set(0);
		assertFalse(SettlementShearsLoan.borrow(store, hand, DIMENSION, CAMP, ACTOR, () -> {
			if (calls.incrementAndGet() == 2) actual.setDamageValue(74);
			return true;
		}));
		assertSame(actual, store.getItem(0)); assertEquals(74, actual.getDamageValue()); assertTrue(hand.get().isEmpty());
	}

	@Test void throwingPostCommitContainerAndEquipmentListenersDoNotDuplicateOrLoseTools() {
		ItemStack actual = decorated(), before = actual.copy();
		SimpleContainer store = new SimpleContainer(actual); TestHand hand = new TestHand();
		store.addListener(changed -> { throw new IllegalStateException("store listener"); });
		hand.equipment.addListener(changed -> { throw new IllegalStateException("equipment listener"); });
		assertTrue(borrow(store, hand)); assertSame(actual, hand.get()); assertTrue(store.isEmpty());
		assertTrue(SettlementShearsLoan.isMarked(hand.get())); assertFalse(borrow(store, hand));
		assertTrue(giveBack(hand, store)); assertSame(actual, store.getItem(0)); assertTrue(hand.get().isEmpty());
		assertTrue(ItemStack.matches(before, actual)); assertFalse(giveBack(hand, store));
	}

	@Test void returnMarkerIsRemovedOnlyAfterARealInsertionEvenWhenItsListenerThrows() {
		TestHand hand = new TestHand(mark(decorated())); ItemStack actual = hand.get();
		SimpleContainer store = new SimpleContainer(1); AtomicInteger observedMarked = new AtomicInteger();
		store.addListener(changed -> {
			if (changed.getItem(0) == actual && SettlementShearsLoan.isMarked(actual)) observedMarked.incrementAndGet();
			throw new IllegalStateException("notification only");
		});
		assertTrue(giveBack(hand, store)); assertEquals(1, observedMarked.get());
		assertSame(actual, store.getItem(0)); assertFalse(SettlementShearsLoan.isMarked(actual));
	}

	@Test void preCommitRejectionRestoresRealCustodyWithoutManufacturingReplacementStacks() {
		ItemStack actual = decorated(), before = actual.copy(); SimpleContainer store = new SimpleContainer(actual);
		SettlementShearsLoan.Hand refusesLoan = new SettlementShearsLoan.Hand() {
			@Override public ItemStack get() { return ItemStack.EMPTY; }
			@Override public void set(ItemStack stack) { throw new IllegalStateException("rejected before commit"); }
		};
		assertFalse(borrow(store, refusesLoan)); assertSame(actual, store.getItem(0)); assertTrue(ItemStack.matches(before, actual));
		TestHand hand = new TestHand(mark(actual)); ItemStack loan = hand.get();
		SimpleContainer refusesReturn = new SimpleContainer(1) {
			@Override public void setItem(int slot, ItemStack stack) { throw new IllegalStateException("rejected before commit"); }
		};
		assertFalse(giveBack(hand, refusesReturn)); assertSame(loan, hand.get());
		assertTrue(SettlementShearsLoan.isMarked(loan)); assertTrue(refusesReturn.isEmpty());
	}

	@Test void authorizationExceptionAfterDetachmentRestoresOriginalCustody() {
		AtomicInteger calls = new AtomicInteger(); ItemStack actual = decorated(), before = actual.copy();
		SimpleContainer store = new SimpleContainer(actual); TestHand hand = new TestHand();
		assertThrows(IllegalStateException.class, () -> SettlementShearsLoan.borrow(store, hand, DIMENSION, CAMP, ACTOR, () -> {
			if (calls.incrementAndGet() == 3) throw new IllegalStateException("authorization unavailable");
			return true;
		}));
		assertSame(actual, store.getItem(0)); assertTrue(ItemStack.matches(before, actual)); assertTrue(hand.get().isEmpty());
		hand.set(mark(actual)); ItemStack loan = hand.get(); store.setItem(0, ItemStack.EMPTY); calls.set(0);
		assertThrows(IllegalStateException.class, () -> SettlementShearsLoan.returnFromHand(hand, store, DIMENSION, CAMP, ACTOR, () -> {
			if (calls.incrementAndGet() == 3) throw new IllegalStateException("authorization unavailable");
			return true;
		}));
		assertSame(loan, hand.get()); assertTrue(SettlementShearsLoan.isMarked(loan)); assertTrue(store.isEmpty());
	}

	@Test void nullInputsAndEmptyWarehousesAreNoOps() {
		SimpleContainer store = new SimpleContainer(1); TestHand hand = new TestHand();
		assertFalse(borrow(store, hand));
		assertFalse(SettlementShearsLoan.borrow(null, hand, DIMENSION, CAMP, ACTOR, () -> true));
		assertFalse(SettlementShearsLoan.borrow(store, null, DIMENSION, CAMP, ACTOR, () -> true));
		assertFalse(SettlementShearsLoan.borrow(store, hand, null, CAMP, ACTOR, () -> true));
		assertFalse(SettlementShearsLoan.borrow(store, hand, DIMENSION, null, ACTOR, () -> true));
		assertFalse(SettlementShearsLoan.borrow(store, hand, DIMENSION, CAMP, null, () -> true));
		assertFalse(SettlementShearsLoan.borrow(store, hand, DIMENSION, CAMP, ACTOR, null));
		assertFalse(SettlementShearsLoan.returnFromHand(null, store, DIMENSION, CAMP, ACTOR, () -> true));
		assertFalse(SettlementShearsLoan.returnFromHand(hand, null, DIMENSION, CAMP, ACTOR, () -> true));
		assertFalse(SettlementShearsLoan.returnFromHand(hand, store, DIMENSION, CAMP, ACTOR, null));
		assertTrue(hand.get().isEmpty()); assertTrue(store.isEmpty());
	}
}
