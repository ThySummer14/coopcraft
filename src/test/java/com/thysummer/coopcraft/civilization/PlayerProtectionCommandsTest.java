package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Command grammar/permissions, pure feedback and source wiring checks, not live-game evidence. */
class PlayerProtectionCommandsTest {
	private record Source(UUID playerId, int permission, boolean spectator) {
		boolean eligible() { return playerId != null && !spectator; }
	}
	private static final UUID ALICE = new UUID(1, 2), BOB = new UUID(3, 4);
	private static final Source PLAYER = new Source(ALICE, 0, false);
	private static final Source OPERATOR = new Source(BOB, 2, false);
	private static final Source CONSOLE = new Source(null, 4, false);
	private static final Source SPECTATOR = new Source(ALICE, 2, true);

	@BeforeAll static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test void treeIsIndependentOfSettlementAndMergesWithoutReplacingOtherCoopCommands() throws Exception {
		var calls = new ArrayList<String>();
		var dispatcher = new CommandDispatcher<Source>();
		dispatcher.register(LiteralArgumentBuilder.<Source>literal("coop")
			.then(LiteralArgumentBuilder.<Source>literal("settlement").executes(ctx -> 7)));
		dispatcher.register(PlayerProtectionCommands.commandTree(Source::eligible, (context, action) -> {
			calls.add(action);
			if (action.equals("start")) assertEquals("My house", StringArgumentType.getString(context, "name"));
			return 1;
		}));
		assertEquals(7, dispatcher.execute("coop settlement", PLAYER));
		for (Source source : List.of(PLAYER, OPERATOR)) {
			for (String command : List.of("start \"My house\"", "confirm", "cancel", "remove " + ALICE)) {
				assertEquals(1, dispatcher.execute("coop protect " + command, source));
			}
		}
		for (Source source : List.of(PLAYER, OPERATOR, CONSOLE, SPECTATOR)) {
			for (String command : List.of("coop protect", "coop protect list", "coop protect list 2", "coop protect show " + ALICE)) {
				assertEquals(1, dispatcher.execute(command, source));
			}
		}
		int accepted = calls.size();
		var tree = dispatcher.getRoot().getChild("coop").getChild("protect");
		for (Source source : List.of(CONSOLE, SPECTATOR)) {
			for (String command : List.of("start \"My house\"", "confirm", "cancel", "remove " + ALICE)) {
				assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop protect " + command, source));
			}
			for (String node : List.of("start", "confirm", "cancel", "remove")) assertFalse(tree.getChild(node).canUse(source));
		}
		for (String malformed : List.of("start", "start unquoted words", "confirm extra", "cancel extra", "show", "remove",
			"list 0", "list 9", "list -1", "create", "start \"My house\" 1 2 3", "remove " + ALICE + " extra")) {
			assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop protect " + malformed, OPERATOR));
		}
		assertEquals(accepted, calls.size(), "Rejected grammar/permissions must never invoke the handler");
	}

	@Test void quotedChineseNamesReachTheActualStartHandlerUnchanged() throws Exception {
		var names = new ArrayList<String>();
		var dispatcher = new CommandDispatcher<Source>();
		dispatcher.register(PlayerProtectionCommands.commandTree(Source::eligible, (context, action) -> {
			assertEquals("start", action);
			names.add(StringArgumentType.getString(context, "name"));
			return 1;
		}));
		assertEquals(1, dispatcher.execute("coop protect start \"我的房子\"", PLAYER));
		assertEquals(1, dispatcher.execute("coop protect start \"我的 房子\"", PLAYER));
		assertEquals(List.of("我的房子", "我的 房子"), names);
		assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop protect start 我的房子", PLAYER));
	}

