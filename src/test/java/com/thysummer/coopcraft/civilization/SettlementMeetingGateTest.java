package com.thysummer.coopcraft.civilization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure predicates used by the production adapter, not a substitute for a real-world GameTest run. */
class SettlementMeetingGateTest {
	@Test
	void organizerMustBeAliveNonSpectatingAndInTheSameWorldWithinFourBlocks() {
		assertTrue(new SettlementMeetings.OrganizerPresence(true, false, true, 16).eligible());
		assertTrue(new SettlementMeetings.OrganizerPresence(true, false, true, 0).eligible());
		assertFalse(new SettlementMeetings.OrganizerPresence(false, false, true, 0).eligible());
		assertFalse(new SettlementMeetings.OrganizerPresence(true, true, true, 0).eligible());
		assertFalse(new SettlementMeetings.OrganizerPresence(true, false, false, 0).eligible());
		for (double distance : new double[] {16.0001, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
			assertFalse(new SettlementMeetings.OrganizerPresence(true, false, true, distance).eligible());
		}
	}

	@Test
	void attendanceRequiresEveryRegistrationMappingLifeAgeLoadTickWorldAndActiveGate() {
		assertTrue(presence(-1, 36).eligible());
		assertTrue(presence(-1, 0).eligible());
		for (int missingGate = 0; missingGate < 8; missingGate++) {
			assertFalse(presence(missingGate, 0).eligible(), "Missing production gate " + missingGate);
		}
		for (double distance : new double[] {36.0001, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
			assertFalse(presence(-1, distance).eligible());
		}
	}

	@Test
	void crossingMinecraftDayRequiresSettlementBeforeMeetingCanRetry() {
		assertTrue(SettlementMeetings.dayReady(7, 7));
		assertFalse(SettlementMeetings.dayReady(8, 7));
		assertFalse(SettlementMeetings.dayReady(8, Long.MIN_VALUE));
		assertTrue(SettlementMeetings.dayReady(6, 7), "A clock rollback cannot itself open another core day");
	}

	private static SettlementMeetings.ResidentPresence presence(int missingGate, double distance) {
		return new SettlementMeetings.ResidentPresence(missingGate != 0, missingGate != 1, missingGate != 2,
			missingGate != 3, missingGate != 4, missingGate != 5, missingGate != 6, missingGate != 7, distance);
	}
}
