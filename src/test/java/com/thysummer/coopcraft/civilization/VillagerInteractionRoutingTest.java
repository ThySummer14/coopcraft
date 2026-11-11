package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonParser;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.thysummer.coopcraft.civilization.SettlementMembershipClaims.*;
import static com.thysummer.coopcraft.civilization.VillagerInteraction.Route.*;
import static org.junit.jupiter.api.Assertions.*;

/** Tests the real production routing seam and configuration, not runtime Mixin application or GUI clicks. */
class VillagerInteractionRoutingTest {
	private static final UUID RESIDENT = new UUID(71, 1);
	private static final UUID NONMEMBER = new UUID(71, 2);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void localMainHandRoutesExistingActionsAndNeverFallsThroughToTrade() {
		var data = home();
		var claim = resolve(List.of(new Source(Level.OVERWORLD, data)), Level.OVERWORLD, RESIDENT);
		assertEquals(DESCRIBE, route(claim, false, false, true));
		assertEquals(DESCRIBE, route(claim, false, true, false));
		assertEquals(INVITE, route(claim, true, false, true));
		assertEquals(GIFT, route(claim, true, true, false));
		assertEquals(SILENT, route(claim, true, false, false));
		assertTrue(VillagerInteraction.blocksVanillaTrade(claim));
		for (boolean shift : List.of(false, true)) {
			assertEquals(SILENT, VillagerInteraction.route(claim, Level.OVERWORLD,
				InteractionHand.OFF_HAND, false, true, shift, true, false));
			assertEquals(SILENT, VillagerInteraction.route(claim, Level.OVERWORLD,
				InteractionHand.MAIN_HAND, false, false, shift, true, false));
		}
	}

	@Test
	void remoteIdentityNeverUsesLocalGiftWorkOrInvitationPathsAndDoesNotWriteEitherWorld() {
		var local = new SettlementSavedData();
		var remote = home();
		var sources = List.of(new Source(Level.OVERWORLD, local), new Source(Level.NETHER, remote));
		var before = snapshots(sources);
		sources.forEach(source -> source.data().setDirty(false));
		var claim = resolve(sources, Level.OVERWORLD, RESIDENT);
		for (boolean shift : List.of(false, true)) {
			assertEquals(AWAY, route(claim, shift, true, false));
			assertEquals(AWAY, route(claim, shift, false, true));
			assertEquals(SILENT, VillagerInteraction.route(claim, Level.OVERWORLD,
				InteractionHand.OFF_HAND, false, true, shift, true, false));
		}
		assertTrue(VillagerInteraction.blocksVanillaTrade(claim));
		assertEquals(before, snapshots(sources));
		assertTrue(sources.stream().noneMatch(source -> source.data().isDirty()));
		assertTrue(local.all().isEmpty());
		assertTrue(remote.simulationOf(remote.all().getFirst()).isEmpty());
	}

	@Test
	void absenceIsTheOnlyIdentityThatAllowsTradeAndVanillaEggExceptionKeepsBothHands() {
		var local = home();
		var source = new Source(Level.OVERWORLD, local);
		var none = resolve(List.of(source), Level.OVERWORLD, NONMEMBER);
		var unique = resolve(List.of(source), Level.OVERWORLD, RESIDENT);
		var unavailable = resolve(List.of(), Level.OVERWORLD, RESIDENT);
		assertFalse(VillagerInteraction.blocksVanillaTrade(none));
		for (InteractionHand hand : InteractionHand.values()) {
			for (boolean eligible : List.of(false, true)) {
				assertEquals(PASS, VillagerInteraction.route(none, Level.OVERWORLD, hand,
					false, eligible, true, true, false));
				for (Resolution identity : List.of(none, unique, unavailable)) {
					assertEquals(PASS, VillagerInteraction.route(identity, Level.OVERWORLD, hand,
						true, eligible, true, false, false));
				}
			}
		}
		// The pre-Mob callback must be inert even before examining an item, entity or world.
		assertEquals(InteractionResult.PASS,
			VillagerInteraction.onUseEntity(null, null, InteractionHand.MAIN_HAND, null, null));
		assertEquals(InteractionResult.PASS,
			VillagerInteraction.onUseEntity(null, null, InteractionHand.OFF_HAND, null, null));
	}

	@Test
	void orphanConflictAndUnavailableAreDistinctFailClosedReadOnlyRoutes() {
		CompoundTag orphanTag = new SettlementSavedData().save(new CompoundTag(), null);
		orphanTag.getList("Members", ListTag.TAG_COMPOUND)
			.add(new SettlementMember(RESIDENT, new UUID(71, 404), 7).save());
		var orphan = SettlementSavedData.load(orphanTag, null);
		var local = home();
		var orphanSources = List.of(new Source(Level.OVERWORLD, orphan));
		var conflictSources = List.of(new Source(Level.OVERWORLD, local), new Source(Level.NETHER, orphan));
		var before = snapshots(conflictSources);
		conflictSources.forEach(source -> source.data().setDirty(false));
		var identities = List.of(resolve(orphanSources, Level.OVERWORLD, RESIDENT),
			resolve(conflictSources, Level.OVERWORLD, RESIDENT),
			resolve(List.of(new Source(Level.NETHER, orphan)), Level.OVERWORLD, RESIDENT));
		var routes = List.of(ORPHAN, CONFLICT, UNAVAILABLE);
		for (int i = 0; i < identities.size(); i++) {
			Resolution identity = identities.get(i);
			assertTrue(VillagerInteraction.blocksVanillaTrade(identity));
			assertEquals(routes.get(i), route(identity, true, true, false));
			assertEquals(routes.get(i), route(identity, true, false, true));
			assertEquals(routes.get(i), route(identity, false, false, true));
			assertEquals(SILENT, VillagerInteraction.route(identity, Level.OVERWORLD,
				InteractionHand.OFF_HAND, false, true, false, false, true));
		}
		assertEquals(before, snapshots(conflictSources));
		assertTrue(conflictSources.stream().noneMatch(source -> source.data().isDirty()));
	}

