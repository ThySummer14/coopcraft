package com.thysummer.coopcraft.civilization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementNamingTest {
	@Test
	void generatesSameNameForSameSeed() {
		assertEquals(SettlementNaming.fromSeed(123456789L), SettlementNaming.fromSeed(123456789L));
	}

	@Test
	void generatesValidNamesForManySeeds() {
		for (long seed = 0; seed < 1000; seed++) {
			String name = SettlementNaming.fromSeed(seed);
			assertFalse(name.isBlank());
			assertTrue(name.matches("[A-Za-z]+"), name);
		}
	}
}
