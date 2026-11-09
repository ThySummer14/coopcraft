package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettlementObservationClaimsTest {
	private static final UUID ACTOR = new UUID(303, 1);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	private static final class Fixture {
		SettlementSavedData data = new SettlementSavedData();
		Settlement camp = data.getOrCreate(BlockPos.ZERO, "Claims", 0);
		int reads;
		Fixture() { data.registerMember(camp, ACTOR, 0); }
		CompoundTag save() { return data.save(new CompoundTag(), null); }
		void load(CompoundTag saved) { data = SettlementSavedData.load(saved, null); }
		SettlementResourceDiscovery.Probe probe(List<SettlementMembershipClaims.Source> sources, Runnable onRead) {
			return new SettlementResourceDiscovery.Probe() {
				@Override public SettlementObservationClaims membership() { return SettlementObservationClaims.capture(sources, Level.OVERWORLD, data); }
				@Override public SettlementResourceDiscovery.Observer observer(UUID id) {
					return new SettlementResourceDiscovery.Observer(BlockPos.ZERO, true, false, true, true, false, false);
				}
				@Override public boolean inBounds(BlockPos pos) { return true; }
				@Override public boolean loaded(BlockPos pos) { return true; }
				@Override public boolean entityTicking(BlockPos pos) { return true; }
				@Override public BlockState blockState(BlockPos pos) {
					reads++; onRead.run();
					return Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3);
				}
			};
		}
		void scan(List<SettlementMembershipClaims.Source> sources) {
			var probe = probe(sources, () -> {});
			for (int i = 0; i < 100; i++) data.resourceDiscovery().tick(data, i, probe);
		}
	}

	private static SettlementMembershipClaims.Source source(ResourceKey<Level> dimension, SettlementSavedData data) {
		return new SettlementMembershipClaims.Source(dimension, data);
	}

	@Test void twoDimensionClaimsIncludingAnOrphanRefuseAllBlockReads() {
		for (boolean orphan : List.of(false, true)) {
			Fixture local = new Fixture(), foreign = new Fixture();
			if (orphan) { CompoundTag saved = foreign.save(); saved.remove("Settlements"); foreign.load(saved); }
			local.scan(List.of(source(Level.OVERWORLD, local.data), source(Level.NETHER, foreign.data)));
			assertEquals(0, local.reads); assertTrue(local.data.resourceObservationsOf(local.camp).isEmpty());
		}
	}

	@Test void orphanedLocalClaimRefusesAllBlockReads() {
		Fixture f = new Fixture(); CompoundTag saved = f.save(); saved.remove("Settlements"); f.load(saved);
		f.scan(List.of(source(Level.OVERWORLD, f.data)));
		assertEquals(0, f.reads);
	}

	@Test void incompleteDuplicateOrTooManySourcesFailClosed() {
		Fixture f = new Fixture(); var local = source(Level.OVERWORLD, f.data);
		List<List<SettlementMembershipClaims.Source>> invalid = new ArrayList<>();
		invalid.add(null); invalid.add(List.of()); invalid.add(List.of(source(Level.NETHER, f.data)));
		invalid.add(List.of(local, local)); invalid.add(List.of(local, source(Level.NETHER, f.data)));
		invalid.add(java.util.Arrays.asList(local, null));
		List<SettlementMembershipClaims.Source> tooMany = new ArrayList<>(); tooMany.add(local);
		for (int i = 0; i < SettlementObservationClaims.MAX_DIMENSIONS; i++) tooMany.add(source(
			ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("coopcraft", "test_" + i)), new SettlementSavedData()));
		invalid.add(tooMany);
		for (var sources : invalid) {
			var snapshot = SettlementObservationClaims.capture(sources, Level.OVERWORLD, f.data);
			assertFalse(snapshot.unique(f.data, f.camp, f.data.rawMember(ACTOR).orElseThrow()));
		}
	}

	@Test void cachedUniqueClaimFailsAfterForeignRegistrationAndCannotReviveAfterRevocationInSameTick() {
		Fixture local = new Fixture(); SettlementSavedData foreign = new SettlementSavedData();
		Settlement other = foreign.getOrCreate(BlockPos.ZERO, "Other", 0);
		var snapshot = SettlementObservationClaims.capture(List.of(source(Level.OVERWORLD, local.data), source(Level.NETHER, foreign)), Level.OVERWORLD, local.data);
		var actor = local.data.rawMember(ACTOR).orElseThrow();
		assertTrue(snapshot.unique(local.data, local.camp, actor));
		foreign.registerMember(other, ACTOR, 0); assertFalse(snapshot.unique(local.data, local.camp, actor));
		foreign.removeMember(ACTOR); assertFalse(snapshot.unique(local.data, local.camp, actor));
	}

	@Test void foreignClaimAddedDuringWorldReadDoesNotRecordThatObservation() {
		Fixture local = new Fixture(); SettlementSavedData foreign = new SettlementSavedData();
		Settlement other = foreign.getOrCreate(BlockPos.ZERO, "Other", 0);
		var sources = List.of(source(Level.OVERWORLD, local.data), source(Level.NETHER, foreign));
		var probe = local.probe(sources, () -> foreign.registerMember(other, ACTOR, 0));
		for (int i = 0; i < 100; i++) local.data.resourceDiscovery().tick(local.data, i, probe);
		assertEquals(1, local.reads); assertTrue(local.data.resourceObservationsOf(local.camp).isEmpty());
	}

	@Test void overwrittenCenterCannotLeaveAStaleIndexedSettlement() {
		Fixture f = new Fixture(); CompoundTag saved = f.save();
		Settlement replacement = new Settlement(new UUID(303, 2), BlockPos.ZERO, "Replacement", 0);
		saved.getList("Settlements", Tag.TAG_COMPOUND).add(replacement.save()); f.load(saved);
		assertEquals(1, f.data.all().size()); assertNull(f.data.settlementById(f.camp.id()));
		assertSame(f.data.all().getFirst(), f.data.settlementById(replacement.id()));
		f.scan(List.of(source(Level.OVERWORLD, f.data))); assertEquals(0, f.reads);
	}

	@Test void duplicateSettlementIdAtDifferentCentersIsAmbiguousRatherThanLastWins() {
		Fixture f = new Fixture(); CompoundTag saved = f.save();
		saved.getList("Settlements", Tag.TAG_COMPOUND).add(new Settlement(f.camp.id(), new BlockPos(100, 0, 0), "Duplicate", 0).save()); f.load(saved);
		assertEquals(2, f.data.all().size()); assertNull(f.data.settlementById(f.camp.id()));
		assertFalse(f.data.canonicalSettlement(f.data.all().getFirst()));
		f.scan(List.of(source(Level.OVERWORLD, f.data))); assertEquals(0, f.reads);
	}
}
