package com.thysummer.coopcraft.civilization;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettlementResourceObservationCommandsTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void actualGrammarAllowsReadOnlyInspectionAndRequiresAdminForExplicitStableIds() throws Exception {
		var dispatcher = new CommandDispatcher<Integer>();
		dispatcher.register(SettlementCommands.commandTree(level -> level >= 2, (context, action, option) -> {
			assertEquals("observations", action);
			if (!option.isEmpty()) assertEquals("berry-100", StringArgumentType.getString(context, "observation_id"));
			return 1;
		}));
		assertEquals(1, dispatcher.execute("coop settlement observations", 0));
		for (String operation : List.of("suppress", "resume")) {
			assertEquals(1, dispatcher.execute("coop settlement observations " + operation + " berry-100", 2));
			assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement observations " + operation + " berry-100", 0));
			assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement observations " + operation, 2));
		}
		assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement observations authorize berry-100", 2));
	}

	@Test void projectionListsHistoricalPositionsAndBothStatesWithoutChangingDataOrCreatingSimulation() {
		var data = new SettlementSavedData(); var camp = data.getOrCreate(BlockPos.ZERO, "Read only", 0);
		data.recordResourceObservation(camp, BlockPos.ZERO, true); data.recordResourceObservation(camp, BlockPos.ZERO.east(), true);
		data.changeResourceObservation(camp, data.resourceObservationsOf(camp).getFirst().id(), true);
		data.setDirty(false); CompoundTag before = data.save(new CompoundTag(), null);
		var lines = SettlementCommands.observationMessages(data, camp);
		assertEquals(5, lines.size());
		var header = assertInstanceOf(TranslatableContents.class, lines.getFirst().getContents());
		assertEquals("coopcraft.command.settlement.observations_header", header.getKey());
		assertArrayEquals(new Object[] {"Read only", 1L, 1L, SettlementResourceObservations.MAX_PER_SETTLEMENT}, header.getArgs());
		var row = assertInstanceOf(TranslatableContents.class, lines.get(2).getContents());
		assertEquals("coopcraft.command.settlement.observations_row", row.getKey());
		assertEquals(BlockPos.ZERO.toShortString(), row.getArgs()[1]);
		assertEquals(before, data.save(new CompoundTag(), null)); assertFalse(data.isDirty()); assertTrue(data.simulationOf(camp).isEmpty());
	}

	@Test void projectionTranslatesEachKindAndShowsCurrentProtectionWithoutMutatingHistory() {
		var data = new SettlementSavedData(); var camp = data.getOrCreate(BlockPos.ZERO, "Materials", 0);
		var kinds = SettlementResourceObservations.Kind.values();
		for (int i = 0; i < kinds.length; i++) data.recordResourceObservation(camp, BlockPos.ZERO.east(i), kinds[i]);
		data.addProtectedArea(new java.util.UUID(42, 1), "Historic site", 0, 0, 0, 0);
		data.setDirty(false); CompoundTag before = data.save(new CompoundTag(), null);
		var lines = SettlementCommands.observationMessages(data, camp);
		var records = data.resourceObservationsOf(camp);
		for (int i = 0; i < records.size(); i++) {
			var record = records.get(i);
			var row = assertInstanceOf(TranslatableContents.class, lines.get(i + 2).getContents());
			assertEquals(5, row.getArgs().length);
			assertEquals("coopcraft.command.settlement.observations_kind_" + record.kind().name().toLowerCase(java.util.Locale.ROOT),
				assertInstanceOf(TranslatableContents.class, ((Component) row.getArgs()[2]).getContents()).getKey());
			assertEquals("coopcraft.command.settlement." + (record.position().equals(BlockPos.ZERO)
				? "observations_protected" : "observations_no_protection"),
				assertInstanceOf(TranslatableContents.class, ((Component) row.getArgs()[4]).getContents()).getKey());
		}
		assertEquals(before, data.save(new CompoundTag(), null)); assertFalse(data.isDirty());
		assertTrue(data.resourcesOf(camp).isEmpty()); assertTrue(data.simulationOf(camp).isEmpty());
	}

	@Test void malformedProtectionShowsVetoOnHistoricalRowWithoutDiscardingIt() {
		var seed = new SettlementSavedData(); var camp = seed.getOrCreate(BlockPos.ZERO, "History", 0);
		seed.recordResourceObservation(camp, BlockPos.ZERO, SettlementResourceObservations.Kind.STONE);
		CompoundTag saved = seed.save(new CompoundTag(), null); saved.putString("PlayerProtectedAreas", "future");
		var data = SettlementSavedData.load(saved, null); camp = data.find(BlockPos.ZERO).orElseThrow();
		data.setDirty(false); CompoundTag before = data.save(new CompoundTag(), null);
		var row = assertInstanceOf(TranslatableContents.class, SettlementCommands.observationMessages(data, camp).get(2).getContents());
		assertEquals("coopcraft.command.settlement.observations_protected",
			assertInstanceOf(TranslatableContents.class, ((Component) row.getArgs()[4]).getContents()).getKey());
		assertEquals(before, data.save(new CompoundTag(), null)); assertFalse(data.isDirty());
	}

	@Test void unsupportedDataIsReportedInsteadOfShownAsAnEmptySuccessfulSurvey() {
		var data = new SettlementSavedData(); var camp = data.getOrCreate(BlockPos.ZERO, "Unknown", 0);
		CompoundTag saved = data.save(new CompoundTag(), null); saved.putString("ResourceObservations", "future");
		var loaded = SettlementSavedData.load(saved, null);
		var lines = SettlementCommands.observationMessages(loaded, loaded.find(BlockPos.ZERO).orElseThrow());
		assertEquals(1, lines.size());
		assertEquals("coopcraft.command.settlement.observation_preserved_unsupported",
			assertInstanceOf(TranslatableContents.class, lines.getFirst().getContents()).getKey());
	}

	@Test void bothLanguagesExplainHistoricalUnknownSourceAndOldHarvestScope() throws Exception {
		for (String language : List.of("en_us", "zh_cn")) {
			var json = JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/coopcraft/lang/" + language + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
			for (String key : List.of("observations_header", "observations_scope", "observations_row", "observations_candidate",
				"observations_suppressed", "observations_kind_sweet_berry", "observations_kind_oak_log", "observations_kind_stone",
				"observations_protected", "observations_no_protection", "observations_help", "observations_status", "observation_changed", "observation_unchanged",
				"observation_not_found", "observation_not_suppressed", "observation_preserved_unsupported")) {
				assertTrue(json.has("coopcraft.command.settlement." + key), language + ": " + key);
			}
			assertTrue(json.get("coopcraft.command.settlement.observations_scope").getAsString().contains("UNKNOWN"));
			assertTrue(json.get("coopcraft.command.settlement.observations_help").getAsString().contains("resume"));
		}
	}
}
