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

class ResidentFoodDayViewTest {
    private static final UUID ID = new UUID(37, 1);
    private static final String PREFIX = "coopcraft.resident_dialog.";
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void observedHistoryAndCurrentGrievanceStayReadOnlyAcrossRepeatedLoads() {
        Fixture f = fixture(); close(f, 1); close(f, 2);
        assertEquals(3, f.person().grievance());
        for (int reload = 0; reload < 3; reload++) {
            var before = save(f); f.data.setDirty(false);
            var lines = view(f);
            assertEquals(2, lines.size());
            assertArrayEquals(new Object[] {1L, 2, SettlementSimulationService.OBSERVED_HUNGER_THRESHOLD}, args(lines.getFirst()));
            assertArrayEquals(new Object[] {3}, args(lines.getLast()));
            assertEquals(before, save(f)); assertFalse(f.data.isDirty());
            var data = SettlementSavedData.load(before, null); var settlement = data.find(BlockPos.ZERO).orElseThrow();
            f = new Fixture(data, settlement, data.simulationOf(settlement).orElseThrow());
            assertEquals(lines, view(f));
            f.state.world().assertConservation();
        }
    }

    @Test void laterFoodAndAidChangeCurrentStateWithoutRewritingRecordedDay() {
        Fixture f = fixture(); close(f, 1); close(f, 2);
        var historical = view(f).getFirst();
        f.state.world().importExternalBatch(ResourceType.FISH, 5, f.state.campOwner());
        assertEquals(60, SettlementSimulationService.eat(f.data, f.settlement, ID));
        f.person().recordAidFrom("minecraft-player:test");
        assertEquals(historical, view(f).getFirst());
        assertArrayEquals(new Object[] {2}, args(view(f).getLast()));
        assertEquals(40, f.person().hunger());
        close(f, 3);
        assertArrayEquals(new Object[] {2L, 0, SettlementSimulationService.OBSERVED_HUNGER_THRESHOLD}, args(view(f).getFirst()));
        assertEquals(75, f.person().hunger(), "The record describes closing hunger, before next-day metabolism");
        f.state.world().assertConservation();
    }

    @Test void unknownHistoryAndInvalidMembershipDoNotInventObservedDays() {
        Fixture f = fixture();
        var before = save(f); f.data.setDirty(false);
        assertTrue(view(f).isEmpty()); assertEquals(before, save(f)); assertFalse(f.data.isDirty());
        close(f, 1);
        var copied = new Settlement(f.settlement.id(), f.settlement.center(), f.settlement.name(), f.settlement.foundedDay());
        assertTrue(ResidentMeetingView.foodDayHistory(f.data, copied, ID).isEmpty());
        assertTrue(ResidentMeetingView.foodDayHistory(f.data, f.settlement, new UUID(37, 2)).isEmpty());
        f.state.setSurvivalEnabled(false, 1); assertTrue(view(f).isEmpty());
        f.state.setSurvivalEnabled(true, 1); assertEquals(2, view(f).size());
        f.data.removeMember(ID); assertTrue(view(f).isEmpty());
    }

    @Test void foodHistoryAndRefusalFitTheExistingFullWindowBudgetAndBothLanguages() throws Exception {
        Fixture f = fixture(); close(f, 1); close(f, 2);
        f.state.world().importExternalBatch(ResourceType.FISH, 5, f.state.campOwner());
        assertEquals(60, SettlementSimulationService.eat(f.data, f.settlement, ID));
        assertEquals(0, SettlementSimulationService.eat(f.data, f.settlement, ID));
        var rations = ResidentMeetingView.rations(f.data, f.settlement, ID, Component.literal("Ada"), 3);
        assertEquals(9, rations.size());
        var all = new ArrayList<Component>();
        for (int i = 0; i < 22; i++) all.add(Component.literal("Existing meeting receipt / resident information " + i));
        all.addAll(rations); assertEquals(31, all.size()); assertTrue(all.size() <= ResidentDialogPayload.MAX_LINES);
        var snapshot = new ResidentDialogPayload.Snapshot(Component.literal("Ada"), "minecraft:overworld", all,
            true, false, false, false, Component.empty(), Component.empty(), Component.empty());
        var packet = new ResidentDialogPayload(ResidentDialogPayload.Kind.UPDATE, new UUID(37, 9), 1, 1, snapshot, Component.empty());
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            ResidentDialogPayload.STREAM_CODEC.encode(buffer, packet);
            assertEquals(packet, ResidentDialogPayload.STREAM_CODEC.decode(buffer)); assertFalse(buffer.isReadable());
        } finally { buffer.release(); }
        for (String locale : List.of("zh_cn", "en_us")) {
            try (var stream = getClass().getResourceAsStream("/assets/coopcraft/lang/" + locale + ".json")) {
                assertNotNull(stream);
                var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                for (Component line : view(f)) {
                    var t = (TranslatableContents) line.getContents();
                    assertTrue(t.getKey().startsWith(PREFIX)); assertTrue(json.has(t.getKey()));
                    assertEquals(t.getArgs().length, json.get(t.getKey()).getAsString().split("%s", -1).length - 1);
                }
            }
        }
    }

    private static Fixture fixture() {
        var data = new SettlementSavedData(); var settlement = data.getOrCreate(BlockPos.ZERO, "Observed food", 0);
        data.registerMember(settlement, ID, 0);
        var state = data.getOrCreateSimulation(settlement); state.mapResident(ID, false, 0);
        state.setSurvivalEnabled(true, 0); state.setNormsEnabled(true);
        return new Fixture(data, settlement, state);
    }
    private static void close(Fixture f, long day) {
        assertTrue(SettlementSimulationService.closeObservedDay(f.data, f.settlement, f.state, day, Map.of(ID, f.person())));
    }
    private static List<Component> view(Fixture f) { return ResidentMeetingView.foodDayHistory(f.data, f.settlement, ID); }
    private static Object[] args(Component c) { return ((TranslatableContents) c.getContents()).getArgs(); }
    private static CompoundTag save(Fixture f) { return f.data.save(new CompoundTag(), null); }
    private record Fixture(SettlementSavedData data, Settlement settlement, SettlementSimulationState state) {
        Person person() { return state.world().person(state.residentId(ID).orElseThrow()); }
    }
}
