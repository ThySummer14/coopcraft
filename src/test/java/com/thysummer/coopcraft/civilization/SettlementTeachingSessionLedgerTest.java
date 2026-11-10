package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.WorkType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Registry lifetime must neither persist nor replace the shared manual/automatic daily ledger. */
class SettlementTeachingSessionLedgerTest {
	private static final UUID A = new UUID(80, 1), B = new UUID(80, 2);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@Test void reservationJoinAndCancellationNeverMutateSavedState() {
		Fixture f = fixture(); CompoundTag before = f.data.save(new CompoundTag(), null); f.data.setDirty(false);
		var registry = f.data.teachingSessions(); var pair = registry.reserve(A, B, 0, null);
		registry.join(pair, A, 0); registry.join(pair, B, 0); registry.pulse(pair, A, 0, true);
		assertEquals(before, f.data.save(new CompoundTag(), null)); assertFalse(f.data.isDirty());
		var restored = SettlementSavedData.load(before, null);
		assertTrue(restored.teachingSessions().pairs().isEmpty());
		registry.cancel(pair); assertFalse(f.data.isDirty());
	}
	@Test void manualSuccessBeforeAutomaticCompletionCannotChargeTwiceEvenAfterModesToggle() {
		Fixture f = fixture(); var registry = f.data.teachingSessions(); var pair = registry.reserve(A, B, 0, null);
		registry.join(pair, A, 0); registry.join(pair, B, 0);
		assertEquals("taught", f.teach().status());
		f.state.setSkillsEnabled(false); f.state.setSkillsEnabled(true);
		for (int tick = 0; tick < 200; tick++) {
			registry.pulse(pair, A, tick, true);
			if (registry.pulse(pair, B, tick, true)) assertEquals("already_taught", f.teach().status());
		}
		assertEquals(1, f.person(A).socialHours()); assertEquals(1, f.person(B).socialHours());
		assertEquals(1.12, f.person(B).skill(WorkType.CRAFT), 1e-10);
	}
	@Test void disabledTransactionAfterCompletionChargesNothingAndNewDayNeedsNewPair() {
		Fixture f = fixture(); var registry = f.data.teachingSessions(); var pair = registry.reserve(A, B, 0, null);
		registry.join(pair, A, 0); registry.join(pair, B, 0);
		f.state.setNormsEnabled(false);
		for (int tick = 0; tick < 200; tick++) {
			registry.pulse(pair, A, tick, true);
			if (registry.pulse(pair, B, tick, true)) assertEquals("disabled", f.teach().status());
		}
		assertEquals(0, f.person(A).socialHours()); assertEquals(0, f.person(B).socialHours());
		f.state.setNormsEnabled(true); f.state.world().advanceDay();
		assertFalse(registry.pulse(pair, B, 200, true));
		assertNotNull(registry.reserve(A, B, 200, null));
	}
	private static Fixture fixture() {
		SettlementSavedData data = new SettlementSavedData(); Settlement camp = data.getOrCreate(BlockPos.ZERO, "Autonomous lessons", 0);
		SettlementSimulationState state = data.getOrCreateSimulation(camp);
		for (UUID id : List.of(A, B)) {
			data.registerMember(camp, id, 0); state.mapResident(id, false, 0);
			state.world().person(state.residentId(id).orElseThrow()).initializeAge(Person.AgeBand.ADULT, 0);
		}
		state.setSurvivalEnabled(true, 0); state.setSkillsEnabled(true); state.setNormsEnabled(true);
		Fixture f = new Fixture(data, camp, state); f.person(A).setSkill(WorkType.CRAFT, 1.8); return f;
	}
	private record Fixture(SettlementSavedData data, Settlement camp, SettlementSimulationState state) {
		Person person(UUID id) { return state.world().person(state.residentId(id).orElseThrow()); }
		SettlementCraftTeaching.Result teach() { return SettlementCraftTeaching.teach(data, camp, A, B, () -> true); }
	}
}
