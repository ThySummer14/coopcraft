package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.WorkType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementCraftTeachingTest {
	private static final UUID TEACHER = new UUID(79, 1), LEARNER = new UUID(79, 2), THIRD = new UUID(79, 3);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void explicitUnrelatedZeroTrustPairUsesSocialTimeAndOnlyChangesLearnerCraft() {
		Fixture f = fixture();
		var inventory = f.state.world().inventoryTotals();
		int oldDuration = SettlementSimulationService.craftingWorkDuration(f.data, f.camp, LEARNER, 1000);
		CompoundTag before = f.save();
		assertEquals("ready", SettlementCraftTeaching.inspect(f.data, f.camp, TEACHER, LEARNER).status());
		assertEquals(before, f.save());
		var result = f.teach();
		assertEquals("taught", result.status());
		assertEquals(1.12, result.skillAfter(), 1e-10);
		assertEquals(1, f.person(TEACHER).socialHours());
		assertEquals(1, f.person(LEARNER).socialHours());
		assertEquals(0, f.person(TEACHER).learningHours());
		assertEquals(0, f.person(LEARNER).learningHours());
		assertEquals(1.8, f.person(TEACHER).skill(WorkType.CRAFT));
		assertEquals(1, f.person(THIRD).skill(WorkType.CRAFT));
		assertEquals(inventory, f.state.world().inventoryTotals());
		assertTrue(SettlementSimulationService.craftingWorkDuration(f.data, f.camp, LEARNER, 1000) < oldDuration);
		var events = f.state.world().ledger().events().stream().filter(e -> e.type() == EventType.TEACHING_SESSION).toList();
		assertEquals(1, events.size());
		assertEquals(List.of(f.person(TEACHER).id(), f.person(LEARNER).id()), events.getFirst().actors());
		assertEquals("minecraft_field_craft", events.getFirst().context().get("source"));
		assertEquals(TEACHER.toString(), events.getFirst().context().get("teacherUuid"));
		assertEquals("1", events.getFirst().context().get("hours"));
		assertEquals("next_settled_day", events.getFirst().context().get("costTiming"));
		assertTrue(events.getFirst().inputs().isEmpty());
		assertTrue(events.getFirst().outputs().isEmpty());
	}

	@Test void actualObservedDayCloseSettlesBothLessonCostsIntoCraftingDuration() {
		Fixture f = fixture();
		for (UUID id : List.of(TEACHER, LEARNER, THIRD)) f.person(id).setHunger(0);
		assertEquals("taught", f.teach().status());
		for (UUID id : List.of(TEACHER, LEARNER)) {
			assertEquals(new SettlementSimulationService.SocialWork(1, 0, 6),
				SettlementSimulationService.socialWork(f.data, f.camp, id).orElseThrow());
			assertEquals((int) Math.round(1000 / f.person(id).skill(WorkType.CRAFT)),
				SettlementSimulationService.craftingWorkDuration(f.data, f.camp, id, 1000));
		}
		assertTrue(SettlementSimulationService.closeObservedDay(f.data, f.camp, f.state, 1,
			Map.of(TEACHER, f.person(TEACHER), LEARNER, f.person(LEARNER), THIRD, f.person(THIRD))));
		for (UUID id : List.of(TEACHER, LEARNER)) {
			assertEquals(1, f.person(id).previousSocialHours());
			assertEquals(new SettlementSimulationService.SocialWork(0, 1, 5),
				SettlementSimulationService.socialWork(f.data, f.camp, id).orElseThrow());
			assertEquals((int) Math.round(1000 * 6.0 / (f.person(id).skill(WorkType.CRAFT) * 5)),
				SettlementSimulationService.craftingWorkDuration(f.data, f.camp, id, 1000));
		}
		assertEquals(0, f.person(THIRD).previousSocialHours());
		assertEquals(1000, SettlementSimulationService.craftingWorkDuration(f.data, f.camp, THIRD, 1000));
		assertEquals(1.12, f.person(LEARNER).skill(WorkType.CRAFT), 1e-10);
	}

	@Test void attendanceSurvivesReloadRolesPartnersAndModeTogglesButAllowsNextDay() {
		Fixture f = fixture(); f.teach();
		for (UUID[] pair : List.of(new UUID[]{TEACHER, LEARNER}, new UUID[]{LEARNER, TEACHER}, new UUID[]{TEACHER, THIRD})) {
			assertEquals("already_taught", SettlementCraftTeaching.inspect(f.data, f.camp, pair[0], pair[1]).status());
		}
		f.state.setSkillsEnabled(false); f.state.setSkillsEnabled(true);
		assertEquals("already_taught", f.teach().status());
		SettlementSavedData restored = SettlementSavedData.load(f.save(), null);
		Settlement camp = restored.all().iterator().next();
		assertEquals("already_taught", SettlementCraftTeaching.teach(restored, camp, TEACHER, LEARNER, () -> true).status());
		f.state.world().advanceDay();
		assertEquals("taught", f.teach().status());
	}

	@Test void optionalLedgerIsLegacyCompatibleAndMalformedDataIsLossless() {
		Fixture f = fixture(); CompoundTag original = f.state.save(); original.remove("CraftLessons");
		assertTrue(SettlementSimulationState.load(f.camp, original).craftLessonsAvailable());
		for (Tag invalid : List.of(StringTag.valueOf("future"), badLedger(2), badLedger(1))) {
			CompoundTag saved = f.state.save(); saved.put("CraftLessons", invalid);
			SettlementSimulationState restored = SettlementSimulationState.load(f.camp, saved);
			assertFalse(restored.craftLessonsAvailable());
			assertEquals(invalid, restored.save().get("CraftLessons"));
			assertEquals(f.state.world().people().size(), restored.world().people().size());
		}
		CompoundTag future = f.state.save();
		CompoundTag days = new CompoundTag(); days.putLong(f.person(TEACHER).id(), 10);
		CompoundTag ledger = new CompoundTag(); ledger.putInt("Version", 1); ledger.put("LastDays", days);
		future.put("CraftLessons", ledger);
		var restored = SettlementSimulationState.load(f.camp, future);
		assertTrue(restored.craftLessonUsed(f.person(TEACHER).id()));
		assertEquals(ledger, restored.save().get("CraftLessons"));
	}

	@Test void deniedAuthorityAndChangedInputsNeverChargeOrTeach() {
		Fixture f = fixture(); CompoundTag before = f.save(); f.data.setDirty(false);
		assertEquals("authority_lost", SettlementCraftTeaching.teach(f.data, f.camp, TEACHER, LEARNER, () -> false).status());
		assertEquals(before, f.save()); assertFalse(f.data.isDirty());
		List<Consumer<Fixture>> changes = List.of(
			g -> { g.state.setSkillsEnabled(false); g.state.setSkillsEnabled(true); },
			g -> g.state.world().advanceDay(),
			g -> g.person(TEACHER).adjustTrust(g.person(LEARNER).id(), 1),
			g -> g.person(TEACHER).setSkill(WorkType.CRAFT, 1.7),
			g -> { g.data.removeMember(TEACHER); g.data.registerMember(g.camp, TEACHER, 0); });
		for (var change : changes) {
			Fixture g = fixture();
			assertEquals("stale", SettlementCraftTeaching.teach(g.data, g.camp, TEACHER, LEARNER, () -> { change.accept(g); return true; }).status());
			assertEquals(0, g.person(TEACHER).socialHours()); assertEquals(0, g.person(LEARNER).socialHours());
			assertEquals(1, g.person(LEARNER).skill(WorkType.CRAFT));
			assertTrue(g.state.world().ledger().events().stream().noneMatch(e -> e.type() == EventType.TEACHING_SESSION));
		}
	}

	@Test void eitherExhaustedParticipantBlocksBothCharges() {
		for (UUID resident : List.of(TEACHER, LEARNER)) {
			Fixture f = fixture(); f.person(resident).restoreSocialState(0, 6, 0);
			CompoundTag before = f.save(); assertEquals("exhausted", f.teach().status()); assertEquals(before, f.save());
		}
	}
	private static CompoundTag badLedger(int version) {
		CompoundTag tag = new CompoundTag(); tag.putInt("Version", version); tag.putString("LastDays", "invalid"); return tag;
	}
	private static Fixture fixture() {
		SettlementSavedData data = new SettlementSavedData(); Settlement camp = data.getOrCreate(BlockPos.ZERO, "Craft lessons", 0);
		SettlementSimulationState state = data.getOrCreateSimulation(camp);
		for (UUID id : List.of(TEACHER, LEARNER, THIRD)) {
			data.registerMember(camp, id, 0); state.mapResident(id, false, 0);
			state.world().person(state.residentId(id).orElseThrow()).initializeAge(Person.AgeBand.ADULT, 0);
		}
		state.setSurvivalEnabled(true, 0); state.setSkillsEnabled(true); state.setNormsEnabled(true);
		Fixture f = new Fixture(data, camp, state); f.person(TEACHER).setSkill(WorkType.CRAFT, 1.8); return f;
	}
	private record Fixture(SettlementSavedData data, Settlement camp, SettlementSimulationState state) {
		Person person(UUID id) { return state.world().person(state.residentId(id).orElseThrow()); }
		CompoundTag save() { return data.save(new CompoundTag(), null); }
		SettlementCraftTeaching.Result teach() { return SettlementCraftTeaching.teach(data, camp, TEACHER, LEARNER, () -> true); }
	}
}
