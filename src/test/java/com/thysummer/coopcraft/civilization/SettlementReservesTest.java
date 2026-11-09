package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementReservesTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void defaultMigrationUnknownValuesAndTwoReloads() {
		for (String value : List.of("missing", "future", "HIGH", "normal", "high")) {
			var data = new SettlementSavedData();
			var settlement = data.getOrCreate(BlockPos.ZERO, "Migration", 0);
			CompoundTag saved = data.save(new CompoundTag(), null);
			saved.putInt("DataVersion", 18);
			var entry = saved.getList("Settlements", ListTag.TAG_COMPOUND).getCompound(0);
			if (value.equals("missing")) entry.remove("FoodReserveTarget");
			else entry.putString("FoodReserveTarget", value);
			data = SettlementSavedData.load(saved, null);
			for (int round = 0; round < 3; round++) {
				assertEquals(value.equals("high") ? FoodReserveTarget.HIGH : FoodReserveTarget.NORMAL, data.foodReserveTargetOf(settlement));
				data.setDirty(false);
				CompoundTag before = data.save(new CompoundTag(), null);
				var snapshot = SettlementReserves.inspect(data, settlement, null);
				SettlementCommands.reservesMessage(settlement.name(), snapshot);
				assertFalse(data.isDirty());
				assertEquals(before, data.save(new CompoundTag(), null));
				data = SettlementSavedData.load(before, null);
				assertEquals(before, data.save(new CompoundTag(), null));
			}
		}
	}

	@Test void settingIsIsolatedIdempotentAndChangesNoOtherState() {
		var data = new SettlementSavedData();
		var a = data.getOrCreate(BlockPos.ZERO, "A", 0);
		var b = data.getOrCreate(new BlockPos(200, 0, 0), "B", 0);
		data.setDirty(false);
		assertFalse(SettlementReserves.setTarget(data, a, FoodReserveTarget.NORMAL));
		assertFalse(data.isDirty());
		CompoundTag before = data.save(new CompoundTag(), null);
		assertTrue(SettlementReserves.setTarget(data, a, FoodReserveTarget.HIGH));
		assertTrue(data.isDirty());
		CompoundTag after = data.save(new CompoundTag(), null);
		before.getList("Settlements", ListTag.TAG_COMPOUND).getCompound(0).putString("FoodReserveTarget", "high");
		assertEquals(before, after);
		data.setDirty(false);
		assertFalse(SettlementReserves.setTarget(data, a, FoodReserveTarget.HIGH));
		assertFalse(data.isDirty());
		for (int i = 0; i < 2; i++) {
			data = SettlementSavedData.load(data.save(new CompoundTag(), null), null);
			assertEquals(FoodReserveTarget.HIGH, data.foodReserveTargetOf(a));
			assertEquals(FoodReserveTarget.NORMAL, data.foodReserveTargetOf(b));
		}
		assertTrue(SettlementReserves.setTarget(data, a, FoodReserveTarget.NORMAL));
	}

	@Test void thresholdsAndUnknownStorageFollowOnlyActiveAuthority() {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "Threshold", 0);
		data.registerMember(settlement, new UUID(1, 1), 0);
		var chest = new SimpleContainer(1);
		for (FoodReserveTarget target : FoodReserveTarget.values()) {
			SettlementReserves.setTarget(data, settlement, target);
			for (int delta : List.of(-1, 0, 1)) {
				chest.setItem(0, new ItemStack(Items.SWEET_BERRIES, target.pointsPerMember() + delta));
				assertEquals(delta < 0, SettlementForaging.reserveLow(data, settlement, chest));
				assertEquals(OptionalLong.of(Math.max(0, -delta)), SettlementReserves.inspect(data, settlement, chest).shortfall());
			}
			var unknown = SettlementReserves.inspect(data, settlement, null);
			assertTrue(unknown.current().isEmpty()); assertTrue(unknown.shortfall().isEmpty());
			assertTrue(SettlementForaging.reserveLow(data, settlement, null), "Preserve legacy unknown-store work behavior");
		}
		var state = data.getOrCreateSimulation(settlement);
		state.world().importExternalBatch(ResourceType.FISH, 2, state.campOwner()); // exactly NORMAL's 40 nutrition
		chest.clearContent();
		SettlementReserves.setTarget(data, settlement, FoodReserveTarget.NORMAL);
		assertTrue(SettlementForaging.reserveLow(data, settlement, chest), "Disabled simulation cannot supply physical stock");
		state.setSurvivalEnabled(true, 0);
		assertFalse(SettlementForaging.reserveLow(data, settlement, null));
		assertEquals(OptionalLong.of(40), SettlementReserves.inspect(data, settlement, null).current());
		SettlementReserves.setTarget(data, settlement, FoodReserveTarget.HIGH);
		chest.setItem(0, new ItemStack(Items.BREAD, 64));
		assertTrue(SettlementForaging.reserveLow(data, settlement, chest), "Physical stock cannot satisfy abstract target");
		state.world().importExternalBatch(ResourceType.FISH, 2, state.campOwner()); // exactly HIGH's 80
		assertFalse(SettlementForaging.reserveLow(data, settlement, null));
		state.world().assertConservation();
	}

	@Test void emptyPopulationAndMixedFoodKeepExplicitUnits() {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "Mixed", 0);
		var chest = new SimpleContainer(new ItemStack(Items.BREAD, 2), new ItemStack(Items.SWEET_BERRIES, 2));
		assertEquals(0, SettlementReserves.inspect(data, settlement, chest).goal());
		assertFalse(SettlementForaging.reserveLow(data, settlement, null));
		assertTrue(data.simulationOf(settlement).isEmpty());
		data.registerMember(settlement, new UUID(4, 1), 0);
		var snapshot = SettlementReserves.inspect(data, settlement, chest);
		assertFalse(snapshot.survival()); assertEquals(8, snapshot.goal());
		assertEquals(OptionalLong.of(8), snapshot.current());
		assertFalse(SettlementForaging.reserveLow(data, settlement, chest));
		data.registerMember(settlement, new UUID(4, 2), 0);
		assertEquals(16, SettlementReserves.inspect(data, settlement, chest).goal());
		assertTrue(SettlementForaging.reserveLow(data, settlement, chest));
		var state = data.getOrCreateSimulation(settlement);
		state.setSurvivalEnabled(true, 0);
		state.world().importExternalBatch(ResourceType.FISH, 1, state.campOwner());
		state.world().importExternalBatch(ResourceType.WILD_FRUIT, 2, state.campOwner());
		state.world().importExternalBatch(ResourceType.TUBERS, 1, state.campOwner());
		snapshot = SettlementReserves.inspect(data, settlement, null);
		assertTrue(snapshot.survival()); assertEquals(80, snapshot.goal());
		assertEquals(OptionalLong.of(80), snapshot.current());
		assertFalse(SettlementForaging.reserveLow(data, settlement, null));
		assertEquals(OptionalLong.of(0), snapshot.shortfall());
		state.world().assertConservation();
	}

	@Test void onlyForagerUsesPreventiveThresholdAndEmergencyAndCooldownStayUnchanged() {
		for (WorkRole role : WorkRole.values()) {
			assertEquals(role == WorkRole.FORAGER, SettlementForaging.needsForage(true, role, () -> true, 200, 200));
			assertTrue(SettlementForaging.needsForage(false, role, () -> false, 200, 200));
			assertFalse(SettlementForaging.needsForage(false, role, () -> true, 199, 200));
			if (role != WorkRole.FORAGER) assertFalse(SettlementForaging.needsForage(true, role,
				() -> { fail("Non-foragers must not inspect reserves"); return true; }, 200, 200));
		}
	}

	@Test void grammarPermissionsAndHandlerDispatch() throws Exception {
		var dispatcher = new CommandDispatcher<Integer>();
		var calls = new java.util.ArrayList<String>();
		dispatcher.register(SettlementCommands.commandTree(level -> level >= 2, (ctx, action, option) -> {
			calls.add(action + ":" + option); return 1;
		}));
		for (int level : List.of(0, 1, 2)) assertEquals(1, dispatcher.execute("coop settlement reserves", level));
		for (String target : List.of("normal", "high")) {
			for (int level : List.of(0, 1)) assertThrows(CommandSyntaxException.class,
				() -> dispatcher.execute("coop settlement reserves " + target, level));
			assertEquals(1, dispatcher.execute("coop settlement reserves " + target, 2));
		}
		for (String invalid : List.of("low", "HIGH", "8", "normal extra", "high extra"))
			assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement reserves " + invalid, 2));
		assertEquals(List.of("reserves:", "reserves:", "reserves:", "reserves:normal", "reserves:high"), calls);
		assertEquals(List.of("high", "normal"), dispatcher.getCompletionSuggestions(dispatcher.parse("coop settlement reserves ", 2))
			.get().getList().stream().map(s -> s.getText()).toList());
	}
	@Test void bilingualMessagesKeepEightOrderedArgumentsAndUnknownStock() throws Exception {
		String prefix = "coopcraft.command.settlement.reserves_";
		for (String locale : List.of("en_us", "zh_cn")) {
			try (var input = getClass().getResourceAsStream("/assets/coopcraft/lang/" + locale + ".json")) {
				assertNotNull(input);
				var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(input,
					java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
				for (String key : List.of("changed", "unchanged", "normal", "high", "nutrition", "points", "unavailable", "status", "hint")) {
					assertTrue(json.has(prefix + key), locale + ": " + key);
					assertFalse(json.get(prefix + key).getAsString().isBlank());
				}
				assertEquals(8, json.get(prefix + "status").getAsString().split("%s", -1).length - 1);
			}
		}
		for (boolean survival : List.of(false, true)) {
			long goal = survival ? 240 : 48;
			var snapshot = new SettlementReserves.Snapshot(FoodReserveTarget.HIGH, 3, survival, OptionalLong.of(30), goal);
			var message = translation(SettlementCommands.reservesMessage("Village", snapshot));
			assertEquals(prefix + "status", message.getKey());
			Object[] args = message.getArgs();
			assertEquals(8, args.length);
			assertEquals("Village", args[0]);
			assertEquals(prefix + "high", translation((net.minecraft.network.chat.Component) args[1]).getKey());
			assertEquals(3, args[2]); assertEquals(16, args[3]);
			assertEquals(prefix + (survival ? "nutrition" : "points"), translation((net.minecraft.network.chat.Component) args[4]).getKey());
			assertEquals(30L, args[5]); assertEquals(goal, args[6]); assertEquals(goal - 30, args[7]);
		}
		var unknown = new SettlementReserves.Snapshot(FoodReserveTarget.NORMAL, 2, false, OptionalLong.empty(), 16);
		Object[] args = translation(SettlementCommands.reservesMessage("Unknown", unknown)).getArgs();
		assertEquals(8, args.length);
		assertEquals(prefix + "normal", translation((net.minecraft.network.chat.Component) args[1]).getKey());
		assertEquals(2, args[2]); assertEquals(8, args[3]); assertEquals(16L, args[6]);
		for (int index : List.of(5, 7)) {
			assertEquals(prefix + "unavailable", translation((net.minecraft.network.chat.Component) args[index]).getKey());
		}
	}

	private static net.minecraft.network.chat.contents.TranslatableContents translation(net.minecraft.network.chat.Component component) {
		return assertInstanceOf(net.minecraft.network.chat.contents.TranslatableContents.class, component.getContents());
	}

}
