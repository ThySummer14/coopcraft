package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.thysummer.coopcraft.civilization.PlayerProtectedAreas.Status.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production SavedData boundary without loading a world or starting a server. */
class PlayerProtectedAreasTest {
	private static final UUID OWNER = new UUID(504, 1);
	private static final UUID OTHER = new UUID(504, 2);
	private static final String FIELD = "PlayerProtectedAreas";

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void reversedNegativeCornersBecomeInclusiveFullHeightBounds() {
		SettlementSavedData data = new SettlementSavedData();
		var added = data.addProtectedArea(OWNER, "  地下室与屋顶  ", -2, -4, -12, -15);
		assertEquals(ADDED, added.status());
		var area = added.area();
		assertEquals("地下室与屋顶", area.name());
		assertEquals(-12, area.minX()); assertEquals(-15, area.minZ());
		assertEquals(-2, area.maxX()); assertEquals(-4, area.maxZ());
		assertEquals(11, area.width()); assertEquals(12, area.depth());
		assertEquals(OWNER, area.ownerId());
		for (int y : new int[] {Integer.MIN_VALUE, -64, 0, 319, Integer.MAX_VALUE}) {
			for (int x : new int[] {-12, -7, -2}) for (int z : new int[] {-15, -9, -4}) {
				assertTrue(data.isResidentModificationBlocked(new BlockPos(x, y, z)));
			}
		}
		for (BlockPos outside : List.of(new BlockPos(-13, 0, -9), new BlockPos(-1, 0, -9),
				new BlockPos(-7, 0, -16), new BlockPos(-7, 0, -3))) {
			assertFalse(data.isResidentModificationBlocked(outside));
		}
		assertEquals(0, data.size(), "Protection does not require or create a settlement");
	}

	@Test void footprintQueriesIncludeSharedEdgesAndHandleLargeOrReversedRectangles() {
		SettlementSavedData data = new SettlementSavedData();
		data.addProtectedArea(OWNER, "House", 10, 20, 12, 24);
		assertTrue(data.isResidentModificationBlocked(0, 0, 10, 20));
		assertTrue(data.isResidentModificationBlocked(12, 24, 20, 30));
		assertTrue(data.isResidentModificationBlocked(100, 100, -100, -100));
		assertTrue(data.isResidentModificationBlocked(Integer.MIN_VALUE, Integer.MIN_VALUE,
			Integer.MAX_VALUE, Integer.MAX_VALUE));
		assertTrue(data.isResidentModificationBlocked(11, 22, 11, 22));
		assertFalse(data.isResidentModificationBlocked(0, 0, 9, 20));
		assertFalse(data.isResidentModificationBlocked(13, 20, 100, 24));
		assertFalse(data.isResidentModificationBlocked(10, 0, 12, 19));
		assertFalse(data.isResidentModificationBlocked(10, 25, 12, 100));
	}

	@Test void protectionIsDimensionLocalAndDoesNotLeakBetweenSavedDataInstances() {
		SettlementSavedData overworld = new SettlementSavedData();
		SettlementSavedData nether = new SettlementSavedData();
		var first = overworld.addProtectedArea(OWNER, "Overworld", 0, 0, 2, 2).area();
		assertTrue(overworld.isResidentModificationBlocked(BlockPos.ZERO));
		assertFalse(nether.isResidentModificationBlocked(BlockPos.ZERO));
		assertTrue(nether.protectedAreas().all().isEmpty());
		var second = nether.addProtectedArea(OTHER, "Nether", 0, 0, 2, 2).area();
		assertNotEquals(first.id(), second.id());
		assertEquals(REMOVED, overworld.removeProtectedArea(first.id(), OWNER, false));
		assertFalse(overworld.isResidentModificationBlocked(BlockPos.ZERO));
		assertTrue(nether.isResidentModificationBlocked(BlockPos.ZERO));
		CompoundTag malformed = save(overworld); malformed.putString(FIELD, "unsupported");
		assertTrue(SettlementSavedData.load(malformed, null).isResidentModificationBlocked(new BlockPos(99, 0, 99)));
		assertFalse(nether.isResidentModificationBlocked(new BlockPos(99, 0, 99)));
	}

