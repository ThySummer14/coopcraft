package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import com.thysummer.coopcraft.civilization.simulation.SocialInformation;
import com.thysummer.coopcraft.civilization.simulation.StorageOwner;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.thysummer.coopcraft.civilization.simulation.NormProposal.Position.*;
import static org.junit.jupiter.api.Assertions.*;

class VillagerMeetingInquiryTest {
	private static final String PREFIX = "coopcraft.interaction.meeting.inquiry.";

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void realAutomaticProposalCanBeExplainedWithoutLearningChargingOrDirtyingAnyState() {
		Fixture f = fixture(3, true);
		NormProposal proposal = automaticProposal(f);
		assertEquals(EventType.SHARE_REFUSED, f.state.world().ledger().event(proposal.originEventId()).orElseThrow().type());
		var source = f.person(1).information().stream().filter(info -> info.kind() == SocialInformation.Kind.NORM_PROPOSAL).findFirst().orElseThrow();
		assertNotEquals(proposal.originEventId(), source.sourceEventId());
		assertEquals(EventType.NORM_PROPOSED, f.state.world().ledger().event(source.sourceEventId()).orElseThrow().type());
		f.person(2).restoreSocialState(7, 5, 3);
		f.state.recordFoodAllocation(f.person(1).id(), 1);
		CompoundTag before = save(f);
		var stock = f.state.world().inventoryTotals();
		f.data.setDirty(false);

		for (int repeat = 0; repeat < 20; repeat++) {
			var known = f.state.norms().inspectProposal(f.person(1), proposal.proposalId()).orElseThrow();
			var unknown = f.state.norms().inspectProposal(f.person(2), proposal.proposalId()).orElseThrow();
			assertTrue(known.known());
			assertTrue(known.canIntroduce(), "Capability is available without asserting present attendance");
			assertTrue(known.opinion().isPresent());
			assertFalse(unknown.known());
			assertFalse(unknown.canIntroduce());
			assertTrue(unknown.opinion().isEmpty());
			var knownLines = inspect(f, 1);
			var unknownLines = inspect(f, 2);
			assertEquals(4, knownLines.size());
			assertEquals(3, unknownLines.size());
			assertEquals(proposal.proposalId(), argument(knownLines.getFirst(), 0));
			assertEquals(proposal.proposalId(), argument(unknownLines.getFirst(), 0));
			assertEquals(PREFIX + "unknown", key(unknownLines.getLast()));
			assertFalse(keys(unknownLines).contains(PREFIX + "opinion"));
			assertFalse(keys(unknownLines).contains(PREFIX + "factors"));
			assertEquals(before, save(f));
			assertFalse(f.data.isDirty());
		}
		assertEquals(Set.of(f.person(1).id()), proposal.knownBy());
		assertEquals(0, proposal.rounds());
		assertTrue(f.person(2).information().isEmpty());
		assertEquals(5, f.person(2).socialHours());
		assertEquals(3, f.person(2).previousSocialHours());
		assertEquals(1, f.state.allocatedFoodUnits(f.person(1).id()));
		assertEquals(stock, f.state.world().inventoryTotals());
		f.state.world().assertConservation();
	}

	@Test
	void ordinaryInquiryChoosesOneOldestOpenProposalEvenWhenOnlyAnotherProposalIsKnownOrReady() {
		Fixture f = fixture(3, true);
		NormProposal later = seed(f, "proposal-z", Norm.NEED_FIRST_COMMON_STORE, 3, Map.of(2, SUPPORT, 3, SUPPORT), false);
		NormProposal first = seed(f, "proposal-a", Norm.NEED_FIRST_COMMON_STORE, 2, Map.of(1, SUPPORT), false);
		CompoundTag before = save(f);
		var lines = inspect(f, 2);
		assertEquals(first.proposalId(), argument(lines.getFirst(), 0), "Same-day ties must use proposal ID, not registration or knowledge order");
		assertEquals(PREFIX + "unknown", key(lines.getLast()));
		assertEquals(PREFIX + "no", key(assertInstanceOf(Component.class, argument(lines.get(1), 0))));
		assertEquals(later.proposalId(), f.state.norms().inspectCommonStoreMeeting(f.people(), f.people(), f.camp()).proposalId(),
			"An inquiry identifies its own proposal; it cannot promise the next meeting's agenda");
		assertEquals(before, save(f));

		Fixture chronological = fixture(2, true);
		NormProposal oldest = seed(chronological, "proposal-z", Norm.NEED_FIRST_COMMON_STORE, 2, Map.of(1, SUPPORT), true);
		nextDay(chronological);
		seed(chronological, "proposal-a", Norm.CARE_FIRST_COMMON_STORE, 1, Map.of(1, SUPPORT), true);
		assertEquals(oldest.proposalId(), argument(inspect(chronological, 1).getFirst(), 0), "Day takes precedence over lexicographic ID");
	}

