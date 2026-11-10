package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import com.google.gson.JsonParser;
import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.Norm;
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

class ResidentFoodAccessViewTest {
    private static final UUID RESIDENT = new UUID(36, 1);
    private static final String PREFIX = "coopcraft.resident_dialog.";

    @BeforeAll static void bootstrapMinecraft() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void frozenRefusalAndCurrentRetryStateRemainSeparateAndReadOnlyAcrossReloads() {
        Fixture f = fixture(20);
        var refusal = f.state.world().ledger().events().getLast();
        assertEquals(EventType.SHARE_REFUSED, refusal.type());
        assertNotNull(find(lines(f, 0), "quota_retry_cap_not_raised"));
        cap(f, 4);
        f.state.world().importExternalBatch(ResourceType.FISH, 2, f.state.campOwner());
        f.state.world().person(f.person()).setHunger(20);
        for (int reload = 0; reload < 3; reload++) {
            var before = save(f); f.data.setDirty(false);
            var lines = lines(f, 0);
            assertArrayEquals(new Object[] {0L, refusal.eventId(), 3, 3}, args(find(lines, "quota_refusal")));
            assertArrayEquals(new Object[] {40, 340L}, args(find(lines, "quota_refusal_context")));
            assertNotNull(find(lines, "quota_retry_ready"));
            assertEquals(6, lines.size());
            assertEquals(before, save(f)); assertFalse(f.data.isDirty());
            assertEquals(24, before.getInt("DataVersion"));
            var loaded = SettlementSavedData.load(before, null);
            var settlement = loaded.find(BlockPos.ZERO).orElseThrow();
            f = new Fixture(loaded, settlement, loaded.simulationOf(settlement).orElseThrow());
            assertEquals(lines, lines(f, 0));
            f.state.world().assertConservation();
        }
    }

    @Test void emptyStockReplenishmentAndActualMealShowDistinctConsequencesWithoutRewritingRefusal() {
        Fixture f = fixture(3); cap(f, 4);
        var original = find(lines(f, 0), "quota_refusal_context");
        assertArrayEquals(new Object[] {40, 0L}, args(original));
        assertNotNull(find(lines(f, 0), "quota_retry_no_food"));
        f.state.world().importExternalBatch(ResourceType.FISH, 1, f.state.campOwner());
        assertNotNull(find(lines(f, 0), "quota_retry_ready"));
        assertEquals(20, SettlementSimulationService.eat(f.data, f.settlement, RESIDENT));
        assertNotNull(find(lines(f, 0), "quota_retry_allowance_spent"));
        assertEquals(original, find(lines(f, 0), "quota_refusal_context"));
        assertEquals(4, f.state.allocatedFoodUnits(f.person()));
        assertEquals(20, f.state.world().person(f.person()).hunger());
        assertEquals(1, f.state.world().ledger().events().stream().filter(e -> e.type() == EventType.SHARE_REFUSED).count());
        f.state.world().assertConservation();
    }

    @Test void pendingDayDisabledNormsAndSatisfiedNeedsDoNotPromiseAnotherTrip() {
        Fixture f = fixture(20); cap(f, 4);
        assertNotNull(find(lines(f, 1), "quota_retry_day_pending"));
        assertNotNull(find(lines(f, -1), "quota_retry_day_pending"));
        f.state.setNormsEnabled(false);
        assertNotNull(find(lines(f, 0), "quota_retry_rules_disabled"));
        f.state.setNormsEnabled(true);
        f.state.world().person(f.person()).setHunger(5);
        assertNotNull(find(lines(f, 0), "quota_retry_satisfied"));
        f.state.world().advanceDay();
        assertNull(find(lines(f, 0), "quota_refusal"), "Yesterday's refusal cannot appear as today's");
    }

    @Test void incompleteLegacyRefusalIsNotBackfilledFromCurrentStockOrAllowance() {
        var data = new SettlementSavedData(); var settlement = data.getOrCreate(BlockPos.ZERO, "Legacy", 0);
        data.registerMember(settlement, RESIDENT, 0);
        var state = data.getOrCreateSimulation(settlement); state.mapResident(RESIDENT, false, 0);
        state.setSurvivalEnabled(true, 0); state.setNormsEnabled(true);
        String person = state.residentId(RESIDENT).orElseThrow();
        long request = state.world().recordShareRequest(person, state.campOwner().id(), 35);
        state.world().recordShareRefusal(request, person, state.campOwner().id(), 0);
        Fixture f = new Fixture(data, settlement, state); cap(f, 4);
        var before = save(f); data.setDirty(false);
        assertEquals(3, lines(f, 0).size());
        assertNull(find(lines(f, 0), "quota_refusal"));
        assertEquals(before, save(f)); assertFalse(data.isDirty());
    }