	@Test void deletingOneOverlapLeavesOtherOwnersProtectionInForce() {
		SettlementSavedData data = new SettlementSavedData();
		var first = data.addProtectedArea(OWNER, "First", 0, 0, 4, 4).area();
		var second = data.addProtectedArea(OTHER, "Second", 2, 2, 6, 6).area();
		assertEquals(REMOVED, data.removeProtectedArea(first.id(), OWNER, false));
		assertFalse(data.isResidentModificationBlocked(BlockPos.ZERO));
		assertTrue(data.isResidentModificationBlocked(new BlockPos(2, 300, 2)));
		assertTrue(data.isResidentModificationBlocked(new BlockPos(6, -64, 6)));
		assertEquals(NOT_OWNER, data.removeProtectedArea(second.id(), OWNER, false));
		assertTrue(data.isResidentModificationBlocked(new BlockPos(2, 300, 2)));
		assertEquals(REMOVED, data.removeProtectedArea(second.id(), OWNER, true));
		assertFalse(data.isResidentModificationBlocked(new BlockPos(2, 300, 2)));
	}

	@Test void singleBlockAndMaximumEdgesAreAcceptedButOversizedOrOverflowingBoundsAreRejected() {
		SettlementSavedData data = new SettlementSavedData();
		int bound = PlayerProtectedAreas.MAX_COORDINATE;
		for (int sign : new int[] {-1, 1}) {
			var one = data.addProtectedArea(OWNER, "Boundary", sign * bound, sign * bound, sign * bound, sign * bound);
			assertEquals(ADDED, one.status());
			assertEquals(1, one.area().width()); assertEquals(1, one.area().depth());
		}
		var largest = data.addProtectedArea(OWNER, "Maximum", -64, -64, 63, 63);
		assertEquals(ADDED, largest.status());
		assertEquals(PlayerProtectedAreas.MAX_EDGE, largest.area().width());
		assertEquals(PlayerProtectedAreas.MAX_EDGE, largest.area().depth());
		for (int[] corners : List.of(new int[] {0, 0, 128, 0}, new int[] {0, 128, 0, 0},
				new int[] {bound + 1, 0, bound + 1, 0}, new int[] {0, -bound - 1, 0, -bound - 1},
				new int[] {Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 0},
				new int[] {0, Integer.MAX_VALUE, 0, Integer.MIN_VALUE})) {
			CompoundTag before = save(data); data.setDirty(false);
			assertFalse(PlayerProtectedAreas.validBounds(corners[0], corners[1], corners[2], corners[3]));
			assertEquals(INVALID, data.addProtectedArea(OWNER, "Invalid", corners[0], corners[1], corners[2], corners[3]).status());
			assertFalse(data.isDirty()); assertEquals(before, save(data));
		}
	}

	@Test void invalidOwnersAndNamesCannotCreateAnAreaOrDirtyData() {
		SettlementSavedData data = new SettlementSavedData();
		for (String name : new String[] {null, "", " \t\n", "x".repeat(49), "a\nb", "a\u0000b",
				"a\u2028b", "a\u2029b", "a\u202Eb", "a\u200Bb", "a\uD800b", "a\uDC00b"}) {
			var result = data.addProtectedArea(OWNER, name, 0, 0, 0, 0);
			assertEquals(INVALID, result.status()); assertNull(result.area());
		}
		assertEquals(INVALID, data.addProtectedArea(null, "House", 0, 0, 0, 0).status());
		assertFalse(data.isDirty()); assertEquals(0, data.protectedAreas().size());
		assertFalse(PlayerProtectedAreas.validName(" noncanonical "));
		assertEquals(ADDED, data.addProtectedArea(OWNER, "x".repeat(48), 0, 0, 0, 0).status());
		assertEquals(ADDED, data.addProtectedArea(OWNER, "🏠".repeat(48), 0, 0, 0, 0).status());
		assertEquals(INVALID, data.addProtectedArea(OWNER, "🏠".repeat(49), 0, 0, 0, 0).status());
	}