	@Test
	void onlyActualHoldTeachesAndChangesStoredPositionsWhileInquiryTracksCurrentConditions() {
		Fixture f = fixture(3, true);
		NormProposal proposal = automaticProposal(f);
		assertEquals(PREFIX + "unknown", key(inspect(f, 2).getLast()));
		assertFalse(proposal.knownBy().contains(f.person(2).id()));
		assertEquals("held", hold(f).status());
		var earlier = f.state.norms().inspectProposal(f.person(2), proposal.proposalId()).orElseThrow();
		assertEquals(SUPPORT, earlier.opinion().orElseThrow().position());
		assertEquals(SUPPORT, proposal.positions().get(f.person(2).id()));
		assertEquals(1, f.person(1).socialHours());
		assertEquals(1, f.person(2).socialHours());
		assertEquals(0, f.person(3).socialHours());
		assertFalse(f.person(3).information().stream().anyMatch(info -> info.objectId().equals(proposal.proposalId())));
		assertTrue(f.person(2).information().stream().anyMatch(info -> info.objectId().equals(proposal.proposalId())));
		f.person(2).setHunger(0);
		f.state.world().importExternalBatch(ResourceType.FISH, 100, StorageOwner.household(f.person(2).householdId()));
		nextDay(f);
		CompoundTag before = save(f);
		f.data.setDirty(false);
		var current = f.state.norms().inspectProposal(f.person(2), proposal.proposalId()).orElseThrow();
		assertEquals(OPPOSE, current.opinion().orElseThrow().position());
		assertTrue(keys(inspect(f, 2)).contains(PREFIX + "position_oppose"));
		assertEquals(SUPPORT, proposal.positions().get(f.person(2).id()), "The stored position stays unchanged until the meeting");
		assertEquals(SUPPORT, earlier.opinion().orElseThrow().position(), "Earlier immutable inquiries retain their old values");
		assertEquals(before, save(f));
		assertFalse(f.data.isDirty());
		assertEquals("held", hold(f).status());
		assertEquals(current.opinion().orElseThrow().position(), proposal.positions().get(f.person(2).id()));
		assertEquals(1, f.person(1).socialHours());
		assertEquals(1, f.person(2).socialHours());
		assertEquals(1, f.person(2).previousSocialHours());
		assertEquals(0, f.person(3).socialHours());
		f.state.world().assertConservation();
	}

	@Test
	void exactSnapshotsComponentsBudgetsAndAllPersistedDataSurviveRepeatedReloads() {
		Fixture f = fixture(3, true);
		NormProposal proposal = automaticProposal(f);
		assertEquals("held", hold(f).status());
		f.state.recordFoodAllocation(f.person(2).id(), 1);
		f.person(2).setHunger(0);
		f.state.world().importExternalBatch(ResourceType.FISH, 6, StorageOwner.household(f.person(2).householdId()));
		var snapshot = f.state.norms().inspectProposal(f.person(2), proposal.proposalId()).orElseThrow();
		var components = inspect(f, 2);
		CompoundTag canonical = save(f);
		var stock = f.state.world().inventoryTotals();
		var events = f.state.world().ledger().events();
		for (int reload = 0; reload < 2; reload++) {
			f = reload(f, save(f));
			f.data.setDirty(false);
			assertEquals(snapshot, f.state.norms().inspectProposal(f.person(2), proposal.proposalId()).orElseThrow());
			assertEquals(components, inspect(f, 2));
			assertEquals(canonical, save(f));
			assertFalse(f.data.isDirty());
			assertEquals(stock, f.state.world().inventoryTotals());
			assertEquals(events, f.state.world().ledger().events());
			assertEquals(1, f.state.allocatedFoodUnits(f.person(2).id()));
			assertEquals(1, f.person(2).socialHours());
			assertEquals(SUPPORT, f.state.world().proposal(proposal.proposalId()).orElseThrow().positions().get(f.person(2).id()));
			assertEquals(OPPOSE, snapshot.opinion().orElseThrow().position());
			f.state.world().assertConservation();
		}
	}

