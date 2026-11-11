package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure inventory/service tests: no level, server, GameTest or pathfinding. */
class ConstructionSupplyTest {
	private static final ResourceLocation DIMENSION = ResourceLocation.parse("minecraft:overworld");
	private static final UUID CAMP = new UUID(13, 1), ACTOR = new UUID(13, 2);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	private static final class Fixture {
		final SimpleContainer store = new SimpleContainer(3), bag = new SimpleContainer(3);
		final ConstructionInstruction step;
		final ConstructionJob job;
		Fixture() { this(false); }
		Fixture(boolean bed) {
			step = new ConstructionInstruction(BlockPos.ZERO, (bed ? Blocks.WHITE_BED : Blocks.OAK_DOOR).defaultBlockState(),
				bed ? Items.WHITE_BED : Items.OAK_DOOR, true);
			job = ConstructionJob.start(new ConstructionPlan(ResourceLocation.parse("coopcraft:test_supply"), List.of(step)), BlockPos.ZERO);
			store.setItem(0, new ItemStack(step.item(), bed ? 1 : 4));
		}
		boolean pickup() { return ConstructionSupply.pickup(store, bag, DIMENSION, CAMP, ACTOR, job, step, () -> true); }
		boolean has() { return ConstructionSupply.hasFor(bag, DIMENSION, CAMP, ACTOR, job, step); }
		int returns(ConstructionJob current) { return ConstructionSupply.returnUnused(bag, store, DIMENSION, CAMP, ACTOR, current, () -> true); }
		void alter(Consumer<CompoundTag> change) {
			CompoundTag root = bag.getItem(0).get(DataComponents.CUSTOM_DATA).copyTag();
			change.accept(root.getCompound(ConstructionSupply.KEY));
			bag.getItem(0).set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		}
	}

	@Test void pickupMovesOneRealDoorOrBedAndKeepsSourceRemainder() {
		for (boolean bed : List.of(false, true)) {
			Fixture f = new Fixture(bed);
			assertTrue(f.pickup()); assertTrue(f.has());
			assertEquals(1, f.bag.getItem(0).getCount());
			assertEquals(bed ? 0 : 3, f.store.countItem(f.step.item()));
			assertFalse(f.pickup());
			assertEquals(1, f.bag.countItem(f.step.item()));
			assertTrue(f.store.getItem(0).getComponentsPatch().isEmpty());
		}
		assertFalse(ConstructionSupply.supported(Items.RED_BED));
		assertFalse(ConstructionSupply.supported(Items.COBBLESTONE));
	}