	@Test void perOwnerQuotaNeverEvictsExistingProtectionAndRemovalRestoresCapacity() {
		SettlementSavedData data = new SettlementSavedData();
		for (int i = 0; i < PlayerProtectedAreas.MAX_PER_OWNER; i++) {
			assertEquals(ADDED, data.addProtectedArea(OWNER, "House " + i, i, 0, i, 0).status());
		}
		CompoundTag before = save(data); data.setDirty(false);
		assertEquals(OWNER_LIMIT, data.addProtectedArea(OWNER, "Extra", 100, 0, 100, 0).status());
		assertFalse(data.isDirty()); assertEquals(before, save(data));
		assertEquals(PlayerProtectedAreas.MAX_PER_OWNER, data.protectedAreas().ownedCount(OWNER));
		assertEquals(ADDED, data.addProtectedArea(OTHER, "Other owner", 100, 0, 100, 0).status());
		UUID first = data.protectedAreas().all().getFirst().id();
		assertEquals(REMOVED, data.removeProtectedArea(first, OWNER, false));
		assertEquals(ADDED, data.addProtectedArea(OWNER, "Replacement", 0, 0, 0, 0).status());
		assertNotEquals(first, data.protectedAreas().all().getLast().id());
	}

	@Test void dimensionQuotaIsSharedByOwnersAndNeverEvictsAnArea() {
		SettlementSavedData data = new SettlementSavedData();
		for (int i = 0; i < PlayerProtectedAreas.MAX_AREAS; i++) {
			UUID owner = new UUID(505, i / PlayerProtectedAreas.MAX_PER_OWNER);
			assertEquals(ADDED, data.addProtectedArea(owner, "House " + i, 0, 0, 0, 0).status());
		}
		CompoundTag before = save(data); data.setDirty(false);
		assertEquals(DIMENSION_LIMIT, data.addProtectedArea(OWNER, "Extra", 0, 0, 0, 0).status());
		assertFalse(data.isDirty()); assertEquals(before, save(data));
		assertEquals(PlayerProtectedAreas.MAX_AREAS, data.protectedAreas().size());
		var first = data.protectedAreas().all().getFirst();
		assertEquals(REMOVED, data.removeProtectedArea(first.id(), OWNER, true));
		assertTrue(data.isResidentModificationBlocked(BlockPos.ZERO));
		assertEquals(ADDED, data.addProtectedArea(OWNER, "Replacement", 0, 0, 0, 0).status());
		assertEquals(PlayerProtectedAreas.MAX_AREAS, data.protectedAreas().size());
	}

	@Test void onlySuccessfulAddsAndRemovesDirtySavedData() {
		SettlementSavedData data = new SettlementSavedData();
		assertFalse(data.isDirty());
		assertEquals(NOT_FOUND, data.removeProtectedArea(UUID.randomUUID(), OWNER, false));
		assertEquals(INVALID, data.removeProtectedArea(null, OWNER, false));
		assertFalse(data.isDirty());
		var area = data.addProtectedArea(OWNER, "House", 0, 0, 2, 2).area();
		assertTrue(data.isDirty()); data.setDirty(false);
		assertEquals(NOT_OWNER, data.removeProtectedArea(area.id(), OTHER, false));
		assertEquals(INVALID, data.removeProtectedArea(area.id(), null, true));
		assertTrue(data.protectedAreas().find(area.id()).isPresent());
		assertEquals(1, data.protectedAreas().ownedCount(OWNER));
		assertEquals(0, data.protectedAreas().ownedCount(null));
		assertTrue(data.protectedAreas().find(null).isEmpty());
		assertTrue(data.isResidentModificationBlocked(BlockPos.ZERO));
		assertTrue(data.isResidentModificationBlocked(0, 0, 0, 0));
		assertTrue(data.isResidentModificationBlocked(null));
		assertFalse(data.isResidentModificationBlocked(new BlockPos(50, 64, 50)));
		assertFalse(data.isDirty());
		save(data); assertFalse(data.isDirty());
		assertEquals(REMOVED, data.removeProtectedArea(area.id(), OWNER, false));
		assertTrue(data.isDirty()); data.setDirty(false);
		assertEquals(NOT_FOUND, data.removeProtectedArea(area.id(), OWNER, false));
		assertFalse(data.isDirty());
	}