	@Test
	void stateModeDayMembershipAndMappingGatesNeverCreateOrRepairState() {
		SettlementSavedData empty = new SettlementSavedData();
		Settlement settlement = empty.getOrCreate(BlockPos.ZERO, "No simulation", 0);
		empty.registerMember(settlement, id(1), 0);
		CompoundTag before = empty.save(new CompoundTag(), null);
		empty.setDirty(false);
		for (int repeat = 0; repeat < 10; repeat++) {
			assertTrue(VillagerMeetingInquiry.inspect(empty, settlement, id(1), 0).isEmpty());
			assertTrue(empty.simulationOf(settlement).isEmpty());
			assertEquals(before, empty.save(new CompoundTag(), null));
			assertFalse(empty.isDirty());
		}
		for (String gate : List.of("survival-disabled", "norms-disabled", "both-disabled", "future-day", "negative-day",
			"missing-member", "missing-mapping", "wrong-membership", "stale-settlement")) {
			Fixture f = fixture(2, true);
			automaticProposal(f);
			UUID resident = id(1);
			long day = 0;
			Settlement target = f.settlement;
			switch (gate) {
				case "survival-disabled" -> f.state.setSurvivalEnabled(false, 0);
				case "norms-disabled" -> f.state.setNormsEnabled(false);
				case "both-disabled" -> { f.state.setSurvivalEnabled(false, 0); f.state.setNormsEnabled(false); }
				case "future-day" -> day = 1;
				case "negative-day" -> day = -1;
				case "missing-member" -> f.data.removeMember(resident);
				case "missing-mapping" -> { resident = id(99); f.data.registerMember(f.settlement, resident, 0); }
				case "wrong-membership" -> {
					f.data.removeMember(resident);
					f.data.registerMember(f.data.getOrCreate(new BlockPos(500, 0, 0), "Other", 0), resident, 0);
				}
				case "stale-settlement" -> target = new Settlement(f.settlement.id(), f.settlement.center(), "Stale copy", 0);
			}
			CompoundTag saved = save(f);
			f.data.setDirty(false);
			var messages = VillagerMeetingInquiry.inspect(f.data, target, resident, day);
			if (gate.endsWith("disabled")) assertTrue(messages.isEmpty(), gate);
			else assertEquals(List.of(PREFIX + (gate.endsWith("day") ? "day_pending" : "unavailable")), messages.stream().map(VillagerMeetingInquiryTest::key).toList(), gate);
			assertEquals(saved, save(f), gate);
			assertFalse(f.data.isDirty(), gate);
			assertTrue(f.state.residentId(id(99)).isEmpty(), "Queries must not lazily map a resident");
		}
	}

	@Test
	void malformedOldestProposalNeverFallsThroughToALaterValidOrKnownOne() {
		for (String invalid : List.of("unknown-rule", "future", "missing-origin", "round-limit", "long-id", "control-id")) {
			Fixture f = fixture(2, true);
			String id = invalid.equals("long-id") ? "a".repeat(65) : invalid.equals("control-id") ? "a\ninvalid" : "a-invalid";
			long origin = refusal(f);
			if (!invalid.equals("future")) seed(f, "z-valid", Norm.NEED_FIRST_COMMON_STORE, 2, Map.of(1, SUPPORT), true);
			// A future-only agenda is invalid; any current valid proposal would correctly sort before it.
			long proposedDay = invalid.equals("future") ? f.state.world().day() + 1 : f.state.world().day();
			f.state.world().registerProposal(NormProposal.restore(id, f.camp(), invalid.equals("unknown-rule") ? "unknown" : Norm.NEED_FIRST_COMMON_STORE,
				f.person(1).id(), proposedDay, invalid.equals("missing-origin") ? 999999 : origin,
				2, 0, invalid.equals("round-limit") ? 5 : 0, NormProposal.Status.OPEN, Map.of(f.person(1).id(), SUPPORT)));
			CompoundTag before = save(f);
			f.data.setDirty(false);
			assertEquals(List.of(PREFIX + "unavailable"), inspect(f, 1).stream().map(VillagerMeetingInquiryTest::key).toList(), invalid);
			assertEquals(before, save(f));
			assertFalse(f.data.isDirty());
		}
		Fixture closed = fixture(2, true);
		closed.state.world().registerProposal(NormProposal.restore("closed", closed.camp(), Norm.NEED_FIRST_COMMON_STORE,
			closed.person(1).id(), 0, refusal(closed), 2, 0, 2, NormProposal.Status.ADOPTED, Map.of(closed.person(1).id(), SUPPORT)));
		assertEquals(List.of(PREFIX + "none"), inspect(closed, 1).stream().map(VillagerMeetingInquiryTest::key).toList());
	}

