package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.FoodDayTestResidents;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.WorkType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementConstructionSocialWorkTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void realMeetingCostsConstructionOnlyAfterObservedDayCloseAndSurvivesTwoReloads() {
		Fixture f = fixture(3);
		long request = f.state.world().recordShareRequest(f.person(1).id(), f.state.campOwner().id(), 40);
		f.state.world().recordShareRefusal(request, f.person(1).id(), f.state.campOwner().id(), 20);
		f.state.norms().observeSocialDay(f.people());
		assertFalse(f.state.world().proposalsForCamp(f.state.campOwner().id()).isEmpty());
		var meeting = f.state.norms().holdCommonStoreMeeting(f.people(),
			List.of(f.person(1), f.person(2)), f.state.campOwner().id(), "0, 64, 0");
		assertEquals("held", meeting.status());
		assertEquals(2, meeting.participants());
		assertEquals(new SettlementSimulationService.SocialWork(1, 0, 6), social(f, 1));
		assertEquals(20, duration(f, 1, 20));
		assertEquals(20, duration(f, 2, 20));
		assertEquals(0, f.person(3).socialHours());
		assertTrue(SettlementSimulationService.closeObservedDay(f.data, f.settlement, f.state, 1,
			Map.of(id(1), f.person(1), id(2), f.person(2), id(3), f.person(3))));
		assertEquals(new SettlementSimulationService.SocialWork(0, 1, 5), social(f, 1));
		CompoundTag before = save(f);
		for (int reload = 0; reload < 3; reload++) {
			assertEquals(24, duration(f, 1, 20));
			assertEquals(24, duration(f, 2, 20));
			assertEquals(20, duration(f, 3, 20));
			assertEquals(120, SettlementSimulationService.gatherWorkDuration(f.data, f.settlement, id(1), 100));
			assertEquals(120, SettlementSimulationService.craftingWorkDuration(f.data, f.settlement, id(1), 100));
			assertEquals(before, save(f), "Duration and view queries must be read-only");
			f.state.world().assertConservation();
			if (reload < 2) f = reload(f, save(f));
		}
	}

	@Test
	void modesAndUnmappedResidentsKeepTheCallerDurationWithoutCreatingState() {
		Fixture f = fixture(1);
		f.person(1).restoreSocialState(0, 0, 1);
		f.state.setNormsEnabled(false);
		assertBaseline(f, id(1));
		f.state.setNormsEnabled(true);
		f.state.setSurvivalEnabled(false, 0);
		assertBaseline(f, id(1));
		f.state.setSurvivalEnabled(true, 0);
		f.data.registerMember(f.settlement, id(2), 0);
		assertBaseline(f, id(2));
		f.state.mapResident(id(3), false, 0);
		assertBaseline(f, id(3));
		SettlementSavedData empty = new SettlementSavedData();
		Settlement settlement = empty.getOrCreate(BlockPos.ZERO, "No simulation", 0);
		empty.registerMember(settlement, id(1), 0);
		CompoundTag before = empty.save(new CompoundTag(), null);
		assertEquals(25, SettlementSimulationService.constructionWorkDuration(empty, settlement, id(1), 25));
		assertTrue(empty.simulationOf(settlement).isEmpty());
		assertEquals(before, empty.save(new CompoundTag(), null));
	}

	@Test
	void rejectsDeadForeignCampAndNoncanonicalSettlementIdentities() {
		Fixture dead = fixture(1);
		dead.person(1).restoreSocialState(0, 0, 1);
		FoodDayTestResidents.kill(dead.person(1));
		assertBaseline(dead, id(1));
		Fixture foreign = fixture(1);
		foreign.person(1).restoreSocialState(0, 0, 1);
		FoodDayTestResidents.move(foreign.person(1), "other-camp");
		assertBaseline(foreign, id(1));
		Fixture f = fixture(1);
		f.person(1).restoreSocialState(0, 0, 1);
		Settlement copy = new Settlement(f.settlement.id(), f.settlement.center(), f.settlement.name(), f.settlement.foundedDay());
		assertEquals(f.settlement, copy);
		assertBaseline(new Fixture(f.data, copy, f.state), id(1));
		Settlement other = f.data.getOrCreate(new BlockPos(4096, 0, 0), "Other", 0);
		SettlementSimulationState otherState = f.data.getOrCreateSimulation(other);
		otherState.mapResident(id(1), false, 0);
		otherState.setSurvivalEnabled(true, 0);
		otherState.setNormsEnabled(true);
		assertBaseline(new Fixture(f.data, other, otherState), id(1));
		assertEquals(24, duration(f, 1, 20));
		assertTrue(SettlementSimulationService.socialWork(null, f.settlement, id(1)).isEmpty());
		assertTrue(SettlementSimulationService.socialWork(f.data, null, id(1)).isEmpty());
		assertTrue(SettlementSimulationService.socialWork(f.data, f.settlement, null).isEmpty());
	}

	@Test
	void constructionIgnoresCoreSkillsAndPreservesAlreadyScaledCallerDuration() {
		Fixture f = fixture(1);
		f.person(1).restoreSocialState(0, 0, 1);
		for (boolean skills : List.of(false, true)) {
			f.state.setSkillsEnabled(skills);
			for (double skill : List.of(0.5, 1.0, 2.0)) {
				for (WorkType type : WorkType.values()) f.person(1).setSkill(type, skill);
				CompoundTag before = save(f);
				assertEquals(24, duration(f, 1, 20));
				assertEquals(30, duration(f, 1, 25), "Caller already applied personality/hunger");
				assertEquals(50, duration(f, 1, 42));
				assertEquals(before, save(f));
			}
		}
	}

	@Test
	void everyHourBoundKeepsPositiveWorkAndNeverConsumesHours() {
		Fixture f = fixture(1);
		int[] expected = {20, 24, 30, 40, 60, 120, 120};
		for (int previous = 0; previous <= 6; previous++) {
			for (int current = 0; current <= 6; current++) {
				f.person(1).restoreSocialState(0, current, previous);
				CompoundTag before = save(f);
				assertEquals(new SettlementSimulationService.SocialWork(current, previous, Math.max(1, 6 - previous)), social(f, 1));
				assertEquals(expected[previous], duration(f, 1, 20));
				assertTrue(duration(f, 1, 1) >= 1);
				assertEquals(Integer.MAX_VALUE, duration(f, 1, Integer.MAX_VALUE));
				assertEquals(before, save(f));
			}
		}
		assertThrows(IllegalArgumentException.class, () -> duration(f, 1, 0));
		assertThrows(IllegalArgumentException.class, () -> duration(f, 1, -1));
	}

	@Test
	void legacyMissingOrUnknownSocialFieldsRemainBaselineAcrossReloads() {
		for (boolean unknown : List.of(false, true)) {
			Fixture f = fixture(1);
			CompoundTag tag = save(f);
			CompoundTag resident = tag.getList("Simulations", ListTag.TAG_COMPOUND).getCompound(0)
				.getList("Residents", ListTag.TAG_COMPOUND).getCompound(0);
			resident.remove("Social");
			if (unknown) {
				CompoundTag social = new CompoundTag();
				social.putInt("FutureSocialHours", 6);
				resident.put("Social", social);
			}
			for (int reload = 0; reload < 2; reload++) {
				f = reload(f, tag);
				assertEquals(20, duration(f, 1, 20));
				assertEquals(new SettlementSimulationService.SocialWork(0, 0, 6), social(f, 1));
				tag = save(f);
			}
		}
	}

	private static void assertBaseline(Fixture f, UUID resident) {
		CompoundTag before = save(f);
		assertTrue(SettlementSimulationService.socialWork(f.data, f.settlement, resident).isEmpty());
		assertEquals(25, SettlementSimulationService.constructionWorkDuration(f.data, f.settlement, resident, 25));
		assertEquals(before, save(f));
	}
	private static int duration(Fixture f, int resident, int duration) {
		return SettlementSimulationService.constructionWorkDuration(f.data, f.settlement, id(resident), duration);
	}
	private static SettlementSimulationService.SocialWork social(Fixture f, int resident) {
		return SettlementSimulationService.socialWork(f.data, f.settlement, id(resident)).orElseThrow();
	}
	private static Fixture fixture(int residents) {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Construction social time", 0);
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		for (int i = 1; i <= residents; i++) {
			data.registerMember(settlement, id(i), 0);
			state.mapResident(id(i), false, 0);
			state.world().person(state.residentId(id(i)).orElseThrow()).observeNorm("neighbor-requests");
		}
		state.setSurvivalEnabled(true, 0);
		state.setNormsEnabled(true);
		state.world().importExternalBatch(ResourceType.FISH, 30, state.campOwner());
		return new Fixture(data, settlement, state);
	}
	private static CompoundTag save(Fixture f) { return f.data.save(new CompoundTag(), null); }
	private static Fixture reload(Fixture f, CompoundTag tag) {
		SettlementSavedData data = SettlementSavedData.load(tag, null);
		Settlement settlement = data.find(f.settlement.center()).orElseThrow();
		return new Fixture(data, settlement, data.simulationOf(settlement).orElseThrow());
	}
	private static UUID id(int i) { return new UUID(41, i); }
	private record Fixture(SettlementSavedData data, Settlement settlement, SettlementSimulationState state) {
		Person person(int i) { return state.world().person(state.residentId(id(i)).orElseThrow()); }
		List<Person> people() { return state.world().people(); }
	}
}
