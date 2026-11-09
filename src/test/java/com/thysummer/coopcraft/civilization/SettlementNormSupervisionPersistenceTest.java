package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.Norm;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettlementNormSupervisionPersistenceTest {
	private static final UUID REQUESTER = new UUID(1, 1);
	private static final UUID SUPERVISOR = new UUID(1, 2);
	private static final UUID OTHER = new UUID(1, 3);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void sameDayRoundTripsPreserveHoursAndDeduplicateAcrossRequesters() {
		Fixture f = fixture();
		allocate(f, REQUESTER, true);
		assertHours(f, 1);
		for (int i = 0; i < 3; i++) {
			f = reloadUnchanged(f);
			assertHours(f, 1);
		}
		allocate(f, REQUESTER, true);
		assertHours(f, 1);
		f = reloadUnchanged(f);
		allocate(f, OTHER, true);
		assertHours(f, 1);
		assertEquals(2, f.state().allocatedFoodUnits(f.person(REQUESTER).id()));
		assertEquals(1, f.state().allocatedFoodUnits(f.person(OTHER).id()));
		reloadUnchanged(f);
	}

	@Test
	void realEatServiceDoesNotRechargeSupervisorsForAnotherResidentAfterLoad() {
		Fixture f = fixture();
		Settlement settlement = f.data().find(BlockPos.ZERO).orElseThrow();
		long stock = f.state().world().inventoryTotals().get(ResourceType.WILD_FRUIT);
		assertEquals(54, SettlementSimulationService.eat(f.data(), settlement, REQUESTER));
		assertHours(f, 1);
		f = reloadUnchanged(f);
		settlement = f.data().find(BlockPos.ZERO).orElseThrow();
		assertEquals(54, SettlementSimulationService.eat(f.data(), settlement, OTHER));
		assertHours(f, 1);
		assertEquals(3, f.state().allocatedFoodUnits(f.person(REQUESTER).id()));
		assertEquals(3, f.state().allocatedFoodUnits(f.person(OTHER).id()));
		assertEquals(stock - 6, f.state().world().inventoryTotals().get(ResourceType.WILD_FRUIT).longValue());
		f.state().world().assertConservation();
		reloadUnchanged(f);
	}

	@Test
	void nextDayChargeStillOccursAfterLoadWithOldEvents() {
		Fixture f = fixture();
		allocate(f, REQUESTER, true);
		f.state().world().advanceDay();
		f = reloadUnchanged(f);
		// The load hook must not recalculate saved hours or replay the daily reset.
		assertHours(f, 1);
		allocate(f, OTHER, true);
		assertHours(f, 2);
		f = reloadUnchanged(f);
		allocate(f, REQUESTER, true);
		assertHours(f, 2);
	}

	@Test
	void unsupervisedShareBeforeSaveDoesNotWaiveFirstActualCharge() {
		Fixture f = fixture();
		allocate(f, REQUESTER, false);
		assertHours(f, 0);
		f = reloadUnchanged(f);
		allocate(f, OTHER, true);
		assertHours(f, 1);
	}

	private static Fixture fixture() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Supervision", 0);
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		for (UUID resident : List.of(REQUESTER, SUPERVISOR, OTHER)) {
			data.registerMember(settlement, resident, 0);
			state.mapResident(resident, false, 0);
		}
		state.setSurvivalEnabled(true, 0);
		state.setNormsEnabled(true);
		state.world().importExternalBatch(ResourceType.WILD_FRUIT, 30, state.campOwner());
		Set<String> supporters = Set.of(state.residentId(REQUESTER).orElseThrow(), state.residentId(SUPERVISOR).orElseThrow());
		state.world().registerNorm(new Norm(Norm.NEED_FIRST_COMMON_STORE, state.campOwner().id(), 3,
			supporters, Set.of(), supporters, 50, Norm.Status.ACTIVE, List.of()));
		return new Fixture(data, state);
	}

	private static void allocate(Fixture f, UUID resident, boolean supervised) {
		SettlementSimulationState state = f.state();
		Person person = f.person(resident);
		long request = state.world().recordShareRequest(person.id(), state.campOwner().id(), 18);
		long allocation = state.world().allocateCommon(state.campOwner().id(), person.id(),
			state.world().batchesAt(state.campOwner()).getFirst().batchId(), 1, request).eventId();
		state.recordFoodAllocation(person.id(), 1);
		if (supervised) {
			state.norms().recordCommonAllocation(person, 1, request, allocation);
		}
		state.world().assertConservation();
	}

	private static Fixture reloadUnchanged(Fixture f) {
		CompoundTag before = f.data().save(new CompoundTag(), null);
		assertEquals(19, before.getInt("DataVersion"));
		SettlementSavedData loaded = SettlementSavedData.load(before, null);
		Settlement settlement = loaded.find(BlockPos.ZERO).orElseThrow();
		Fixture restored = new Fixture(loaded, loaded.simulationOf(settlement).orElseThrow());
		// Includes every event, resource batch, resident, quota and schema field.
		assertEquals(before, loaded.save(new CompoundTag(), null));
		restored.state().norms().restoreChargedSupervision();
		assertEquals(before, loaded.save(new CompoundTag(), null));
		restored.state().world().assertConservation();
		return restored;
	}

	private static void assertHours(Fixture f, int hours) {
		assertEquals(hours, f.person(REQUESTER).socialHours());
		assertEquals(hours, f.person(SUPERVISOR).socialHours());
	}

	private record Fixture(SettlementSavedData data, SettlementSimulationState state) {
		Person person(UUID id) {
			return state.world().person(state.residentId(id).orElseThrow());
		}
	}
}
