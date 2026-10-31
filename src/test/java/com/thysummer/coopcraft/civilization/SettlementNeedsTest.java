package com.thysummer.coopcraft.civilization;

import net.minecraft.SharedConstants;
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
