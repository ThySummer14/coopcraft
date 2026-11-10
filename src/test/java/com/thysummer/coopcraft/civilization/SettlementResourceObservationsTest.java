package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Real SavedData save/load boundary, not a second serializer or discovery implementation. */
class SettlementResourceObservationsTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	private static final class Fixture {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(BlockPos.ZERO, "Observations", 0);
		Fixture() { data.registerMember(camp, new UUID(302, 1), 0); }
		CompoundTag save() { return data.save(new CompoundTag(), null); }
		List<SettlementResourceObservations.Observation> records() { return data.resourceObservationsOf(camp); }
		void add(int count) { for (int i = 0; i < count; i++) data.recordResourceObservation(camp, new BlockPos(i, 0, 0), true); }
	}

	@Test void legacySaveWithMissingFieldStartsEmptyAndKeepsManualMarks() {
		Fixture f = new Fixture(); f.data.toggleResource(f.camp, BlockPos.ZERO); CompoundTag old = f.save();
		old.remove("ResourceObservations"); old.putInt("DataVersion", 19);
		var loaded = SettlementSavedData.load(old, null); var camp = loaded.find(BlockPos.ZERO).orElseThrow();
		assertFalse(loaded.resourceObservationsBlocked()); assertTrue(loaded.resourceObservationsOf(camp).isEmpty());
		assertEquals(f.data.resourcesOf(f.camp), loaded.resourcesOf(camp)); assertFalse(loaded.isDirty());
	}

	@Test void roundTripPreservesTypeUnknownSourceSuppressionAndDoesNotCreatePermission() {
		Fixture f = new Fixture(); f.add(2); String id = f.records().getFirst().id();
		f.data.changeResourceObservation(f.camp, id, true);
		var loaded = SettlementSavedData.load(f.save(), null); var camp = loaded.find(BlockPos.ZERO).orElseThrow();
		assertEquals(f.records(), loaded.resourceObservationsOf(camp));
		assertTrue(loaded.resourcesOf(camp).isEmpty()); assertFalse(loaded.isDirty());
		assertFalse(loaded.recordResourceObservation(camp, BlockPos.ZERO, true));
		assertFalse(loaded.recordResourceObservation(camp, BlockPos.ZERO, false));
		assertEquals(SettlementResourceObservations.State.SUPPRESSED, loaded.resourceObservationsOf(camp).getFirst().state());
	}

	@Test void suppressAtCapacityIsAnInPlaceVetoAndNeverEvictsAnotherRecord() {
		Fixture f = new Fixture(); f.add(SettlementResourceObservations.MAX_PER_SETTLEMENT + 5);
		assertEquals(SettlementResourceObservations.MAX_PER_SETTLEMENT, f.records().size());
		for (var entry : List.copyOf(f.records())) {
			assertEquals(SettlementResourceObservations.Change.CHANGED, f.data.changeResourceObservation(f.camp, entry.id(), true));
		}
		assertEquals(SettlementResourceObservations.MAX_PER_SETTLEMENT, f.records().size());
		for (var entry : f.records()) {
			assertFalse(f.data.recordResourceObservation(f.camp, entry.position(), true));
			assertEquals(SettlementResourceObservations.State.SUPPRESSED, entry.state());
		}
		assertFalse(f.data.recordResourceObservation(f.camp, new BlockPos(99, 0, 0), true));
		String id = f.records().getFirst().id();
		assertEquals(SettlementResourceObservations.Change.CHANGED, f.data.changeResourceObservation(f.camp, id, false));
		assertEquals(SettlementResourceObservations.MAX_PER_SETTLEMENT - 1, f.records().size());
		assertTrue(f.data.recordResourceObservation(f.camp, new BlockPos(99, 0, 0), true));
		assertTrue(f.data.resourcesOf(f.camp).isEmpty());
	}

	@Test void dimensionCapIsSharedAcrossSettlementsWithoutDroppingSuppression() {
		Fixture f = new Fixture();
		for (int c = 0; c < SettlementResourceObservations.MAX_PER_DIMENSION / SettlementResourceObservations.MAX_PER_SETTLEMENT; c++) {
			Settlement camp = f.data.getOrCreate(new BlockPos(c * 100, 0, 0), "Camp " + c, 0);
			for (int i = 0; i < SettlementResourceObservations.MAX_PER_SETTLEMENT; i++) {
				assertTrue(f.data.recordResourceObservation(camp, camp.center().offset(i, 0, 0), true));
			}
		}
		Settlement extra = f.data.getOrCreate(new BlockPos(2000, 0, 0), "Extra", 0);
		assertFalse(f.data.recordResourceObservation(extra, extra.center(), true));
		String id = f.records().getFirst().id();
		assertEquals(SettlementResourceObservations.Change.CHANGED, f.data.changeResourceObservation(f.camp, id, true));
		assertEquals(SettlementResourceObservations.Change.CHANGED, f.data.changeResourceObservation(f.camp, id, false));
		assertTrue(f.data.recordResourceObservation(extra, extra.center(), true));
		assertEquals(SettlementResourceObservations.MAX_PER_DIMENSION,
			f.save().getCompound("ResourceObservations").getList("Records", Tag.TAG_COMPOUND).size());
	}

	@Test void repeatedSuppressionAndInvalidIdsDoNotDirtyData() {
		Fixture f = new Fixture(); f.add(1); String id = f.records().getFirst().id();
		assertEquals(SettlementResourceObservations.Change.NOT_SUPPRESSED, f.data.changeResourceObservation(f.camp, id, false));
		f.data.changeResourceObservation(f.camp, id, true); f.data.setDirty(false);
		assertEquals(SettlementResourceObservations.Change.UNCHANGED, f.data.changeResourceObservation(f.camp, id, true));
		assertEquals(SettlementResourceObservations.Change.NOT_FOUND, f.data.changeResourceObservation(f.camp, "missing", true));
		assertFalse(f.data.isDirty());
		var copiedCamp = new Settlement(f.camp.id(), f.camp.center(), f.camp.name(), f.camp.foundedDay());
		assertFalse(f.data.recordResourceObservation(copiedCamp, BlockPos.ZERO, true));
		assertEquals(SettlementResourceObservations.Change.NOT_FOUND, f.data.changeResourceObservation(copiedCamp, id, false));
	}

	@Test void malformedAndUnknownDataRemainExactAndDisableAllObservationWritesAndReads() {
		Fixture f = new Fixture(); f.add(1); f.data.toggleResource(f.camp, BlockPos.ZERO.above());
		CompoundTag valid = f.save().getCompound("ResourceObservations");
		List<Tag> bad = new ArrayList<>();
		bad.add(StringTag.valueOf("unrecognized raw data"));
		CompoundTag version = valid.copy(); version.putInt("Version", 99); bad.add(version);
		CompoundTag wrongVersionType = valid.copy(); wrongVersionType.putString("Version", "1"); bad.add(wrongVersionType);
		CompoundTag missing = valid.copy(); missing.remove("Records"); bad.add(missing);
		CompoundTag unknownRoot = valid.copy(); unknownRoot.putString("FutureField", "keep me"); bad.add(unknownRoot);
		CompoundTag wrongRecords = valid.copy(); wrongRecords.putString("Records", "keep me too"); bad.add(wrongRecords);
		for (String field : List.of("SettlementId", "Position", "Kind", "Source", "State")) {
			CompoundTag malformed = valid.copy(); malformed.getList("Records", Tag.TAG_COMPOUND).getCompound(0).remove(field); bad.add(malformed);
		}
		for (String field : List.of("Kind", "Source", "State", "Position")) {
			CompoundTag unknown = valid.copy(); unknown.getList("Records", Tag.TAG_COMPOUND).getCompound(0).putString(field, "NATURAL"); bad.add(unknown);
		}
		CompoundTag unknownEntry = valid.copy(); unknownEntry.getList("Records", Tag.TAG_COMPOUND).getCompound(0).putInt("FutureEvidence", 5); bad.add(unknownEntry);
		CompoundTag duplicate = valid.copy(); ListTag duplicates = duplicate.getList("Records", Tag.TAG_COMPOUND);
		duplicates.add(duplicates.getCompound(0).copy()); bad.add(duplicate);
		CompoundTag oversized = valid.copy(); ListTag entries = oversized.getList("Records", Tag.TAG_COMPOUND);
		for (int i = 1; i <= SettlementResourceObservations.MAX_PER_SETTLEMENT; i++) {
			CompoundTag entry = entries.getCompound(0).copy(); entry.putLong("Position", new BlockPos(i, 0, 0).asLong()); entries.add(entry);
		}
		bad.add(oversized);
		CompoundTag mixedDuplicate = valid.copy(); ListTag mixedRecords = mixedDuplicate.getList("Records", Tag.TAG_COMPOUND);
		CompoundTag mixedRecord = mixedRecords.getCompound(0).copy(); mixedRecord.putString("Kind", "STONE"); mixedRecords.add(mixedRecord); bad.add(mixedDuplicate);
		for (Tag raw : List.copyOf(bad)) {
			if (raw instanceof CompoundTag malformed && malformed.getInt("Version") == 2) {
				CompoundTag legacy = malformed.copy(); legacy.putInt("Version", 1); bad.add(legacy);
			}
		}
		for (String kind : List.of("OAK_LOG", "STONE", "FUTURE_KIND")) {
			CompoundTag legacyKind = valid.copy(); legacyKind.putInt("Version", 1);
			legacyKind.getList("Records", Tag.TAG_COMPOUND).getCompound(0).putString("Kind", kind); bad.add(legacyKind);
		}
		for (String field : List.of("SettlementId", "Position", "Kind", "Source", "State")) {
			CompoundTag wrongType = valid.copy(); wrongType.getList("Records", Tag.TAG_COMPOUND).getCompound(0).putInt(field, 1); bad.add(wrongType);
		}
		for (Tag raw : bad) {
			CompoundTag saved = f.save(); saved.put("ResourceObservations", raw.copy());
			var loaded = SettlementSavedData.load(saved, null); var camp = loaded.find(BlockPos.ZERO).orElseThrow();
			assertTrue(loaded.resourceObservationsBlocked()); assertTrue(loaded.resourceObservationsOf(camp).isEmpty());
			assertFalse(loaded.recordResourceObservation(camp, BlockPos.ZERO, true));
			assertFalse(loaded.recordResourceObservation(camp, BlockPos.ZERO, SettlementResourceObservations.Kind.OAK_LOG));
			assertFalse(loaded.recordResourceObservation(camp, BlockPos.ZERO, SettlementResourceObservations.Kind.STONE));
			assertFalse(loaded.recordResourceObservation(camp, BlockPos.ZERO, null));
			assertEquals(SettlementResourceObservations.Change.PRESERVED_UNSUPPORTED, loaded.changeResourceObservation(camp, "berry-0", true));
			assertEquals(SettlementResourceObservations.Change.PRESERVED_UNSUPPORTED, loaded.changeResourceObservation(camp, "berry-0", false));
			loaded.resourceDiscovery().tick(loaded, 1, new SettlementResourceDiscovery.Probe() {
				@Override public SettlementObservationClaims membership() { fail("Blocked data must not scan claims"); return null; }
				@Override public SettlementResourceDiscovery.Observer observer(UUID id) { fail("Blocked data must not scan residents"); return null; }
				@Override public boolean inBounds(BlockPos pos) { fail("No world query"); return false; }
				@Override public boolean loaded(BlockPos pos) { fail("No chunk query"); return false; }
				@Override public boolean entityTicking(BlockPos pos) { fail("No ticking query"); return false; }
				@Override public BlockState blockState(BlockPos pos) { fail("No block query"); return null; }
			});
			assertFalse(loaded.isDirty()); assertEquals(raw, loaded.save(new CompoundTag(), null).get("ResourceObservations"));
			assertEquals(f.data.resourcesOf(f.camp), loaded.resourcesOf(camp));
		}
	}

	@Test void versionOneMigratesOnlyBerrySchemaAndKeepsStableIdsAndCoordinateVeto() {
		Fixture f = new Fixture(); f.add(2);
		String veto = f.records().getFirst().id();
		f.data.changeResourceObservation(f.camp, veto, true);
		CompoundTag old = f.save(); old.getCompound("ResourceObservations").putInt("Version", 1);
		var loaded = SettlementSavedData.load(old, null); var camp = loaded.find(BlockPos.ZERO).orElseThrow();
		assertEquals(f.records(), loaded.resourceObservationsOf(camp));
		assertEquals("berry-0", veto);
		assertFalse(loaded.isDirty());
		assertFalse(loaded.recordResourceObservation(camp, BlockPos.ZERO, SettlementResourceObservations.Kind.OAK_LOG));
		assertFalse(loaded.recordResourceObservation(camp, BlockPos.ZERO, SettlementResourceObservations.Kind.STONE));
		assertFalse(loaded.recordResourceObservation(camp, BlockPos.ZERO, null));
		CompoundTag migrated = loaded.save(new CompoundTag(), null);
		assertEquals(2, migrated.getCompound("ResourceObservations").getInt("Version"));
		assertEquals(22, migrated.getInt("DataVersion"));
		var reloaded = SettlementSavedData.load(migrated, null); var recamp = reloaded.find(BlockPos.ZERO).orElseThrow();
		assertFalse(reloaded.recordResourceObservation(recamp, BlockPos.ZERO, SettlementResourceObservations.Kind.STONE));
		assertEquals(SettlementResourceObservations.Change.CHANGED, reloaded.changeResourceObservation(recamp, veto, false));
		assertTrue(reloaded.resourceObservationsOf(recamp).stream().noneMatch(record -> record.position().equals(BlockPos.ZERO)));
		assertTrue(reloaded.recordResourceObservation(recamp, BlockPos.ZERO, SettlementResourceObservations.Kind.STONE));
	}

	@Test void typedRoundTripChangesOnlyObservationSubtreeNotAuthorityOrInventoryState() {
		Fixture f = new Fixture();
		f.data.toggleResource(f.camp, new BlockPos(9, 0, 0));
		f.data.setStockpile(f.camp, new BlockPos(10, 0, 0));
		CompoundTag before = f.save();
		for (var kind : SettlementResourceObservations.Kind.values()) {
			assertTrue(f.data.recordResourceObservation(f.camp, new BlockPos(kind.ordinal(), 0, 0), kind));
		}
		assertEquals(List.of("berry-", "oak-", "stone-"), f.records().stream()
			.map(record -> record.id().substring(0, record.id().indexOf('-') + 1)).toList());
		String oakId = f.records().get(1).id(); f.data.changeResourceObservation(f.camp, oakId, true);
		CompoundTag after = f.save();
		var loaded = SettlementSavedData.load(after, null); var camp = loaded.find(BlockPos.ZERO).orElseThrow();
		assertEquals(f.records(), loaded.resourceObservationsOf(camp));
		assertTrue(loaded.resourceObservationsOf(camp).stream().allMatch(record -> record.source() == SettlementResourceObservations.Source.UNKNOWN));
		assertFalse(loaded.recordResourceObservation(camp, new BlockPos(1, 0, 0), SettlementResourceObservations.Kind.STONE));
		assertFalse(loaded.recordResourceObservation(camp, new BlockPos(1, 0, 0), null));
		assertEquals(f.data.resourcesOf(f.camp), loaded.resourcesOf(camp));
		before.remove("ResourceObservations"); after.remove("ResourceObservations");
		assertEquals(before, after, "Discovery must not change any inventory, authority, job or other saved subtree");
		assertFalse(loaded.isDirty());
	}

	@Test void typedReplacementAtFullSharedCapsRejectsStaleIdsAndNeverEvictsSuppression() {
		var observations = new SettlementResourceObservations();
		UUID first = new UUID(500, 0);
		for (int c = 0; c < SettlementResourceObservations.MAX_PER_DIMENSION / SettlementResourceObservations.MAX_PER_SETTLEMENT; c++) {
			UUID camp = new UUID(500, c);
			for (int i = 0; i < SettlementResourceObservations.MAX_PER_SETTLEMENT; i++) {
				assertTrue(observations.observe(camp, new BlockPos(i, 0, 0), SettlementResourceObservations.Kind.values()[i % 3]));
			}
			assertFalse(observations.observe(camp, new BlockPos(100, 0, 0), SettlementResourceObservations.Kind.STONE));
		}
		UUID extra = new UUID(501, 0);
		assertFalse(observations.observe(extra, BlockPos.ZERO, SettlementResourceObservations.Kind.OAK_LOG));
		String oldId = observations.of(first).getFirst().id();
		assertTrue(observations.observe(first, BlockPos.ZERO, SettlementResourceObservations.Kind.STONE));
		assertEquals("stone-0", observations.of(first).getFirst().id());
		assertEquals(SettlementResourceObservations.Change.NOT_FOUND, observations.suppress(first, oldId));
		assertEquals(SettlementResourceObservations.Change.NOT_FOUND, observations.resume(first, oldId));
		assertFalse(observations.observe(first, BlockPos.ZERO, SettlementResourceObservations.Kind.STONE));
		assertEquals(SettlementResourceObservations.Change.CHANGED, observations.suppress(first, "stone-0"));
		assertFalse(observations.observe(first, BlockPos.ZERO, SettlementResourceObservations.Kind.OAK_LOG));
		assertFalse(observations.observe(first, BlockPos.ZERO, null));
		var loaded = SettlementResourceObservations.load(observations.save());
		assertEquals(observations.of(first), loaded.of(first));
		assertFalse(loaded.observe(first, BlockPos.ZERO, SettlementResourceObservations.Kind.OAK_LOG));
		assertEquals(SettlementResourceObservations.Change.CHANGED, loaded.resume(first, "stone-0"));
		assertTrue(loaded.observe(extra, BlockPos.ZERO, SettlementResourceObservations.Kind.OAK_LOG));
		assertFalse(loaded.observe(first, BlockPos.ZERO, SettlementResourceObservations.Kind.STONE));
	}

	@Test void ordinaryCandidatesCanBeRemovedAndBooleanCompatibilityStillMeansRipeBerry() {
		Fixture f = new Fixture();
		assertTrue(f.data.recordResourceObservation(f.camp, BlockPos.ZERO, SettlementResourceObservations.Kind.OAK_LOG));
		assertTrue(f.data.recordResourceObservation(f.camp, BlockPos.ZERO, true));
		assertEquals(SettlementResourceObservations.Kind.SWEET_BERRY, f.records().getFirst().kind());
		assertTrue(f.data.recordResourceObservation(f.camp, BlockPos.ZERO, false));
		assertTrue(f.records().isEmpty());
		assertTrue(f.data.recordResourceObservation(f.camp, BlockPos.ZERO, SettlementResourceObservations.Kind.STONE));
		assertTrue(f.data.recordResourceObservation(f.camp, BlockPos.ZERO, null));
		f.data.setDirty(false);
		assertFalse(f.data.recordResourceObservation(f.camp, BlockPos.ZERO, null));
		var copiedCamp = new Settlement(f.camp.id(), f.camp.center(), f.camp.name(), f.camp.foundedDay());
		assertFalse(f.data.recordResourceObservation(copiedCamp, BlockPos.ZERO, SettlementResourceObservations.Kind.STONE));
		assertFalse(f.data.isDirty());
	}

	@Test void snapshotsAndPreservedSaveTagsCannotMutateSavedDataThroughAliases() {
		Fixture f = new Fixture(); f.add(1);
		assertThrows(UnsupportedOperationException.class, () -> f.records().clear());
		CompoundTag bad = new CompoundTag(); bad.putString("Future", "original");
		CompoundTag saved = f.save(); saved.put("ResourceObservations", bad);
		var loaded = SettlementSavedData.load(saved, null); bad.putString("Future", "input changed");
		CompoundTag first = loaded.save(new CompoundTag(), null); first.getCompound("ResourceObservations").putString("Future", "output changed");
		assertEquals("original", loaded.save(new CompoundTag(), null).getCompound("ResourceObservations").getString("Future"));
	}
}
