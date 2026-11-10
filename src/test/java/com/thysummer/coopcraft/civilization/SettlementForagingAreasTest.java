package com.thysummer.coopcraft.civilization;

import com.thysummer.coopcraft.civilization.simulation.ResourceType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.thysummer.coopcraft.civilization.SettlementForagingAreas.Status.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SavedData/NBT boundaries and permission queries, without a world, client or GameTest. */
class SettlementForagingAreasTest {
	private static final String FIELD = "ForagingAreas";
	private static final UUID AUTHOR = new UUID(901, 1), OTHER_AUTHOR = new UUID(901, 2);

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void permissionIsAnInclusiveFullHeightSquareAndIsDimensionLocal() {
		SettlementSavedData data = populatedData();
		Settlement camp = camp(data);
		var area = data.permitForagingArea(camp, AUTHOR, new BlockPos(3, 300, 4), 2).area();
		assertEquals(camp.id(), area.settlementId()); assertEquals(AUTHOR, area.authorizer());
		for (int y : new int[] {Integer.MIN_VALUE, -64, 0, 320, Integer.MAX_VALUE}) {
			for (int x : new int[] {1, 3, 5}) for (int z : new int[] {2, 4, 6}) {
				BlockPos position = new BlockPos(x, y, z);
				assertTrue(area.contains(position)); assertTrue(data.canHarvestBerries(camp, position));
			}
		}
		for (BlockPos position : List.of(new BlockPos(0, 0, 4), new BlockPos(6, 0, 4),
				new BlockPos(3, 0, 1), new BlockPos(3, 0, 7), new BlockPos(Integer.MIN_VALUE, 0, Integer.MAX_VALUE))) {
			assertFalse(area.contains(position)); assertFalse(data.canHarvestBerries(camp, position));
		}
		assertFalse(area.contains(null)); assertFalse(data.canHarvestBerries(camp, null));
		SettlementSavedData otherDimension = populatedData();
		assertFalse(otherDimension.canHarvestBerries(camp(otherDimension), new BlockPos(3, 0, 4)));
		assertTrue(otherDimension.foragingAreaOf(camp(otherDimension)).isEmpty());
	}

	@Test void onlyRealChangesDirtyDataAndReplaceTheAuthorizationIdentity() {
		SettlementSavedData data = populatedData(); Settlement camp = camp(data);
		assertEquals(NOT_FOUND, data.clearForagingArea(camp)); assertFalse(data.isDirty());
		var created = data.permitForagingArea(camp, AUTHOR, BlockPos.ZERO, 2);
		assertEquals(CREATED, created.status()); assertTrue(data.isDirty()); data.setDirty(false);
		CompoundTag firstSave = save(data);
		var unchanged = data.permitForagingArea(camp, AUTHOR, new BlockPos(0, 300, 0), 2);
		assertEquals(UNCHANGED, unchanged.status()); assertSame(created.area(), unchanged.area());
		assertEquals(firstSave, save(data)); assertFalse(data.isDirty());
		var replaced = data.permitForagingArea(camp, OTHER_AUTHOR, BlockPos.ZERO, 2);
		assertEquals(REPLACED, replaced.status()); assertNotEquals(created.area().id(), replaced.area().id());
		assertTrue(data.isDirty()); data.setDirty(false);
		var resized = data.permitForagingArea(camp, OTHER_AUTHOR, BlockPos.ZERO, 3);
		assertEquals(REPLACED, resized.status()); assertNotEquals(replaced.area().id(), resized.area().id());
		var moved = data.permitForagingArea(camp, OTHER_AUTHOR, new BlockPos(1, 0, 0), 3);
		assertEquals(REPLACED, moved.status()); assertNotEquals(resized.area().id(), moved.area().id());
		assertEquals(REMOVED, data.clearForagingArea(camp)); assertTrue(data.isDirty()); data.setDirty(false);
		assertFalse(data.canHarvestBerries(camp, BlockPos.ZERO)); assertTrue(data.foragingAreaOf(camp).isEmpty());
		assertEquals(NOT_FOUND, data.clearForagingArea(camp)); assertFalse(data.isDirty());
		var recreated = data.permitForagingArea(camp, AUTHOR, BlockPos.ZERO, 2);
		assertEquals(CREATED, recreated.status()); assertNotEquals(created.area().id(), recreated.area().id());
	}

