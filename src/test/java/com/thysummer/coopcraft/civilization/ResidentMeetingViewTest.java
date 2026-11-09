package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.MeetingTestEvents;
import com.thysummer.coopcraft.civilization.simulation.Norm;
import com.thysummer.coopcraft.civilization.simulation.NormProposal;
import com.thysummer.coopcraft.civilization.simulation.NormSystem;
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

class ResidentMeetingViewTest {
    @BeforeAll static void bootstrapMinecraft() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void fullActualPreviewAndResidentRationsFitTheProtocolWithoutChangingAnySavedState() {
        Fixture f = fixture(3);
        seed(f, "proposal-food", 1, 2, 3);
        var before = save(f);
        f.data.setDirty(false);
        var result = result(f, false);
        for (int i = 0; i < 5; i++) {
            var view = ResidentMeetingView.present(result);
            assertTrue(view.canHold());
            var lines = ResidentMeetingView.rations(f.data, f.settlement, id(1), Component.literal("Ada"), 0);
            assertEquals(3, lines.size());
            var snapshot = new ResidentDialogPayload.Snapshot(Component.literal("Ada"), "minecraft:overworld", lines,
                true, false, true, view.canHold(), Component.empty(), Component.empty(), view.hint());
            var packet = new ResidentDialogPayload(ResidentDialogPayload.Kind.UPDATE, id(9), 1, 1, snapshot, Component.empty());
            var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                ResidentDialogPayload.STREAM_CODEC.encode(buf, packet);
                assertEquals(packet, ResidentDialogPayload.STREAM_CODEC.decode(buf));
                assertFalse(buf.isReadable());
            } finally { buf.release(); }
            assertEquals(before, save(f));
            assertFalse(f.data.isDirty());
        }
        f.state.world().assertConservation();
    }

    @Test void meetingAgendaIsTheActuallySelectedProposalRatherThanTheResidentsOldestInquiry() {
        Fixture f = fixture(3);
        seed(f, "proposal-a", 1);
        seed(f, "proposal-z", 2, 3);
        var inquiry = VillagerMeetingInquiry.inspect(f.data, f.settlement, id(2), 0);
        assertEquals("proposal-a", args(inquiry.getFirst())[0]);
        var result = result(f, false);
        assertEquals("proposal-z", result.discussion().proposalId());
        var view = ResidentMeetingView.present(result);
        assertTrue(view.canHold());
        assertEquals("proposal-z", args(find(view.hint(), "coopcraft.command.settlement.meeting_rule_agenda"))[0]);
        assertNotNull(find(view.hint(), "coopcraft.resident_dialog.gathering_cost"));
        assertNotNull(find(view.hint(), "coopcraft.resident_dialog.gathering_title"));
    }

    @Test void oversizedOrAmbiguousReviewDisablesTheActionInsteadOfTruncatingItsMeaning() {
        Fixture f = fixture(2); seed(f, "proposal-food", 1, 2);
        var original = result(f, false);
        var huge = new SettlementMeetings.Result(original.status(), original.coreDay(), original.lastMeetingDay(),
            "x".repeat(ResidentDialogPayload.MAX_COMPONENT_BYTES + 1), original.discussion(), original.feedback());
        var view = ResidentMeetingView.present(huge);
        assertFalse(view.canHold());
        assertEquals("coopcraft.resident_dialog.gathering_unreviewable", key(view.hint()));
        var noAgenda = new SettlementMeetings.Result("ready", 0, Long.MIN_VALUE, "here", original.discussion(),
            new SettlementMeetingFeedback.Snapshot(java.util.Optional.empty(), original.feedback().effects(), List.of()));
        assertFalse(ResidentMeetingView.present(noAgenda).canHold());
        var invalidId = new NormSystem.MeetingResult("ready", "bad\nidentity", 2, 2, 0, "deliberating");
        var ambiguous = new SettlementMeetings.Result("ready", 0, Long.MIN_VALUE, "here", invalidId, original.feedback());
        assertFalse(ResidentMeetingView.present(ambiguous).canHold());
    }

    @Test void frozenReceiptSurvivesRefreshAndLaterRoundWhileActualRationsFollowAdoptedRule() {
        Fixture f = fixture(2); seed(f, "proposal-food", 1, 2);
        var stock = f.state.world().inventoryTotals();
        var first = result(f, true);
        var receipt = ResidentMeetingView.receipt(first);
        assertTrue(receipt.size() <= 12, "Keep the full-window ration feedback budget intact");
        assertTrue(VillagerMeetingInquiry.inspect(f.data, f.settlement, id(1), 0).size() <= 4);
        var frozen = receipt.stream().map(Component::copy).toList();
        assertEquals("held", first.status());
        assertEquals(1, first.discussion().round());
        assertEquals(1, f.person(1).socialHours());
        assertEquals(stock, f.state.world().inventoryTotals(), "A meeting cannot consume or distribute food");
        assertTrue(ResidentMeetingView.receipt(result(f, false)).isEmpty());
        f.state.norms().startDay(f.people()); f.state.world().advanceDay(); f.state.markSurvivalDay(1);
        assertEquals(3, SettlementRations.inspect(f.data, f.settlement).getFirst().dailyUnitCap());
        var second = result(f, true);
        assertTrue(ResidentMeetingView.receipt(second).size() <= 12);
        assertEquals("adopted", second.discussion().outcome());
        assertEquals(2, SettlementRations.inspect(f.data, f.settlement).getFirst().dailyUnitCap());
        assertEquals(stock, f.state.world().inventoryTotals());
        SettlementSimulationService.eat(f.data, f.settlement, id(1));
        var ration = SettlementRations.inspect(f.data, f.settlement).getFirst();
        assertEquals(2, ration.allocatedUnits()); assertEquals(0, ration.remainingUnits());
        assertEquals(40, ration.foodNutritionReceived().orElseThrow()); assertEquals(60, ration.hunger());
        assertEquals(frozen, receipt, "An old receipt is not recomputed from the next round or current rules");
        assertEquals(first.discussion().deliberationEventId(), args(find(receipt,
            "coopcraft.command.settlement.meeting_receipt_reference"))[0]);
        var currentLines = ResidentMeetingView.rations(f.data, f.settlement, id(1), Component.literal("Ada"), 1);
        for (int reload = 0; reload < 2; reload++) {
            var before = save(f);
            var loaded = SettlementSavedData.load(before, null);
            var settlement = loaded.find(BlockPos.ZERO).orElseThrow();
            f = new Fixture(loaded, settlement, loaded.simulationOf(settlement).orElseThrow());
            assertEquals(before, save(f));
            assertEquals(currentLines, ResidentMeetingView.rations(f.data, f.settlement, id(1), Component.literal("Ada"), 1));
            assertEquals("already_held", result(f, false).status());
            f.state.world().assertConservation();
        }
    }

    @Test void missingHistoryAndPendingDayStayExplicitAndReadsNeverCreateMissingMappings() {
        Fixture f = fixture(2);
        f.state.recordFoodAllocation(f.person(1).id(), 2);
        var before = save(f); f.data.setDirty(false);
        var lines = ResidentMeetingView.rations(f.data, f.settlement, id(1), Component.literal("Ada"), 1);
        assertNotNull(find(lines, "coopcraft.command.settlement.rations_unknown"));
        assertNotNull(find(lines, "coopcraft.command.settlement.meeting_day_pending"));
        assertEquals(before, save(f)); assertFalse(f.data.isDirty());
        var empty = new SettlementSavedData(); var camp = empty.getOrCreate(BlockPos.ZERO, "No simulation", 0);
        empty.registerMember(camp, id(1), 0); empty.setDirty(false);
        assertTrue(ResidentMeetingView.rations(empty, camp, id(1), Component.empty(), 0).isEmpty());
        assertTrue(empty.simulationOf(camp).isEmpty()); assertFalse(empty.isDirty());
        f.data.registerMember(f.settlement, id(3), 0);
        assertTrue(ResidentMeetingView.rations(f.data, f.settlement, id(3), Component.empty(), 0).isEmpty());
        assertTrue(f.state.residentId(id(3)).isEmpty());
        f.state.setSurvivalEnabled(false, 0);
        assertTrue(ResidentMeetingView.rations(f.data, f.settlement, id(1), Component.empty(), 0).isEmpty());
    }

    @Test void everyActualViewHasBothTranslationsWithMatchingArguments() throws Exception {
        Fixture f = fixture(2); seed(f, "proposal-food", 1, 2);
        List<Component> all = new ArrayList<>();
        all.add(ResidentMeetingView.present(result(f, false)).hint());
        all.addAll(ResidentMeetingView.receipt(result(f, true)));
        all.addAll(ResidentMeetingView.rations(f.data, f.settlement, id(1), Component.literal("Ada"), 1));
        for (String suffix : List.of("gathering_unreviewable", "hold_meeting", "hold_meeting_hint", "current_information"))
            all.add(Component.translatable("coopcraft.resident_dialog." + suffix));
        all.add(Component.translatable("coopcraft.command.settlement.meeting_review_changed"));
        for (String locale : List.of("zh_cn", "en_us")) {
            try (var stream = getClass().getResourceAsStream("/assets/coopcraft/lang/" + locale + ".json")) {
                assertNotNull(stream);
                var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                for (Component component : all) translated(json, component);
            }
        }
    }

    private static void translated(JsonObject json, Component component) {
        if (component.getContents() instanceof TranslatableContents t) {
            assertTrue(json.has(t.getKey()), t.getKey());
            assertEquals(t.getArgs().length, json.get(t.getKey()).getAsString().split("%s", -1).length - 1, t.getKey());
            for (Object arg : t.getArgs()) if (arg instanceof Component child) translated(json, child);
        }
        component.getSiblings().forEach(child -> translated(json, child));
    }
    private static Component find(List<Component> lines, String key) {
        for (Component line : lines) { var found = find(line, key); if (found != null) return found; }
        return null;
    }
    private static Component find(Component component, String key) {
        if (component.getContents() instanceof TranslatableContents t) {
            if (t.getKey().equals(key)) return component;
            for (Object arg : t.getArgs()) if (arg instanceof Component child) { var found = find(child, key); if (found != null) return found; }
        }
        return find(component.getSiblings(), key);
    }
    private static String key(Component c) { return ((TranslatableContents)c.getContents()).getKey(); }
    private static Object[] args(Component c) { return ((TranslatableContents)c.getContents()).getArgs(); }
    private static Fixture fixture(int count) {
        var data = new SettlementSavedData(); var settlement = data.getOrCreate(BlockPos.ZERO, "Window meeting", 0);
        var state = data.getOrCreateSimulation(settlement);
        for (int i = 1; i <= count; i++) {
            data.registerMember(settlement, id(i), 0); state.mapResident(id(i), false, 0);
            state.world().person(state.residentId(id(i)).orElseThrow()).observeNorm("neighbor-requests");
        }
        state.setSurvivalEnabled(true, 0); state.setNormsEnabled(true);
        state.world().importExternalBatch(ResourceType.FISH, 30, state.campOwner());
        return new Fixture(data, settlement, state);
    }
    private static void seed(Fixture f, String id, Integer... members) {
        long request = f.state.world().recordShareRequest(f.person(1).id(), f.camp(), 40);
        long origin = f.state.world().recordShareRefusal(request, f.person(1).id(), f.camp(), 20);
        Map<String,NormProposal.Position> positions = new LinkedHashMap<>();
        for (int i : members) positions.put(f.person(i).id(), NormProposal.Position.SUPPORT);
        f.state.world().registerProposal(NormProposal.restore(id, f.camp(), Norm.NEED_FIRST_COMMON_STORE,
            f.person(1).id(), f.state.world().day(), origin, 2, 0, 0, NormProposal.Status.OPEN, positions));
        MeetingTestEvents.record(f.state.world(), EventType.NORM_PROPOSED, List.of(f.person(1).id()), Map.of("proposalId", id), List.of(origin));
    }
    private static SettlementMeetings.Result result(Fixture f, boolean hold) {
        var discussion = hold ? f.state.norms().holdCommonStoreMeeting(f.people(), f.people(), f.camp(), "minecraft:overworld 0,64,0")
            : f.state.norms().inspectCommonStoreMeeting(f.people(), f.people(), f.camp());
        return new SettlementMeetings.Result(discussion.status(), f.state.world().day(), f.state.norms().lastMeetingDay(f.camp()),
            "minecraft:overworld 0,64,0", discussion, SettlementMeetingFeedback.inspect(f.state, discussion));
    }
    private static CompoundTag save(Fixture f) { return f.data.save(new CompoundTag(), null); }
    private static UUID id(int n) { return new UUID(35, n); }
    private record Fixture(SettlementSavedData data, Settlement settlement, SettlementSimulationState state) {
        String camp() { return state.campOwner().id(); }
        Person person(int n) { return state.world().person(state.residentId(id(n)).orElseThrow()); }
        List<Person> people() { return state.world().people().stream().toList(); }
    }
}
