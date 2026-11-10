package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettlementCargoRecoveryTest {
	private static final BlockPos PLACE = new BlockPos(12, 64, 30);
	private static HolderLookup.Provider registries;

	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}

	private static SettlementMembershipClaims.Resolution member(UUID dead) {
		SettlementSavedData memberships = new SettlementSavedData();
		Settlement camp = memberships.getOrCreate(PLACE, "Camp", 0);
		memberships.registerMember(camp, dead, 0);
		return SettlementMembershipClaims.resolve(List.of(
			new SettlementMembershipClaims.Source(Level.OVERWORLD, memberships)), Level.OVERWORLD, dead);
	}

	private static ItemStack cargo(int count, String label) {
		ItemStack stack = new ItemStack(Items.COBBLESTONE, count);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(label));
		CompoundTag payload = new CompoundTag();
		payload.putString("cargo_test", label);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(payload));
		return stack;
	}

	private static int capture(SettlementCargoRecovery data, UUID dead, Container source) {
		return data.captureDeath(member(dead), Level.OVERWORLD, dead, PLACE, "Resident", () -> source);
	}

	@Test void ordinaryFoodCargoAndPersonalRemainderSurviveSaveAndPartialRecovery() {
		UUID dead = new UUID(57, 1);
		SimpleContainer source = new SimpleContainer(8);
		ItemStack named = new ItemStack(Items.SWEET_BERRIES, 3);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Harvest"));
		source.setItem(0, named.copy());
		source.setItem(1, new ItemStack(Items.POTATO, 4));
		source.setItem(2, new ItemStack(Items.BREAD, 2));
		var data = new SettlementCargoRecovery();
		assertEquals(7, capture(data, dead, source));
		assertEquals(2, source.countItem(Items.BREAD));
		assertEquals(0, source.countItem(Items.POTATO));
		assertEquals(0, source.countItem(Items.SWEET_BERRIES));
		var loaded = SettlementCargoRecovery.load(data.save(new CompoundTag(), registries), registries);
		SimpleContainer recipient = new SimpleContainer(1);
		assertEquals(3, loaded.recover(dead, recipient, () -> true).moved());
		assertTrue(ItemStack.isSameItemSameComponents(named, recipient.getItem(0)));
		assertEquals(4, loaded.inspect(dead).orElseThrow().totalCount());
		recipient.clearContent();
		assertEquals(4, loaded.recover(dead, recipient, () -> true).moved());
		assertEquals(0, loaded.recover(dead, recipient, () -> true).moved());
	}

	@Test void survivalDeathPreservesPhysicalPotatoesButKeepsOtherOrdinaryFoodOutsideCustody() {
		UUID dead = new UUID(57, 2);
		var claims = member(dead);
		var claim = claims.uniqueClaim().orElseThrow();
		var simulation = claim.data().getOrCreateSimulation(claim.settlement());
		simulation.mapResident(dead, false, 0);
		simulation.setSurvivalEnabled(true, 0);
		SimpleContainer source = new SimpleContainer(new ItemStack(Items.POTATO, 8),
			new ItemStack(Items.SWEET_BERRIES, 9), new ItemStack(Items.COBBLESTONE, 2));
		var before = simulation.save();
		var data = new SettlementCargoRecovery();
		assertEquals(10, data.captureDeath(claims, Level.OVERWORLD, dead, PLACE, "Resident", () -> source));
		assertEquals(0, source.countItem(Items.POTATO));
		assertEquals(9, source.countItem(Items.SWEET_BERRIES));
		assertEquals(before, simulation.save());
	}

	@Test void allPotatoKindsUseRealCustodyInBothModesWithoutTouchingSimulationOrOtherFood() {
		for (boolean survival : List.of(false, true)) {
			UUID dead = new UUID(62, survival ? 1 : 2);
			var claims = member(dead); var claim = claims.uniqueClaim().orElseThrow();
			var simulation = claim.data().getOrCreateSimulation(claim.settlement());
			simulation.mapResident(dead, false, 0); simulation.setSurvivalEnabled(survival, 0);
			simulation.setProductionEnabled(true);
			ItemStack custom = new ItemStack(Items.POTATO, 3);
			custom.set(DataComponents.CUSTOM_NAME, Component.literal("Existing personal potatoes"));
			ItemStack poison = new ItemStack(Items.POISONOUS_POTATO, 2);
			CompoundTag payload = new CompoundTag(); payload.putString("byproduct_test", "unchanged");
			poison.set(DataComponents.CUSTOM_DATA, CustomData.of(payload));
			SimpleContainer source = new SimpleContainer(custom.copy(), poison.copy(), new ItemStack(Items.POTATO, 4),
				new ItemStack(Items.SWEET_BERRIES, 1), new ItemStack(Items.BREAD, 2), new ItemStack(Items.WOODEN_PICKAXE));
			var before = simulation.save(); var recovery = new SettlementCargoRecovery();
			assertEquals(survival ? 9 : 10,
				recovery.captureDeath(claims, Level.OVERWORLD, dead, PLACE, "Resident", () -> source));
			assertEquals(0, recovery.captureDeath(claims, Level.OVERWORLD, dead, PLACE, "Resident", () -> source));
			assertEquals(0, source.countItem(Items.POTATO)); assertEquals(0, source.countItem(Items.POISONOUS_POTATO));
			assertEquals(survival ? 1 : 0, source.countItem(Items.SWEET_BERRIES));
			assertEquals(2, source.countItem(Items.BREAD)); assertEquals(1, source.countItem(Items.WOODEN_PICKAXE));
			CompoundTag saved = recovery.save(new CompoundTag(), registries);
			assertEquals(3, saved.getInt("DataVersion"));
			recovery = SettlementCargoRecovery.load(saved, registries);
			assertEquals(saved, recovery.save(new CompoundTag(), registries));
			SimpleContainer full = new SimpleContainer(new ItemStack(Items.DIRT, 64));
			assertEquals(0, recovery.recover(dead, full, () -> true).moved());
			assertEquals(saved, recovery.save(new CompoundTag(), registries));
			SimpleContainer destination = new SimpleContainer(1);
			assertEquals(3, recovery.recover(dead, destination, () -> true).moved());
			assertTrue(ItemStack.matches(custom, destination.getItem(0)));
			recovery = SettlementCargoRecovery.load(recovery.save(new CompoundTag(), registries), registries);
			destination.clearContent();
			assertEquals(2, recovery.recover(dead, destination, () -> true).moved());
			assertTrue(ItemStack.matches(poison, destination.getItem(0)));
			destination.clearContent();
			assertEquals(4, recovery.recover(dead, destination, () -> true).moved());
			assertEquals(4, destination.countItem(Items.POTATO));
			assertFalse(SettlementFoodAge.isMarked(destination.getItem(0)));
			destination.clearContent();
			assertEquals(survival ? 0 : 1, recovery.recover(dead, destination, () -> true).moved());
			assertEquals(0, recovery.recover(dead, destination, () -> true).moved());
			assertTrue(recovery.inspect(dead).isEmpty()); assertEquals(before, simulation.save());
		}
	}

	@Test void potatoCustodyRecordsOnlyWhatTheSourceActuallyRemoves() {
		UUID dead = new UUID(62, 3);
		SimpleContainer source = new SimpleContainer(new ItemStack(Items.POISONOUS_POTATO, 5)) {
			@Override public ItemStack removeItem(int slot, int count) { return super.removeItem(slot, Math.min(count, 2)); }
		};
		var recovery = new SettlementCargoRecovery();
		assertEquals(2, capture(recovery, dead, source));
		assertEquals(3, source.countItem(Items.POISONOUS_POTATO));
		assertEquals(2, recovery.inspect(dead).orElseThrow().totalCount());
		assertEquals(0, capture(recovery, dead, source));
		SimpleContainer destination = new SimpleContainer(1);
		assertEquals(2, recovery.recover(dead, destination, () -> true).moved());
		assertEquals(5, source.countItem(Items.POISONOUS_POTATO) + destination.countItem(Items.POISONOUS_POTATO));
	}

	@Test void movesActualMaterialSlotsAndPreservesAllOtherInventory() {
		SettlementCargoRecovery data = new SettlementCargoRecovery();
		UUID dead = UUID.randomUUID();
		SimpleContainer source = new SimpleContainer(cargo(12, "A"), cargo(9, "B"),
			new ItemStack(Items.STICK, 3), new ItemStack(Items.SMOOTH_STONE, 2),
			new ItemStack(Items.BREAD, 5), new ItemStack(Items.IRON_AXE), new ItemStack(Items.EMERALD, 7));
		assertEquals(26, capture(data, dead, source));
		for (int slot = 0; slot < 4; slot++) assertTrue(source.getItem(slot).isEmpty());
		assertEquals(5, source.getItem(4).getCount());
		assertTrue(source.getItem(5).is(Items.IRON_AXE));
		assertEquals(7, source.getItem(6).getCount());
		assertEquals(0, capture(data, dead, source));
		var snapshot = data.inspect(dead).orElseThrow();
		assertEquals(26, snapshot.totalCount());
		assertEquals(3, snapshot.items().size());
		assertThrows(UnsupportedOperationException.class, () -> snapshot.items().clear());
		SimpleContainer player = new SimpleContainer(8);
		assertEquals(26, data.recover(dead, player, () -> true).moved());
		assertTrue(ItemStack.isSameItemSameComponents(cargo(12, "A"), player.getItem(0)));
		assertTrue(ItemStack.isSameItemSameComponents(cargo(9, "B"), player.getItem(1)));
		assertEquals(12, player.getItem(0).getCount());
		assertEquals(9, player.getItem(1).getCount());
		assertTrue(data.inspect(dead).isEmpty());
		assertEquals(0, data.recover(dead, player, () -> true).moved());
		assertEquals(0, capture(data, dead, source));
		assertTrue(data.nearby(PLACE, 3, 10).isEmpty());
	}

	@Test void creditsOnlyWhatSourceActuallyRemoved() {
		SettlementCargoRecovery data = new SettlementCargoRecovery();
		UUID dead = UUID.randomUUID();
		SimpleContainer source = new SimpleContainer(cargo(10, "A")) {
			@Override public ItemStack removeItem(int slot, int amount) { return super.removeItem(slot, Math.min(amount, 2)); }
		};
		assertEquals(2, capture(data, dead, source));
		assertEquals(8, source.getItem(0).getCount());
		assertEquals(2, data.inspect(dead).orElseThrow().totalCount());
	}

	@Test void nonmembersUnknownConflictsOrOtherDimensionsNeverReadInventory() {
		UUID dead = UUID.randomUUID();
		SettlementCargoRecovery data = new SettlementCargoRecovery();
		AtomicInteger reads = new AtomicInteger();
		for (var status : SettlementMembershipClaims.Status.values()) {
			if (status == SettlementMembershipClaims.Status.UNIQUE) continue;
			assertEquals(0, data.captureDeath(new SettlementMembershipClaims.Resolution(status, List.of()),
				Level.OVERWORLD, dead, PLACE, "Resident", () -> { reads.incrementAndGet(); return new SimpleContainer(8); }));
		}
		assertEquals(0, data.captureDeath(member(dead), Level.NETHER, dead, PLACE, "Resident", () -> {
			reads.incrementAndGet(); return new SimpleContainer(8);
		}));
		assertEquals(0, reads.get());
		SimpleContainer ordinaryVillager = new SimpleContainer(cargo(4, "ordinary"));
		var none = SettlementMembershipClaims.resolve(List.of(new SettlementMembershipClaims.Source(
			Level.OVERWORLD, new SettlementSavedData())), Level.OVERWORLD, dead);
		assertEquals(0, data.captureDeath(none, Level.OVERWORLD, dead, PLACE, "Resident", () -> ordinaryVillager));
		assertEquals(4, ordinaryVillager.getItem(0).getCount());
	}

	@Test void deathEventContractDoesNotRequireLiveEntityLookupOrNonremovedEntity() {
		// AFTER_DEATH may supply a discarded original during zombie infection. This pure boundary only
		// requires its previously resolved raw claim and retained inventory; no entity/liveness input exists.
		UUID discardedOriginal = UUID.randomUUID();
		SettlementCargoRecovery data = new SettlementCargoRecovery();
		SimpleContainer retainedInventory = new SimpleContainer(cargo(4, "discarded"));
		assertEquals(4, data.captureDeath(member(discardedOriginal), Level.OVERWORLD, discardedOriginal,
			PLACE, "discarded", () -> retainedInventory));
		assertTrue(retainedInventory.getItem(0).isEmpty());
	}

	@Test void fullAndPartialRecoveryRetainRemainderAndCheckPresenceBeforeReads() {
		SettlementCargoRecovery data = new SettlementCargoRecovery();
		UUID dead = UUID.randomUUID();
		capture(data, dead, new SimpleContainer(cargo(5, "A")));
		SimpleContainer unreadable = new SimpleContainer(1) {
			@Override public int getContainerSize() { throw new AssertionError("inventory read before arrival"); }
			@Override public ItemStack getItem(int slot) { throw new AssertionError("inventory read before arrival"); }
		};
		assertEquals(SettlementCargoRecovery.Status.NOT_AT_PLACE, data.recover(dead, unreadable, () -> false).status());
		SimpleContainer full = new SimpleContainer(new ItemStack(Items.DIRT, 64));
		assertEquals(SettlementCargoRecovery.Status.NO_CAPACITY, data.recover(dead, full, () -> true).status());
		SimpleContainer partial = new SimpleContainer(cargo(63, "A"));
		var result = data.recover(dead, partial, () -> true);
		assertEquals(1, result.moved());
		assertEquals(4, result.remaining());
		assertEquals(64, partial.getItem(0).getCount());
		assertEquals(0, data.recover(dead, partial, () -> true).moved());
		assertEquals(4, data.inspect(dead).orElseThrow().totalCount());
	}

	@Test void normalNbtRoundTripPreservesComponentsAndPartialCustodyWithoutDuplication() {
		UUID dead = UUID.randomUUID();
		SettlementCargoRecovery data = new SettlementCargoRecovery();
		SimpleContainer source = new SimpleContainer(cargo(5, "A"), cargo(7, "B"));
		capture(data, dead, source);
		SimpleContainer firstPlayer = new SimpleContainer(cargo(63, "A"));
		assertEquals(1, data.recover(dead, firstPlayer, () -> true).moved());
		CompoundTag saved = data.save(new CompoundTag(), registries);
		SettlementCargoRecovery restored = SettlementCargoRecovery.load(saved, registries);
		assertEquals(11, restored.inspect(dead).orElseThrow().totalCount());
		assertEquals(0, capture(restored, dead, source));
		SimpleContainer secondPlayer = new SimpleContainer(8);
		assertEquals(11, restored.recover(dead, secondPlayer, () -> true).moved());
		assertEquals(4, secondPlayer.getItem(0).getCount());
		assertEquals(7, secondPlayer.getItem(1).getCount());
		assertTrue(ItemStack.isSameItemSameComponents(cargo(4, "A"), secondPlayer.getItem(0)));
		assertTrue(ItemStack.isSameItemSameComponents(cargo(7, "B"), secondPlayer.getItem(1)));
		SettlementCargoRecovery empty = SettlementCargoRecovery.load(restored.save(new CompoundTag(), registries), registries);
		assertTrue(empty.inspect(dead).isEmpty());
		assertEquals(0, empty.recover(dead, secondPlayer, () -> true).moved());
		assertEquals(0, capture(empty, dead, source));
	}

	@Test void snapshotsRespectRadiusLimitAndBoundNameWithoutMutableStackExposure() {
		SettlementCargoRecovery data = new SettlementCargoRecovery();
		UUID dead = UUID.randomUUID();
		data.captureDeath(member(dead), Level.OVERWORLD, dead, PLACE, "\n§" + "x".repeat(200),
			() -> new SimpleContainer(cargo(1, "A")));
		assertEquals(80, data.inspect(dead).orElseThrow().name().length());
		assertEquals(1, data.nearby(PLACE.offset(3, 0, 0), 3, 1).size());
		assertTrue(data.nearby(PLACE.offset(4, 0, 0), 3, 10).isEmpty());
		assertTrue(data.nearby(PLACE, 3, 0).isEmpty());
		assertTrue(data.nearby(PLACE, Double.NaN, 10).isEmpty());
	}
	@Test void unknownVersionPreservesOriginalAndRejectsAllInventoryAccess() {
		UUID dead = UUID.randomUUID();
		SettlementCargoRecovery original = new SettlementCargoRecovery();
		capture(original, dead, new SimpleContainer(cargo(3, "A")));
		CompoundTag future = original.save(new CompoundTag(), registries);
		future.putInt("DataVersion", 99);
		future.putString("FutureField", "must survive");
		assertUnavailablePreserved(future, dead);
	}

	@Test void duplicateRecordsOversizeStacksAndMalformedStackArePreservedFailClosed() {
		UUID dead = UUID.randomUUID();
		SettlementCargoRecovery original = new SettlementCargoRecovery();
		capture(original, dead, new SimpleContainer(cargo(3, "A")));
		CompoundTag valid = original.save(new CompoundTag(), registries);
		CompoundTag duplicate = valid.copy();
		var records = duplicate.getList("Records", net.minecraft.nbt.Tag.TAG_COMPOUND);
		records.add(records.getCompound(0).copy());
		assertUnavailablePreserved(duplicate, dead);
		CompoundTag oversized = valid.copy();
		var stacks = oversized.getList("Records", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0)
			.getList("Stacks", net.minecraft.nbt.Tag.TAG_COMPOUND);
		while (stacks.size() <= 9) stacks.add(stacks.getCompound(0).copy());
		assertUnavailablePreserved(oversized, dead);
		CompoundTag malformed = valid.copy();
		malformed.getList("Records", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0)
			.getList("Stacks", net.minecraft.nbt.Tag.TAG_COMPOUND).set(0, new CompoundTag());
		assertUnavailablePreserved(malformed, dead);
	}

	private static void assertUnavailablePreserved(CompoundTag original, UUID dead) {
		SettlementCargoRecovery restored = SettlementCargoRecovery.load(original, registries);
		assertFalse(restored.available());
		SimpleContainer source = new SimpleContainer(cargo(9, "untouched"));
		assertEquals(0, restored.captureDeath(member(dead), Level.OVERWORLD, dead, PLACE, "Resident", () -> {
			fail("unavailable storage must not even obtain source inventory"); return source;
		}));
		assertEquals(9, source.getItem(0).getCount());
		assertEquals(SettlementCargoRecovery.Status.UNAVAILABLE, restored.recover(dead, source, () -> {
			fail("unavailable storage must not invoke destination closure"); return true;
		}).status());
		assertEquals(original, restored.save(new CompoundTag(), registries));
		CompoundTag detached = restored.save(new CompoundTag(), registries);
		detached.remove("Records");
		assertEquals(original, restored.save(new CompoundTag(), registries));
	}

	@Test void strandedSelectsOnlyOutOfBoundsCargoInStableOrderAndPreservesOriginalPositions() {
		SettlementCargoRecovery data = new SettlementCargoRecovery();
		UUID first = new UUID(0, 1), second = new UUID(0, 2), normal = new UUID(0, 3);
		BlockPos voidDeath = new BlockPos(12, -200, 30);
		BlockPos borderDeath = new BlockPos(400, 64, 30);
		data.captureDeath(member(second), Level.OVERWORLD, second, borderDeath, "border", () -> new SimpleContainer(cargo(2, "B")));
		data.captureDeath(member(first), Level.OVERWORLD, first, voidDeath, "void", () -> new SimpleContainer(cargo(3, "A")));
		data.captureDeath(member(normal), Level.OVERWORLD, normal, PLACE, "normal", () -> new SimpleContainer(cargo(4, "C")));
		java.util.function.Predicate<BlockPos> inBounds = pos -> pos.getY() >= -64 && pos.getY() < 320
			&& Math.abs(pos.getX()) < 100 && Math.abs(pos.getZ()) < 100;
		CompoundTag before = data.save(new CompoundTag(), registries);
		assertEquals(List.of(first, second), data.stranded(inBounds, 8).stream().map(SettlementCargoRecovery.Snapshot::recordId).toList());
		assertEquals(List.of(first), data.stranded(inBounds, 1).stream().map(SettlementCargoRecovery.Snapshot::recordId).toList());
		assertEquals(voidDeath, data.inspect(first).orElseThrow().position());
		assertEquals(borderDeath, data.inspect(second).orElseThrow().position());
		assertEquals(before, data.save(new CompoundTag(), registries));
		SettlementCargoRecovery restored = SettlementCargoRecovery.load(before, registries);
		assertEquals(voidDeath, restored.inspect(first).orElseThrow().position());
		assertEquals(borderDeath, restored.inspect(second).orElseThrow().position());
		assertEquals(3, restored.recover(first, new SimpleContainer(8), () -> true).moved());
		assertEquals(List.of(second), restored.stranded(inBounds, 8).stream().map(SettlementCargoRecovery.Snapshot::recordId).toList());
		assertTrue(restored.stranded(pos -> true, 8).isEmpty());
	}

	@Test void strandedReadsOnlyStoredPositionsWithoutReopeningMaterialInventory() {
		SettlementCargoRecovery data = new SettlementCargoRecovery();
		UUID dead = UUID.randomUUID();
		boolean[] captured = { false };
		SimpleContainer source = new SimpleContainer(cargo(5, "A")) {
			@Override public ItemStack getItem(int slot) {
				if (captured[0]) throw new AssertionError("stranded must not reopen source inventory");
				return super.getItem(slot);
			}
		};
		capture(data, dead, source);
		captured[0] = true;
		AtomicInteger checks = new AtomicInteger();
		assertEquals(5, data.stranded(pos -> {
			checks.incrementAndGet(); assertEquals(PLACE, pos); return false;
		}, 8).getFirst().totalCount());
		assertEquals(1, checks.get());
		assertTrue(data.stranded(pos -> { fail("zero limit must not evaluate bounds"); return false; }, 0).isEmpty());
		assertTrue(data.stranded(null, 8).isEmpty());
		CompoundTag future = data.save(new CompoundTag(), registries);
		future.putInt("DataVersion", 99);
		SettlementCargoRecovery unavailable = SettlementCargoRecovery.load(future, registries);
		assertTrue(unavailable.stranded(pos -> { fail("unavailable storage must not evaluate bounds"); return false; }, 8).isEmpty());
	}

}