	@Test void querySnapshotsCannotMutateSavedDataOrFollowLaterEdits() {
		SettlementSavedData data = new SettlementSavedData();
		PlayerProtectedAreas empty = data.protectedAreas();
		var area = data.addProtectedArea(OWNER, "House", 0, 0, 2, 2).area();
		PlayerProtectedAreas snapshot = data.protectedAreas();
		assertEquals(0, empty.size());
		assertThrows(UnsupportedOperationException.class, () -> snapshot.all().clear());
		assertThrows(UnsupportedOperationException.class, () -> snapshot.all().add(area));
		data.removeProtectedArea(area.id(), OWNER, false);
		assertEquals(List.of(area), snapshot.all());
		assertEquals(0, data.protectedAreas().size());
		assertFalse(data.isResidentModificationBlocked(BlockPos.ZERO));
	}

	@Test void stableIdsOwnersAndBoundsSurviveTwoReloadsWithoutDirtying() {
		SettlementSavedData data = populatedData();
		var first = data.addProtectedArea(OWNER, "First", -12, -13, -10, -11).area();
		var second = data.addProtectedArea(OTHER, "Second", 6, 8, 2, 3).area();
		CompoundTag expected = save(data);
		assertEquals(23, expected.getInt("DataVersion"));
		assertEquals(1, expected.getCompound(FIELD).getInt("Version"));
		for (int pass = 0; pass < 2; pass++) {
			data = SettlementSavedData.load(save(data), null);
			assertFalse(data.isDirty()); assertFalse(data.protectedAreas().blocked());
			assertEquals(List.of(first, second), data.protectedAreas().all());
			assertEquals(expected, save(data));
			assertTrue(data.isResidentModificationBlocked(new BlockPos(-12, -64, -13)));
			assertTrue(data.isResidentModificationBlocked(new BlockPos(6, 319, 8)));
		}
	}

	@Test void legacyMissingFieldDoesNotImportObservationsOrManualResourceMarks() {
		SettlementSavedData source = populatedData();
		CompoundTag legacy = save(source); legacy.remove(FIELD); legacy.putInt("DataVersion", 20);
		SettlementSavedData data = SettlementSavedData.load(legacy, null);
		for (int pass = 0; pass < 2; pass++) {
			assertFalse(data.isDirty()); assertFalse(data.protectedAreas().blocked());
			assertTrue(data.protectedAreas().all().isEmpty());
			assertFalse(data.isResidentModificationBlocked(BlockPos.ZERO));
			assertFalse(data.isResidentModificationBlocked(-100, -100, 100, 100));
			assertEquals(withoutProtectionAndVersion(legacy), withoutProtectionAndVersion(save(data)));
			data = SettlementSavedData.load(save(data), null);
		}
	}

	@Test void absentOrRecognizedLegacyRootVersionsMayInitializeEmptyProtection() {
		for (Tag version : new Tag[] {null, IntTag.valueOf(0), IntTag.valueOf(1), IntTag.valueOf(19), IntTag.valueOf(20)}) {
			CompoundTag legacy = save(populatedData()); legacy.remove(FIELD);
			if (version == null) legacy.remove("DataVersion");
			else legacy.put("DataVersion", version);
			SettlementSavedData data = SettlementSavedData.load(legacy, null);
			for (int reload = 0; reload <= 2; reload++) {
				assertFalse(data.protectedAreas().blocked());
				assertFalse(data.isResidentModificationBlocked(BlockPos.ZERO));
				assertTrue(data.protectedAreas().all().isEmpty());
				assertFalse(data.isDirty());
				CompoundTag output = save(data);
				assertTrue(output.contains(FIELD, Tag.TAG_COMPOUND));
				assertEquals(1, output.getCompound(FIELD).getInt("Version"));
				assertTrue(output.getCompound(FIELD).getList("Areas", Tag.TAG_COMPOUND).isEmpty());
				assertEquals(withoutProtectionAndVersion(legacy), withoutProtectionAndVersion(output));
				data = SettlementSavedData.load(output, null);
			}
		}
	}