	@Test
	void remoteCoordinatesStagesAndSimulationFlagsCannotReenableTradeOrAllocateDuringRead() {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(new BlockPos(1_000_000, 64, -1_000_000), "Distant home", 0);
		data.registerMember(settlement, RESIDENT, 0);
		var sources = List.of(new Source(Level.OVERWORLD, data));
		for (SettlementStage stage : SettlementStage.values()) {
			data.setStage(settlement, stage);
			var before = snapshots(sources);
			assertEquals(DESCRIBE, route(resolve(sources, Level.OVERWORLD, RESIDENT), false, false, true));
			assertTrue(VillagerInteraction.blocksVanillaTrade(resolve(sources, Level.OVERWORLD, RESIDENT)));
			assertEquals(before, snapshots(sources));
			assertTrue(data.simulationOf(settlement).isEmpty());
		}
		var state = data.getOrCreateSimulation(settlement);
		for (int flags = 0; flags < 32; flags++) {
			state.setSurvivalEnabled((flags & 1) != 0, 0);
			state.setProductionEnabled((flags & 2) != 0);
			state.setSkillsEnabled((flags & 4) != 0);
			state.setNormsEnabled((flags & 8) != 0);
			state.setGrowthEnabled((flags & 16) != 0);
			var before = snapshots(sources);
			data.setDirty(false);
			assertEquals(DESCRIBE, route(resolve(sources, Level.OVERWORLD, RESIDENT), false, false, true));
			assertTrue(VillagerInteraction.blocksVanillaTrade(resolve(sources, Level.OVERWORLD, RESIDENT)));
			assertEquals(before, snapshots(sources));
			assertFalse(data.isDirty());
		}
	}

	@Test
	void actualComponentsMatchBothLanguageFilesAndUseHomeAndCurrentDimensions() throws Exception {
		var claim = resolve(List.of(new Source(Level.NETHER, home())), Level.NETHER, RESIDENT)
			.uniqueClaim().orElseThrow();
		Component name = Component.literal("Resident");
		var away = translation(VillagerInteraction.awayMessage(name, claim, Level.OVERWORLD));
		assertEquals("coopcraft.interaction.membership.away", away.getKey());
		assertArrayEquals(new Object[] { name, claim.settlement().name(), "minecraft:the_nether", "minecraft:overworld" }, away.getArgs());
		var orphan = translation(VillagerInteraction.diagnostic(ORPHAN, name));
		var conflict = translation(VillagerInteraction.diagnostic(CONFLICT, name));
		var unavailable = translation(VillagerInteraction.diagnostic(UNAVAILABLE, name));
		assertEquals("coopcraft.interaction.membership.orphan", orphan.getKey());
		assertEquals("coopcraft.interaction.membership.conflict", conflict.getKey());
		assertEquals("coopcraft.interaction.membership.unavailable", unavailable.getKey());
		for (var component : List.of(orphan, conflict, unavailable)) assertArrayEquals(new Object[] { name }, component.getArgs());
		for (String language : List.of("zh_cn", "en_us")) {
			try (var input = getClass().getResourceAsStream("/assets/coopcraft/lang/" + language + ".json")) {
				assertNotNull(input);
				var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
				for (var component : List.of(away, orphan, conflict, unavailable)) {
					String template = json.get(component.getKey()).getAsString();
					assertFalse(template.isBlank());
					assertEquals(component.getArgs().length, template.split("%s", -1).length - 1);
				}
			}
		}
	}

	@Test
	void commonMixinConfigurationIncludesBothGuardsExactlyOnce() throws Exception {
		try (var input = getClass().getResourceAsStream("/coopcraft.mixins.json")) {
			assertNotNull(input);
			var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
			assertEquals("com.thysummer.coopcraft.mixin", json.get("package").getAsString());
			var names = new java.util.ArrayList<String>();
			json.getAsJsonArray("mixins").forEach(value -> names.add(value.getAsString()));
			assertEquals(1, java.util.Collections.frequency(names, "VillagerInteractionMixin"));
			assertEquals(1, java.util.Collections.frequency(names, "MerchantMenuMixin"));
		}
	}

	private static VillagerInteraction.Route route(Resolution identity, boolean shift, boolean food, boolean empty) {
		return VillagerInteraction.route(identity, Level.OVERWORLD, InteractionHand.MAIN_HAND,
			false, true, shift, food, empty);
	}

	private static SettlementSavedData home() {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "Home", 0);
		data.registerMember(settlement, RESIDENT, 0);
		return data;
	}

	private static List<CompoundTag> snapshots(List<Source> sources) {
		return sources.stream().map(source -> source.data().save(new CompoundTag(), null)).toList();
	}

	private static TranslatableContents translation(Component component) {
		return assertInstanceOf(TranslatableContents.class, component.getContents());
	}
}