	@Test void ordinaryComponentsAndEmptySlotAreMandatory() {
		Fixture f = new Fixture();
		f.store.getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("Reserved"));
		assertFalse(f.pickup()); assertTrue(f.bag.isEmpty());
		assertEquals(0, ConstructionSupply.countAvailable(f.store, Items.OAK_DOOR));
		f.store.getItem(0).remove(DataComponents.CUSTOM_NAME);
		assertEquals(4, ConstructionSupply.countAvailable(f.store, Items.OAK_DOOR));
		for (int slot = 0; slot < 3; slot++) f.bag.setItem(slot, new ItemStack(Items.OAK_DOOR));
		assertFalse(f.pickup()); assertEquals(4, f.store.countItem(Items.OAK_DOOR));
		assertFalse(ConstructionSupply.pickup(f.store, f.store, DIMENSION, CAMP, ACTOR, f.job, f.step, () -> true));
	}

	@Test void companionInstructionAndUnverifiedJobsCannotTakeSupply() {
		Fixture f = new Fixture();
		ConstructionInstruction companion = new ConstructionInstruction(f.step.offset(), f.step.state(), f.step.item(), false);
		assertFalse(ConstructionSupply.pickup(f.store, f.bag, DIMENSION, CAMP, ACTOR, f.job, companion, () -> true));
		assertFalse(ConstructionSupply.pickup(f.store, f.bag, DIMENSION, CAMP, ACTOR,
			new ConstructionJob(f.job.blueprintId(), BlockPos.ZERO, 0), f.step, () -> true));
		CompoundTag broken = f.job.save(); broken.putString("JobId", "broken");
		assertFalse(ConstructionSupply.pickup(f.store, f.bag, DIMENSION, CAMP, ACTOR, ConstructionJob.load(broken), f.step, () -> true));
		assertEquals(4, f.store.countItem(Items.OAK_DOOR));
	}

	@Test void notAtStoreDoesNotInspectOrTouchInventories() {
		Fixture f = new Fixture();
		SimpleContainer unreadable = new SimpleContainer(1) {
			@Override public int getContainerSize() { return fail("No inventory read before arrival"); }
		};
		assertFalse(ConstructionSupply.pickup(unreadable, f.bag,
			DIMENSION, CAMP, ACTOR, f.job, f.step, () -> false));
		assertEquals(0, ConstructionSupply.returnUnused(unreadable, f.store, DIMENSION, CAMP, ACTOR, null, () -> false));
	}

	@Test void finalPickupGateRechecksSourceSlotCapacityAndExistingMarks() {
		for (int change = 0; change < 4; change++) {
			Fixture f = new Fixture(); AtomicInteger calls = new AtomicInteger(); final int kind = change;
			assertFalse(ConstructionSupply.pickup(f.store, f.bag, DIMENSION, CAMP, ACTOR, f.job, f.step, () -> {
				if (calls.incrementAndGet() == 2) {
					if (kind == 0) return false;
					if (kind == 1) f.store.setItem(0, new ItemStack(Items.DIRT));
					if (kind == 2) f.bag.setItem(0, new ItemStack(Items.DIRT));
					if (kind == 3) f.store.getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("Changed"));
				}
				return true;
			}));
			assertFalse(ConstructionSupply.hasAny(f.bag));
			if (kind != 1) assertEquals(4, f.store.countItem(Items.OAK_DOOR));
		}
	}

	@Test void placementReservesExactItemThenConsumesOrRestoresIt() {
		Fixture f = new Fixture(); assertTrue(f.pickup());
		ItemStack expected = f.bag.getItem(0).copy();
		assertFalse(ConstructionSupply.place(f.bag, DIMENSION, CAMP, ACTOR, f.job, f.step, () -> true, () -> {
			assertTrue(f.bag.isEmpty()); return false;
		}));
		assertTrue(ItemStack.matches(expected, f.bag.getItem(0)));
		assertTrue(ConstructionSupply.place(f.bag, DIMENSION, CAMP, ACTOR, f.job, f.step, () -> true, () -> {
			assertTrue(f.bag.isEmpty()); return true;
		}));
		assertTrue(f.bag.isEmpty()); assertEquals(3, f.store.countItem(Items.OAK_DOOR));
		assertFalse(ConstructionSupply.place(f.bag, DIMENSION, CAMP, ACTOR, f.job, f.step, () -> true,
			() -> fail("An already consumed item cannot place again")));
	}

	@Test void placementExceptionRestoresAndFinalGateFailureNeverPlaces() {
		Fixture f = new Fixture(); assertTrue(f.pickup());
		assertThrows(IllegalStateException.class, () -> ConstructionSupply.place(f.bag, DIMENSION, CAMP, ACTOR, f.job, f.step,
			() -> true, () -> { throw new IllegalStateException("placement aborted"); }));
		assertTrue(f.has());
		AtomicInteger gates = new AtomicInteger();
		assertFalse(ConstructionSupply.place(f.bag, DIMENSION, CAMP, ACTOR, f.job, f.step,
			() -> gates.incrementAndGet() == 1, () -> fail("Final authorization failed")));
		assertTrue(f.has());
	}

	@Test void scopeIncludesDimensionSettlementActorJobStepAndPlan() {
		Fixture f = new Fixture(); assertTrue(f.pickup()); ItemStack item = f.bag.getItem(0);
		assertFalse(ConstructionSupply.matches(item, ResourceLocation.parse("minecraft:the_nether"), CAMP, ACTOR, f.job, f.step));
		assertFalse(ConstructionSupply.matches(item, DIMENSION, UUID.randomUUID(), ACTOR, f.job, f.step));
		assertFalse(ConstructionSupply.matches(item, DIMENSION, CAMP, UUID.randomUUID(), f.job, f.step));
		assertFalse(ConstructionSupply.matches(item, DIMENSION, CAMP, ACTOR,
			new ConstructionJob(f.job.blueprintId(), f.job.origin(), f.job.nextIndex(), f.job.planFingerprint()), f.step));
		assertFalse(ConstructionSupply.matches(item, DIMENSION, CAMP, ACTOR, f.job.advance(), f.step));
		assertFalse(ConstructionSupply.matches(item, DIMENSION, CAMP, ACTOR, f.job.withPlanFingerprint("changed"), f.step));
		assertFalse(ConstructionSupply.matches(new ItemStack(Items.OAK_DOOR), DIMENSION, CAMP, ACTOR, f.job, f.step));
	}

	@Test void persistedItemAndJobRemainValidWithoutATransientPermit() {
		Fixture f = new Fixture(); assertTrue(f.pickup());
		var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		ItemStack restored = ItemStack.parse(registries, f.bag.getItem(0).save(registries)).orElseThrow();
		ConstructionJob job = ConstructionJob.load(f.job.save());
		assertTrue(ConstructionSupply.matches(restored, DIMENSION, CAMP, ACTOR, job, f.step));
		assertFalse(ConstructionSupply.matches(restored, DIMENSION, CAMP, UUID.randomUUID(), job, f.step));
		f.bag.setItem(0, restored);
		assertEquals(0, ConstructionSupply.returnUnused(f.bag, f.store, DIMENSION, CAMP, UUID.randomUUID(), null, () -> true));
		assertTrue(f.has());
	}

	@Test void malformedOrFutureMarkersAreQuarantinedWithoutLaundering() {
		List<Consumer<CompoundTag>> corruptions = List.of(
			t -> t.putInt("Version", 2), t -> t.putByte("Version", (byte) 1), t -> t.remove("Actor"),
			t -> t.putString("Actor", ACTOR.toString()), t -> t.putIntArray("Job", new int[] {1, 2}),
			t -> t.putLong("Index", 0), t -> t.putInt("Index", -1), t -> t.putString("Plan", ""),
			t -> t.putString("Dimension", "bad dimension"), t -> t.putString("Item", "minecraft:white_bed"),
			t -> t.putString("Unknown", "preserve"));
		for (Consumer<CompoundTag> corrupt : corruptions) {
			Fixture f = new Fixture(); assertTrue(f.pickup()); f.alter(corrupt);
			ItemStack before = f.bag.getItem(0).copy();
			assertTrue(ConstructionSupply.isMarked(before)); assertTrue(ConstructionSupply.hasAny(f.bag));
			assertFalse(f.has()); assertFalse(f.pickup()); assertEquals(0, f.returns(null));
			assertFalse(ConstructionSupply.hasReturnable(f.bag, DIMENSION, CAMP, ACTOR, null));
			assertTrue(ItemStack.matches(before, f.bag.getItem(0)));
		}
	}

	@Test void unknownCustomDataExtraComponentsAndWrongCountRemainUntouched() {
		for (int kind = 0; kind < 4; kind++) {
			Fixture f = new Fixture(); assertTrue(f.pickup());
			if (kind == 0) f.bag.getItem(0).setCount(2);
			if (kind == 1) f.bag.getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("Preserve"));
			if (kind >= 2) {
				CompoundTag root = f.bag.getItem(0).get(DataComponents.CUSTOM_DATA).copyTag();
				if (kind == 2) root.putString("unrelated", "Preserve");
				else root.putString(ConstructionSupply.KEY, "malformed");
				f.bag.getItem(0).set(DataComponents.CUSTOM_DATA, CustomData.of(root));
			}
			ItemStack before = f.bag.getItem(0).copy();
			assertTrue(ConstructionSupply.isMarked(before)); assertFalse(f.has());
			assertEquals(0, f.returns(null)); assertTrue(ItemStack.matches(before, f.bag.getItem(0)));
		}
	}

	@Test void cancellationAdvanceAndPlanChangeReturnActualItemAndStripOnlyValidProtocol() {
		for (int kind = 0; kind < 3; kind++) {
			Fixture f = new Fixture(); assertTrue(f.pickup());
			assertFalse(ConstructionSupply.hasReturnable(f.bag, DIMENSION, CAMP, ACTOR, f.job));
			assertEquals(0, f.returns(f.job));
			ConstructionJob current = kind == 0 ? null : kind == 1 ? f.job.advance() : f.job.withPlanFingerprint("changed");
			assertTrue(ConstructionSupply.hasReturnable(f.bag, DIMENSION, CAMP, ACTOR, current));
			assertEquals(1, f.returns(current)); assertTrue(f.bag.isEmpty());
			assertEquals(4, f.store.countItem(Items.OAK_DOOR));
			assertTrue(f.store.getItem(0).getComponentsPatch().isEmpty()); assertEquals(0, f.returns(current));
		}
	}

	@Test void returnRequiresMatchingOriginAndRealStoreCapacity() {
		Fixture f = new Fixture(); assertTrue(f.pickup());
		assertEquals(0, ConstructionSupply.returnUnused(f.bag, f.store, DIMENSION, UUID.randomUUID(), ACTOR, null, () -> true));
		assertEquals(0, ConstructionSupply.returnUnused(f.bag, f.store, DIMENSION, CAMP, UUID.randomUUID(), null, () -> true));
		assertEquals(0, ConstructionSupply.returnUnused(f.bag, f.store, ResourceLocation.parse("minecraft:the_nether"), CAMP, ACTOR, null, () -> true));
		for (int slot = 0; slot < 3; slot++) f.store.setItem(slot, new ItemStack(Items.DIRT, 64));
		assertEquals(0, f.returns(null)); assertTrue(f.has());
		f.store.setItem(1, new ItemStack(Items.OAK_DOOR, 63));
		assertEquals(1, f.returns(null)); assertEquals(64, f.store.getItem(1).getCount());
		assertTrue(f.bag.isEmpty());
	}

	@Test void finalReturnGateRevalidatesCapacityWithoutDroppingItem() {
		Fixture f = new Fixture(); assertTrue(f.pickup()); AtomicInteger calls = new AtomicInteger();
		assertEquals(0, ConstructionSupply.returnUnused(f.bag, f.store, DIMENSION, CAMP, ACTOR, null, () -> {
			if (calls.incrementAndGet() == 2) for (int slot = 0; slot < 3; slot++) f.store.setItem(slot, new ItemStack(Items.DIRT, 64));
			return true;
		}));
		assertTrue(f.has());
	}

	@Test void markedItemInStoreIsNeverOrdinaryStock() {
		Fixture f = new Fixture(); assertTrue(f.pickup());
		f.store.setItem(0, f.bag.removeItem(0, 1));
		assertEquals(0, ConstructionSupply.countAvailable(f.store, Items.OAK_DOOR));
		assertFalse(ConstructionSupply.ordinary(f.store.getItem(0)));
		assertFalse(f.pickup()); assertTrue(f.bag.isEmpty());
	}
	@Test void anyExistingOrNewlyInsertedMarkerBlocksAnotherPickup() {
		Fixture f = new Fixture();
		ItemStack unknown = new ItemStack(Items.DIRT);
		CompoundTag tag = new CompoundTag(); tag.putString(ConstructionSupply.KEY, "unknown");
		unknown.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		f.bag.setItem(2, unknown);
		assertFalse(f.pickup()); assertEquals(4, f.store.countItem(Items.OAK_DOOR));
		f.bag.setItem(2, ItemStack.EMPTY);
		AtomicInteger calls = new AtomicInteger();
		assertFalse(ConstructionSupply.pickup(f.store, f.bag, DIMENSION, CAMP, ACTOR, f.job, f.step, () -> {
			if (calls.incrementAndGet() == 2) f.bag.setItem(2, unknown);
			return true;
		}));
		assertEquals(4, f.store.countItem(Items.OAK_DOOR)); assertEquals(0, f.bag.countItem(Items.OAK_DOOR));
		assertTrue(ItemStack.matches(unknown, f.bag.getItem(2)));
	}

	@Test void returnMovesAtMostActualMarkedSlotsAndHonorsUnstackableBeds() {
		Fixture f = new Fixture(true); assertTrue(f.pickup());
		Fixture second = new Fixture(true); assertTrue(second.pickup());
		f.bag.setItem(1, second.bag.removeItem(0, 1));
		for (int slot = 0; slot < 3; slot++) f.store.setItem(slot, new ItemStack(Items.WHITE_BED));
		assertEquals(0, f.returns(null)); assertEquals(2, f.bag.countItem(Items.WHITE_BED));
		f.store.setItem(0, ItemStack.EMPTY);
		assertEquals(1, f.returns(null)); assertEquals(1, f.bag.countItem(Items.WHITE_BED));
		assertEquals(1, f.store.getItem(0).getCount());
		assertTrue(ConstructionSupply.ordinary(f.store.getItem(0)));
	}

}
