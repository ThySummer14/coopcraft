package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConstructionSupplyRecoveryTest {
	private static final BlockPos PLACE = new BlockPos(12, 64, 30);
	private static HolderLookup.Provider registries;

	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}

	private record Fixture(UUID actor, UUID camp, SettlementMembershipClaims.Resolution claims,
		SettlementSimulationState simulation, ConstructionJob job, ConstructionInstruction instruction) { }

	private static Fixture fixture(Item item, boolean survival) {
		UUID actor = UUID.randomUUID();
		SettlementSavedData memberships = new SettlementSavedData();
		Settlement camp = memberships.getOrCreate(PLACE, "Camp", 0);
		memberships.registerMember(camp, actor, 0);
		var simulation = memberships.getOrCreateSimulation(camp);
		simulation.mapResident(actor, false, 0);
		simulation.setSurvivalEnabled(survival, 0);
		var claims = SettlementMembershipClaims.resolve(List.of(
			new SettlementMembershipClaims.Source(Level.OVERWORLD, memberships)), Level.OVERWORLD, actor);
		var instruction = new ConstructionInstruction(BlockPos.ZERO,
			(item == Items.OAK_DOOR ? Blocks.OAK_DOOR : Blocks.WHITE_BED).defaultBlockState(), item, true);
		var job = new ConstructionJob(ResourceLocation.parse("coopcraft:recovery_test"), PLACE, 0, "recovery-plan-v1");
		return new Fixture(actor, camp.id(), claims, simulation, job, instruction);
	}

	private static ItemStack pickup(Fixture fixture, SimpleContainer bag) {
		SimpleContainer stockpile = new SimpleContainer(new ItemStack(fixture.instruction().item()));
		assertTrue(ConstructionSupply.pickup(stockpile, bag, Level.OVERWORLD.location(), fixture.camp(),
			fixture.actor(), fixture.job(), fixture.instruction(), () -> true));
		assertTrue(stockpile.isEmpty());
		assertEquals(8, bag.getContainerSize());
		assertEquals(1, bag.getItem(0).getCount());
		assertTrue(matches(fixture, bag.getItem(0)));
		return bag.getItem(0).copy();
	}

	private static boolean matches(Fixture fixture, ItemStack stack) {
		return ConstructionSupply.matches(stack, Level.OVERWORLD.location(), fixture.camp(),
			fixture.actor(), fixture.job(), fixture.instruction());
	}

	private static int capture(Fixture fixture, SettlementCargoRecovery recovery, SimpleContainer bag) {
		return recovery.captureDeath(fixture.claims(), Level.OVERWORLD, fixture.actor(), PLACE, "Builder", () -> bag);
	}

	private static SettlementCargoRecovery roundTrip(SettlementCargoRecovery recovery) {
		CompoundTag saved = recovery.save(new CompoundTag(), registries);
		SettlementCargoRecovery loaded = SettlementCargoRecovery.load(saved, registries);
		assertTrue(loaded.available());
		assertEquals(saved, loaded.save(new CompoundTag(), registries));
		return loaded;
	}

	@Test void actualDoorAndBedPickupSurviveDeathSavePartialFullAndRepeatedRecovery() {
		for (Item item : List.of(Items.OAK_DOOR, Items.WHITE_BED)) {
			for (boolean survival : List.of(false, true)) {
				Fixture fixture = fixture(item, survival);
				SimpleContainer bag = new SimpleContainer(8);
				ItemStack paid = pickup(fixture, bag);
				bag.setItem(1, new ItemStack(Items.COBBLESTONE, 3));
				bag.setItem(2, new ItemStack(Items.OAK_DOOR, 2));
				bag.setItem(3, new ItemStack(Items.WHITE_BED));
				CompoundTag simulationBefore = fixture.simulation().save();
				var recovery = new SettlementCargoRecovery();
				assertEquals(4, capture(fixture, recovery, bag));
				assertTrue(bag.getItem(0).isEmpty());
				assertTrue(bag.getItem(1).isEmpty());
				assertEquals(2, bag.getItem(2).getCount());
				assertEquals(1, bag.getItem(3).getCount());
				assertEquals(0, capture(fixture, recovery, bag));
				recovery = roundTrip(recovery);
				SimpleContainer fullPlayer = new SimpleContainer(new ItemStack(Items.DIRT, 64));
				CompoundTag beforeBlockedPickup = recovery.save(new CompoundTag(), registries);
				assertEquals(SettlementCargoRecovery.Status.NO_CAPACITY,
					recovery.recover(fixture.actor(), fullPlayer, () -> true).status());
				assertEquals(beforeBlockedPickup, recovery.save(new CompoundTag(), registries));
				SimpleContainer player = new SimpleContainer(1);
				var partial = recovery.recover(fixture.actor(), player, () -> true);
				assertEquals(1, partial.moved());
				assertEquals(3, partial.remaining());
				assertTrue(ItemStack.matches(paid, player.getItem(0)));
				assertTrue(ConstructionSupply.isMarked(player.getItem(0)));
				assertTrue(matches(fixture, player.getItem(0)), "Recovery must not rewrite the original paid identity");
				assertEquals(0, ConstructionMaterials.available(player, fixture.simulation(), item));
				assertEquals(0, recovery.recover(fixture.actor(), player, () -> true).moved());
				recovery = roundTrip(recovery);
				SimpleContainer otherPlayer = new SimpleContainer(8);
				assertEquals(3, recovery.recover(fixture.actor(), otherPlayer, () -> true).moved());
				assertEquals(3, otherPlayer.countItem(Items.COBBLESTONE));
				recovery = roundTrip(recovery);
				assertTrue(recovery.inspect(fixture.actor()).isEmpty());
				assertEquals(0, recovery.recover(fixture.actor(), otherPlayer, () -> true).moved());
				assertEquals(0, capture(fixture, recovery, bag));
				assertEquals(simulationBefore, fixture.simulation().save());
			}
		}
	}

	@Test void unknownMalformedAndUnsupportedMarkedItemsRemainQuarantinedAfterRecovery() {
		for (boolean survival : List.of(false, true)) {
			for (int variant = 0; variant < 4; variant++) {
				Fixture fixture = fixture(Items.OAK_DOOR, survival);
				SimpleContainer bag = new SimpleContainer(8);
				ItemStack original = pickup(fixture, bag);
				CompoundTag root = original.get(DataComponents.CUSTOM_DATA).copyTag();
				switch (variant) {
					case 0 -> root.getCompound(ConstructionSupply.KEY).putInt("Version", 99);
					case 1 -> root.putString(ConstructionSupply.KEY, "unknown malformed marker");
					case 2 -> original = new ItemStack(Items.EMERALD, 3);
					case 3 -> original.setCount(3);
					default -> throw new AssertionError("Unexpected variant");
				}
				original.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
				if (variant == 2) original.set(DataComponents.CUSTOM_NAME, Component.literal("Retain unsupported cargo"));
				bag.setItem(0, original.copy());
				assertTrue(ConstructionSupply.isMarked(original));
				assertFalse(matches(fixture, original));
				var recovery = new SettlementCargoRecovery();
				assertEquals(original.getCount(), capture(fixture, recovery, bag));
				assertTrue(bag.isEmpty());
				recovery = roundTrip(recovery);
				SimpleContainer player = new SimpleContainer(8);
				assertEquals(original.getCount(), recovery.recover(fixture.actor(), player, () -> true).moved());
				assertTrue(ItemStack.matches(original, player.getItem(0)));
				assertTrue(ConstructionSupply.isMarked(player.getItem(0)));
				assertFalse(ConstructionSupply.hasFor(player, Level.OVERWORLD.location(), fixture.camp(),
					fixture.actor(), fixture.job(), fixture.instruction()));
				assertFalse(ConstructionSupply.place(player, Level.OVERWORLD.location(), fixture.camp(), fixture.actor(),
					fixture.job(), fixture.instruction(), () -> true, () -> { fail("Malformed supply must not place"); return true; }));
				assertEquals(0, ConstructionSupply.returnUnused(player, new SimpleContainer(8), Level.OVERWORLD.location(),
					fixture.camp(), fixture.actor(), null, () -> true));
				assertTrue(ItemStack.matches(original, player.getItem(0)));
				assertEquals(0, recovery.recover(fixture.actor(), player, () -> true).moved());
				assertEquals(0, capture(fixture, recovery, bag));
			}
		}
	}

	@Test void partialStackRecoveryPreservesUnknownMarkerAndExactRemainderAcrossReload() {
		Fixture fixture = fixture(Items.OAK_DOOR, true);
		SimpleContainer bag = new SimpleContainer(8);
		ItemStack unknown = pickup(fixture, bag);
		CompoundTag root = unknown.get(DataComponents.CUSTOM_DATA).copyTag();
		root.getCompound(ConstructionSupply.KEY).putInt("Version", 99);
		unknown.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		unknown.setCount(5);
		bag.setItem(0, unknown.copy());
		var recovery = new SettlementCargoRecovery();
		assertEquals(5, capture(fixture, recovery, bag));
		ItemStack almostFull = unknown.copy();
		almostFull.setCount(63);
		SimpleContainer player = new SimpleContainer(almostFull);
		var partial = recovery.recover(fixture.actor(), player, () -> true);
		assertEquals(1, partial.moved());
		assertEquals(4, partial.remaining());
		assertEquals(64, player.getItem(0).getCount());
		assertTrue(ItemStack.isSameItemSameComponents(unknown, player.getItem(0)));
		recovery = roundTrip(recovery);
		assertEquals(0, recovery.recover(fixture.actor(), player, () -> true).moved());
		SimpleContainer nextPlayer = new SimpleContainer(1);
		assertEquals(4, recovery.recover(fixture.actor(), nextPlayer, () -> true).moved());
		ItemStack remainder = unknown.copy();
		remainder.setCount(4);
		assertTrue(ItemStack.matches(remainder, nextPlayer.getItem(0)));
		assertFalse(matches(fixture, nextPlayer.getItem(0)));
		assertEquals(0, roundTrip(recovery).recover(fixture.actor(), nextPlayer, () -> true).moved());
	}

	@Test void publicStoreAvailabilityCountsOnlyOrdinaryDoorAndBedStacks() {
		for (Item item : List.of(Items.OAK_DOOR, Items.WHITE_BED)) {
			Fixture fixture = fixture(item, true);
			SimpleContainer bag = new SimpleContainer(8);
			ItemStack paid = pickup(fixture, bag);
			var recovery = new SettlementCargoRecovery();
			assertEquals(1, capture(fixture, recovery, bag));
			SimpleContainer recovered = new SimpleContainer(1);
			assertEquals(1, roundTrip(recovery).recover(fixture.actor(), recovered, () -> true).moved());
			ItemStack named = new ItemStack(item);
			named.set(DataComponents.CUSTOM_NAME, Component.literal("Personal item"));
			ItemStack custom = new ItemStack(item);
			CompoundTag data = new CompoundTag();
			data.putString("unrelated_data", "retain");
			custom.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
			ItemStack malformed = new ItemStack(item);
			CompoundTag marker = new CompoundTag();
			marker.putString(ConstructionSupply.KEY, "unknown");
			malformed.set(DataComponents.CUSTOM_DATA, CustomData.of(marker));
			SimpleContainer store = new SimpleContainer(paid, recovered.getItem(0).copy(), named, custom, malformed,
				new ItemStack(item), new ItemStack(item));
			List<ItemStack> before = java.util.stream.IntStream.range(0, store.getContainerSize())
				.mapToObj(slot -> store.getItem(slot).copy()).toList();
			assertEquals(7, store.countItem(item));
			assertEquals(2, ConstructionMaterials.available(store, fixture.simulation(), item));
			assertEquals(2, ConstructionMaterials.available(store, null, item));
			assertEquals(0, ConstructionMaterials.available(null, fixture.simulation(), item));
			for (int slot = 0; slot < store.getContainerSize(); slot++) assertTrue(ItemStack.matches(before.get(slot), store.getItem(slot)));
		}
	}
}
