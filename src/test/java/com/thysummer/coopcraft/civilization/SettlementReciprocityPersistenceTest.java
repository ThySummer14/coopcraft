package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.thysummer.coopcraft.civilization.simulation.Person;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementReciprocityPersistenceTest {
	private static final UUID RESIDENT = new UUID(15, 1);
	private static final String FIRST = "minecraft-player:first";
	private static final String SECOND = "minecraft-player:second";
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void multipleDonorsAndDifferentCountsSurviveTwoReloadsWithoutReplayingAid() {
		var f = new Fixture();
		f.person().restoreSocialState(40, 3, 2);
		f.person().recordAidFrom(FIRST);
		for (int i = 0; i < 3; i++) f.person().recordAidFrom(SECOND);
		assertEquals(Map.of(FIRST, 1, SECOND, 3), f.person().reciprocityMemories());
		var trust = f.person().trustedPeople();
		var events = f.state.world().ledger().events();
		CompoundTag before = f.state.save();
		for (int round = 0; round < 2; round++) {
			f.state = SettlementSimulationState.load(f.settlement, f.state.save());
			assertEquals(Map.of(FIRST, 1, SECOND, 3), f.person().reciprocityMemories());
			assertEquals(trust, f.person().trustedPeople());
			assertEquals(2, f.person().trustIn(FIRST)); assertEquals(6, f.person().trustIn(SECOND));
			assertEquals(36, f.person().grievance());
			assertEquals(3, f.person().socialHours()); assertEquals(2, f.person().previousSocialHours());
			assertEquals(events, f.state.world().ledger().events());
			assertEquals(before, f.state.save());
		}
	}

	@Test void missingLegacyFieldMeansNoInventedMemoriesAndPreservesTrust() {
		var f = new Fixture();
		f.person().recordAidFrom(FIRST); f.person().restoreSocialState(17, 2, 1);
		CompoundTag legacy = f.state.save(); social(legacy).remove("Reciprocity");
		for (int round = 0; round < 2; round++) {
			f.state = SettlementSimulationState.load(f.settlement, legacy);
			assertTrue(f.person().reciprocityMemories().isEmpty());
			assertEquals(0, f.person().reciprocityWith(FIRST)); assertEquals(2, f.person().trustIn(FIRST));
			assertEquals(17, f.person().grievance()); assertEquals(2, f.person().socialHours());
			legacy = f.state.save();
		}
	}

	@Test void malformedEntriesAreSkippedIndividuallyAndValidDuplicateLastWins() {
		var f = new Fixture();
		f.person().recordAidFrom(FIRST); f.person().restoreSocialState(23, 1, 4);
		CompoundTag saved = f.state.save();
		var entries = new ListTag();
		entries.add(entry(FIRST, 2));
		entries.add(entry("", 1)); entries.add(entry(" \t", 1));
		entries.add(entry("negative", -1));
		var missingId = entry("ignored", 7); missingId.remove("PersonId"); entries.add(missingId);
		var missingCount = entry("missing-count", 7); missingCount.remove("Value"); entries.add(missingCount);
		var wrongId = entry("ignored", 7); wrongId.putInt("PersonId", 42); entries.add(wrongId);
		var longCount = entry("long-count", 1); longCount.putLong("Value", 4L); entries.add(longCount);
		var shortCount = entry("short-count", 1); shortCount.putShort("Value", (short) 4); entries.add(shortCount);
		var textCount = entry("text-count", 1); textCount.putString("Value", "4"); entries.add(textCount);
		var doubleCount = entry("double-count", 1); doubleCount.putDouble("Value", 4.0); entries.add(doubleCount);
		entries.add(entry("zero-count", 0));
		entries.add(entry(SECOND, Integer.MAX_VALUE)); entries.add(entry(FIRST, 5));
		social(saved).put("Reciprocity", entries);
		f.state = assertDoesNotThrow(() -> SettlementSimulationState.load(f.settlement, saved));
		assertEquals(Map.of(FIRST, 5, SECOND, Integer.MAX_VALUE), f.person().reciprocityMemories());
		assertEquals(2, f.person().trustIn(FIRST)); assertEquals(0, f.person().trustIn(SECOND));
		assertEquals(23, f.person().grievance());
		CompoundTag sanitized = f.state.save();
		assertEquals(2, social(sanitized).getList("Reciprocity", Tag.TAG_COMPOUND).size());
		f.state = SettlementSimulationState.load(f.settlement, sanitized);
		assertEquals(sanitized, f.state.save());
		assertEquals(Map.of(FIRST, 5, SECOND, Integer.MAX_VALUE), f.person().reciprocityMemories());
		assertEquals(23, f.person().grievance());
	}

	@Test void wrongListTypeAndZeroDuplicateRemainSafe() {
		for (boolean wrongList : List.of(false, true)) {
			var f = new Fixture();
			CompoundTag saved = f.state.save();
			if (wrongList) social(saved).putString("Reciprocity", "not a list");
			else {
				var list = new ListTag(); list.add(entry(FIRST, 5)); list.add(entry(FIRST, 0));
				social(saved).put("Reciprocity", list);
			}
			f.state = assertDoesNotThrow(() -> SettlementSimulationState.load(f.settlement, saved));
			assertTrue(f.person().reciprocityMemories().isEmpty());
			assertEquals(0, f.person().trustIn(FIRST));
		}
	}

	@Test void restoreIsSideEffectFreeAndPublicMemoriesCannotMutateState() {
		var f = new Fixture();
		f.person().recordAidFrom(FIRST); f.person().restoreSocialState(31, 2, 3);
		f.person().restoreReciprocity(FIRST, 99);
		f.person().restoreReciprocity(SECOND, 2);
		var memories = f.person().reciprocityMemories();
		assertThrows(UnsupportedOperationException.class, () -> memories.put(FIRST, 100));
		f.person().restoreReciprocity(FIRST, 0);
		assertEquals(0, f.person().reciprocityWith(FIRST));
		assertEquals(Map.of(SECOND, 2), f.person().reciprocityMemories());
		assertEquals(2, f.person().trustIn(FIRST)); assertEquals(0, f.person().trustIn(SECOND));
		assertEquals(31, f.person().grievance()); assertEquals(2, f.person().socialHours());
		assertThrows(IllegalArgumentException.class, () -> f.person().restoreReciprocity(null, 1));
		assertThrows(IllegalArgumentException.class, () -> f.person().restoreReciprocity(" ", 1));
		assertThrows(IllegalArgumentException.class, () -> f.person().restoreReciprocity(SECOND, -1));
		assertThrows(IllegalArgumentException.class, () -> f.person().recordAidFrom(null));
		assertThrows(IllegalArgumentException.class, () -> f.person().recordAidFrom("\t"));
		assertEquals(Map.of(SECOND, 2), f.person().reciprocityMemories());
		assertEquals(31, f.person().grievance()); assertEquals(2, f.person().trustIn(FIRST));
	}

	@Test void maximumCountSaturatesWhileNewAidStillHasItsSingleSocialEffect() {
		var f = new Fixture();
		f.person().restoreReciprocity(FIRST, Integer.MAX_VALUE - 1);
		f.person().restoreSocialState(20, 0, 0);
		for (int i = 1; i <= 2; i++) {
			f.person().recordAidFrom(FIRST);
			assertEquals(Integer.MAX_VALUE, f.person().reciprocityWith(FIRST));
			assertEquals(i * 2, f.person().trustIn(FIRST)); assertEquals(20 - i, f.person().grievance());
			f.state = SettlementSimulationState.load(f.settlement, f.state.save());
			assertEquals(Integer.MAX_VALUE, f.person().reciprocityWith(FIRST));
			assertEquals(i * 2, f.person().trustIn(FIRST)); assertEquals(20 - i, f.person().grievance());
		}
	}

	private static CompoundTag entry(String donor, int count) {
		var tag = new CompoundTag(); tag.putString("PersonId", donor); tag.putInt("Value", count); return tag;
	}
	private static CompoundTag social(CompoundTag state) {
		return state.getList("Residents", Tag.TAG_COMPOUND).getCompound(0).getCompound("Social");
	}
	private static final class Fixture {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Social memory", 0);
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		Fixture() { data.registerMember(settlement, RESIDENT, 0); state.mapResident(RESIDENT, false, 80); }
		Person person() { return state.world().person(state.residentId(RESIDENT).orElseThrow()); }
	}
}