	@Test void currentAndFutureRootVersionsWithMissingProtectionStayBlockedAcrossTwoReloads() {
		for (int version : new int[] {21, 22, Integer.MAX_VALUE}) {
			assertMissingProtectionStaysBlocked(IntTag.valueOf(version));
		}
	}

	@Test void malformedRootVersionsCannotDisguiseMissingProtectionAsALegacySave() {
		for (Tag version : List.of(IntTag.valueOf(-1), IntTag.valueOf(Integer.MIN_VALUE),
				StringTag.valueOf("20"), ByteTag.valueOf((byte) 20), ShortTag.valueOf((short) 20),
				LongTag.valueOf(20L), FloatTag.valueOf(20.0F), DoubleTag.valueOf(20.0),
				new IntArrayTag(new int[] {20}), new CompoundTag(), new ListTag())) {
			assertMissingProtectionStaysBlocked(version);
		}
	}

	@Test void aValidProtectionSubtreeDoesNotReinterpretUnrelatedRootVersionSemantics() {
		SettlementSavedData source = populatedData();
		var area = source.addProtectedArea(OWNER, "House", 0, 0, 2, 2).area();
		for (Tag version : List.of(IntTag.valueOf(99), StringTag.valueOf("future root"))) {
			CompoundTag input = save(source); input.put("DataVersion", version);
			SettlementSavedData data = SettlementSavedData.load(input, null);
			assertFalse(data.protectedAreas().blocked());
			assertEquals(List.of(area), data.protectedAreas().all());
			assertTrue(data.isResidentModificationBlocked(BlockPos.ZERO));
			assertFalse(data.isResidentModificationBlocked(new BlockPos(99, 0, 99)));
			assertFalse(data.isDirty());
			assertEquals(withoutProtectionAndVersion(input), withoutProtectionAndVersion(save(data)));
		}
	}

	@Test void editingProtectionLeavesJobsMembersResourcesAndSimulationCargoUnchanged() {
		SettlementSavedData data = populatedData();
		CompoundTag before = save(data);
		var area = data.addProtectedArea(OWNER, "Over existing work", -10, -10, 10, 10).area();
		assertEquals(withoutProtectionAndVersion(before), withoutProtectionAndVersion(save(data)));
		assertEquals(REMOVED, data.removeProtectedArea(area.id(), OWNER, false));
		assertEquals(before, save(data));
	}

	@Test void malformedUnknownAndOverQuotaSubtreesArePreservedAndFailClosedAcrossTwoReloads() {
		SettlementSavedData source = populatedData();
		source.addProtectedArea(OWNER, "House", -3, -5, 4, 6);
		CompoundTag baseline = save(source);
		CompoundTag valid = baseline.getCompound(FIELD);
		List<Tag> malformed = malformedSubtrees(valid);
		for (Tag raw : malformed) {
			CompoundTag input = baseline.copy(); input.put(FIELD, raw.copy());
			SettlementSavedData data = SettlementSavedData.load(input, null);
			for (int pass = 0; pass < 2; pass++) {
				assertTrue(data.protectedAreas().blocked(), raw::toString);
				assertTrue(data.protectedAreas().all().isEmpty());
				assertTrue(data.isResidentModificationBlocked(BlockPos.ZERO));
				assertTrue(data.isResidentModificationBlocked(new BlockPos(100_000, 64, -100_000)));
				assertTrue(data.isResidentModificationBlocked(-100_000, -100_000, 100_000, 100_000));
				assertEquals(DATA_UNAVAILABLE, data.addProtectedArea(OWNER, "Cannot overwrite", 0, 0, 1, 1).status());
				assertEquals(DATA_UNAVAILABLE, data.removeProtectedArea(UUID.randomUUID(), OWNER, true));
				assertFalse(data.isDirty());
				CompoundTag output = save(data);
				assertEquals(raw, output.get(FIELD));
				assertEquals(withoutProtectionAndVersion(baseline), withoutProtectionAndVersion(output));
				data = SettlementSavedData.load(output, null);
			}
		}
	}