	@Test void freshSavedDataEnforcesCreatorAndOperatorPermissionsForEachRemoval() throws Exception {
		var data = new SettlementSavedData();
		var first = data.addProtectedArea(ALICE, "Alice", 0, 0, 3, 3).area();
		var second = data.addProtectedArea(ALICE, "Alice2", 5, 5, 8, 8).area();
		var dispatcher = new CommandDispatcher<Source>();
		dispatcher.register(PlayerProtectionCommands.commandTree(Source::eligible, (context, action) -> {
			assertEquals("remove", action);
			Source source = context.getSource();
			return data.removeProtectedArea(PlayerProtectionCommands.parseId(StringArgumentType.getString(context, "id")),
				source.playerId(), source.permission() >= 2) == PlayerProtectedAreas.Status.REMOVED ? 1 : 0;
		}));
		data.setDirty(false);
		assertEquals(0, dispatcher.execute("coop protect remove " + first.id(), new Source(BOB, 0, false)));
		assertFalse(data.isDirty());
		assertEquals(1, dispatcher.execute("coop protect remove " + first.id(), PLAYER));
		assertTrue(data.isDirty());
		data.setDirty(false);
		assertEquals(0, dispatcher.execute("coop protect remove " + first.id(), PLAYER));
		assertFalse(data.isDirty(), "Repeated remove does not dirty saved data");
		assertEquals(1, dispatcher.execute("coop protect remove " + second.id(), OPERATOR));
		assertEquals(0, data.protectedAreas().size());
	}

	@Test void selectionConfirmationRechecksFreshQuotaAndDoesNotWriteDuringCornerClicks() {
		var data = new SettlementSavedData();
		var sessions = new PlayerProtectionSelection();
		var actor = new PlayerProtectionSelection.Actor(ALICE, "minecraft:overworld", true);
		sessions.start(actor, "Pending", 0);
		sessions.click(actor, 1, true, true, true, 0, 0, (x, z) -> true);
		sessions.click(actor, 2, true, true, true, 2, 2, (x, z) -> true);
		assertFalse(data.isDirty());
		assertEquals(0, data.protectedAreas().size());
		for (int i = 0; i < PlayerProtectedAreas.MAX_PER_OWNER; i++) data.addProtectedArea(ALICE, "Area " + i, i, i, i, i);
		data.setDirty(false);
		var result = sessions.confirm(actor, 3, (x, z) -> true, (owner, name, bounds) ->
			data.addProtectedArea(owner, name, bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ()));
		assertEquals(PlayerProtectedAreas.Status.OWNER_LIMIT, result.saved().status());
		assertFalse(data.isDirty());
		assertEquals(PlayerProtectedAreas.MAX_PER_OWNER, data.protectedAreas().size());
	}

	@Test void canonicalUuidParsingNeverAcceptsShortOrAmbiguousIds() {
		assertEquals(ALICE, PlayerProtectionCommands.parseId(ALICE.toString()));
		UUID upper = UUID.fromString("ABCDEFAB-ABCD-ABCD-ABCD-ABCDEFABCDEF");
		assertEquals(upper, PlayerProtectionCommands.parseId(upper.toString().toUpperCase(java.util.Locale.ROOT)));
		for (String raw : new String[] {null, "", "1-1-1-1-1", "not-a-uuid", ALICE + " ", " " + ALICE,
			"00000000-0000-0000-0000-00000000000g"}) assertNull(PlayerProtectionCommands.parseId(raw));
	}

