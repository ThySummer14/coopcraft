package com.thysummer.coopcraft.civilization;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.thysummer.coopcraft.civilization.SettlementHousing.RegistrationResult.*;
import static org.junit.jupiter.api.Assertions.*;

class SettlementHousingTest {
	private static final BlockPos FOOT = new BlockPos(15, 64, 3);
	private static final BlockPos HEAD = FOOT.east();

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static class World implements SettlementHousing.BedAccess {
		final Map<BlockPos, BlockState> states = new HashMap<>();
		final Set<BlockPos> unloaded = new HashSet<>();
		final Set<BlockPos> reads = new HashSet<>();
		public boolean isLoaded(BlockPos pos) { return !unloaded.contains(pos); }
		public BlockState stateAt(BlockPos pos) {
			assertTrue(isLoaded(pos), "Must never read unloaded position " + pos);
			reads.add(pos);
			return states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
		}
		void bed(BedBlock block) {
			states.put(FOOT, block.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.FOOT));
			states.put(HEAD, block.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.HEAD));
		}
	}

	private static Settlement settlement(SettlementSavedData data) {
		return data.getOrCreate(BlockPos.ZERO, "Housing", 0);
	}

	@Test
	void maintenanceIsStaggeredExactlyOncePerHundredTicksIncludingLongBoundaries() {
		for (int hash : new int[] { Integer.MIN_VALUE, -101, -1, 0, 1, 99, Integer.MAX_VALUE }) {
			Settlement s = new Settlement(new UUID(0, Integer.toUnsignedLong(hash)), BlockPos.ZERO, "Phase", 0);
			assertEquals(hash, s.id().hashCode());
			for (long start : new long[] { Long.MIN_VALUE, -150, -100, -1, 0, 1, Long.MAX_VALUE - 99 }) {
				int due = 0;
				for (int offset = 0; offset < 100; offset++) {
					long time = start + offset;
					boolean expected = java.math.BigInteger.valueOf(time).mod(java.math.BigInteger.valueOf(100)).intValue()
						== Math.floorMod(hash, 100);
					assertEquals(expected, SettlementHousing.maintenanceDue(s, time));
					if (SettlementHousing.maintenanceDue(s, time)) due++;
				}
				assertEquals(1, due);
			}
		}
	}

	@Test
	void acceptsBothHalvesAndEveryVanillaColorWithoutDuplicateCapacity() {
		for (var block : new BedBlock[] { (BedBlock) Blocks.WHITE_BED, (BedBlock) Blocks.ORANGE_BED,
			(BedBlock) Blocks.MAGENTA_BED, (BedBlock) Blocks.LIGHT_BLUE_BED, (BedBlock) Blocks.YELLOW_BED,
			(BedBlock) Blocks.LIME_BED, (BedBlock) Blocks.PINK_BED, (BedBlock) Blocks.GRAY_BED,
			(BedBlock) Blocks.LIGHT_GRAY_BED, (BedBlock) Blocks.CYAN_BED, (BedBlock) Blocks.PURPLE_BED,
			(BedBlock) Blocks.BLUE_BED, (BedBlock) Blocks.BROWN_BED, (BedBlock) Blocks.GREEN_BED,
			(BedBlock) Blocks.RED_BED, (BedBlock) Blocks.BLACK_BED }) {
			World world = new World(); world.bed(block);
			SettlementSavedData data = new SettlementSavedData(); Settlement s = settlement(data);
			assertEquals(ADDED, SettlementHousing.registerBed(world, data, s, HEAD));
			assertEquals(ALREADY_REGISTERED, SettlementHousing.registerBed(world, data, s, FOOT));
			assertEquals(Set.of(FOOT), data.housingBeds(s));
		}
	}

	@Test
	void removalOfEitherHalfRestoresHousingProjectAndDoesNotAdvanceSimulation() {
		for (BlockPos broken : new BlockPos[] { FOOT, HEAD }) {
			World world = new World(); world.bed((BedBlock) Blocks.WHITE_BED);
			SettlementSavedData data = new SettlementSavedData(); Settlement s = settlement(data);
			data.registerMember(s, UUID.randomUUID(), 0);
			data.setHearth(s, s.center());
			SettlementHousing.registerBed(world, data, s, HEAD);
			assertTrue(ConstructionPlanner.projects(data, s).isEmpty());
			world.states.remove(broken);
			SettlementHousing.reconcile(world, data, s);
			assertEquals(0, data.housingCapacity(s));
			assertEquals(1, ConstructionPlanner.housingPlan(data, s).missingBeds());
			assertEquals(ConstructionPlanner.STONE_COTTAGE, ConstructionPlanner.projects(data, s).getFirst().blueprintId());
			assertTrue(data.simulationOf(s).isEmpty());
			assertTrue(data.getConstructionJob(s).isEmpty());
		}
	}

	@Test
	void rejectsMixedColorsWrongFacingAndWrongPartButAcceptsWholeBedRecolor() {
		World world = new World(); world.bed((BedBlock) Blocks.WHITE_BED);
		SettlementSavedData data = new SettlementSavedData(); Settlement s = settlement(data);
		SettlementHousing.registerBed(world, data, s, FOOT);
		world.bed((BedBlock) Blocks.RED_BED);
		SettlementHousing.reconcile(world, data, s);
		assertEquals(1, data.housingCapacity(s));
		BlockState valid = world.states.get(HEAD);
		for (BlockState invalid : new BlockState[] {
			Blocks.BLUE_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.HEAD),
			valid.setValue(BedBlock.FACING, Direction.WEST), valid.setValue(BedBlock.PART, BedPart.FOOT) }) {
			world.states.put(HEAD, invalid);
			assertEquals(INVALID, SettlementHousing.registerBed(world, data, s, FOOT));
			SettlementHousing.reconcile(world, data, s);
			assertEquals(0, data.housingCapacity(s));
			data.addHousingBed(s, FOOT);
		}
	}

	@Test
	void unloadedHalvesNeverReadOrRemoveAndRemovedHalfWaitsAtChunkEdge() {
		for (BlockPos unavailable : new BlockPos[] { FOOT, HEAD }) {
			World world = new World(); world.bed((BedBlock) Blocks.WHITE_BED);
			SettlementSavedData data = new SettlementSavedData(); Settlement s = settlement(data);
			data.addHousingBed(s, FOOT);
			world.unloaded.add(unavailable);
			assertEquals(UNLOADED, SettlementHousing.registerBed(world, data, s, FOOT));
			SettlementHousing.reconcile(world, data, s);
			assertEquals(1, data.housingCapacity(s));
			assertFalse(world.reads.contains(unavailable));
			world.states.remove(FOOT);
			SettlementHousing.reconcile(world, data, s);
			assertEquals(1, data.housingCapacity(s));
			world.unloaded.clear();
			SettlementHousing.reconcile(world, data, s);
			assertEquals(0, data.housingCapacity(s));
		}
	}

	@Test
	void legacyHeadsNormalizeAndCrossSettlementDuplicatesHaveStableOwnership() {
		World world = new World(); world.bed((BedBlock) Blocks.WHITE_BED);
		SettlementSavedData data = new SettlementSavedData(); Settlement first = settlement(data);
		Settlement second = data.getOrCreate(new BlockPos(200, 64, 0), "Other", 0);
		data.addHousingBed(first, HEAD);
		assertEquals(OTHER_SETTLEMENT, SettlementHousing.registerBed(world, data, second, FOOT));
		data.addHousingBed(second, FOOT);
		// Even if the later settlement is maintained first, legacy ownership stays stable.
		SettlementHousing.reconcile(world, data, second);
		SettlementHousing.reconcile(world, data, first);
		assertEquals(Set.of(FOOT), data.housingBeds(first));
		assertEquals(0, data.housingCapacity(second));
		assertEquals(OTHER_SETTLEMENT, SettlementHousing.registerBed(world, data, second, HEAD));
	}

	@Test
	void snapshotIsImmutableAndDoubleReloadPreservesNormalizationAndRemoval() {
		World world = new World(); world.bed((BedBlock) Blocks.WHITE_BED);
		SettlementSavedData data = new SettlementSavedData(); Settlement s = settlement(data);
		data.addHousingBed(s, HEAD); data.addHousingBed(s, FOOT);
		Set<BlockPos> snapshot = data.housingBeds(s);
		assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
		data = SettlementSavedData.load(data.save(new CompoundTag(), null), null);
		SettlementHousing.reconcile(world, data, s);
		assertEquals(2, snapshot.size());
		assertEquals(Set.of(FOOT), data.housingBeds(s));
		data = SettlementSavedData.load(data.save(new CompoundTag(), null), null);
		assertEquals(Set.of(FOOT), data.housingBeds(s));
		world.states.remove(HEAD);
		SettlementHousing.reconcile(world, data, s);
		data = SettlementSavedData.load(data.save(new CompoundTag(), null), null);
		data = SettlementSavedData.load(data.save(new CompoundTag(), null), null);
		assertEquals(0, data.housingCapacity(s));
	}

	@Test
	void keepsLegacyHeadUntilFootLoadsAndMarksActualChangesDirty() {
		World world = new World(); world.bed((BedBlock) Blocks.WHITE_BED);
		SettlementSavedData data = new SettlementSavedData(); Settlement s = settlement(data);
		data.addHousingBed(s, HEAD);
		data.setDirty(false);
		world.unloaded.add(FOOT);
		SettlementHousing.reconcile(world, data, s);
		assertEquals(Set.of(HEAD), data.housingBeds(s));
		assertFalse(data.isDirty());
		assertFalse(world.reads.contains(FOOT));
		world.unloaded.clear();
		SettlementHousing.reconcile(world, data, s);
		assertEquals(Set.of(FOOT), data.housingBeds(s));
		assertTrue(data.isDirty());
		data.setDirty(false);
		world.states.remove(HEAD);
		SettlementHousing.reconcile(world, data, s);
		assertEquals(0, data.housingCapacity(s));
		assertTrue(data.isDirty());
	}

	@Test
	void rejectsNonBedWithoutRegisteringOrDirtyingData() {
		World world = new World();
		SettlementSavedData data = new SettlementSavedData(); Settlement s = settlement(data);
		world.states.put(FOOT, Blocks.STONE.defaultBlockState());
		data.setDirty(false);
		assertEquals(INVALID, SettlementHousing.registerBed(world, data, s, FOOT));
		assertEquals(0, data.housingCapacity(s));
		assertFalse(data.isDirty());
	}

	@Test
	void doesNotClaimUnregisteredBedsOrDirtyAnUnchangedRegistration() {
		World world = new World(); world.bed((BedBlock) Blocks.WHITE_BED);
		SettlementSavedData data = new SettlementSavedData(); Settlement s = settlement(data);
		data.setDirty(false);
		SettlementHousing.reconcile(world, data, s);
		assertEquals(0, data.housingCapacity(s)); assertTrue(world.reads.isEmpty()); assertFalse(data.isDirty());
		assertEquals(ADDED, SettlementHousing.registerBed(world, data, s, FOOT));
		data.setDirty(false);
		SettlementHousing.reconcile(world, data, s);
		assertEquals(ALREADY_REGISTERED, SettlementHousing.registerBed(world, data, s, HEAD));
		assertFalse(data.isDirty());
	}
}