	@Test void preservedTagsAndValidSaveOutputsHaveNoMutableAliases() {
		CompoundTag raw = new CompoundTag(); raw.putString("Unknown", "original");
		CompoundTag root = save(new SettlementSavedData()); root.put(FIELD, raw);
		SettlementSavedData data = SettlementSavedData.load(root, null);
		raw.putString("Unknown", "input modified");
		save(data).getCompound(FIELD).putString("Unknown", "output modified");
		assertEquals("original", save(data).getCompound(FIELD).getString("Unknown"));
		assertTrue(data.isResidentModificationBlocked(BlockPos.ZERO));
		SettlementSavedData valid = new SettlementSavedData();
		var area = valid.addProtectedArea(OWNER, "House", 0, 0, 1, 1).area();
		CompoundTag serialized = save(valid);
		entry(serialized.getCompound(FIELD)).putInt("MaxX", 127);
		assertEquals(area, valid.protectedAreas().find(area.id()).orElseThrow());
		assertFalse(valid.isResidentModificationBlocked(new BlockPos(50, 0, 0)));
	}

	private static List<Tag> malformedSubtrees(CompoundTag valid) {
		List<Tag> bad = new ArrayList<>();
		bad.add(StringTag.valueOf("unrecognized raw data")); bad.add(IntTag.valueOf(3)); bad.add(new ListTag());
		bad.add(new CompoundTag());
		bad.add(changed(valid, root -> root.putInt("Version", 2)));
		bad.add(changed(valid, root -> root.putString("Version", "1")));
		bad.add(changed(valid, root -> root.putByte("Version", (byte) 1)));
		bad.add(changed(valid, root -> root.remove("Version")));
		bad.add(changed(valid, root -> root.remove("Areas")));
		bad.add(changed(valid, root -> root.putString("FuturePolicy", "retain me")));
		bad.add(changed(valid, root -> root.putString("Areas", "wrong list type")));
		bad.add(changed(valid, root -> {
			ListTag strings = new ListTag(); strings.add(StringTag.valueOf("not an area")); root.put("Areas", strings);
		}));
		for (String field : List.of("Id", "OwnerId", "Name", "MinX", "MinZ", "MaxX", "MaxZ")) {
			bad.add(changed(valid, root -> entry(root).remove(field)));
			bad.add(changed(valid, root -> entry(root).put(field,
				field.equals("Name") ? IntTag.valueOf(1) : StringTag.valueOf("wrong type"))));
		}
		bad.add(changed(valid, root -> entry(root).putIntArray("Id", new int[] {1, 2, 3})));
		bad.add(changed(valid, root -> entry(root).putIntArray("OwnerId", new int[] {1, 2, 3, 4, 5})));
		bad.add(changed(valid, root -> entry(root).putString("FutureField", "preserve")));
		bad.add(changed(valid, root -> entry(root).putInt("MinX", 5)));
		bad.add(changed(valid, root -> entry(root).putInt("MinZ", 7)));
		bad.add(changed(valid, root -> entry(root).putInt("MaxX", 125)));
		bad.add(changed(valid, root -> entry(root).putInt("MaxZ", 123)));
		bad.add(changed(valid, root -> entry(root).putInt("MinX", Integer.MIN_VALUE)));
		bad.add(changed(valid, root -> entry(root).putInt("MaxX", Integer.MAX_VALUE)));
		bad.add(changed(valid, root -> entry(root).putLong("MinZ", -5L)));
		bad.add(changed(valid, root -> {
			entry(root).putInt("MinX", PlayerProtectedAreas.MAX_COORDINATE + 1);
			entry(root).putInt("MaxX", PlayerProtectedAreas.MAX_COORDINATE + 1);
		}));
		for (String name : List.of("", " ", "x".repeat(49), " untrimmed ", "a\nb", "a\u2028b",
				"a\u2029b", "a\u202Eb", "a\u200Bb", "a\uD800b", "a\uDC00b")) {
			bad.add(changed(valid, root -> entry(root).putString("Name", name)));
		}
		bad.add(changed(valid, root -> root.getList("Areas", Tag.TAG_COMPOUND).add(entry(root).copy())));
		bad.add(changed(valid, root -> {
			ListTag entries = root.getList("Areas", Tag.TAG_COMPOUND);
			for (int i = 1; i <= PlayerProtectedAreas.MAX_PER_OWNER; i++) {
				CompoundTag extra = entry(root).copy(); extra.putUUID("Id", new UUID(506, i)); entries.add(extra);
			}
		}));
		bad.add(changed(valid, root -> {
			ListTag entries = root.getList("Areas", Tag.TAG_COMPOUND);
			for (int i = 1; i <= PlayerProtectedAreas.MAX_AREAS; i++) {
				CompoundTag extra = entry(root).copy(); extra.putUUID("Id", new UUID(507, i));
				extra.putUUID("OwnerId", new UUID(508, i)); entries.add(extra);
			}
		}));
		return bad;
	}