	@Test void queriesAndPreviewAreReadOnlyBoundedAndIncludeTheLimitedProtectionScope() {
		var data = new SettlementSavedData();
		for (int i = 0; i < 17; i++) data.addProtectedArea(i < 16 ? ALICE : BOB, "Area " + i, i, i, i, i);
		data.setDirty(false);
		var snapshot = data.protectedAreas();
		var firstPage = PlayerProtectionCommands.listMessages(snapshot, "minecraft:overworld", 1);
		var secondPage = PlayerProtectionCommands.listMessages(snapshot, "minecraft:overworld", 2);
		assertEquals(18, firstPage.size());
		assertEquals(3, secondPage.size());
		assertEquals("coopcraft.command.protect.scope", translation(firstPage.getLast()).getKey());
		assertArrayEquals(new Object[] {"minecraft:overworld", 17, 2, 2}, translation(secondPage.getFirst()).getArgs());
		var detail = PlayerProtectionCommands.areaMessages(snapshot.all().getFirst(), "minecraft:overworld", -64, 319);
		assertEquals("coopcraft.command.protect.scope", translation(detail.getLast()).getKey());
		assertEquals(-64, translation(detail.get(1)).getArgs()[7]);
		assertEquals(319, translation(detail.get(1)).getArgs()[8]);
		var draft = new PlayerProtectionSelection.Draft("minecraft:overworld", "Home",
			new PlayerProtectionSelection.Point(10, 20), new PlayerProtectionSelection.Point(3, 4), 0);
		var preview = PlayerProtectionCommands.selectionMessages(
			new PlayerProtectionSelection.Result(PlayerProtectionSelection.Status.PREVIEW, draft), -64, 319);
		assertEquals("coopcraft.command.protect.preview", translation(preview.getFirst()).getKey());
		assertEquals("coopcraft.command.protect.scope", translation(preview.get(2)).getKey());
		assertEquals("coopcraft.command.protect.confirm_help", translation(preview.getLast()).getKey());
		assertFalse(data.isDirty());
		assertSame(snapshot, data.protectedAreas());
	}

	@Test void legacyMarkerDispatcherHasAnEarlyReturnBeforeAnyBlockRead() throws Exception {
		String source = Files.readString(Path.of("src/main/java/com/thysummer/coopcraft/civilization/Civilization.java"));
		int method = source.indexOf("private static InteractionResult onUseBlock(");
		int intercept = source.indexOf("PlayerProtectionCommands.interceptUseBlock(", method);
		int loaded = source.indexOf("level.isLoaded(", method);
		int state = source.indexOf("level.getBlockState(", method);
		assertTrue(method >= 0 && intercept > method && intercept < loaded && loaded < state);
		String guard = source.substring(intercept, loaded);
		assertTrue(guard.contains("!= InteractionResult.PASS"));
		assertTrue(guard.contains("return "));
		assertTrue(source.contains("PlayerProtectionCommands.register()"));
	}

	@Test void allUserFacingProtectionMessagesHaveBothTranslationsAndExpectedArguments() throws Exception {
		Map<String, Integer> counts = new LinkedHashMap<>(Map.of("started", 1, "first_corner", 2,
			"preview", 1, "range", 9, "area", 3, "list", 4, "list_entry", 7, "added", 2, "removed", 1));
		for (String key : List.of("help", "scope", "confirm_help", "guidance", "no_selection", "cancelled", "expired",
			"dimension_changed", "player_unavailable", "session_limit", "invalid_name", "out_of_bounds", "too_large",
			"need_corners", "data_unavailable", "dimension_limit", "owner_limit", "invalid_id", "not_found", "not_owner", "invalid")) counts.put(key, 0);
		for (String language : List.of("zh_cn", "en_us")) {
			try (var input = getClass().getResourceAsStream("/assets/coopcraft/lang/" + language + ".json")) {
				assertNotNull(input);
				var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
				counts.forEach((suffix, count) -> {
					String key = "coopcraft.command.protect." + suffix;
					assertTrue(json.has(key), language + ": " + key);
					String value = json.get(key).getAsString();
					assertFalse(value.isBlank());
					assertEquals(count, value.split("%s", -1).length - 1, key);
				});
				String scope = json.get("coopcraft.command.protect.scope").getAsString();
				assertTrue(scope.contains("CoopCraft"));
				assertTrue(scope.contains(language.equals("zh_cn") ? "原版" : "vanilla"));
			}
		}
	}

	private static TranslatableContents translation(Component component) {
		return assertInstanceOf(TranslatableContents.class, component.getContents());
	}
}
