package com.thysummer.coopcraft.civilization;

import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettlementCargoCommandsTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test
	void cargoCommandsAreOrdinaryPlayerActionsWithAnExplicitRecordId() throws Exception {
		var dispatcher = new CommandDispatcher<Integer>();
		dispatcher.register(SettlementCommands.commandTree(level -> level >= 2, (context, action, option) -> {
			assertEquals("cargo", action);
			return option.isEmpty() ? 1 : 2;
		}));
		assertEquals(1, dispatcher.execute("coop settlement cargo", 0));
		assertEquals(2, dispatcher.execute("coop settlement cargo recover " + new UUID(1, 2), 0));
		assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement cargo recover", 0));
		assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement cargo erase", 0));
	}

	@Test
	void onlyCanonicalRecordIdsAreAccepted() {
		UUID id = UUID.fromString("abcdefab-1234-5678-9012-abcdefabcdef");
		assertEquals(id, SettlementCargoCommands.parseId(id.toString()));
		assertEquals(id, SettlementCargoCommands.parseId(id.toString().toUpperCase(java.util.Locale.ROOT)));
		for (String invalid : new String[] {"", "1-1-1-1-1", "../record", id + " "})
			assertNull(SettlementCargoCommands.parseId(invalid));
		assertNull(SettlementCargoCommands.parseId(null));
	}

	@Test
	void onlyOperatorsCanListOrRescueStrandedRemnants() throws Exception {
		var dispatcher = new CommandDispatcher<Integer>();
		dispatcher.register(SettlementCommands.commandTree(level -> level >= 2, (context, action, option) -> 1));
		for (String action : new String[] {"cargo stranded", "cargo rescue " + new UUID(1, 2)}) {
			for (int permission : new int[] {0, 1})
				assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement " + action, permission));
			assertEquals(1, dispatcher.execute("coop settlement " + action, 2));
		}
		assertTrue(SettlementCargoCommands.canRescue(true, true, true));
		assertFalse(SettlementCargoCommands.canRescue(false, true, true));
		assertFalse(SettlementCargoCommands.canRescue(true, false, true));
		assertFalse(SettlementCargoCommands.canRescue(true, true, false));
	}

	@Test
	void recoveryRequiresAliveNonSpectatorAtTheLoadedTickingLocation() {
		assertTrue(SettlementCargoCommands.canRecover(true, false, false, true, true, true, true, 9));
		for (double distance : new double[] {9.01, -1, Double.NaN, Double.POSITIVE_INFINITY})
			assertFalse(SettlementCargoCommands.canRecover(true, false, false, true, true, true, true, distance));
		assertFalse(SettlementCargoCommands.canRecover(false, false, false, true, true, true, true, 0));
		assertFalse(SettlementCargoCommands.canRecover(true, true, false, true, true, true, true, 0));
		assertFalse(SettlementCargoCommands.canRecover(true, false, true, true, true, true, true, 0));
		assertFalse(SettlementCargoCommands.canRecover(true, false, false, false, true, true, true, 0));
		assertFalse(SettlementCargoCommands.canRecover(true, false, false, true, false, true, true, 0));
		assertFalse(SettlementCargoCommands.canRecover(true, false, false, true, true, false, true, 0));
		assertFalse(SettlementCargoCommands.canRecover(true, false, false, true, true, true, false, 0));
	}

	@Test
	void fullMainInventoryCannotSpillCargoIntoArmorOrOffhand() {
		SimpleContainer inventory = new SimpleContainer(41);
		for (int slot = 0; slot < 36; slot++) inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
		var view = new SettlementCargoCommands.MainInventory(inventory);
		assertEquals(36, view.getContainerSize());
		assertEquals(0, SettlementStockpile.insert(view, new ItemStack(Items.COBBLESTONE, 8)));
		for (int slot = 36; slot < 41; slot++) assertTrue(inventory.getItem(slot).isEmpty());
		inventory.setItem(35, new ItemStack(Items.COBBLESTONE, 63));
		assertEquals(1, SettlementStockpile.insert(view, new ItemStack(Items.COBBLESTONE, 8)));
		assertEquals(64, inventory.getItem(35).getCount());
		for (int slot = 36; slot < 41; slot++) assertTrue(inventory.getItem(slot).isEmpty());
		assertThrows(IndexOutOfBoundsException.class, () -> view.setItem(36, new ItemStack(Items.COBBLESTONE)));
	}

	@Test
	void clearingTheMainViewLeavesEquipmentAndOffhandUntouched() {
		SimpleContainer inventory = new SimpleContainer(41);
		inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 8));
		inventory.setItem(36, new ItemStack(Items.DIAMOND_BOOTS));
		inventory.setItem(40, new ItemStack(Items.SHIELD));
		var view = new SettlementCargoCommands.MainInventory(inventory);
		assertFalse(view.isEmpty());
		view.clearContent();
		assertTrue(view.isEmpty());
		assertTrue(inventory.getItem(36).is(Items.DIAMOND_BOOTS));
		assertTrue(inventory.getItem(40).is(Items.SHIELD));
		assertEquals(3, new SettlementCargoCommands.MainInventory(new SimpleContainer(3)).getContainerSize());
	}

	@Test
	void allRecoveryStatusesAndVisibleActionsHaveBothTranslations() throws Exception {
		for (String language : new String[] {"zh_cn", "en_us"}) {
			try (var stream = getClass().getResourceAsStream("/assets/coopcraft/lang/" + language + ".json")) {
				assertNotNull(stream);
				var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream,
					java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
				for (var status : SettlementCargoRecovery.Status.values()) {
					assertFalse(json.get("coopcraft.command.settlement.cargo_"
						+ status.name().toLowerCase(java.util.Locale.ROOT)).getAsString().isBlank());
				}
				assertFalse(json.get("coopcraft.interaction.action.return_to_stockpile").getAsString().isBlank());
				for (String blocker : new String[] {"cargo_store_unavailable", "cargo_path_blocked", "cargo_store_full"})
					assertFalse(json.get("coopcraft.interaction.blocker." + blocker).getAsString().isBlank());
				String death = json.get("coopcraft.command.settlement.cargo_death").getAsString();
				for (String argument : new String[] {"%1$s", "%2$s", "%3$s"}) assertTrue(death.contains(argument));
			}
		}
	}
}
