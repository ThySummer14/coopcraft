package com.thysummer.coopcraft.civilization;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
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

/** Actual saved records, containers and component round trips only; no server/client/GameTest starts. */
class SettlementShearsReclaimTest {
	private static final BlockPos PLACE = new BlockPos(4, 64, 9);
	private static final ResourceLocation DIMENSION = Level.OVERWORLD.location();
	private static final UUID FOREIGN = new UUID(96, 1);
	private static HolderLookup.Provider registries;

	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		registries = VanillaRegistries.createLookup();
	}

	private static final class Fixture {
		final UUID actor = UUID.randomUUID();
		final SettlementSavedData memberships = new SettlementSavedData();
		final Settlement camp = memberships.getOrCreate(PLACE, "Camp", 0);
		final SimpleContainer bag = new SimpleContainer(8), hand = new SimpleContainer(1);
		SettlementCargoRecovery recovery = new SettlementCargoRecovery();
		Fixture() { memberships.registerMember(camp, actor, 0); }
		int capture() {
			var claims = SettlementMembershipClaims.resolve(List.of(
				new SettlementMembershipClaims.Source(Level.OVERWORLD, memberships)), Level.OVERWORLD, actor);
			return recovery.captureDeath(claims, Level.OVERWORLD, actor, PLACE, "Worker", () -> bag,
				() -> hand.getItem(0), expected -> {
					if (hand.getItem(0) != expected) return false;
					hand.setItem(0, ItemStack.EMPTY); return true;
				});
		}
		ItemStack loan() { return loan(decorated()); }
		ItemStack loan(ItemStack tool) { return SettlementShearsLoan.mark(tool, DIMENSION, camp.id(), actor, UUID.randomUUID()); }
		void captureLoans(int count) {
			for (int slot = 0; slot < count; slot++) bag.setItem(slot, loan());
			assertEquals(count, capture());
			recovery = SettlementCargoRecovery.load(save(recovery), registries);
		}
		SettlementCargoRecovery.Result reclaim(Container destination, BooleanSupplier authority) {
			return recovery.reclaimShears(actor, DIMENSION, camp.id(), destination, authority);
		}
	}

	private static ItemStack decorated() {
		ItemStack tool = new ItemStack(Items.SHEARS);
		tool.setDamageValue(73);
		tool.set(DataComponents.CUSTOM_NAME, Component.literal("Old warehouse shears"));
		tool.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Keep its history"))));
		tool.set(DataComponents.REPAIR_COST, 7);
		tool.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(42));
		tool.remove(DataComponents.TOOL);
		CompoundTag custom = new CompoundTag(); custom.putString("OtherMod", "unchanged");
		tool.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
		var enchantments = registries.lookupOrThrow(Registries.ENCHANTMENT);
		tool.enchant(enchantments.getOrThrow(Enchantments.UNBREAKING), 3);
		tool.enchant(enchantments.getOrThrow(Enchantments.VANISHING_CURSE), 1);
		return tool;
	}

	private static ItemStack alter(ItemStack source, Consumer<CompoundTag> edit) {
		ItemStack changed = source.copy();
		CompoundTag root = changed.get(DataComponents.CUSTOM_DATA).copyTag();
		edit.accept(root.getCompound(SettlementShearsLoan.KEY));
		changed.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		return changed;
	}

	private static CompoundTag save(SettlementCargoRecovery recovery) { return recovery.save(new CompoundTag(), registries); }
	private static CompoundTag entry(CompoundTag saved) { return saved.getList("Records", Tag.TAG_COMPOUND).getCompound(0); }
	private static ListTag stacks(CompoundTag saved) { return entry(saved).getList("Stacks", Tag.TAG_COMPOUND); }

	/** Test-only identity inspection: production previews must never expose these mutable references. */
	private static ItemStack actualStack(Fixture fixture, int index) throws ReflectiveOperationException {
		Field records = SettlementCargoRecovery.class.getDeclaredField("records"); records.setAccessible(true);
		Object record = ((Map<?, ?>) records.get(fixture.recovery)).get(fixture.actor);
		Field stacks = record.getClass().getDeclaredField("stacks"); stacks.setAccessible(true);
		return (ItemStack) ((List<?>) stacks.get(record)).get(index);
	}

	@Test void mixedNineSlotRecordReclaimsOnlyStrictLoansProvenByTheDeathCamp() throws Exception {
		Fixture f = new Fixture();
		f.bag.setItem(0, f.loan());
		f.bag.setItem(1, alter(f.loan(), stamp -> stamp.putUUID("Settlement", FOREIGN)));
		f.bag.setItem(2, alter(f.loan(), stamp -> stamp.putUUID("Actor", FOREIGN)));
		f.bag.setItem(3, alter(f.loan(), stamp -> stamp.putString("Dimension", Level.NETHER.location().toString())));
		f.bag.setItem(4, alter(f.loan(), stamp -> stamp.putInt("Version", 99)));
		ItemStack conflict = f.loan(); CompoundTag root = conflict.get(DataComponents.CUSTOM_DATA).copyTag();
		root.putString(ConstructionSupply.KEY, "other purpose"); conflict.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		f.bag.setItem(5, conflict);
		ItemStack stackable = new ItemStack(Items.SHEARS);
		stackable.remove(DataComponents.MAX_DAMAGE); stackable.remove(DataComponents.DAMAGE);
		stackable.set(DataComponents.MAX_STACK_SIZE, 64);
		f.bag.setItem(6, f.loan(stackable).copyWithCount(2));
		f.bag.setItem(7, new ItemStack(Items.COBBLESTONE, 3)); f.hand.setItem(0, f.loan());
		assertEquals(12, f.capture());
		CompoundTag captured = save(f.recovery);
		assertEquals(3, captured.getInt("DataVersion")); assertEquals(f.camp.id(), entry(captured).getUUID("DeathSettlement"));
		assertEquals(9, stacks(captured).size());
		f.recovery = SettlementCargoRecovery.load(captured, registries); f.recovery.setDirty(false);
		var preview = f.recovery.reclaimableShears(f.actor, DIMENSION);
		assertEquals(List.of(new SettlementCargoRecovery.ShearsClaim(f.camp.id(), 2)), preview);
		assertThrows(UnsupportedOperationException.class, preview::clear);
		assertEquals(captured, save(f.recovery)); assertFalse(f.recovery.isDirty());
		ItemStack first = actualStack(f, 0), second = actualStack(f, 8);
		first.setDamageValue(74);
		ItemStack expectedFirst = SettlementShearsLoan.unmark(first), expectedSecond = SettlementShearsLoan.unmark(second);
		SimpleContainer destination = new SimpleContainer(36);
		var result = f.reclaim(destination, () -> true);
		assertEquals(SettlementCargoRecovery.Status.RECOVERED, result.status());
		assertEquals(2, result.moved()); assertEquals(10, result.remaining()); assertTrue(f.recovery.isDirty());
		assertSame(first, destination.getItem(0)); assertSame(second, destination.getItem(1));
		assertEquals(expectedFirst.save(registries), first.save(registries));
		assertEquals(expectedSecond.getComponentsPatch(), second.getComponentsPatch());
		ListTag remaining = stacks(save(f.recovery)); assertEquals(7, remaining.size());
		for (int index = 0; index < 7; index++) assertEquals(stacks(captured).get(index + 1), remaining.get(index));
		assertTrue(f.recovery.reclaimableShears(f.actor, DIMENSION).isEmpty());
		assertEquals(SettlementCargoRecovery.Status.INVALID, f.reclaim(destination, () -> true).status());
		assertEquals(2, destination.countItem(Items.SHEARS));
		assertEquals(save(f.recovery), save(SettlementCargoRecovery.load(save(f.recovery), registries)));
	}

	@Test void identityPreviewUsesTheUnchangedStrictParserAndDoesNotExposeStacks() {
		Fixture f = new Fixture(); ItemStack valid = f.loan();
		var identity = SettlementShearsLoan.identityOf(valid).orElseThrow();
		assertEquals(DIMENSION, identity.dimension()); assertEquals(f.camp.id(), identity.camp()); assertEquals(f.actor, identity.actor());
		assertEquals(valid.get(DataComponents.CUSTOM_DATA).copyTag().getCompound(SettlementShearsLoan.KEY).getUUID("Loan"), identity.loan());
		for (ItemStack invalid : List.of(alter(valid, stamp -> stamp.putInt("Version", 2)),
			alter(valid, stamp -> stamp.putInt("Extra", 1)), alter(valid, stamp -> stamp.putByte("Version", (byte) 1)),
			alter(valid, stamp -> stamp.putString("Dimension", "overworld")), alter(valid, stamp -> stamp.putByte("HadCustomData", (byte) 2)),
			alter(valid, stamp -> stamp.remove("Actor")), new ItemStack(Items.SHEARS), valid.copyWithCount(2))) {
			assertFalse(SettlementShearsLoan.isRecognized(invalid)); assertTrue(SettlementShearsLoan.identityOf(invalid).isEmpty());
		}
		assertTrue(SettlementShearsLoan.identityOf(null).isEmpty());
		assertEquals(f.actor, identity.actor());
	}

	@Test void partialCapacityPersistsMarkedRemainderAndRepeatedRecoveryNeverManufacturesTools() throws Exception {
		Fixture f = new Fixture(); f.captureLoans(2);
		ItemStack first = actualStack(f, 0), second = actualStack(f, 1); CompoundTag secondBefore = (CompoundTag) second.save(registries);
		SimpleContainer destination = new SimpleContainer(36);
		for (int slot = 1; slot < 36; slot++) destination.setItem(slot, new ItemStack(Items.DIRT, 64));
		assertEquals(1, f.reclaim(destination, () -> true).moved()); assertSame(first, destination.getItem(0));
		assertSame(second, actualStack(f, 0)); assertEquals(secondBefore, second.save(registries));
		assertEquals(1, f.recovery.inspect(f.actor).orElseThrow().totalCount());
		CompoundTag remainder = save(f.recovery); f.recovery.setDirty(false);
		assertEquals(SettlementCargoRecovery.Status.NO_CAPACITY, f.reclaim(destination, () -> true).status());
		assertFalse(f.recovery.isDirty()); assertEquals(remainder, save(f.recovery));
		f.recovery = SettlementCargoRecovery.load(remainder, registries);
		SimpleContainer otherPlayer = new SimpleContainer(36);
		assertEquals(1, f.reclaim(otherPlayer, () -> true).moved()); assertTrue(f.recovery.inspect(f.actor).isEmpty());
		assertEquals(0, f.reclaim(otherPlayer, () -> true).moved());
		assertEquals(2, destination.countItem(Items.SHEARS) + otherPlayer.countItem(Items.SHEARS));
	}

	@Test void authorityAndIdentityFailuresDoNotReadOrWriteDestination() {
		Fixture f = new Fixture(); f.captureLoans(1); CompoundTag before = save(f.recovery);
		SimpleContainer unreadable = new SimpleContainer(36) {
			@Override public int getContainerSize() { throw new AssertionError("unauthorized inventory read"); }
			@Override public ItemStack getItem(int slot) { throw new AssertionError("unauthorized inventory read"); }
			@Override public void setItem(int slot, ItemStack stack) { throw new AssertionError("unauthorized inventory write"); }
		};
		assertEquals(SettlementCargoRecovery.Status.NOT_AT_PLACE, f.reclaim(unreadable, () -> false).status());
		AtomicInteger checks = new AtomicInteger();
		assertEquals(SettlementCargoRecovery.Status.NOT_AT_PLACE,
			f.reclaim(unreadable, () -> checks.incrementAndGet() < 2).status());
		assertEquals(SettlementCargoRecovery.Status.INVALID,
			f.recovery.reclaimShears(f.actor, DIMENSION, FOREIGN, unreadable, () -> true).status());
		assertEquals(SettlementCargoRecovery.Status.INVALID,
			f.recovery.reclaimShears(f.actor, Level.NETHER.location(), f.camp.id(), unreadable, () -> true).status());
		assertEquals(SettlementCargoRecovery.Status.INVALID,
			f.recovery.reclaimShears(FOREIGN, DIMENSION, f.camp.id(), unreadable, () -> true).status());
		assertEquals(before, save(f.recovery)); assertFalse(f.recovery.isDirty());
		assertTrue(f.recovery.reclaimableShears(null, DIMENSION).isEmpty());
		assertTrue(f.recovery.reclaimableShears(f.actor, null).isEmpty());
		assertEquals(SettlementCargoRecovery.Status.INVALID, f.reclaim(unreadable, null).status());
	}

	@Test void revocationAtEveryAuthorizationBoundaryRetainsOneUnchangedLoan() throws Exception {
		for (int boundary = 1; boundary <= 4; boundary++) {
			Fixture f = new Fixture(); f.captureLoans(1); ItemStack actual = actualStack(f, 0);
			CompoundTag before = save(f.recovery); SimpleContainer destination = new SimpleContainer(36);
			int revokeAt = boundary; AtomicInteger checks = new AtomicInteger();
			assertEquals(SettlementCargoRecovery.Status.NOT_AT_PLACE,
				f.reclaim(destination, () -> checks.incrementAndGet() < revokeAt).status());
			assertEquals(revokeAt, checks.get()); assertSame(actual, actualStack(f, 0));
			assertEquals(before, save(f.recovery)); assertTrue(destination.isEmpty());
			assertEquals(boundary == 4, f.recovery.isDirty());
		}
	}

	@Test void revocationAndAuthorityExceptionsAfterDetachRestoreTheSameMarkedRecordObject() throws Exception {
		for (boolean throwsAfterDetach : List.of(false, true)) {
			Fixture f = new Fixture(); f.captureLoans(1); ItemStack actual = actualStack(f, 0);
			CompoundTag before = save(f.recovery); SimpleContainer destination = new SimpleContainer(36);
			BooleanSupplier authority = () -> {
				if (stacks(save(f.recovery)).isEmpty()) {
					if (throwsAfterDetach) throw new IllegalStateException("authority unavailable after detach");
					return false;
				}
				return true;
			};
			if (throwsAfterDetach) assertThrows(IllegalStateException.class, () -> f.reclaim(destination, authority));
			else assertEquals(SettlementCargoRecovery.Status.NOT_AT_PLACE, f.reclaim(destination, authority).status());
			assertSame(actual, actualStack(f, 0)); assertEquals(before, save(f.recovery));
			assertTrue(f.recovery.isDirty()); assertTrue(destination.isEmpty()); assertTrue(SettlementShearsLoan.isMarked(actual));
		}
	}

	@Test void authorityIsRecheckedForEveryToolAndPrecommitFailureRollsBack() throws Exception {
		Fixture f = new Fixture(); f.captureLoans(2); SimpleContainer destination = new SimpleContainer(36);
		assertEquals(1, f.reclaim(destination, () -> destination.countItem(Items.SHEARS) == 0).moved());
		assertEquals(1, f.recovery.inspect(f.actor).orElseThrow().totalCount());
		ItemStack remainder = actualStack(f, 0); CompoundTag before = save(f.recovery);
		SimpleContainer rejectsWrite = new SimpleContainer(36) {
			@Override public void setItem(int slot, ItemStack stack) { throw new IllegalStateException("rejected before commit"); }
		};
		assertEquals(SettlementCargoRecovery.Status.NO_CAPACITY, f.reclaim(rejectsWrite, () -> true).status());
		assertSame(remainder, actualStack(f, 0)); assertEquals(before, save(f.recovery)); assertTrue(rejectsWrite.isEmpty());
	}

	@Test void postcommitListenerFailureStillCommitsOnlyTheRealMarkedReferenceOnce() throws Exception {
		Fixture f = new Fixture(); f.captureLoans(1); ItemStack actual = actualStack(f, 0);
		ItemStack expected = SettlementShearsLoan.unmark(actual); AtomicInteger sawMarked = new AtomicInteger();
		SimpleContainer destination = new SimpleContainer(36);
		destination.addListener(changed -> {
			assertSame(actual, changed.getItem(0));
			assertTrue(stacks(save(f.recovery)).isEmpty(), "Record must detach before insertion");
			if (SettlementShearsLoan.isMarked(actual)) sawMarked.incrementAndGet();
			throw new IllegalStateException("postcommit notification");
		});
		assertEquals(1, f.reclaim(destination, () -> true).moved()); assertEquals(1, sawMarked.get());
		assertSame(actual, destination.getItem(0)); assertEquals(expected.save(registries), actual.save(registries));
		assertTrue(f.recovery.inspect(f.actor).isEmpty()); assertTrue(f.recovery.isDirty());
		assertEquals(0, f.reclaim(destination, () -> true).moved()); assertEquals(1, destination.countItem(Items.SHEARS));
	}

	@Test void aReplacedRecordCannotBecomeAuthorityForTheOldSource() {
		for (int boundary : List.of(1, 2)) {
			Fixture f = new Fixture(); f.captureLoans(1); SimpleContainer ordinaryRecipient = new SimpleContainer(36);
			CompoundTag original = stacks(save(f.recovery)).getCompound(0).copy();
			CompoundTag[] replacement = { null };
			SimpleContainer reclaimRecipient = new SimpleContainer(36); AtomicInteger checks = new AtomicInteger();
			var result = f.reclaim(reclaimRecipient, () -> {
				if (checks.incrementAndGet() == boundary) {
					assertEquals(1, f.recovery.recover(f.actor, ordinaryRecipient, () -> true).moved());
					f.bag.setItem(0, f.loan()); assertEquals(1, f.capture());
					replacement[0] = save(f.recovery); f.recovery.setDirty(false);
				}
				return true;
			});
			assertEquals(SettlementCargoRecovery.Status.NOT_AT_PLACE, result.status());
			assertEquals(0, result.moved()); assertEquals(boundary, checks.get());
			assertTrue(reclaimRecipient.isEmpty()); assertTrue(SettlementShearsLoan.isMarked(ordinaryRecipient.getItem(0)));
			assertEquals(original, ordinaryRecipient.getItem(0).save(registries));
			assertEquals(replacement[0], save(f.recovery)); assertFalse(f.recovery.isDirty());
			assertEquals(1, f.recovery.reclaimableShears(f.actor, DIMENSION).getFirst().count());
		}
	}

	@Test void oldRecordsNeverInferDeathCampFromAnOtherwiseValidLoanEvenAfterMigration() {
		for (int version : List.of(1, 2, 3)) {
			Fixture f = new Fixture(); f.captureLoans(1); CompoundTag old = save(f.recovery);
			old.putInt("DataVersion", version); entry(old).remove("DeathSettlement");
			f.recovery = SettlementCargoRecovery.load(old, registries);
			assertTrue(f.recovery.available()); assertTrue(f.recovery.reclaimableShears(f.actor, DIMENSION).isEmpty());
			SimpleContainer destination = new SimpleContainer(36);
			assertEquals(SettlementCargoRecovery.Status.INVALID, f.reclaim(destination, () -> true).status());
			assertTrue(destination.isEmpty()); assertFalse(f.recovery.isDirty());
			CompoundTag migrated = save(f.recovery); assertEquals(3, migrated.getInt("DataVersion"));
			assertFalse(entry(migrated).contains("DeathSettlement")); assertEquals(old.get("Records"), migrated.get("Records"));
			f.recovery = SettlementCargoRecovery.load(migrated, registries);
			assertTrue(f.recovery.reclaimableShears(f.actor, DIMENSION).isEmpty());
			assertEquals(1, f.recovery.recover(f.actor, destination, () -> true).moved());
			assertTrue(SettlementShearsLoan.isMarked(destination.getItem(0)));
		}
	}

	@Test void foreignCampLoanDoesNotProveOwnershipAndNormalRecoverKeepsEveryMarkerRaw() {
		Fixture f = new Fixture(); ItemStack foreign = alter(f.loan(), stamp -> stamp.putUUID("Settlement", FOREIGN));
		f.bag.setItem(0, foreign); assertEquals(1, f.capture());
		f.recovery = SettlementCargoRecovery.load(save(f.recovery), registries);
		CompoundTag before = save(f.recovery); SimpleContainer destination = new SimpleContainer(36);
		assertTrue(SettlementShearsLoan.isRecognized(foreign)); assertTrue(f.recovery.reclaimableShears(f.actor, DIMENSION).isEmpty());
		assertEquals(SettlementCargoRecovery.Status.INVALID, f.reclaim(destination, () -> true).status());
		assertEquals(SettlementCargoRecovery.Status.INVALID,
			f.recovery.reclaimShears(f.actor, DIMENSION, FOREIGN, destination, () -> true).status());
		assertEquals(before, save(f.recovery)); assertEquals(1, f.recovery.recover(f.actor, destination, () -> true).moved());
		assertEquals(foreign.save(registries), destination.getItem(0).save(registries));
		assertEquals(0, f.reclaim(destination, () -> true).moved());
	}

	@Test void unmarkedStoredToolsRemainUnchangedAndAreNotRetroactivelyLabeled() {
		Fixture f = new Fixture(); f.captureLoans(1); CompoundTag stored = save(f.recovery);
		ItemStack ordinary = decorated(); stacks(stored).add(ordinary.save(registries));
		f.recovery = SettlementCargoRecovery.load(stored, registries); SimpleContainer destination = new SimpleContainer(36);
		assertEquals(1, f.reclaim(destination, () -> true).moved());
		assertEquals(ordinary.save(registries), stacks(save(f.recovery)).get(0));
		assertTrue(f.recovery.reclaimableShears(f.actor, DIMENSION).isEmpty());
	}

	@Test void reclaimPreservesAbsentAndExplicitlyEmptyCustomDataAndNeverMergesStackableTools() {
		ItemStack emptyCustom = new ItemStack(Items.SHEARS);
		emptyCustom.set(DataComponents.CUSTOM_DATA, CustomData.of(new CompoundTag()));
		ItemStack stackable = new ItemStack(Items.SHEARS);
		stackable.remove(DataComponents.MAX_DAMAGE); stackable.remove(DataComponents.DAMAGE);
		stackable.set(DataComponents.MAX_STACK_SIZE, 64);
		for (ItemStack ordinary : List.of(new ItemStack(Items.SHEARS), emptyCustom, stackable)) {
			Fixture f = new Fixture(); f.bag.setItem(0, f.loan(ordinary)); assertEquals(1, f.capture());
			f.recovery = SettlementCargoRecovery.load(save(f.recovery), registries);
			SimpleContainer destination = new SimpleContainer(ordinary.copy(), ItemStack.EMPTY);
			assertEquals(1, f.reclaim(destination, () -> true).moved());
			assertEquals(1, destination.getItem(0).getCount()); assertEquals(1, destination.getItem(1).getCount());
			assertEquals(ordinary.getComponentsPatch(), destination.getItem(1).getComponentsPatch());
			assertEquals(ordinary.save(registries), destination.getItem(1).save(registries));
		}
	}

	@Test void futureMalformedAndLegacyEvidenceFieldsArePreservedWithoutAnyReclaimAccess() {
		Fixture f = new Fixture(); f.captureLoans(1); CompoundTag valid = save(f.recovery);
		for (int variant = 0; variant < 5; variant++) {
			CompoundTag bad = valid.copy();
			if (variant == 0) bad.putInt("DataVersion", 4);
			if (variant == 1) entry(bad).putString("DeathSettlement", f.camp.id().toString());
			if (variant == 2) entry(bad).putIntArray("DeathSettlement", new int[] { 1, 2, 3 });
			if (variant == 3) bad.putInt("DataVersion", 1);
			if (variant == 4) bad.putInt("DataVersion", 2);
			SettlementCargoRecovery blocked = SettlementCargoRecovery.load(bad, registries);
			assertFalse(blocked.available()); assertTrue(blocked.reclaimableShears(f.actor, DIMENSION).isEmpty());
			assertEquals(SettlementCargoRecovery.Status.UNAVAILABLE,
				blocked.reclaimShears(f.actor, DIMENSION, f.camp.id(), new SimpleContainer(36), () -> {
					fail("Unreadable records cannot consult authority or inventory"); return true;
				}).status());
			assertFalse(blocked.isDirty()); assertEquals(bad, save(blocked));
			CompoundTag detached = save(blocked); detached.remove("Records"); assertEquals(bad, save(blocked));
		}
	}
}