	private static CompoundTag changed(CompoundTag valid, Consumer<CompoundTag> edit) {
		CompoundTag copy = valid.copy(); edit.accept(copy); return copy;
	}

	private static void assertMissingProtectionStaysBlocked(Tag version) {
		CompoundTag input = save(populatedData()); input.remove(FIELD); input.put("DataVersion", version.copy());
		SettlementSavedData data = SettlementSavedData.load(input, null);
		for (int reload = 0; reload <= 2; reload++) {
			assertTrue(data.protectedAreas().blocked(), version::toString);
			assertTrue(data.protectedAreas().all().isEmpty());
			assertTrue(data.isResidentModificationBlocked(BlockPos.ZERO));
			assertTrue(data.isResidentModificationBlocked(new BlockPos(100_000, 319, -100_000)));
			assertTrue(data.isResidentModificationBlocked(-100_000, -100_000, 100_000, 100_000));
			assertEquals(DATA_UNAVAILABLE, data.addProtectedArea(OWNER, "Cannot repair by editing", 0, 0, 1, 1).status());
			assertEquals(DATA_UNAVAILABLE, data.removeProtectedArea(UUID.randomUUID(), OWNER, true));
			assertFalse(data.isDirty());
			CompoundTag output = save(data);
			assertFalse(output.contains(FIELD), "Saving must not silently replace missing protection with an empty subtree");
			assertEquals(withoutProtectionAndVersion(input), withoutProtectionAndVersion(output));
			CompoundTag reusedTarget = input.copy(); reusedTarget.put(FIELD, new CompoundTag());
			data.save(reusedTarget, null);
			assertFalse(reusedTarget.contains(FIELD), "A reused output tag must retain the missing-field state too");
			assertFalse(data.isDirty());
			data = SettlementSavedData.load(output, null);
		}
	}

	private static CompoundTag entry(CompoundTag protection) {
		return protection.getList("Areas", Tag.TAG_COMPOUND).getCompound(0);
	}

	private static CompoundTag save(SettlementSavedData data) { return data.save(new CompoundTag(), null); }

	private static CompoundTag withoutProtectionAndVersion(CompoundTag tag) {
		CompoundTag copy = tag.copy(); copy.remove(FIELD); copy.remove("DataVersion"); return copy;
	}

	private static SettlementSavedData populatedData() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement camp = data.getOrCreate(BlockPos.ZERO, "Existing settlement", 4);
		data.registerMember(camp, OWNER, 5); data.setSatiety(camp, OWNER, 42);
		data.setStockpile(camp, new BlockPos(2, 64, 2));
		data.toggleResource(camp, BlockPos.ZERO);
		data.recordResourceObservation(camp, new BlockPos(1, 64, 1), true);
		data.setConstructionJob(camp, new ConstructionJob(ConstructionPlanner.STONE_COTTAGE,
			new BlockPos(3, 64, 3), 7, "existing-fingerprint", new UUID(509, 1)));
		var simulation = data.getOrCreateSimulation(camp);
		simulation.mapResident(OWNER, false, 42);
		simulation.world().importExternalBatch(ResourceType.WOOD, 12, simulation.campOwner());
		simulation.world().importExternalBatch(ResourceType.WILD_FRUIT, 5, simulation.campOwner());
		data.setDirty(false);
		return data;
	}
}