	@Test
	void everyActualInquiryComponentAndNestedFactorHasMatchingBilingualPlaceholders() throws Exception {
		var actual = new ArrayList<Component>();
		Fixture automatic = fixture(2, true);
		automaticProposal(automatic);
		collect(actual, inspect(automatic, 1));
		collect(actual, inspect(automatic, 2));
		collect(actual, VillagerMeetingInquiry.inspect(automatic.data, automatic.settlement, id(1), 1));
		collect(actual, VillagerMeetingInquiry.inspect(automatic.data, automatic.settlement, id(99), 0));
		collect(actual, inspect(fixture(2, false), 1));
		for (String variant : List.of("no-factors", "grievance", "trust", "surplus", "no-source", "exhausted", "child", "care")) {
			Fixture f = fixture(2, false);
			f.people().forEach(person -> person.setHunger(0));
			seed(f, "agenda", variant.equals("care") ? Norm.CARE_FIRST_COMMON_STORE : Norm.NEED_FIRST_COMMON_STORE,
				variant.equals("care") ? 1 : 4, Map.of(1, SUPPORT), !variant.equals("no-source"));
			switch (variant) {
				case "grievance" -> f.person(1).restoreSocialState(8, 0, 0);
				case "trust" -> f.person(1).setTrust(f.person(1).id(), 100);
				case "surplus" -> f.state.world().importExternalBatch(ResourceType.FISH, 6, StorageOwner.household(f.person(1).householdId()));
				case "exhausted" -> f.person(1).restoreSocialState(0, 6, 0);
				case "child" -> f.person(1).initializeAge(Person.AgeBand.CHILD, 0);
			}
			collect(actual, inspect(f, 1));
		}
		Fixture undecided = fixture(2, true);
		undecided.people().forEach(person -> person.setHunger(0));
		undecided.person(1).setTrust(undecided.person(1).id(), 40);
		seed(undecided, "agenda", Norm.NEED_FIRST_COMMON_STORE, 3, Map.of(1, SUPPORT), true);
		collect(actual, inspect(undecided, 1));
		// Exercise the shared rendering adapter for both signs of every factor, including
		// legacy-only LARGE_ALLOCATION which real-world directional opinions no longer emit.
		for (NormSystem.ProposalFactorKind kind : NormSystem.ProposalFactorKind.values()) {
			for (double contribution : List.of(-1.0, 1.0)) {
				actual.add(VillagerMeetingInquiry.factors(new NormSystem.ProposalOpinion(UNDECIDED, 35 + contribution,
					List.of(new NormSystem.ProposalFactor(kind, contribution)))));
			}
		}
		actual.add(VillagerMeetingInquiry.factors(new NormSystem.ProposalOpinion(OPPOSE, 35, List.of())));
		Set<String> emitted = keys(actual);
		for (String locale : List.of("en_us", "zh_cn")) {
			JsonObject json = translations(locale);
			for (Component component : actual) assertTranslatedArguments(json, locale, component);
			Set<String> declared = json.keySet().stream().filter(key -> key.startsWith(PREFIX)).collect(java.util.stream.Collectors.toSet());
			assertEquals(declared, emitted.stream().filter(key -> key.startsWith(PREFIX)).collect(java.util.stream.Collectors.toSet()),
				"Exercise every inquiry translation through query and shared factor-renderer output: " + locale);
		}
	}