    @Test void allRetryStatesAreTranslatedAndFullWindowBudgetFitsCodec() throws Exception {
        Fixture f = fixture(20); cap(f, 4);
        List<Component> full = new ArrayList<>();
        // Existing receipt has at most 12 lines, resident inspection at most 10.
        // Pending-day rations include the additional warning and all three new lines.
        for (int i = 0; i < 22; i++) full.add(Component.literal("Existing receipt/inspection line " + i));
        full.addAll(lines(f, 1));
        assertEquals(29, full.size());
        assertTrue(full.size() <= ResidentDialogPayload.MAX_LINES);
        var snapshot = new ResidentDialogPayload.Snapshot(Component.literal("Ada"), "minecraft:overworld", full,
            true, true, false, false, Component.empty(), Component.empty(), Component.empty());
        var payload = new ResidentDialogPayload(ResidentDialogPayload.Kind.UPDATE, new UUID(36, 9), 1, 1, snapshot, Component.empty());
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            ResidentDialogPayload.STREAM_CODEC.encode(buffer, payload);
            assertEquals(payload, ResidentDialogPayload.STREAM_CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally { buffer.release(); }
        List<Component> translated = new ArrayList<>(lines(f, 1));
        for (var status : SettlementFoodAccess.Status.values())
            translated.add(Component.translatable(PREFIX + "quota_retry_" + status.name().toLowerCase(Locale.ROOT)));
        for (String locale : List.of("zh_cn", "en_us")) {
            try (var stream = getClass().getResourceAsStream("/assets/coopcraft/lang/" + locale + ".json")) {
                assertNotNull(stream);
                var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                for (Component line : translated) {
                    var t = (TranslatableContents) line.getContents();
                    assertTrue(json.has(t.getKey()), t.getKey());
                    assertEquals(t.getArgs().length, json.get(t.getKey()).getAsString().split("%s", -1).length - 1, t.getKey());
                }
            }
        }
    }

    private static Fixture fixture(int fish) {
        var data = new SettlementSavedData(); var settlement = data.getOrCreate(BlockPos.ZERO, "Food access", 0);
        data.registerMember(settlement, RESIDENT, 0);
        var state = data.getOrCreateSimulation(settlement); state.mapResident(RESIDENT, false, 0);
        state.setSurvivalEnabled(true, 0); state.setNormsEnabled(true);
        state.world().importExternalBatch(ResourceType.FISH, fish, state.campOwner());
        assertEquals(60, SettlementSimulationService.eat(data, settlement, RESIDENT));
        assertEquals(0, SettlementSimulationService.eat(data, settlement, RESIDENT));
        return new Fixture(data, settlement, state);
    }
    private static void cap(Fixture f, int cap) {
        f.state.world().registerNorm(new Norm(Norm.NEED_FIRST_COMMON_STORE, f.state.campOwner().id(), cap,
            Set.of(), Set.of(), Set.of(), 80, Norm.Status.ACTIVE, List.of()));
    }
    private static List<Component> lines(Fixture f, long day) {
        return ResidentMeetingView.rations(f.data, f.settlement, RESIDENT, Component.literal("Ada"), day);
    }
    private static Component find(List<Component> lines, String suffix) {
        return lines.stream().filter(c -> c.getContents() instanceof TranslatableContents t && t.getKey().equals(PREFIX + suffix))
            .findFirst().orElse(null);
    }
    private static Object[] args(Component component) { return ((TranslatableContents) component.getContents()).getArgs(); }
    private static CompoundTag save(Fixture f) { return f.data.save(new CompoundTag(), null); }
    private record Fixture(SettlementSavedData data, Settlement settlement, SettlementSimulationState state) {
        String person() { return state.residentId(RESIDENT).orElseThrow(); }
    }
}