	@Test void everyCornerMustStayInsideTheHorizontalCampRadiusIgnoringHeight() {
		SettlementSavedData data = populatedData(); Settlement camp = camp(data);
		for (BlockPos center : List.of(new BlockPos(62, 100_000, 0), new BlockPos(-62, -100_000, 0),
				new BlockPos(0, 100_000, 62), new BlockPos(44, -100_000, 44))) {
			assertTrue(SettlementForagingAreas.validRange(camp, center, 1));
			assertNotEquals(INVALID, data.permitForagingArea(camp, AUTHOR, center, 1).status());
		}
		assertTrue(SettlementForagingAreas.validRange(camp, new BlockPos(45, 1000, 0), 16));
		for (BlockPos center : List.of(new BlockPos(63, 0, 0), new BlockPos(0, 0, -63),
				new BlockPos(45, 0, 45), new BlockPos(Integer.MIN_VALUE, 0, Integer.MAX_VALUE))) {
			data.setDirty(false); CompoundTag before = save(data);
			assertFalse(SettlementForagingAreas.validRange(camp, center, 1));
			assertEquals(INVALID, data.permitForagingArea(camp, AUTHOR, center, 1).status());
			assertFalse(data.isDirty()); assertEquals(before, save(data));
		}
		assertFalse(SettlementForagingAreas.validRange(camp, new BlockPos(46, 0, 0), 16));
		assertFalse(SettlementForagingAreas.validRange(null, BlockPos.ZERO, 1));
		assertFalse(SettlementForagingAreas.validRange(camp, null, 1));
	}

	@Test void invalidRadiiCoordinatesAndNullsCannotCreateOrReplacePermissions() {
		SettlementForagingAreas areas = new SettlementForagingAreas(); UUID id = new UUID(902, 1);
		int bound = 29_999_984;
		for (int sign : new int[] {-1, 1}) {
			BlockPos center = new BlockPos(sign * (bound - 16), 0, sign * (bound - 16));
			assertEquals(CREATED, areas.permit(id, AUTHOR, center, 16).status());
		}
		for (int radius : new int[] {Integer.MIN_VALUE, -1, 0, 17, Integer.MAX_VALUE}) {
			assertEquals(INVALID, areas.permit(id, AUTHOR, BlockPos.ZERO, radius).status());
		}
		for (BlockPos center : List.of(new BlockPos(bound, 0, 0), new BlockPos(0, 0, -bound),
				new BlockPos(Integer.MIN_VALUE, 0, 0), new BlockPos(0, 0, Integer.MAX_VALUE))) {
			assertEquals(INVALID, areas.permit(id, AUTHOR, center, 1).status());
		}
		assertEquals(INVALID, areas.permit(null, AUTHOR, BlockPos.ZERO, 1).status());
		assertEquals(INVALID, areas.permit(id, null, BlockPos.ZERO, 1).status());
		assertEquals(INVALID, areas.permit(id, AUTHOR, null, 1).status());
		assertEquals(INVALID, areas.revoke(null).status()); assertTrue(areas.of(null).isEmpty());
		assertThrows(IllegalArgumentException.class,
			() -> new SettlementForagingAreas.Area(id, id, AUTHOR, Integer.MAX_VALUE, 0, 1));
		SettlementSavedData data = populatedData(); Settlement camp = camp(data);
		data.permitForagingArea(camp, AUTHOR, BlockPos.ZERO, 1); data.setDirty(false);
		CompoundTag before = save(data);
		assertEquals(INVALID, data.permitForagingArea(camp, null, BlockPos.ZERO, 1).status());
		assertEquals(INVALID, data.permitForagingArea(null, AUTHOR, BlockPos.ZERO, 1).status());
		assertEquals(INVALID, data.clearForagingArea(null));
		assertFalse(data.isDirty()); assertEquals(before, save(data));
	}

