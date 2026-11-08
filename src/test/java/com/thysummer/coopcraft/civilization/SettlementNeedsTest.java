package com.thysummer.coopcraft.civilization;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementNeedsTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void consumesExactlyOneRealFood() {
		SimpleContainer inventory = new SimpleContainer(1);
		inventory.setItem(0, new ItemStack(Items.BREAD, 3));

		assertEquals(4, SettlementNeeds.consumeOneFood(inventory));
		assertEquals(2, inventory.countItem(Items.BREAD));
		assertEquals(4, SettlementNeeds.consumeOneFood(inventory));
		assertEquals(4, SettlementNeeds.consumeOneFood(inventory));
		assertEquals(0, SettlementNeeds.consumeOneFood(inventory));
		assertTrue(inventory.isEmpty());
	}

	@Test
	void consumesExactlyOneBerryAndDetectsItAsFood() {
		SimpleContainer inventory = new SimpleContainer(1);
		inventory.setItem(0, new ItemStack(Items.SWEET_BERRIES, 3));

		assertTrue(SettlementNeeds.hasFood(inventory, null));
		assertEquals(1, SettlementNeeds.consumeOneFood(inventory));
		assertEquals(2, inventory.countItem(Items.SWEET_BERRIES));
	}

	@Test
	void decaysEveryEligibleMemberOncePerPeriodRegardlessOfUuid() {
		for (long settlementBits : new long[] {0, 1, -1, 599, 600, Long.MIN_VALUE, Long.MAX_VALUE}) {
			SettlementSavedData data = new SettlementSavedData();
			Settlement settlement = new Settlement(new UUID(0, settlementBits), BlockPos.ZERO, "Test", 0);
			List<UUID> members = List.of(new UUID(0, 0), new UUID(0, 1), new UUID(1, -1),
				new UUID(0, Long.MIN_VALUE), new UUID(0, Long.MAX_VALUE));
			members.forEach(id -> data.registerMember(settlement, id, 0));
			UUID unavailable = new UUID(1, 42);
			data.registerMember(settlement, unavailable, 0);
			AtomicInteger checks = new AtomicInteger();
			for (int period = 0; period < 4; period++) {
				for (long time = -1200 + period * 600; time < -600 + period * 600; time++) {
					SettlementNeeds.tick(data, settlement, time, id -> {
						checks.incrementAndGet();
						return !id.equals(unavailable);
					});
				}
				for (UUID id : members) {
					assertEquals(79 - period, data.needsOf(settlement, id).satiety(),
						"settlement=" + settlementBits + ", member=" + id);
				}
				assertEquals(80, data.needsOf(settlement, unavailable).satiety());
				assertEquals((period + 1) * (members.size() + 1), checks.get());
			}
		}
	}

	@Test
	void decayScheduleMatchesExactArithmeticAtLongBoundaries() {
		long[] starts = {Long.MIN_VALUE, -1200, -601, -1, 0, 1, Long.MAX_VALUE - 599};
		for (long bits : new long[] {Long.MIN_VALUE, Long.MAX_VALUE, -600, -1, 0, 1, 599, 600}) {
			UUID id = new UUID(0, bits);
			for (long start : starts) {
				int due = 0;
				for (int offset = 0; offset < 600; offset++) {
					long time = start + offset;
					boolean expected = BigInteger.valueOf(time).add(BigInteger.valueOf(bits))
						.mod(BigInteger.valueOf(600)).signum() == 0;
					assertEquals(expected, SettlementNeeds.isDecayTick(id, time), "time=" + time + ", bits=" + bits);
					if (expected) {
						due++;
					}
				}
				assertEquals(1, due);
			}
		}
	}

	@Test
	void simulationSurvivalDisablesRealDecayAndCanBeTurnedOff() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = new Settlement(new UUID(0, 0), BlockPos.ZERO, "Test", 0);
		UUID member = new UUID(0, 1);
		data.registerMember(settlement, member, 0);
		SettlementSimulationState simulation = data.getOrCreateSimulation(settlement);
		simulation.setSurvivalEnabled(true, 0);

		// No server is needed: simulation mode must return before looking up live entities.
		SettlementNeeds.tick(null, data, settlement, 600);
		assertEquals(80, data.needsOf(settlement, member).satiety());
		simulation.setSurvivalEnabled(false, 0);
		SettlementNeeds.tick(data, settlement, 1200, id -> true);
		assertEquals(79, data.needsOf(settlement, member).satiety());
	}

	@Test
	void detectsOnlyActualFood() {
		SimpleContainer inventory = new SimpleContainer(1);
		inventory.setItem(0, new ItemStack(Items.COBBLESTONE));

		assertFalse(SettlementNeeds.hasFood(inventory, null));
		inventory.setItem(0, new ItemStack(Items.CARROT));
		assertTrue(SettlementNeeds.hasFood(null, inventory));
	}

	@Test
	void restoresAndDecaysWithinBounds() {
		assertEquals(30, SettlementNeeds.restoredSatiety(10, 4));
		assertEquals(100, SettlementNeeds.restoredSatiety(95, 4));
		assertEquals(79, SettlementNeeds.decayedSatiety(80));
		assertEquals(0, SettlementNeeds.decayedSatiety(0));
	}

	@Test
	void validatesAndPreservesNeeds() {
		MemberNeeds needs = new MemberNeeds(37);
		assertEquals(needs, MemberNeeds.load(needs.save()));
		assertEquals(80, MemberNeeds.DEFAULT.satiety());
		assertThrows(IllegalArgumentException.class, () -> new MemberNeeds(101));
	}
}
