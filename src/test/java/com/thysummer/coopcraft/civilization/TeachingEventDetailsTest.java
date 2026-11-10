package com.thysummer.coopcraft.civilization;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.net.SettlementOverviewPayload;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TeachingEventDetailsTest {
	private static Map<String, String> context() {
		return Map.of("source", "minecraft_field_craft", "workType", "CRAFT", "skillBefore", "1.0",
			"skillAfter", "1.03", "hours", "1", "costTiming", "next_settled_day");
	}

	private static SimulationEvent event(EventType type, List<String> actors, Map<String, String> context) {
		return new SimulationEvent(1, 7, type, actors, List.of(), List.of(), context, List.of());
	}

	@Test void recordedTeacherLearnerGainAndDelayedCostFitTheExistingWireLimit() {
		var event = event(EventType.TEACHING_SESSION, List.of("teacher", "learner"), context());
		var details = TeachingEventDetails.project(event, id -> id.equals("teacher") ? "Ada" : "Bram");
		assertTrue(TeachingEventDetails.isFieldLesson(event));
		assertEquals(5, details.size()); assertTrue(details.size() <= SettlementOverviewPayload.MAX_EVENT_DETAILS);
		assertEquals("coopcraft.simulation.detail.teacher", details.get(0).label());
		assertEquals("Ada", details.get(0).value()); assertEquals("Bram", details.get(1).value());
		assertEquals("coopcraft.simulation.worktype.CRAFT", details.get(2).value());
		assertEquals("1.0 → 1.03", details.get(3).value());
		assertEquals("coopcraft.simulation.teaching_cost.next_settled_day", details.get(4).value());
		assertThrows(UnsupportedOperationException.class, details::clear);
	}

	@Test void unrelatedOrIncompleteHistoricalEvidenceNeverClaimsSuccessfulInstruction() {
		assertFalse(TeachingEventDetails.isFieldLesson(null));
		assertTrue(TeachingEventDetails.project(event(EventType.SKILL_PRACTICED, List.of("t", "l"), context()), id -> id).isEmpty());
		assertTrue(TeachingEventDetails.project(event(EventType.TEACHING_SESSION, List.of("t", "l"), Map.of()), id -> id).isEmpty());
		for (String key : context().keySet()) {
			var missing = new LinkedHashMap<>(context()); missing.remove(key);
			assertTrue(TeachingEventDetails.project(event(EventType.TEACHING_SESSION, List.of("t", "l"), missing), id -> id).isEmpty(), key);
		}
		for (List<String> actors : List.of(List.<String>of(), List.of("t"), List.of("t", "t"), List.of("t", "l", "extra")))
			assertTrue(TeachingEventDetails.project(event(EventType.TEACHING_SESSION, actors, context()), id -> id).isEmpty());
	}

	@Test void malformedSkillAndUnrecordedTimingDoNotAcquireModernMeaning() {
		for (String value : List.of("NaN", "Infinity", "-1", "0", "2.1", "unknown", "1.0")) {
			var bad = new LinkedHashMap<>(context()); bad.put("skillAfter", value);
			assertTrue(TeachingEventDetails.project(event(EventType.TEACHING_SESSION, List.of("t", "l"), bad), id -> id).isEmpty(), value);
		}
		for (var replacement : List.of(Map.entry("hours", "2"), Map.entry("workType", "GATHER"), Map.entry("costTiming", "immediate"))) {
			var bad = new LinkedHashMap<>(context()); bad.put(replacement.getKey(), replacement.getValue());
			assertTrue(TeachingEventDetails.project(event(EventType.TEACHING_SESSION, List.of("t", "l"), bad), id -> id).isEmpty());
		}
	}

	@Test void namesAreBoundedWithoutReadingPresentSkillsOrInventories() {
		var event = event(EventType.TEACHING_SESSION, List.of("t", "l"), context());
		var details = TeachingEventDetails.project(event, id -> "x".repeat(200));
		assertEquals(96, details.getFirst().value().length());
		assertEquals("1.0 → 1.03", details.get(3).value());
		assertTrue(TeachingEventDetails.project(event, null).isEmpty());
	}

	@Test void overviewRoutesMarkedLessonsBeforeCurrentProposalOrGenericActorProjection() throws Exception {
		String source = Files.readString(Path.of("src/main/java/com/thysummer/coopcraft/civilization/Civilization.java"));
		int lesson = source.indexOf("if (TeachingEventDetails.isFieldLesson(event))");
		assertTrue(lesson > 0);
		assertTrue(lesson < source.indexOf("if (SettlementMeetingDetails.isMeeting(event))"));
		assertTrue(source.contains("TeachingEventDetails.project(event, actor -> residentName(level, actor))"));
	}
}