	@Test void dimensionCapacityNeverEvictsAndStillAllowsReplacementAndExplicitRemoval() {
		SettlementSavedData data = new SettlementSavedData(); List<Settlement> camps = new ArrayList<>();
		for (int i = 0; i <= SettlementForagingAreas.MAX_PER_DIMENSION; i++) {
			camps.add(data.getOrCreate(new BlockPos(i * 100, 0, 0), "Camp " + i, 0));
			if (i < SettlementForagingAreas.MAX_PER_DIMENSION) {
				assertEquals(CREATED, data.permitForagingArea(camps.get(i), AUTHOR, camps.get(i).center(), 1).status());
			}
		}
		Settlement extra = camps.getLast(), first = camps.getFirst();
		CompoundTag before = save(data); data.setDirty(false);
		assertEquals(LIMIT, data.permitForagingArea(extra, AUTHOR, extra.center(), 1).status());
		assertFalse(data.isDirty()); assertEquals(before, save(data));
		assertEquals(REPLACED, data.permitForagingArea(first, OTHER_AUTHOR, first.center(), 2).status());
		assertEquals(REMOVED, data.clearForagingArea(first));
		assertEquals(CREATED, data.permitForagingArea(extra, AUTHOR, extra.center(), 1).status());
		assertTrue(data.foragingAreaOf(first).isEmpty());
		assertEquals(SettlementForagingAreas.MAX_PER_DIMENSION, save(data).getCompound(FIELD).getList("Areas", Tag.TAG_COMPOUND).size());
		SettlementSavedData restored = SettlementSavedData.load(save(data), null);
		assertFalse(restored.foragingAreasBlocked()); assertFalse(restored.isDirty());
		assertEquals(save(data), save(restored));
	}

	@Test void missingFieldNeverDerivesPermissionFromOldVersionsObservationsOrResourceMarks() {
		for (Tag version : new Tag[] {null, IntTag.valueOf(0), IntTag.valueOf(19), IntTag.valueOf(20),
				IntTag.valueOf(21), IntTag.valueOf(22), IntTag.valueOf(99), StringTag.valueOf("future")}) {
			CompoundTag input = save(populatedData()); input.remove(FIELD);
			if (version == null) input.remove("DataVersion"); else input.put("DataVersion", version);
			SettlementSavedData data = SettlementSavedData.load(input, null);
			for (int pass = 0; pass <= 2; pass++) {
				Settlement camp = camp(data);
				assertFalse(data.foragingAreasBlocked()); assertTrue(data.foragingAreaOf(camp).isEmpty());
				assertFalse(data.canHarvestBerries(camp, BlockPos.ZERO));
				assertFalse(data.canHarvestBerries(camp, new BlockPos(1, 64, 1)));
				assertFalse(data.isResidentModificationBlocked(BlockPos.ZERO));
				assertEquals(withoutForagingAndVersion(input), withoutForagingAndVersion(save(data)));
				assertFalse(data.isDirty()); data = SettlementSavedData.load(save(data), null);
			}
		}
	}

	@Test void validPermissionsAndIdsSurviveTwoReloadsWithoutDirtyingOrChangingOtherState() {
		SettlementSavedData data = populatedData(); Settlement camp = camp(data);
		var area = data.permitForagingArea(camp, AUTHOR, new BlockPos(5, -64, -6), 16).area();
		CompoundTag expected = save(data);
		assertEquals(24, expected.getInt("DataVersion")); assertEquals(1, expected.getCompound(FIELD).getInt("Version"));
		for (int pass = 0; pass < 2; pass++) {
			data = SettlementSavedData.load(save(data), null); camp = camp(data);
			assertFalse(data.foragingAreasBlocked()); assertFalse(data.isDirty());
			assertEquals(area, data.foragingAreaOf(camp).orElseThrow());
			assertTrue(data.canHarvestBerries(camp, new BlockPos(5, 1000, -6)));
			assertEquals(expected, save(data));
		}
	}