	@Test
	void realIncreasesAndCutsRenderActualSignsWithoutChangingStoredVotes() {
		for (int cap : List.of(2, 4)) {
			Fixture f = fixture(2, true);
			NormProposal proposal = seed(f, "direction", Norm.NEED_FIRST_COMMON_STORE, cap, Map.of(1, SUPPORT), true);
			CompoundTag before = save(f);
			var opinion = f.state.norms().inspectProposal(f.person(1), proposal.proposalId()).orElseThrow().opinion().orElseThrow();
			var hunger = opinion.factors().stream().filter(factor -> factor.kind() == NormSystem.ProposalFactorKind.PERSONAL_HUNGER)
				.findFirst().orElseThrow();
			Component rendered = VillagerMeetingInquiry.factors(new NormSystem.ProposalOpinion(opinion.position(), opinion.score(), List.of(hunger)));
			assertTrue(keys(List.of(rendered)).contains(PREFIX + (cap == 4 ? "factor_positive" : "factor_negative")));
			assertFalse(keys(List.of(rendered)).contains(PREFIX + (cap == 4 ? "factor_negative" : "factor_positive")));
			assertEquals(SUPPORT, proposal.positions().get(f.person(1).id()));
			assertEquals(before, save(f));
		}
	}

	@Test
	void smallPersonalityEffectRemainsVisibleAlongsideThreeStrongerContextualReasons() {
		var opinion = new NormSystem.ProposalOpinion(SUPPORT, 70.5, List.of(
			new NormSystem.ProposalFactor(NormSystem.ProposalFactorKind.PERSONAL_HUNGER, 15),
			new NormSystem.ProposalFactor(NormSystem.ProposalFactorKind.NEIGHBOR_REQUESTS, 15),
			new NormSystem.ProposalFactor(NormSystem.ProposalFactorKind.HOUSEHOLD_HUNGER, 10),
			new NormSystem.ProposalFactor(NormSystem.ProposalFactorKind.COMMUNITY_RESERVE, -5),
			new NormSystem.ProposalFactor(NormSystem.ProposalFactorKind.SELFISHNESS, 0.5)));
		Component rendered = VillagerMeetingInquiry.factors(opinion);
		assertEquals(7, rendered.getSiblings().size(), "Four factors and three separators");
		Set<String> shown = keys(List.of(rendered));
		assertTrue(shown.contains(PREFIX + "factor_personal_hunger"));
		assertTrue(shown.contains(PREFIX + "factor_household_hunger"));
		assertTrue(shown.contains(PREFIX + "factor_neighbor_requests"));
		assertTrue(shown.contains(PREFIX + "factor_selfishness"));
		assertFalse(shown.contains(PREFIX + "factor_community_reserve"));
		var duplicate = new NormSystem.ProposalOpinion(UNDECIDED, 40, List.of(
			new NormSystem.ProposalFactor(NormSystem.ProposalFactorKind.SELFISHNESS, 5)));
		assertEquals(1, VillagerMeetingInquiry.factors(duplicate).getSiblings().size());
	}

	private static void collect(List<Component> all, List<Component> lines) {
		assertFalse(lines.isEmpty());
		assertTrue(lines.size() <= 4);
		all.addAll(lines);
	}

	private static Set<String> keys(List<Component> components) {
		Set<String> keys = new LinkedHashSet<>();
		components.forEach(component -> collectKeys(component, keys));
		return keys;
	}

	private static void collectKeys(Component component, Set<String> keys) {
		if (component.getContents() instanceof TranslatableContents translated) {
			keys.add(translated.getKey());
			for (Object argument : translated.getArgs()) if (argument instanceof Component child) collectKeys(child, keys);
		}
		component.getSiblings().forEach(sibling -> collectKeys(sibling, keys));
	}

	private static void assertTranslatedArguments(JsonObject json, String locale, Component component) {
		if (component.getContents() instanceof TranslatableContents translated) {
			String key = translated.getKey();
			assertTrue(json.has(key), locale + ": " + key);
			String text = json.get(key).getAsString();
			assertFalse(text.isBlank(), locale + ": " + key);
			assertFalse(text.contains("\n"), locale + ": " + key);
			assertTrue(text.length() <= 240, locale + ": " + key);
			assertEquals(translated.getArgs().length, text.split("%s", -1).length - 1, locale + ": " + key);
			for (Object argument : translated.getArgs()) if (argument instanceof Component child) assertTranslatedArguments(json, locale, child);
		}
		for (Component sibling : component.getSiblings()) assertTranslatedArguments(json, locale, sibling);
	}

