package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Actual harvest/finder boundaries with supplied world facts, without a server or GameTest. */
class SettlementProtectedHarvestTest {
	private static final UUID OWNER = new UUID(811, 1), ACTOR = new UUID(811, 2);
	private static final BlockPos TARGET = new BlockPos(1, 0, 0);
	private enum Kind { GATHER, BERRY, POTATO }

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	private static BlockState ripeBerry(int age) {
		return Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, age);
	}
	private static BlockState ripePotato() { return ((CropBlock) Blocks.POTATOES).getStateForAge(7); }

	private static UUID protect(SettlementSavedData data, BlockPos pos) {
		var added = data.addProtectedArea(OWNER, "Keep building", pos.getX(), pos.getZ(), pos.getX(), pos.getZ());
		assertEquals(PlayerProtectedAreas.Status.ADDED, added.status());
		return added.area().id();
	}

	private static final class Fixture {
		SettlementSavedData data = new SettlementSavedData();
		Settlement camp = data.getOrCreate(BlockPos.ZERO, "Protected harvest", 0);
		final Kind kind;
		final SimpleContainer bag;
		BlockState block;
		int reads, blockReads, loots, writes, spilled, protectOnRead;
		boolean writesSucceed = true;
		Runnable afterLoot = () -> {}, duringPlanning = () -> {};
		boolean planning;

		Fixture(Kind kind, boolean simulation) {
			this.kind = kind;
			block = switch (kind) {
				case GATHER -> Blocks.STONE.defaultBlockState();
				case BERRY -> ripeBerry(3);
				case POTATO -> ripePotato();
			};
			bag = new SimpleContainer(4) {
				private void planned() { if (planning) { planning = false; duringPlanning.run(); } }
				@Override public ItemStack getItem(int slot) { planned(); return super.getItem(slot); }
				@Override public boolean canAddItem(ItemStack stack) { planned(); return super.canAddItem(stack); }
			};
			data.registerMember(camp, ACTOR, 0);
			if (kind == Kind.BERRY) assertEquals(SettlementForagingAreas.Status.CREATED,
				data.permitForagingArea(camp, OWNER, TARGET, 4).status());
			data.toggleResource(camp, TARGET);
			if (simulation) {
				var state = data.getOrCreateSimulation(camp);
				state.mapResident(ACTOR, false, 0);
				state.setSurvivalEnabled(true, 0);
				state.setProductionEnabled(true);
				state.setSkillsEnabled(true);
			}
		}
		boolean loaded() { reads++; return true; }
		BlockState readBlock() {
			blockReads++;
			if (blockReads == protectOnRead) protect(data, TARGET);
			return block;
		}
		boolean write(BlockState mature) {
			writes++;
			if (!writesSucceed) return false;
			block = switch (kind) {
				case GATHER -> Blocks.AIR.defaultBlockState();
				case BERRY -> mature.setValue(SweetBerryBushBlock.AGE, 1);
				case POTATO -> ((CropBlock) Blocks.POTATOES).getStateForAge(0);
			};
			return true;
		}
		boolean valid() {
			return switch (kind) {
				case GATHER -> SettlementGathering.validTarget(data, camp, ACTOR, TARGET,
					() -> true, this::loaded, () -> true, this::readBlock);
				case BERRY -> SettlementForaging.validTarget(data, camp, ACTOR, TARGET,
					() -> true, this::loaded, () -> true, this::readBlock);
				case POTATO -> SettlementCropHarvest.validTarget(data, camp, TARGET,
					this::loaded, () -> true, this::readBlock);
			};
		}
		ItemStack freshLoot() {
			return switch (kind) {
				case GATHER -> new ItemStack(Items.COBBLESTONE, 3);
				case BERRY -> new ItemStack(Items.SWEET_BERRIES, 3);
				case POTATO -> new ItemStack(Items.POTATO, 4);
			};
		}
		List<ItemStack> loot(ItemStack stack) { loots++; afterLoot.run(); return List.of(stack); }
		int run(ItemStack stack) {
			return switch (kind) {
				case GATHER -> SettlementGathering.harvest(data, camp, ACTOR, TARGET, bag,
					() -> true, this::loaded, () -> true, this::readBlock,
					() -> true, () -> Blocks.DIRT.defaultBlockState(), ignored -> loot(stack),
					this::write, overflow -> spilled += overflow.getCount());
				case BERRY -> SettlementForaging.harvest(data, camp, ACTOR, TARGET, bag,
					() -> true, this::loaded, () -> true, this::readBlock, () -> loot(stack), this::write);
				case POTATO -> SettlementCropHarvest.harvest(data, camp, ACTOR, TARGET, bag,
					() -> true, this::loaded, () -> true, this::readBlock, () -> loot(stack), this::write);
			};
		}
		CompoundTag saved() { return data.save(new CompoundTag(), null); }
		void load(CompoundTag saved) { data = SettlementSavedData.load(saved, null); camp = data.find(BlockPos.ZERO).orElseThrow(); }
		void unchanged(CompoundTag saved, BlockState original, ItemStack source, int sourceCount) {
			assertEquals(0, writes); assertEquals(original, block); assertTrue(bag.isEmpty()); assertEquals(0, spilled);
			assertEquals(sourceCount, source.getCount(), "Seed/loot source must not be consumed");
			assertTrue(data.resourcesOf(camp).contains(TARGET), "Protection must retain existing resource marks");
			assertEquals(saved, saved(), "Protection denial must not change production, clock, events or persisted data");
			data.simulationOf(camp).ifPresent(state -> state.world().assertConservation());
		}
	}

	@Test void protectionOverridesMarkedResourcesAndBothRipeBerryAgesWithoutWorldReads() {
		for (boolean simulation : List.of(false, true)) for (Kind kind : Kind.values()) {
			List<BlockState> blocks = switch (kind) {
				case GATHER -> List.of(Blocks.STONE.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState());
				case BERRY -> List.of(ripeBerry(2), ripeBerry(3));
				case POTATO -> List.of(ripePotato());
			};
			for (BlockState block : blocks) {
				Fixture f = new Fixture(kind, simulation); f.block = block; protect(f.data, TARGET);
				CompoundTag before = f.saved(); ItemStack source = f.freshLoot(); int count = source.getCount();
				assertFalse(f.valid()); assertEquals(0, f.run(source)); f.unchanged(before, block, source, count);
				assertEquals(0, f.reads); assertEquals(0, f.blockReads); assertEquals(0, f.loots);
			}
		}
	}

	@Test void areasOutsideTargetsPreserveExistingSuccessfulHarvests() {
		for (boolean simulation : List.of(false, true)) for (Kind kind : Kind.values()) {
			Fixture f = new Fixture(kind, simulation); protect(f.data, TARGET.east());
			assertTrue(f.valid()); assertEquals(3, f.run(f.freshLoot())); assertEquals(1, f.writes);
			assertEquals(3, f.bag.getItem(0).getCount()); assertEquals(0, f.spilled);
			f.data.simulationOf(f.camp).ifPresent(state -> state.world().assertConservation());
		}
	}

	@Test void protectionAddedAfterSelectionInvalidatesContinuationAndRemovalPermitsNewAttempt() {
		for (boolean simulation : List.of(false, true)) for (Kind kind : Kind.values()) {
			Fixture f = new Fixture(kind, simulation); assertTrue(f.valid());
			UUID area = protect(f.data, TARGET); assertFalse(f.valid());
			CompoundTag before = f.saved(); ItemStack source = f.freshLoot(); int count = source.getCount();
			assertEquals(0, f.run(source)); f.unchanged(before, f.block, source, count);
			assertEquals(PlayerProtectedAreas.Status.REMOVED, f.data.removeProtectedArea(area, OWNER, false));
			assertTrue(f.valid()); assertEquals(3, f.run(source)); assertEquals(1, f.writes);
		}
	}

	@Test void protectionAddedAfterLootOrDuringCapacityPlanningPreventsEveryCommit() {
		for (boolean simulation : List.of(false, true)) for (Kind kind : Kind.values()) {
			for (boolean planning : List.of(false, true)) {
				Fixture f = new Fixture(kind, simulation);
				AtomicReference<CompoundTag> protectedState = new AtomicReference<>();
				Runnable protect = () -> { protect(f.data, TARGET); protectedState.set(f.saved()); };
				if (planning) { f.planning = true; f.duringPlanning = protect; } else f.afterLoot = protect;
				BlockState original = f.block; ItemStack source = f.freshLoot(); int count = source.getCount();
				assertEquals(0, f.run(source)); assertNotNull(protectedState.get());
				f.unchanged(protectedState.get(), original, source, count); assertEquals(1, f.loots);
			}
		}
	}

	@Test void protectionIsReadAgainImmediatelyBeforeWorldMutationAfterLastStateRead() {
		for (Kind kind : Kind.values()) {
			Fixture f = new Fixture(kind, true); BlockState original = f.block; CompoundTag before = f.saved();
			// Final state read: gathering planned/valid/exact, berries mature/exact, potatoes valid/mature/valid/exact.
			f.protectOnRead = switch (kind) { case GATHER -> 3; case BERRY -> 2; case POTATO -> 4; };
			ItemStack source = f.freshLoot(); int count = source.getCount();
			assertEquals(0, f.run(source)); assertEquals(f.protectOnRead, f.blockReads);
			assertTrue(f.data.isResidentModificationBlocked(TARGET));
			before.put("PlayerProtectedAreas", f.saved().get("PlayerProtectedAreas").copy());
			f.unchanged(before, original, source, count);
			assertTrue(f.data.simulationOf(f.camp).orElseThrow().world().ledger().events().isEmpty());
		}
	}

	@Test void protectionDoesNotTouchExistingPlainOrStampedCargo() {
		for (Kind kind : Kind.values()) for (boolean late : List.of(false, true)) {
			Fixture f = new Fixture(kind, true);
			UUID clock = f.data.simulationOf(f.camp).orElseThrow().foodClockId().orElseThrow();
			ItemStack berries = SettlementFoodAge.stamp(new ItemStack(Items.SWEET_BERRIES, 11), clock, 0);
			ItemStack potatoes = new ItemStack(Items.POTATO, 7);
			f.bag.setItem(0, berries.copy()); f.bag.setItem(1, potatoes.copy());
			AtomicReference<CompoundTag> protectedState = new AtomicReference<>();
			Runnable protect = () -> { protect(f.data, TARGET); protectedState.set(f.saved()); };
			if (late) f.afterLoot = protect; else protect.run();
			BlockState original = f.block; ItemStack source = f.freshLoot(); int count = source.getCount();
			assertEquals(0, f.run(source)); assertEquals(0, f.writes); assertEquals(original, f.block);
			assertTrue(ItemStack.matches(berries, f.bag.getItem(0))); assertTrue(ItemStack.matches(potatoes, f.bag.getItem(1)));
			assertTrue(f.bag.getItem(2).isEmpty()); assertTrue(f.bag.getItem(3).isEmpty());
			assertEquals(count, source.getCount()); assertEquals(protectedState.get(), f.saved());
		}
	}

	@Test void outsideProtectionCapacityAndWorldWriteFailuresStillConserveEverything() {
		for (boolean simulation : List.of(false, true)) for (Kind kind : Kind.values()) {
			for (boolean capacity : List.of(false, true)) {
				Fixture f = new Fixture(kind, simulation); protect(f.data, TARGET.east());
				f.writesSucceed = capacity;
				if (capacity) for (int slot = 0; slot < f.bag.getContainerSize(); slot++) f.bag.setItem(slot, new ItemStack(Items.DIRT, 64));
				CompoundTag before = f.saved(); BlockState original = f.block;
				ItemStack source = f.freshLoot(); int count = source.getCount();
				assertEquals(0, f.run(source)); assertEquals(capacity ? 0 : 1, f.writes); assertEquals(original, f.block);
				assertEquals(capacity ? 256 : 0, f.bag.countItem(Items.DIRT)); assertEquals(0, f.spilled);
				assertEquals(count, source.getCount()); assertEquals(before, f.saved());
				assertTrue(f.data.resourcesOf(f.camp).contains(TARGET));
				f.data.simulationOf(f.camp).ifPresent(state -> state.world().assertConservation());
			}
		}
	}

	@Test void protectedObsoleteGatheringMarksAreRetained() {
		Fixture f = new Fixture(Kind.GATHER, false); f.block = Blocks.DIRT.defaultBlockState();
		protect(f.data, TARGET); CompoundTag before = f.saved(); ItemStack source = f.freshLoot();
		assertEquals(0, f.run(source)); f.unchanged(before, Blocks.DIRT.defaultBlockState(), source, 3);
		assertEquals(0, f.blockReads);
	}

	@Test void protectionDuringObsoleteMarkRecheckAlsoPreventsCleanup() {
		Fixture f = new Fixture(Kind.GATHER, false); f.block = Blocks.DIRT.defaultBlockState(); f.protectOnRead = 2;
		assertEquals(0, f.run(f.freshLoot())); assertEquals(2, f.blockReads);
		assertTrue(f.data.isResidentModificationBlocked(TARGET)); assertTrue(f.data.resourcesOf(f.camp).contains(TARGET));
		assertEquals(0, f.writes); assertEquals(0, f.loots);
	}

	@Test void damagedOrMissingCurrentProtectionDataBlocksAllServicesBeforeWorldReadsAcrossReloads() {
		for (boolean simulation : List.of(false, true)) for (Kind kind : Kind.values()) for (boolean missing : List.of(false, true)) {
			Fixture f = new Fixture(kind, simulation); CompoundTag damaged = f.saved();
			damaged.putInt("DataVersion", 21);
			if (missing) damaged.remove("PlayerProtectedAreas");
			else damaged.put("PlayerProtectedAreas", StringTag.valueOf("bad"));
			f.load(damaged);
			for (int round = 0; round < 2; round++) {
				CompoundTag before = f.saved(); ItemStack source = f.freshLoot(); int count = source.getCount();
				assertFalse(f.valid()); assertEquals(0, f.run(source)); f.unchanged(before, f.block, source, count);
				assertEquals(0, f.reads); assertEquals(0, f.blockReads); assertEquals(0, f.loots); f.load(before);
			}
		}
	}

	@Test void legacySaveWithoutProtectionFieldKeepsExistingHarvestAuthority() {
		for (Kind kind : Kind.values()) {
			Fixture f = new Fixture(kind, false); CompoundTag legacy = f.saved();
			legacy.putInt("DataVersion", 20); legacy.remove("PlayerProtectedAreas");
			f.load(legacy); assertEquals(3, f.run(f.freshLoot())); assertEquals(1, f.writes);
		}
	}

	@Test void clearProtectionNeverReplacesRequiredWoodStoneOrPotatoMarks() {
		for (Kind kind : List.of(Kind.GATHER, Kind.POTATO)) {
			Fixture f = new Fixture(kind, false); f.data.removeResource(f.camp, TARGET);
			assertFalse(f.data.isResidentModificationBlocked(TARGET)); assertFalse(f.valid());
			assertEquals(0, f.run(f.freshLoot())); assertEquals(0, f.reads); assertEquals(0, f.writes);
		}
		Fixture berries = new Fixture(Kind.BERRY, false); berries.data.removeResource(berries.camp, TARGET);
		assertTrue(berries.valid()); assertEquals(3, berries.run(berries.freshLoot()));
	}

	@Test void berryFinderSkipsNearestProtectedBushWithoutReadingItAndFindsOutsideBush() {
		SettlementSavedData data = new SettlementSavedData(); BlockPos farther = TARGET.east(2);
		Settlement camp = data.getOrCreate(BlockPos.ZERO, "Berry finder", 0);
		data.registerMember(camp, ACTOR, 0);
		assertEquals(SettlementForagingAreas.Status.CREATED,
			data.permitForagingArea(camp, OWNER, TARGET, 4).status());
		UUID area = protect(data, TARGET); AtomicInteger protectedReads = new AtomicInteger();
		var block = (java.util.function.Function<BlockPos, BlockState>) pos -> {
			if (pos.getX() == TARGET.getX() && pos.getZ() == TARGET.getZ()) protectedReads.incrementAndGet();
			return pos.equals(TARGET) || pos.equals(farther) ? ripeBerry(3) : Blocks.AIR.defaultBlockState();
		};
		assertEquals(farther, SettlementForaging.findRipeBush(data, camp, ACTOR, BlockPos.ZERO,
			() -> true, pos -> true, pos -> true, pos -> true, pos -> true, block).orElseThrow());
		assertEquals(0, protectedReads.get());
		assertEquals(PlayerProtectedAreas.Status.REMOVED, data.removeProtectedArea(area, OWNER, false));
		assertEquals(TARGET, SettlementForaging.findRipeBush(data, camp, ACTOR, BlockPos.ZERO,
			() -> true, pos -> true, pos -> true, pos -> true, pos -> true, block).orElseThrow());
	}

	@Test void potatoFinderSkipsProtectedMarkedCropAndRetainsBothMarks() {
		Fixture f = new Fixture(Kind.POTATO, false); BlockPos farther = TARGET.east(2);
		f.data.toggleResource(f.camp, farther); UUID area = protect(f.data, TARGET);
		assertEquals(farther, SettlementCropHarvest.findRipe(f.data, f.camp, BlockPos.ZERO,
			pos -> { assertNotEquals(TARGET, pos); return true; }, pos -> true, pos -> ripePotato()).orElseThrow());
		assertTrue(f.data.resourcesOf(f.camp).containsAll(List.of(TARGET, farther)));
		assertEquals(PlayerProtectedAreas.Status.REMOVED, f.data.removeProtectedArea(area, OWNER, false));
		assertEquals(TARGET, SettlementCropHarvest.findRipe(f.data, f.camp, BlockPos.ZERO,
			pos -> true, pos -> true, pos -> ripePotato()).orElseThrow());
	}

	@Test void damagedProtectionPreventsFinderWorldReads() {
		Fixture f = new Fixture(Kind.POTATO, false);
		assertEquals(SettlementForagingAreas.Status.CREATED,
			f.data.permitForagingArea(f.camp, OWNER, TARGET, 4).status());
		CompoundTag saved = f.saved();
		saved.put("PlayerProtectedAreas", StringTag.valueOf("bad")); f.load(saved);
		java.util.function.Predicate<BlockPos> noReads = pos -> { fail("Damaged protection must short-circuit world reads"); return false; };
		assertTrue(SettlementForaging.findRipeBush(f.data, f.camp, ACTOR, BlockPos.ZERO,
			() -> true, pos -> true, noReads, noReads, pos -> true, pos -> ripeBerry(3)).isEmpty());
		assertTrue(SettlementCropHarvest.findRipe(f.data, f.camp, BlockPos.ZERO, noReads, noReads, pos -> ripePotato()).isEmpty());
	}
}