	@Test void unknownMalformedDuplicateAndOverCapacitySubtreesStayIntactAndBlocked() {
		SettlementSavedData source = populatedData();
		source.permitForagingArea(camp(source), AUTHOR, BlockPos.ZERO, 2);
		CompoundTag baseline = save(source);
		for (Tag raw : malformedSubtrees(baseline.getCompound(FIELD))) {
			CompoundTag input = baseline.copy(); input.put(FIELD, raw.copy());
			SettlementSavedData data = SettlementSavedData.load(input, null);
			for (int pass = 0; pass <= 2; pass++) {
				Settlement camp = camp(data);
				assertTrue(data.foragingAreasBlocked(), raw::toString); assertTrue(data.foragingAreaOf(camp).isEmpty());
				assertFalse(data.canHarvestBerries(camp, BlockPos.ZERO));
				assertEquals(PRESERVED_UNSUPPORTED, data.permitForagingArea(camp, AUTHOR, BlockPos.ZERO, 1).status());
				assertEquals(PRESERVED_UNSUPPORTED, data.clearForagingArea(camp));
				assertFalse(data.isDirty()); assertEquals(raw, save(data).get(FIELD));
				assertEquals(withoutForagingAndVersion(baseline), withoutForagingAndVersion(save(data)));
				data = SettlementSavedData.load(save(data), null);
			}
		}
	}

	@Test void rawPreservationAndQueryResultsCannotExposeMutableAliases() {
		CompoundTag unknown = new CompoundTag(); unknown.putString("FuturePolicy", "original");
		SettlementForagingAreas blocked = SettlementForagingAreas.load(unknown);
		unknown.putString("FuturePolicy", "modified input");
		((CompoundTag) blocked.save()).putString("FuturePolicy", "modified output");
		assertEquals("original", ((CompoundTag) blocked.save()).getString("FuturePolicy"));
		SettlementForagingAreas original = new SettlementForagingAreas(); UUID campId = new UUID(903, 1);
		var added = original.permit(campId, AUTHOR, BlockPos.ZERO, 1);
		assertTrue(original.of(campId).isEmpty());
		SettlementForagingAreas snapshot = added.value();
		CompoundTag saved = (CompoundTag) snapshot.save(); entry(saved).putInt("Radius", 16);
		assertEquals(added.area(), snapshot.of(campId).orElseThrow());
		assertFalse(snapshot.of(campId).orElseThrow().contains(new BlockPos(2, 0, 0)));
		assertEquals(REMOVED, snapshot.revoke(campId).status());
		assertEquals(added.area(), snapshot.of(campId).orElseThrow());
	}

	@Test void noncanonicalAmbiguousAndOrphanSettlementsCannotUseOrAlterPermission() {
		SettlementSavedData source = populatedData(); Settlement camp = camp(source);
		var area = source.permitForagingArea(camp, AUTHOR, BlockPos.ZERO, 2).area();
		Settlement copied = new Settlement(camp.id(), camp.center(), camp.name(), camp.foundedDay());
		source.setDirty(false); CompoundTag before = save(source);
		assertTrue(source.foragingAreaOf(copied).isEmpty()); assertFalse(source.canHarvestBerries(copied, BlockPos.ZERO));
		assertEquals(INVALID, source.permitForagingArea(copied, AUTHOR, BlockPos.ZERO, 2).status());
		assertEquals(INVALID, source.clearForagingArea(copied)); assertFalse(source.isDirty());
		assertEquals(before, save(source));
		CompoundTag orphan = before.copy(); orphan.put("Settlements", new ListTag());
		SettlementSavedData orphanData = SettlementSavedData.load(orphan, null);
		assertFalse(orphanData.canHarvestBerries(camp, BlockPos.ZERO)); assertTrue(orphanData.foragingAreaOf(camp).isEmpty());
		assertEquals(before.get(FIELD), save(orphanData).get(FIELD)); assertFalse(orphanData.isDirty());
		CompoundTag ambiguous = before.copy(); CompoundTag duplicate = camp.save();
		duplicate.putLong("Center", new BlockPos(100, 0, 0).asLong());
		ambiguous.getList("Settlements", Tag.TAG_COMPOUND).add(duplicate);
		SettlementSavedData duplicateData = SettlementSavedData.load(ambiguous, null);
		for (Settlement duplicateCamp : duplicateData.all()) {
			assertTrue(duplicateData.foragingAreaOf(duplicateCamp).isEmpty());
			assertFalse(duplicateData.canHarvestBerries(duplicateCamp, BlockPos.ZERO));
			assertEquals(INVALID, duplicateData.clearForagingArea(duplicateCamp));
		}
		assertEquals(area.id(), entry(save(duplicateData).getCompound(FIELD)).getUUID("Id"));
		assertFalse(duplicateData.isDirty());
	}

