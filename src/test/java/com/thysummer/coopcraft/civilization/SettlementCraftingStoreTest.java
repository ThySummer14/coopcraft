package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettlementCraftingStoreTest {
	private static final BlockPos STORE = new BlockPos(15, 64, 0);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void singleChestBindsOnlyItsOwnPositionAndRejectsReplacementOrRemoval() {
		FakeProbe probe = new FakeProbe();
		probe.put(STORE, chest(Direction.NORTH, ChestType.SINGLE));
		SettlementCraftingStore.Binding binding = SettlementCraftingStore.capture(STORE, probe);
		assertNotNull(binding);
		assertTrue(binding.valid());
		assertTrue(probe.reads.stream().allMatch(read -> read.position().equals(STORE)));
		Object original = probe.identities.get(STORE);
		probe.identities.put(STORE, new Object());
		assertFalse(binding.valid());
		probe.identities.put(STORE, original);
		assertTrue(binding.valid());
		probe.identities.remove(STORE); // The real adapter also returns null for removed/wrong-level entities.
		assertFalse(binding.valid());
		assertNull(SettlementCraftingStore.capture(STORE, probe));
	}

	@Test
	void barrelOpeningIsAllowedButRotationAndReplacementAreNot() {
		FakeProbe probe = new FakeProbe();
		BlockState closed = Blocks.BARREL.defaultBlockState();
		probe.put(STORE, closed);
		SettlementCraftingStore.Binding binding = SettlementCraftingStore.capture(STORE, probe);
		assertNotNull(binding);
		probe.states.put(STORE, closed.setValue(BarrelBlock.OPEN, true));
		assertTrue(binding.valid());
		probe.states.put(STORE, closed.setValue(BarrelBlock.FACING,
			closed.getValue(BarrelBlock.FACING).getOpposite()));
		assertFalse(binding.valid());
		probe.states.put(STORE, closed);
		probe.identities.put(STORE, new Object());
		assertFalse(binding.valid());
	}

	@Test
	void doubleChestAcceptsEitherHalfInEveryFacing() {
		for (Direction facing : Direction.Plane.HORIZONTAL) {
			for (ChestType type : List.of(ChestType.LEFT, ChestType.RIGHT)) {
				FakeProbe probe = pair(facing, type);
				SettlementCraftingStore.Binding binding = SettlementCraftingStore.capture(STORE, probe);
				assertNotNull(binding);
				assertTrue(binding.valid());
				BlockPos partner = partner(facing, type);
				assertTrue(probe.reads.contains(new Read("identity", STORE)));
				assertTrue(probe.reads.contains(new Read("identity", partner)));
				assertNotNull(SettlementCraftingStore.capture(partner, probe));
			}
		}
	}

	@Test
	void captureNeverReadsAnUnknownMainHalf() {
		FakeProbe probe = pair(Direction.NORTH, ChestType.LEFT);
		probe.unready.add(STORE);
		assertNull(SettlementCraftingStore.capture(STORE, probe));
		assertEquals(List.of(), probe.reads);
	}

	@Test
	void captureOnlyDiscoversMainStateWhenPartnerIsUnknown() {
		FakeProbe probe = pair(Direction.NORTH, ChestType.LEFT);
		probe.unready.add(partner(Direction.NORTH, ChestType.LEFT));
		assertNull(SettlementCraftingStore.capture(STORE, probe));
		assertEquals(List.of(new Read("state", STORE)), probe.reads);
	}

	@Test
	void captureRechecksMainReadinessAfterDiscoveryBeforeAnyEntityRead() {
		FakeProbe probe = pair(Direction.NORTH, ChestType.LEFT);
		probe.afterState = () -> probe.unready.add(STORE);
		assertNull(SettlementCraftingStore.capture(STORE, probe));
		assertEquals(List.of(new Read("state", STORE)), probe.reads);
	}

	@Test
	void validationPreflightsEveryKnownHalfBeforeReadingAnyState() {
		for (BlockPos unavailable : List.of(STORE, partner(Direction.NORTH, ChestType.LEFT))) {
			FakeProbe probe = pair(Direction.NORTH, ChestType.LEFT);
			SettlementCraftingStore.Binding binding = SettlementCraftingStore.capture(STORE, probe);
			assertNotNull(binding);
			probe.reads.clear();
			probe.unready.add(unavailable);
			assertFalse(binding.valid());
			assertEquals(List.of(), probe.reads);
		}
	}

	@Test
	void refusesFalsePairsAndUnsupportedMainBlocks() {
		List<BlockState> invalidPartners = List.of(
			Blocks.BARREL.defaultBlockState(), Blocks.STONE.defaultBlockState(),
			Blocks.TRAPPED_CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.RIGHT),
			chest(Direction.NORTH, ChestType.SINGLE), chest(Direction.NORTH, ChestType.LEFT),
			chest(Direction.SOUTH, ChestType.RIGHT), chest(Direction.EAST, ChestType.RIGHT));
		for (BlockState invalid : invalidPartners) {
			FakeProbe probe = pair(Direction.NORTH, ChestType.LEFT);
			probe.states.put(partner(Direction.NORTH, ChestType.LEFT), invalid);
			assertNull(SettlementCraftingStore.capture(STORE, probe));
			assertFalse(probe.reads.stream().anyMatch(read -> read.kind().equals("identity")));
		}
		for (BlockState invalid : List.of(Blocks.STONE.defaultBlockState(), Blocks.TRAPPED_CHEST.defaultBlockState())) {
			FakeProbe probe = new FakeProbe();
			probe.put(STORE, invalid);
			assertNull(SettlementCraftingStore.capture(STORE, probe));
		}
	}

	@Test
	void eitherHalfReplacementRemovalRotationOrSplitInvalidatesBinding() {
		for (BlockPos changed : List.of(STORE, partner(Direction.NORTH, ChestType.LEFT))) {
			FakeProbe probe = pair(Direction.NORTH, ChestType.LEFT);
			SettlementCraftingStore.Binding binding = SettlementCraftingStore.capture(STORE, probe);
			assertNotNull(binding);
			BlockState originalState = probe.states.get(changed);
			Object originalIdentity = probe.identities.get(changed);
			for (BlockState changedState : List.of(originalState.setValue(ChestBlock.TYPE, ChestType.SINGLE),
				originalState.setValue(ChestBlock.FACING, Direction.SOUTH), Blocks.AIR.defaultBlockState(),
				originalState.setValue(ChestBlock.TYPE, originalState.getValue(ChestBlock.TYPE) == ChestType.LEFT
					? ChestType.RIGHT : ChestType.LEFT))) {
				probe.states.put(changed, changedState);
				assertFalse(binding.valid());
			}
			probe.states.put(changed, originalState);
			assertTrue(binding.valid());
			probe.identities.put(changed, new Object());
			assertFalse(binding.valid());
			probe.identities.remove(changed);
			assertFalse(binding.valid());
			assertNull(SettlementCraftingStore.capture(STORE, probe));
			probe.identities.put(changed, originalIdentity);
			assertTrue(binding.valid());
		}
	}

	@Test
	void becomingDoubleChestInvalidatesSingleChestBinding() {
		FakeProbe probe = new FakeProbe();
		probe.put(STORE, chest(Direction.NORTH, ChestType.SINGLE));
		SettlementCraftingStore.Binding binding = SettlementCraftingStore.capture(STORE, probe);
		assertNotNull(binding);
		probe.states.put(STORE, chest(Direction.NORTH, ChestType.LEFT));
		assertFalse(binding.valid());
	}

	@Test
	void snapshotsMutablePositionAndRejectsNullPosition() {
		FakeProbe probe = new FakeProbe();
		probe.put(STORE, chest(Direction.NORTH, ChestType.SINGLE));
		BlockPos.MutableBlockPos mutable = STORE.mutable();
		SettlementCraftingStore.Binding binding = SettlementCraftingStore.capture(mutable, probe);
		assertNotNull(binding);
		mutable.set(900, 70, 900);
		assertTrue(binding.valid());
		probe.reads.clear();
		assertNull(SettlementCraftingStore.capture((BlockPos) null, probe));
		assertTrue(probe.reads.isEmpty());
	}

	@Test
	void overflowingPartnerCoordinatesAreRejectedWithoutWrappedReads() {
		for (Direction facing : Direction.Plane.HORIZONTAL) {
			BlockState main = chest(facing, ChestType.LEFT);
			Direction connection = ChestBlock.getConnectedDirection(main);
			BlockPos edge = new BlockPos(connection.getStepX() > 0 ? Integer.MAX_VALUE
				: connection.getStepX() < 0 ? Integer.MIN_VALUE : 0, 64,
				connection.getStepZ() > 0 ? Integer.MAX_VALUE : connection.getStepZ() < 0 ? Integer.MIN_VALUE : 0);
			FakeProbe probe = new FakeProbe();
			probe.put(edge, main);
			assertNull(SettlementCraftingStore.capture(edge, probe));
			assertEquals(List.of(new Read("state", edge)), probe.reads);
		}
	}

	private static BlockState chest(Direction facing, ChestType type) {
		return Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing).setValue(ChestBlock.TYPE, type);
	}

	private static BlockPos partner(Direction facing, ChestType type) {
		return STORE.relative(ChestBlock.getConnectedDirection(chest(facing, type)));
	}

	private static FakeProbe pair(Direction facing, ChestType type) {
		FakeProbe probe = new FakeProbe();
		probe.put(STORE, chest(facing, type));
		probe.put(partner(facing, type), chest(facing, type == ChestType.LEFT ? ChestType.RIGHT : ChestType.LEFT));
		return probe;
	}

	private record Read(String kind, BlockPos position) { }

	/** No mutation or container operations exist on the production Probe interface. */
	private static final class FakeProbe implements SettlementCraftingStore.Probe {
		final Map<BlockPos, BlockState> states = new HashMap<>();
		final Map<BlockPos, Object> identities = new HashMap<>();
		final Set<BlockPos> unready = new HashSet<>();
		final List<Read> reads = new ArrayList<>();
		Runnable afterState = () -> { };

		void put(BlockPos position, BlockState state) {
			states.put(position, state);
			identities.put(position, new Object());
		}
		@Override
		public boolean ready(BlockPos position) { return states.containsKey(position) && !unready.contains(position); }
		@Override
		public BlockState state(BlockPos position) {
			assertTrue(ready(position), "Must not read unknown state: " + position);
			reads.add(new Read("state", position));
			afterState.run();
			return states.get(position);
		}
		@Override
		public Object identity(BlockPos position) {
			assertTrue(ready(position), "Must not read unknown entity: " + position);
			reads.add(new Read("identity", position));
			return identities.get(position);
		}
	}
}
