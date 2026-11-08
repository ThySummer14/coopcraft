package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Real ItemStack/vanilla-registry unit tests; no world, Villager Goal, or material withdrawal runs here. */
class SettlementToolUpgradeTest {

	private static HolderLookup.Provider registries;
	private static Holder<Enchantment> efficiency;
	private static Holder<Enchantment> unbreaking;
	private static Holder<Enchantment> fortune;
	private static Holder<Enchantment> mending;
	private static Holder<Enchantment> vanishing;

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		registries = VanillaRegistries.createLookup();
		var enchantments = registries.lookupOrThrow(Registries.ENCHANTMENT);
		efficiency = enchantments.getOrThrow(Enchantments.EFFICIENCY);
		unbreaking = enchantments.getOrThrow(Enchantments.UNBREAKING);
		fortune = enchantments.getOrThrow(Enchantments.FORTUNE);
		mending = enchantments.getOrThrow(Enchantments.MENDING);
		vanishing = enchantments.getOrThrow(Enchantments.VANISHING_CURSE);
	}

	@Test
	void legacyFreshStackLosesTheOriginalComponentsAndHigherEnchantments() {
		ItemStack source = personalPickaxe();
		source.enchant(efficiency, 5);
		var before = source.save(registries);

		// Characterize the old craftPolishedPickaxe implementation without keeping a failing test.
		ItemStack legacy = new ItemStack(Items.STONE_PICKAXE);
		legacy.enchant(efficiency, 1);
		legacy.enchant(unbreaking, 1);

		assertNull(legacy.get(DataComponents.CUSTOM_NAME));
		assertNull(legacy.get(DataComponents.CUSTOM_DATA));
		assertNotEquals(source.get(DataComponents.LORE), legacy.get(DataComponents.LORE));
		assertEquals(0, legacy.getDamageValue());
		assertEquals(1, legacy.getEnchantments().getLevel(efficiency));
		assertEquals(0, legacy.getEnchantments().getLevel(fortune));
		assertEquals(0, legacy.getEnchantments().getLevel(mending));
		assertEquals(0, legacy.getEnchantments().getLevel(vanishing));
		assertNotEquals(before, legacy.save(registries));
		assertEquals(before, source.save(registries));
	}

	@Test
	void keepsNameCustomDataLoreDamageAndOtherEnchantmentsWithoutMutatingSource() {
		ItemStack source = personalPickaxe();
		source.enchant(efficiency, 5);
		var before = source.save(registries);

		ItemStack polished = polish(source).orElseThrow();

		assertNotSame(source, polished);
		assertEquals(Items.STONE_PICKAXE, polished.getItem());
		assertEquals(1, polished.getCount());
		assertEquals(Component.literal("Resident's old pickaxe"), polished.get(DataComponents.CUSTOM_NAME));
		assertEquals(source.get(DataComponents.CUSTOM_DATA), polished.get(DataComponents.CUSTOM_DATA));
		assertEquals(source.get(DataComponents.LORE), polished.get(DataComponents.LORE));
		assertEquals(73, polished.getDamageValue());
		assertEquals(7, polished.get(DataComponents.REPAIR_COST));
		assertEquals(5, polished.getEnchantments().getLevel(efficiency));
		assertEquals(1, polished.getEnchantments().getLevel(unbreaking));
		assertOtherEnchantmentsPreserved(polished);
		assertOnlyEnchantmentsChanged(source, polished);
		assertEquals(before, source.save(registries));
		assertEquals(0, source.getEnchantments().getLevel(unbreaking));
	}

	@Test
	void addsExactlyTheMissingMinimumWithoutReducingEitherExistingLevel() {
		for (int efficiencyLevel : List.of(0, 1, 5)) {
			for (int unbreakingLevel : List.of(0, 1, 3)) {
				ItemStack source = personalPickaxe();
				if (efficiencyLevel > 0) source.enchant(efficiency, efficiencyLevel);
				if (unbreakingLevel > 0) source.enchant(unbreaking, unbreakingLevel);
				var before = source.save(registries);

				Optional<ItemStack> result = polish(source);

				if (efficiencyLevel > 0 && unbreakingLevel > 0) {
					assertTrue(result.isEmpty(), "An already polished tool must not request another upgrade");
				} else {
					ItemStack polished = result.orElseThrow();
					assertEquals(Math.max(1, efficiencyLevel), polished.getEnchantments().getLevel(efficiency));
					assertEquals(Math.max(1, unbreakingLevel), polished.getEnchantments().getLevel(unbreaking));
					assertOtherEnchantmentsPreserved(polished);
					assertOnlyEnchantmentsChanged(source, polished);
				}
				assertEquals(before, source.save(registries));
			}
		}
	}

	@Test
	void upgradesAnOrdinaryUnenchantedStonePickaxe() {
		ItemStack source = new ItemStack(Items.STONE_PICKAXE);
		var before = source.save(registries);
		assertTrue(SettlementToolUpgrade.supportsPolishing(source));

		ItemStack polished = polish(source).orElseThrow();

		assertEquals(1, polished.getEnchantments().getLevel(efficiency));
		assertEquals(1, polished.getEnchantments().getLevel(unbreaking));
		assertOnlyEnchantmentsChanged(source, polished);
		assertEquals(before, source.save(registries));
	}

	@Test
	void preservesHiddenEnchantmentTooltipWhenAddingTheMissingLevels() {
		ItemStack source = personalPickaxe();
		source.set(DataComponents.ENCHANTMENTS, source.getEnchantments().withTooltip(false));
		var before = source.save(registries);

		ItemStack polished = polish(source).orElseThrow();

		assertEquals(polished.getEnchantments().withTooltip(false), polished.getEnchantments());
		assertEquals(1, polished.getEnchantments().getLevel(efficiency));
		assertEquals(1, polished.getEnchantments().getLevel(unbreaking));
		assertOtherEnchantmentsPreserved(polished);
		assertOnlyEnchantmentsChanged(source, polished);
		assertEquals(before, source.save(registries));
	}

	@Test
	void preservesExplicitComponentRemovalPatches() {
		ItemStack source = personalPickaxe();
		assertNotNull(source.get(DataComponents.RARITY));
		source.remove(DataComponents.RARITY);
		source.remove(DataComponents.REPAIR_COST);
		assertEquals(Optional.empty(), source.getComponentsPatch().get(DataComponents.RARITY));
		assertEquals(Optional.empty(), source.getComponentsPatch().get(DataComponents.REPAIR_COST));
		var before = source.save(registries);

		ItemStack polished = polish(source).orElseThrow();

		assertNull(polished.get(DataComponents.RARITY));
		assertNull(polished.get(DataComponents.REPAIR_COST));
		assertEquals(Optional.empty(), polished.getComponentsPatch().get(DataComponents.RARITY));
		assertEquals(Optional.empty(), polished.getComponentsPatch().get(DataComponents.REPAIR_COST));
		assertOnlyEnchantmentsChanged(source, polished);
		assertSameStack(polished, roundTrip(polished));
		assertEquals(before, source.save(registries));
	}

	@Test
	void refusesToRestoreAnExplicitlyRemovedEnchantmentsComponent() {
		ItemStack source = personalPickaxe();
		source.remove(DataComponents.ENCHANTMENTS);
		var before = source.save(registries);

		for (int reload = 0; reload < 2; reload++) {
			assertEquals(Optional.empty(), source.getComponentsPatch().get(DataComponents.ENCHANTMENTS));
			assertNull(source.get(DataComponents.ENCHANTMENTS));
			assertFalse(SettlementToolUpgrade.supportsPolishing(source));
			assertTrue(polish(source).isEmpty());
			assertEquals(before, source.save(registries));
			source = roundTrip(source);
		}
		assertEquals(before, source.save(registries));
	}

	@Test
	void changingTheReturnedStackCannotChangeTheOriginalTool() {
		ItemStack source = personalPickaxe();
		var before = source.save(registries);
		ItemStack polished = polish(source).orElseThrow();

		polished.setDamageValue(90);
		polished.set(DataComponents.CUSTOM_NAME, Component.literal("Changed copy"));
		polished.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Changed lore"))));
		CompoundTag replacement = new CompoundTag();
		replacement.putString("Owner", "Someone else");
		polished.set(DataComponents.CUSTOM_DATA, CustomData.of(replacement));
		polished.enchant(fortune, 5);

		assertEquals(before, source.save(registries));
		assertEquals(73, source.getDamageValue());
		assertEquals(0, source.getEnchantments().getLevel(efficiency));
		assertEquals(0, source.getEnchantments().getLevel(unbreaking));
		assertOtherEnchantmentsPreserved(source);
	}

	@Test
	void existingSavedToolsUpgradeAndRoundTripWithoutComponentMigration() {
		for (int efficiencyLevel : List.of(0, 5)) {
			ItemStack original = personalPickaxe();
			if (efficiencyLevel > 0) original.enchant(efficiency, efficiencyLevel);
			original.remove(DataComponents.RARITY);
			var existingSave = original.save(registries);
			ItemStack restored = roundTrip(original);
			assertSameStack(original, restored);
			assertEquals(existingSave, restored.save(registries));

			ItemStack polished = polish(restored).orElseThrow();
			var upgradedSave = polished.save(registries);
			for (int reload = 0; reload < 2; reload++) {
				polished = roundTrip(polished);
				assertEquals(upgradedSave, polished.save(registries));
				assertOnlyEnchantmentsChanged(original, polished);
				assertEquals(Math.max(1, efficiencyLevel), polished.getEnchantments().getLevel(efficiency));
				assertEquals(1, polished.getEnchantments().getLevel(unbreaking));
				assertOtherEnchantmentsPreserved(polished);
				assertTrue(polish(polished).isEmpty());
				assertEquals(upgradedSave, polished.save(registries));
			}
			assertEquals(existingSave, restored.save(registries));
		}
	}

	@Test
	void repeatedUpgradeIsRejectedWithoutChangingTheAlreadyPolishedStack() {
		ItemStack polished = polish(personalPickaxe()).orElseThrow();
		var before = polished.save(registries);
		assertTrue(SettlementToolUpgrade.supportsPolishing(polished));

		for (int attempt = 0; attempt < 3; attempt++) {
			assertTrue(polish(polished).isEmpty());
			assertEquals(before, polished.save(registries));
		}
	}

	@Test
	void rejectsEmptyOtherToolsAndNonSingleStacksWithoutChangingThem() {
		for (ItemStack source : List.of(ItemStack.EMPTY,
			new ItemStack(Items.STONE_PICKAXE, 0), new ItemStack(Items.STONE_PICKAXE, 2),
			new ItemStack(Items.WOODEN_PICKAXE), new ItemStack(Items.IRON_PICKAXE),
			new ItemStack(Items.STONE_AXE), new ItemStack(Items.STICK))) {
			var item = source.getItem();
			int count = source.getCount();
			var components = source.getComponentsPatch();

			assertFalse(SettlementToolUpgrade.supportsPolishing(source));
			assertTrue(polish(source).isEmpty());

			assertEquals(item, source.getItem());
			assertEquals(count, source.getCount());
			assertEquals(components, source.getComponentsPatch());
		}
	}

	private static Optional<ItemStack> polish(ItemStack source) {
		return SettlementToolUpgrade.polish(source, efficiency, unbreaking);
	}

	private static ItemStack personalPickaxe() {
		ItemStack source = new ItemStack(Items.STONE_PICKAXE);
		source.set(DataComponents.CUSTOM_NAME, Component.literal("Resident's old pickaxe"));
		source.set(DataComponents.LORE, new ItemLore(List.of(
			Component.literal("Made before the upgrade"), Component.literal("Keep its history"))));
		CompoundTag history = new CompoundTag();
		history.putInt("Uses", 42);
		CompoundTag data = new CompoundTag();
		data.putString("Owner", "Resident");
		data.put("History", history);
		source.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
		source.set(DataComponents.REPAIR_COST, 7);
		source.setDamageValue(73);
		source.enchant(fortune, 3);
		source.enchant(mending, 1);
		source.enchant(vanishing, 1);
		return source;
	}

	private static ItemStack roundTrip(ItemStack stack) {
		return ItemStack.parse(registries, stack.save(registries)).orElseThrow();
	}

	private static void assertOtherEnchantmentsPreserved(ItemStack stack) {
		assertEquals(3, stack.getEnchantments().getLevel(fortune));
		assertEquals(1, stack.getEnchantments().getLevel(mending));
		assertEquals(1, stack.getEnchantments().getLevel(vanishing));
	}

	private static void assertOnlyEnchantmentsChanged(ItemStack source, ItemStack polished) {
		ItemStack expected = source.copy();
		expected.enchant(efficiency, 1);
		expected.enchant(unbreaking, 1);
		assertSameStack(expected, polished);
	}

	private static void assertSameStack(ItemStack expected, ItemStack actual) {
		assertEquals(expected.getItem(), actual.getItem());
		assertEquals(expected.getCount(), actual.getCount());
		assertEquals(expected.getComponentsPatch(), actual.getComponentsPatch());
		assertEquals(expected.save(registries), actual.save(registries));
	}
}