	@Test void liveChecksRevalidateCampBoundsAndCurrentProtectionWithoutChangingPermits() {
		SettlementSavedData data = populatedData(); Settlement camp = camp(data);
		var area = data.permitForagingArea(camp, AUTHOR, BlockPos.ZERO, 2).area();
		var protection = data.addProtectedArea(AUTHOR, "Keep", 0, 0, 0, 0).area();
		assertFalse(data.canHarvestBerries(camp, new BlockPos(0, 300, 0)));
		assertTrue(data.canHarvestBerries(camp, new BlockPos(1, -64, 0)));
		data.removeProtectedArea(protection.id(), AUTHOR, false);
		assertTrue(data.canHarvestBerries(camp, BlockPos.ZERO));
		CompoundTag relocated = save(data);
		relocated.getList("Settlements", Tag.TAG_COMPOUND).getCompound(0).putLong("Center", new BlockPos(1000, 0, 0).asLong());
		SettlementSavedData moved = SettlementSavedData.load(relocated, null); Settlement movedCamp = moved.all().getFirst();
		assertEquals(area, moved.foragingAreaOf(movedCamp).orElseThrow());
		assertFalse(moved.canHarvestBerries(movedCamp, BlockPos.ZERO)); assertFalse(moved.isDirty());
		assertEquals(relocated.get(FIELD), save(moved).get(FIELD));
		for (Tag invalidProtection : new Tag[] {null, StringTag.valueOf("unknown protection")}) {
			CompoundTag damaged = save(data);
			if (invalidProtection == null) damaged.remove("PlayerProtectedAreas");
			else damaged.put("PlayerProtectedAreas", invalidProtection);
			SettlementSavedData blocked = SettlementSavedData.load(damaged, null);
			assertFalse(blocked.foragingAreasBlocked()); assertTrue(blocked.protectedAreas().blocked());
			assertEquals(area, blocked.foragingAreaOf(camp(blocked)).orElseThrow());
			assertFalse(blocked.canHarvestBerries(camp(blocked), BlockPos.ZERO));
			assertEquals(damaged.get(FIELD), save(blocked).get(FIELD)); assertFalse(blocked.isDirty());
		}
	}

	@Test void editsAndRepeatedQueriesPreserveMembersJobsObservationsAndSimulationCargo() {
		SettlementSavedData data = populatedData(); Settlement camp = camp(data); CompoundTag before = save(data);
		data.permitForagingArea(camp, AUTHOR, BlockPos.ZERO, 16); data.setDirty(false);
		for (int i = 0; i < 100; i++) {
			assertFalse(data.foragingAreasBlocked()); assertTrue(data.foragingAreaOf(camp).isPresent());
			assertTrue(data.canHarvestBerries(camp, new BlockPos(1, 64, 1)));
			assertFalse(data.canHarvestBerries(camp, new BlockPos(50, 64, 50)));
		}
		assertFalse(data.isDirty()); assertEquals(withoutForagingAndVersion(before), withoutForagingAndVersion(save(data)));
		data.permitForagingArea(camp, OTHER_AUTHOR, new BlockPos(3, 0, 3), 4);
		assertEquals(withoutForagingAndVersion(before), withoutForagingAndVersion(save(data)));
		assertEquals(REMOVED, data.clearForagingArea(camp)); assertEquals(before, save(data));
	}

