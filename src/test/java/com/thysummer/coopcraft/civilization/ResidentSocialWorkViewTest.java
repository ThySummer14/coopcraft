package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.google.gson.JsonParser;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.net.ResidentDialogPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResidentSocialWorkViewTest {
	private static final UUID ID = new UUID(41, 1);
	private static final String PREFIX = "coopcraft.resident_dialog.";
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void appliedFactorAndCurrentSpendingStayReadOnlyAcrossTwoReloads() {
		Fixture f = fixture();
		f.person().restoreSocialState(4, 2, 1);
		for (int reload = 0; reload < 3; reload++) {
			var before = save(f); f.data.setDirty(false);
			var lines = view(f, 0);
			assertEquals(1, lines.size()); assertEquals(PREFIX + "social_work_cost", key(lines.getFirst()));
			assertArrayEquals(new Object[] {0L, 2, 1, 5}, args(lines.getFirst()));
			assertEquals(24, SettlementSimulationService.constructionWorkDuration(f.data, f.settlement, ID, 20));
			assertEquals(before, save(f)); assertFalse(f.data.isDirty());
			assertThrows(UnsupportedOperationException.class, () -> lines.add(Component.empty()));
			f = reload(f); assertEquals(lines, view(f, 0)); f.state.world().assertConservation();
		}
	}

	@Test void exhaustedHoursDescribeGatherStopWithoutClaimingAllWorkStops() {
		Fixture f = fixture(); f.person().restoreSocialState(0, 3, 6);
		var before = save(f); f.data.setDirty(false);
		assertEquals(PREFIX + "social_work_exhausted", key(view(f, 0).getFirst()));
		assertArrayEquals(new Object[] {0L, 3, 6, 1}, args(view(f, 0).getFirst()));
		assertFalse(SettlementSimulationService.hasGatherTime(f.data, f.settlement, ID));
		assertEquals(120, SettlementSimulationService.constructionWorkDuration(f.data, f.settlement, ID, 20));
		assertEquals(before, save(f)); assertFalse(f.data.isDirty());
	}

	@Test void pendingDaysAndDisabledOrMissingRecordsDoNotInventAppliedCosts() {
		Fixture f = fixture(); f.person().restoreSocialState(0, 2, 4);
		var before = save(f); f.data.setDirty(false);
		for (long day : new long[] {-1, 1}) {
			assertEquals(PREFIX + "social_work_day_pending", key(view(f, day).getFirst()));
			assertEquals(0, args(view(f, day).getFirst()).length);
		}
		assertEquals(before, save(f)); assertFalse(f.data.isDirty());
		f.state.setNormsEnabled(false); assertTrue(view(f, 0).isEmpty());
		f.state.setNormsEnabled(true); f.state.setSurvivalEnabled(false, 0); assertTrue(view(f, 0).isEmpty());
		f.state.setSurvivalEnabled(true, 0);
		assertTrue(ResidentMeetingView.socialWorkCost(f.data, f.settlement, new UUID(41, 2), 0).isEmpty());
		var copy = new Settlement(f.settlement.id(), f.settlement.center(), f.settlement.name(), f.settlement.foundedDay());
		assertTrue(ResidentMeetingView.socialWorkCost(f.data, copy, ID, 0).isEmpty());
		f.data.removeMember(ID); assertTrue(view(f, 0).isEmpty());
		var blank = new SettlementSavedData(); var settlement = blank.getOrCreate(BlockPos.ZERO, "No mapping", 0);
		blank.registerMember(settlement, ID, 0); blank.setDirty(false); var saved = blank.save(new CompoundTag(), null);
		assertTrue(ResidentMeetingView.socialWorkCost(blank, settlement, ID, 0).isEmpty());
		assertEquals(saved, blank.save(new CompoundTag(), null)); assertFalse(blank.isDirty());
	}

	@Test void fullWindowStillFitsExactlyThirtyTwoLinesAndBothLanguages() throws Exception {
		Fixture f = fixture();
		assertTrue(SettlementSimulationService.closeObservedDay(f.data, f.settlement, f.state, 1, Map.of(ID, f.person())));
		f.state.world().importExternalBatch(ResourceType.FISH, 2, f.state.campOwner());
		assertEquals(40, SettlementSimulationService.eat(f.data, f.settlement, ID));
		var rations = ResidentMeetingView.rations(f.data, f.settlement, ID, Component.literal("Ada"), 2);
		assertEquals(9, rations.size());
		var full = new ArrayList<Component>();
		for (int i = 0; i < 22; i++) full.add(Component.literal("Existing receipt and inspection line " + i));
		full.addAll(view(f, 2)); full.addAll(rations);
		assertEquals(32, full.size()); assertEquals(ResidentDialogPayload.MAX_LINES, full.size());
		var snapshot = new ResidentDialogPayload.Snapshot(Component.literal("Ada"), "minecraft:overworld", full,
			true, false, false, false, Component.empty(), Component.empty(), Component.empty());
		var packet = new ResidentDialogPayload(ResidentDialogPayload.Kind.UPDATE, new UUID(41, 9), 1, 1, snapshot, Component.empty());
		var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		try {
			ResidentDialogPayload.STREAM_CODEC.encode(buffer, packet);
			assertEquals(packet, ResidentDialogPayload.STREAM_CODEC.decode(buffer)); assertFalse(buffer.isReadable());
		} finally { buffer.release(); }
		var translated = new ArrayList<>(view(f, 2));
		translated.addAll(view(f, 1)); f.person().restoreSocialState(0, 0, 6); translated.addAll(view(f, 1));
		translated.add(Component.translatable(PREFIX + "gathering_cost"));
		for (String locale : List.of("zh_cn", "en_us")) {
			try (var stream = getClass().getResourceAsStream("/assets/coopcraft/lang/" + locale + ".json")) {
				assertNotNull(stream);
				var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
				for (Component line : translated) {
					assertTrue(json.has(key(line)), key(line));
					assertEquals(args(line).length, json.get(key(line)).getAsString().split("%s", -1).length - 1);
				}
			}
		}
		f.state.world().assertConservation();
	}

	private static Fixture fixture() {
		var data = new SettlementSavedData(); var settlement = data.getOrCreate(BlockPos.ZERO, "Social work", 0);
		data.registerMember(settlement, ID, 0); var state = data.getOrCreateSimulation(settlement);
		state.mapResident(ID, false, 0); state.setSurvivalEnabled(true, 0); state.setNormsEnabled(true);
		return new Fixture(data, settlement, state);
	}
	private static List<Component> view(Fixture f, long day) { return ResidentMeetingView.socialWorkCost(f.data, f.settlement, ID, day); }
	private static String key(Component c) { return ((TranslatableContents) c.getContents()).getKey(); }
	private static Object[] args(Component c) { return ((TranslatableContents) c.getContents()).getArgs(); }
	private static CompoundTag save(Fixture f) { return f.data.save(new CompoundTag(), null); }
	private static Fixture reload(Fixture f) {
		var data = SettlementSavedData.load(save(f), null); var settlement = data.find(BlockPos.ZERO).orElseThrow();
		return new Fixture(data, settlement, data.simulationOf(settlement).orElseThrow());
	}
	private record Fixture(SettlementSavedData data, Settlement settlement, SettlementSimulationState state) {
		Person person() { return state.world().person(state.residentId(ID).orElseThrow()); }
	}
}