	private static JsonObject translations(String locale) throws Exception {
		try (var input = VillagerMeetingInquiryTest.class.getResourceAsStream("/assets/coopcraft/lang/" + locale + ".json")) {
			assertNotNull(input);
			return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static String key(Component component) {
		return assertInstanceOf(TranslatableContents.class, component.getContents()).getKey();
	}

	private static Object argument(Component component, int index) {
		return assertInstanceOf(TranslatableContents.class, component.getContents()).getArgs()[index];
	}

	private static Fixture fixture(int residents, boolean neighbors) {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Resident inquiries", 0);
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		for (int index = 1; index <= residents; index++) {
			data.registerMember(settlement, id(index), 0);
			state.mapResident(id(index), false, 0);
			if (neighbors) state.world().person(state.residentId(id(index)).orElseThrow()).observeNorm("neighbor-requests");
		}
		state.setSurvivalEnabled(true, 0);
		state.setNormsEnabled(true);
		state.world().importExternalBatch(ResourceType.FISH, 30, state.campOwner());
		return new Fixture(data, settlement, state);
	}

	private static long refusal(Fixture f) {
		long request = f.state.world().recordShareRequest(f.person(1).id(), f.camp(), 40);
		return f.state.world().recordShareRefusal(request, f.person(1).id(), f.camp(), 20);
	}

	private static NormProposal automaticProposal(Fixture f) {
		refusal(f);
		f.state.norms().observeSocialDay(f.people());
		return f.state.world().proposalsForCamp(f.camp()).getFirst();
	}

	private static NormProposal seed(Fixture f, String id, String norm, int cap, Map<Integer, NormProposal.Position> known, boolean information) {
		long origin = refusal(f);
		Map<String, NormProposal.Position> positions = new LinkedHashMap<>();
		known.forEach((resident, position) -> positions.put(f.person(resident).id(), position));
		NormProposal proposal = NormProposal.restore(id, f.camp(), norm, f.person(1).id(), f.state.world().day(), origin,
			cap, 0, 0, NormProposal.Status.OPEN, positions);
		f.state.world().registerProposal(proposal);
		long event = MeetingTestEvents.record(f.state.world(), EventType.NORM_PROPOSED, List.of(f.person(1).id()),
			Map.of("proposalId", id, "normId", norm), List.of(origin));
		if (information) f.person(1).learnInformation(new SocialInformation("proposal-info:" + id, SocialInformation.Kind.NORM_PROPOSAL,
			f.person(1).id(), id, event, f.state.world().day(), null, 1));
		return proposal;
	}

	private static List<Component> inspect(Fixture f, int person) {
		return VillagerMeetingInquiry.inspect(f.data, f.settlement, id(person), f.state.lastSurvivalDay());
	}

	private static com.thysummer.coopcraft.civilization.simulation.NormSystem.MeetingResult hold(Fixture f) {
		return f.state.norms().holdCommonStoreMeeting(f.people(), List.of(f.person(1), f.person(2)), f.camp(), "0, 64, 0");
	}

	private static void nextDay(Fixture f) {
		f.state.norms().startDay(f.people());
		f.state.world().advanceDay();
		f.state.markSurvivalDay(f.state.world().day());
	}

	private static CompoundTag save(Fixture f) { return f.data.save(new CompoundTag(), null); }

	private static Fixture reload(Fixture previous, CompoundTag tag) {
		SettlementSavedData data = SettlementSavedData.load(tag, null);
		Settlement settlement = data.find(previous.settlement.center()).orElseThrow();
		return new Fixture(data, settlement, data.simulationOf(settlement).orElseThrow());
	}

	private static UUID id(int index) { return new UUID(29, index); }

	private record Fixture(SettlementSavedData data, Settlement settlement, SettlementSimulationState state) {
		String camp() { return state.campOwner().id(); }
		Person person(int index) { return state.world().person(state.residentId(id(index)).orElseThrow()); }
		List<Person> people() { return state.world().people(); }
	}
}