	private static List<Tag> malformedSubtrees(CompoundTag valid) {
		List<Tag> malformed = new ArrayList<>();
		malformed.add(StringTag.valueOf("future")); malformed.add(IntTag.valueOf(1));
		malformed.add(new CompoundTag()); malformed.add(new ListTag());
		malformed.add(changed(valid, root -> root.putInt("Version", 2)));
		malformed.add(changed(valid, root -> root.putByte("Version", (byte) 1)));
		malformed.add(changed(valid, root -> root.putString("Version", "1")));
		malformed.add(changed(valid, root -> root.remove("Version")));
		malformed.add(changed(valid, root -> root.remove("Areas")));
		malformed.add(changed(valid, root -> root.putString("FuturePolicy", "retain")));
		malformed.add(changed(valid, root -> root.putString("Areas", "wrong type")));
		malformed.add(changed(valid, root -> {
			ListTag strings = new ListTag(); strings.add(StringTag.valueOf("invalid")); root.put("Areas", strings);
		}));
		for (String field : List.of("Id", "SettlementId", "Authorizer", "CenterX", "CenterZ", "Radius")) {
			malformed.add(changed(valid, root -> entry(root).remove(field)));
			malformed.add(changed(valid, root -> entry(root).putString(field, "wrong type")));
		}
		for (String field : List.of("Id", "SettlementId", "Authorizer")) {
			malformed.add(changed(valid, root -> entry(root).putIntArray(field, new int[] {1, 2, 3})));
			malformed.add(changed(valid, root -> entry(root).putIntArray(field, new int[] {1, 2, 3, 4, 5})));
		}
		for (String field : List.of("CenterX", "CenterZ", "Radius")) {
			for (Tag wrongType : List.of(ByteTag.valueOf((byte) 1), LongTag.valueOf(1L), DoubleTag.valueOf(1.0))) {
				malformed.add(changed(valid, root -> entry(root).put(field, wrongType)));
			}
		}
		malformed.add(changed(valid, root -> entry(root).putString("FutureField", "retain")));
		for (int radius : new int[] {Integer.MIN_VALUE, -1, 0, 17, Integer.MAX_VALUE}) {
			malformed.add(changed(valid, root -> entry(root).putInt("Radius", radius)));
		}
		for (String field : List.of("CenterX", "CenterZ")) {
			for (int coordinate : new int[] {Integer.MIN_VALUE, -29_999_984, 29_999_984, Integer.MAX_VALUE}) {
				malformed.add(changed(valid, root -> entry(root).putInt(field, coordinate)));
			}
		}
		malformed.add(changed(valid, root -> {
			CompoundTag sameCamp = entry(root).copy(); sameCamp.putUUID("Id", new UUID(904, 1));
			root.getList("Areas", Tag.TAG_COMPOUND).add(sameCamp);
		}));
		malformed.add(changed(valid, root -> {
			CompoundTag sameId = entry(root).copy(); sameId.putUUID("SettlementId", new UUID(905, 1));
			root.getList("Areas", Tag.TAG_COMPOUND).add(sameId);
		}));
		malformed.add(changed(valid, root -> {
			for (int i = 1; i <= SettlementForagingAreas.MAX_PER_DIMENSION; i++) {
				CompoundTag extra = entry(root).copy(); extra.putUUID("Id", new UUID(906, i));
				extra.putUUID("SettlementId", new UUID(907, i)); root.getList("Areas", Tag.TAG_COMPOUND).add(extra);
			}
		}));
		return malformed;
	}

	private static CompoundTag changed(CompoundTag valid, Consumer<CompoundTag> edit) {
		CompoundTag copy = valid.copy(); edit.accept(copy); return copy;
	}
	private static CompoundTag entry(CompoundTag root) { return root.getList("Areas", Tag.TAG_COMPOUND).getCompound(0); }
	private static CompoundTag save(SettlementSavedData data) { return data.save(new CompoundTag(), null); }
	private static Settlement camp(SettlementSavedData data) { return data.find(BlockPos.ZERO).orElseThrow(); }
	private static CompoundTag withoutForagingAndVersion(CompoundTag tag) {
		CompoundTag copy = tag.copy(); copy.remove(FIELD); copy.remove("DataVersion"); return copy;
	}

	private static SettlementSavedData populatedData() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement camp = data.getOrCreate(BlockPos.ZERO, "Existing camp", 4);
		data.registerMember(camp, AUTHOR, 5); data.setSatiety(camp, AUTHOR, 42);
		data.setStockpile(camp, new BlockPos(2, 64, 2)); data.toggleResource(camp, BlockPos.ZERO);
		data.recordResourceObservation(camp, new BlockPos(1, 64, 1), true);
		data.setConstructionJob(camp, new ConstructionJob(ConstructionPlanner.STONE_COTTAGE,
			new BlockPos(3, 64, 3), 7, "existing-fingerprint", new UUID(908, 1)));
		var simulation = data.getOrCreateSimulation(camp); simulation.mapResident(AUTHOR, false, 42);
		simulation.world().importExternalBatch(ResourceType.WOOD, 12, simulation.campOwner());
		simulation.world().importExternalBatch(ResourceType.WILD_FRUIT, 5, simulation.campOwner());
		data.setDirty(false); return data;
	}
}
