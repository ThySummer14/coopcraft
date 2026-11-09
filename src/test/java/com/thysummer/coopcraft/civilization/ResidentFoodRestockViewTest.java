package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import com.google.gson.JsonParser;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.net.ResidentDialogPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.thysummer.coopcraft.civilization.SettlementFoodRestockTest.*;

class ResidentFoodRestockViewTest {
	private static final String PREFIX = "coopcraft.resident_dialog.";
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void frozenInterruptionAndCurrentRestockStayDistinctAcrossRepeatedLoads() {
		Fixture f = fixture(2); eat(f);
		var interruption = snapshot(f).evidence();
		assertNotNull(find(lines(f, 0), "meal_retry_no_food"));
		f.state().world().importExternalBatch(ResourceType.FISH, 1, f.state().campOwner());
		for (int i = 0; i < 3; i++) {
			var before = save(f); f.data().setDirty(false);
			var lines = lines(f, 0);
			assertEquals(6, lines.size());
			assertArrayEquals(new Object[] {0L, interruption.eventId(), 2, 3}, args(find(lines, "meal_interrupted")));
			assertArrayEquals(new Object[] {60}, args(find(lines, "meal_interrupted_context")));
			assertNotNull(find(lines, "meal_retry_ready")); assertNull(find(lines, "quota_refusal"));
			assertEquals(before, save(f)); assertFalse(f.data().isDirty());
			f = reload(f); assertEquals(lines, lines(f, 0));
		}
		assertEquals(20, eat(f));
		assertNotNull(find(lines(f, 0), "meal_retry_allocation_changed"));
		assertArrayEquals(new Object[] {60}, args(find(lines(f, 0), "meal_interrupted_context")));
	}

	@Test void genuineQuotaRefusalTakesPrecedenceWithoutAppendingBothEvidenceBlocks() {
		Fixture f = fixture(2); eat(f);
		f.state().world().importExternalBatch(ResourceType.FISH, 1, f.state().campOwner());
		eat(f); eat(f);
		assertTrue(snapshot(f).evidence().eventId() > 0);
		var lines = lines(f, 0);
		assertEquals(6, lines.size()); assertNotNull(find(lines, "quota_refusal"));
		assertNull(find(lines, "meal_interrupted"));
		assertNotNull(find(lines, "quota_retry_cap_not_raised"));
		f.state().world().assertConservation();
	}

	@Test void fullHistoryPendingDayBudgetAndAllRestockTranslationsFitCodec() throws Exception {
		Fixture f = fixture(2);
		assertTrue(SettlementSimulationService.closeObservedDay(f.data(), f.settlement(), f.state(), 1, Map.of(A, f.person())));
		eat(f);
		var rations = lines(f, 2); assertEquals(9, rations.size());
		var full = new ArrayList<Component>();
		for (int i = 0; i < 22; i++) full.add(Component.literal("Existing receipt and inspection line " + i));
		full.addAll(rations); assertEquals(31, full.size()); assertTrue(full.size() <= ResidentDialogPayload.MAX_LINES);
		var snapshot = new ResidentDialogPayload.Snapshot(Component.literal("Ada"), "minecraft:overworld", full,
			true, false, false, false, Component.empty(), Component.empty(), Component.empty());
		var payload = new ResidentDialogPayload(ResidentDialogPayload.Kind.UPDATE, new UUID(39, 99), 1, 1, snapshot, Component.empty());
		var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		try {
			ResidentDialogPayload.STREAM_CODEC.encode(buffer, payload);
			assertEquals(payload, ResidentDialogPayload.STREAM_CODEC.decode(buffer)); assertFalse(buffer.isReadable());
		} finally { buffer.release(); }
		var translated = new ArrayList<Component>(rations);
		for (var status : SettlementFoodRestock.Status.values()) translated.add(Component.translatable(PREFIX + "meal_retry_" + status.name().toLowerCase(Locale.ROOT)));
		for (String locale : List.of("zh_cn", "en_us")) {
			try (var stream = getClass().getResourceAsStream("/assets/coopcraft/lang/" + locale + ".json")) {
				assertNotNull(stream);
				var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
				for (Component line : translated) {
					var contents = (TranslatableContents) line.getContents();
					assertTrue(json.has(contents.getKey()), contents.getKey());
					assertEquals(contents.getArgs().length, json.get(contents.getKey()).getAsString().split("%s", -1).length - 1, contents.getKey());
				}
			}
		}
	}

	private static List<Component> lines(Fixture f, long day) { return ResidentMeetingView.rations(f.data(), f.settlement(), A, Component.literal("Ada"), day); }
	private static Component find(List<Component> lines, String suffix) { return lines.stream().filter(line -> line.getContents() instanceof TranslatableContents t
		&& t.getKey().equals(PREFIX + suffix)).findFirst().orElse(null); }
	private static Object[] args(Component component) { return ((TranslatableContents) component.getContents()).getArgs(); }
}
