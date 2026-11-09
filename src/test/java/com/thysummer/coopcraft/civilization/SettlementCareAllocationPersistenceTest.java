package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.Norm;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementCareAllocationPersistenceTest {
	private static final UUID CHILD = new UUID(3, 1);
	private static final UUID SUPERVISOR = new UUID(3, 2);
	private static final UUID OTHER = new UUID(3, 3);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void splitCareMealIsIdenticalWithRepeatedReloadsAfterBasicAllowance() {
		Fixture uninterrupted = fixture();
		// Compare two paths from the same world identity, including its persisted food clock.
		Fixture restored = reloadUnchanged(uninterrupted);
		assertEquals(40, feed(uninterrupted, 2));
		assertEquals(40, feed(restored, 2));
		assertEquals(0, careEvents(restored).size());
		List<SimulationEvent> oldEvents = restored.state().world().ledger().events();
		for (int i = 0; i < 3; i++) restored = reloadUnchanged(restored);
		assertEquals(20, feed(uninterrupted, 1));
		assertEquals(20, feed(restored, 1));
		assertEquals(1, careEvents(restored).size());
		assertEquals(51, care(restored).stability());
		assertEquals(3, restored.state().allocatedFoodUnits(restored.person(CHILD).id()));
		assertEquals(0, restored.state().remainingFoodUnits(restored.person(CHILD).id()));
		assertEquals(60, restored.data().needsOf(restored.settlement(), CHILD).satiety());
		assertEquals(oldEvents, restored.state().world().ledger().events().subList(0, oldEvents.size()));
		assertEquivalent(uninterrupted, restored);
		reloadUnchanged(restored);
	}

	@Test
	void nextDayUsesFreshActualTotalsBeforeAndAfterReload() {
		Fixture uninterrupted = fixture();
		// Compare two paths from the same world identity, including its persisted food clock.
		Fixture restored = reloadUnchanged(uninterrupted);
		feed(uninterrupted, 3);
		feed(restored, 3);
		for (Fixture f : List.of(uninterrupted, restored)) {
			f.state().world().advanceDay();
			f.person(CHILD).setHunger(100);
		}
		restored = reloadUnchanged(restored);
		feed(uninterrupted, 1);
		feed(restored, 1);
		assertEquals(1, careEvents(restored).size()); // Yesterday's practice only.
		assertEquals(1, restored.state().allocatedFoodUnits(restored.person(CHILD).id()));
		assertEquivalent(uninterrupted, restored);
		restored = reloadUnchanged(restored);
		feed(uninterrupted, 2);
		feed(restored, 2);
		assertEquals(2, careEvents(restored).size());
		assertEquals(52, care(restored).stability());
		assertEquivalent(uninterrupted, restored);
	}

	@Test
	void enablingNormsUsesFoodAlreadyReceivedWithoutBackfillingPractices() {
		Fixture uninterrupted = fixture();
		// Compare two paths from the same world identity, including its persisted food clock.
		Fixture restored = reloadUnchanged(uninterrupted);
		for (Fixture f : List.of(uninterrupted, restored)) {
			f.state().setNormsEnabled(false);
			feed(f, 2);
			assertTrue(f.state().world().ledger().events().stream().noneMatch(e -> e.type() == EventType.NORM_ENFORCED));
		}
		restored = reloadUnchanged(restored);
		for (Fixture f : List.of(uninterrupted, restored)) {
			f.state().setNormsEnabled(true);
			feed(f, 1);
		}
		assertEquals(1, careEvents(restored).size());
		assertEquals(2, restored.state().world().ledger().events().stream().filter(e -> e.type() == EventType.NORM_ENFORCED).count());
		assertEquivalent(uninterrupted, restored);
	}

	@Test
	void togglingNormsWithExistingCacheStillMatchesThePersistedQuota() {
		Fixture uninterrupted = fixture();
		// Compare two paths from the same world identity, including its persisted food clock.
		Fixture restored = reloadUnchanged(uninterrupted);
		for (Fixture f : List.of(uninterrupted, restored)) {
			feed(f, 1);
			f.state().setNormsEnabled(false);
			feed(f, 1);
		}
		restored = reloadUnchanged(restored);
		for (Fixture f : List.of(uninterrupted, restored)) {
			f.state().setNormsEnabled(true);
			feed(f, 1);
		}
		assertEquals(1, careEvents(restored).size());
		assertEquals(1, restored.person(SUPERVISOR).socialHours());
		assertEquivalent(uninterrupted, restored);
	}

	@Test
	void adultsAndOtherResidentsDoNotInheritTheChildsCareCount() {
		Fixture f = fixture();
		feed(f, 3);
		f = reloadUnchanged(f);
		f.state().world().importExternalBatch(ResourceType.FISH, 2, f.state().campOwner());
		assertEquals(40, SettlementSimulationService.eat(f.data(), f.settlement(), OTHER));
		assertEquals(1, careEvents(f).size());
		assertEquals(2, f.state().allocatedFoodUnits(f.person(OTHER).id()));
		assertEquals(3, f.state().allocatedFoodUnits(f.person(CHILD).id()));
		assertEquals(1, f.person(SUPERVISOR).socialHours());
		reloadUnchanged(f);
	}

	private static Fixture fixture() {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "Care allocation", 0);
		var state = data.getOrCreateSimulation(settlement);
		for (UUID resident : List.of(CHILD, SUPERVISOR, OTHER)) {
			data.registerMember(settlement, resident, 0);
			state.mapResident(resident, resident.equals(CHILD), 0);
		}
		state.setSurvivalEnabled(true, 0);
		state.setNormsEnabled(true);
		var supporters = Set.of(state.residentId(SUPERVISOR).orElseThrow(), state.residentId(OTHER).orElseThrow());
		for (String id : List.of(Norm.NEED_FIRST_COMMON_STORE, Norm.CARE_FIRST_COMMON_STORE)) {
			state.world().registerNorm(new Norm(id, state.campOwner().id(), id.equals(Norm.NEED_FIRST_COMMON_STORE) ? 2 : 1,
				supporters, Set.of(), supporters, 50, Norm.Status.ACTIVE, List.of()));
		}
		return new Fixture(data, state);
	}

	private static int feed(Fixture f, int units) {
		f.state().world().importExternalBatch(ResourceType.FISH, units, f.state().campOwner());
		return SettlementSimulationService.eat(f.data(), f.settlement(), CHILD);
	}

	private static Norm care(Fixture f) {
		return f.state().world().norm(f.state().campOwner().id(), Norm.CARE_FIRST_COMMON_STORE).orElseThrow();
	}

	private static List<SimulationEvent> careEvents(Fixture f) {
		return f.state().world().ledger().events().stream()
			.filter(e -> Norm.CARE_FIRST_COMMON_STORE.equals(e.context().get("normId"))).toList();
	}

	private static Fixture reloadUnchanged(Fixture f) {
		CompoundTag saved = f.data().save(new CompoundTag(), null);
		assertEquals(19, saved.getInt("DataVersion"));
		var loaded = SettlementSavedData.load(saved, null);
		var restored = new Fixture(loaded, loaded.simulationOf(loaded.find(BlockPos.ZERO).orElseThrow()).orElseThrow());
		assertEquals(saved, restored.data().save(new CompoundTag(), null));
		restored.state().world().assertConservation();
		return restored;
	}

	private static void assertEquivalent(Fixture first, Fixture second) {
		assertEquals(first.data().save(new CompoundTag(), null), second.data().save(new CompoundTag(), null));
		first.state().world().assertConservation();
		second.state().world().assertConservation();
	}

	private record Fixture(SettlementSavedData data, SettlementSimulationState state) {
		Settlement settlement() { return data.find(BlockPos.ZERO).orElseThrow(); }
		Person person(UUID id) { return state.world().person(state.residentId(id).orElseThrow()); }
	}
}
