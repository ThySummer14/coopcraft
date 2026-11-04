package com.thysummer.coopcraft.civilization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagerInteractionDecisionTest {
	@Test
	void refusesFoodWhenAlreadyFull() {
		var decision = VillagerInteractionDecision.decide(100, 2, 0, 0, 50, true);

		assertFalse(decision.accepted());
		assertEquals("already_full", decision.reason());
	}

	@Test
	void refusesWhenVillageHasReservesAndResidentIsComfortable() {
		var decision = VillagerInteractionDecision.decide(70, 2, 16, 0, 0, true);

		assertFalse(decision.accepted());
		assertEquals("village_has_food", decision.reason());
	}

	@Test
	void sociableResidentAcceptsEvenWithReserves() {
		var decision = VillagerInteractionDecision.decide(65, 2, 16, 0, 100, true);

		assertTrue(decision.accepted());
		assertEquals("welcomed", decision.reason());
	}

	@Test
	void remembersPreviousHelpBeforePlainHunger() {
		var decision = VillagerInteractionDecision.decide(65, 2, 16, 10, 0, true);

		assertTrue(decision.accepted());
		assertEquals("remembers_help", decision.reason());
	}

	@Test
	void hungryResidentAcceptsDespiteReserves() {
		var decision = VillagerInteractionDecision.decide(30, 2, 16, 0, 0, true);

		assertTrue(decision.accepted());
		assertEquals("hungry", decision.reason());
	}

	@Test
	void lowCommunalFoodMakesResidentsAccept() {
		var decision = VillagerInteractionDecision.decide(80, 5, 39, 0, 50, true);

		assertTrue(decision.accepted());
		assertEquals("low_communal_food", decision.reason());
	}

	@Test
	void rejectsFoodThatTheAuthorityCannotRecord() {
		var decision = VillagerInteractionDecision.decide(80, 2, 0, 0, 50, false);

		assertFalse(decision.accepted());
		assertEquals("unsupported_food", decision.reason());
	}

	@Test
	void validatesInputs() {
		assertThrows(IllegalArgumentException.class,
			() -> VillagerInteractionDecision.decide(-1, 1, 0, 0, 50, true));
		assertThrows(IllegalArgumentException.class,
			() -> VillagerInteractionDecision.decide(50, 1, -1, 0, 50, true));
		assertThrows(IllegalArgumentException.class,
			() -> VillagerInteractionDecision.decide(50, 1, 0, 0, 101, true));
	}
}
