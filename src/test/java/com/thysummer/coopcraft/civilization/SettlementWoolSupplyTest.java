package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettlementWoolSupplyTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void unknownStoreIsNotReportedAsEmptyOrAsAnActionableBudget() {
		assertTrue(SettlementWoolSupply.inspect(Map.of(Items.WHITE_BED, 2), null).isEmpty());
	}

	@Test
	void unrelatedOrFinishedDemandDoesNotReadTheInventory() {
		SimpleContainer unreadable = new SimpleContainer(1) {
			@Override public ItemStack getItem(int slot) { throw new AssertionError("No bed demand"); }
		};
		assertTrue(SettlementWoolSupply.inspect(Map.of(Items.OAK_LOG, 24), unreadable).isEmpty());
		assertTrue(SettlementWoolSupply.inspect(Map.of(Items.WHITE_BED, 0), unreadable).isEmpty());
		assertTrue(SettlementWoolSupply.inspect(Map.of(), unreadable).isEmpty());
	}

	@Test
	void twoBedsNeedSixWoolOrTwentyFourStringWithoutReservingThem() {
		SimpleContainer store = new SimpleContainer(2);
		store.setItem(0, new ItemStack(Items.STRING, 7));
		var expected = new SettlementWoolSupply.Snapshot(2, 6, 24, 7);
		for (int i = 0; i < 3; i++) assertEquals(expected,
			SettlementWoolSupply.inspect(Map.of(Items.WHITE_BED, 2), store).orElseThrow());
		assertEquals(7, store.countItem(Items.STRING));
		assertEquals(0, store.countItem(Items.WHITE_WOOL));
	}

	@Test
	void namedFinishedBedCountsButNamedWoolAndStringDoNot() {
		SimpleContainer store = new SimpleContainer(6);
		store.setItem(0, named(Items.WHITE_BED, 1));
		store.setItem(1, named(Items.WHITE_WOOL, 6));
		store.setItem(2, named(Items.STRING, 24));
		store.setItem(3, new ItemStack(Items.WHITE_WOOL, 2));
		store.setItem(4, new ItemStack(Items.STRING, 3));
		assertEquals(new SettlementWoolSupply.Snapshot(1, 1, 4, 3),
			SettlementWoolSupply.inspect(Map.of(Items.WHITE_BED, 2), store).orElseThrow());
		assertTrue(SettlementWoolSupply.inspect(Map.of(Items.WHITE_BED, 1), store).isEmpty());
		assertEquals(8, store.countItem(Items.WHITE_WOOL));
		assertEquals(27, store.countItem(Items.STRING));
	}

	@Test
	void coloredBedsAndWoolAreNotSilentlyConverted() {
		SimpleContainer store = new SimpleContainer(3);
		store.setItem(0, new ItemStack(Items.RED_BED));
		store.setItem(1, new ItemStack(Items.RED_WOOL, 6));
		assertEquals(new SettlementWoolSupply.Snapshot(1, 3, 12, 0),
			SettlementWoolSupply.inspect(Map.of(Items.WHITE_BED, 1), store).orElseThrow());
		assertTrue(SettlementWoolSupply.inspect(Map.of(Items.RED_BED, 1), store).isEmpty());
	}

	@Test
	void excessWoolNeverProducesNegativeStringBudget() {
		SimpleContainer store = new SimpleContainer(1);
		store.setItem(0, new ItemStack(Items.WHITE_WOOL, 64));
		assertEquals(new SettlementWoolSupply.Snapshot(2, 0, 0, 0),
			SettlementWoolSupply.inspect(Map.of(Items.WHITE_BED, 2), store).orElseThrow());
	}

	@Test
	void budgetMultiplicationDoesNotOverflowIntegerCounts() {
		assertEquals(new SettlementWoolSupply.Snapshot(Integer.MAX_VALUE, 6442450941L, 25769803764L, 0),
			SettlementWoolSupply.inspect(Map.of(Items.WHITE_BED, Integer.MAX_VALUE), new SimpleContainer(1)).orElseThrow());
	}

	@Test
	void commandMessageUsesSnapshotValuesAndBothLanguageResourcesExplainLimits() throws Exception {
		var message = SettlementCommands.woolSupplyMessage(new SettlementWoolSupply.Snapshot(2, 4, 16, 8));
		var contents = assertInstanceOf(TranslatableContents.class, message.getContents());
		assertEquals("coopcraft.command.settlement.wool_supply", contents.getKey());
		assertArrayEquals(new Object[] {2L, 4L, 16L, 8L}, contents.getArgs());
		for (String language : new String[] {"zh_cn", "en_us"}) {
			try (var stream = getClass().getResourceAsStream("/assets/coopcraft/lang/" + language + ".json")) {
				assertNotNull(stream);
				var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
				String budget = json.get(contents.getKey()).getAsString();
				assertEquals(4, budget.split("%s", -1).length - 1);
				assertFalse(json.get("coopcraft.command.settlement.material_sources").getAsString().isBlank());
			}
		}
	}

	private static ItemStack named(net.minecraft.world.item.Item item, int count) {
		ItemStack stack = new ItemStack(item, count);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Keep the components"));
		return stack;
	}
}
