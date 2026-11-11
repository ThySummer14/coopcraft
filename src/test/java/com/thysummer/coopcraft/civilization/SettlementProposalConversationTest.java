package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.MeetingTestEvents;
import com.thysummer.coopcraft.civilization.simulation.Norm;
import com.thysummer.coopcraft.civilization.simulation.NormProposal;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.SocialInformation;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementProposalConversationTest {
	private static final UUID SPEAKER = new UUID(83, 1), LISTENER = new UUID(83, 2), OTHER = new UUID(83, 3);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void sharesOnlyOneNoticeChargesBothAndPersistsWithoutSkillsOrSessionState() {
		Fixture f = fixture();
		CompoundTag before = f.save(); f.data.setDirty(false);
		var ticket = f.prepare(); assertNotNull(ticket);
		assertEquals(before, f.save()); assertFalse(f.data.isDirty());
		var inventory = f.state.world().inventoryTotals();
		assertTrue(SettlementProposalConversation.finish(ticket, () -> true));
		assertTrue(f.data.isDirty()); assertTrue(ticket.sharedEventId > 0);
		assertFalse(SettlementProposalConversation.finish(ticket, () -> true));
		assertEquals(1, f.person(SPEAKER).socialHours()); assertEquals(1, f.person(LISTENER).socialHours());
		assertEquals(0, f.person(OTHER).socialHours()); assertTrue(f.person(OTHER).information().isEmpty());
		assertEquals(inventory, f.state.world().inventoryTotals());
		assertTrue(f.person(LISTENER).knowsInformation("notice")); assertNull(f.prepare());
		var event = f.state.world().ledger().event(ticket.sharedEventId).orElseThrow();
		assertEquals(EventType.INFORMATION_SHARED, event.type());
		assertEquals("minecraft_proposal_conversation", event.context().get("source"));
		assertEquals(SPEAKER.toString(), event.context().get("speakerUuid"));
		assertEquals(LISTENER.toString(), event.context().get("listenerUuid"));
		assertEquals(f.camp.id().toString(), event.context().get("settlement"));
		assertTrue(event.inputs().isEmpty()); assertTrue(event.outputs().isEmpty());
		assertEquals(0, f.state.world().proposal("agenda").orElseThrow().rounds());
		var registry = f.data.proposalConversationSessions();
		assertNotNull(registry.reserve(SPEAKER, LISTENER, 0, null));
		SettlementSavedData restored = SettlementSavedData.load(f.save(), null);
		Settlement camp = restored.all().iterator().next();
		var state = restored.simulationOf(camp).orElseThrow();
		for (UUID id : List.of(SPEAKER, LISTENER)) assertEquals(1, state.world().person(state.residentId(id).orElseThrow()).socialHours());
		assertTrue(state.world().person(state.residentId(LISTENER).orElseThrow()).knowsInformation("notice"));
		assertTrue(restored.proposalConversationSessions().pairs().isEmpty());
		assertNull(SettlementProposalConversation.prepare(restored, camp, SPEAKER, LISTENER));
	}

	@Test void consumedRegistryTokenStillAllowsExactlyOneFinalCoreCommit() {
		Fixture f = fixture(); var ticket = f.prepare();
		var registry = new SettlementProposalConversationSessions<SettlementProposalConversation.Ticket>();
		var pair = registry.reserve(SPEAKER, LISTENER, 0, ticket);
		assertTrue(registry.join(pair, SPEAKER, 0)); assertTrue(registry.join(pair, LISTENER, 0));
		for (int tick = 1; tick <= 200; tick++) {
			assertFalse(registry.pulse(pair, SPEAKER, tick, true));
			boolean complete = registry.pulse(pair, LISTENER, tick, true);
			assertEquals(tick == 200, complete);
			if (complete) assertTrue(SettlementProposalConversation.finish(ticket,
				() -> pair.completed() && !registry.active(pair, 200)));
		}
		assertEquals(1, f.person(SPEAKER).socialHours()); assertEquals(1, f.person(LISTENER).socialHours());
		assertFalse(SettlementProposalConversation.finish(ticket, pair::completed));
	}

	@Test void falseAuthorityConsumesAttemptWithoutMutatingPersistentState() {
		Fixture f = fixture(); var ticket = f.prepare(); CompoundTag before = f.save(); f.data.setDirty(false);
		assertFalse(SettlementProposalConversation.finish(ticket, () -> false));
		assertFalse(SettlementProposalConversation.finish(ticket, () -> true));
		assertEquals(before, f.save()); assertFalse(f.data.isDirty()); assertEquals(0, ticket.sharedEventId);
	}

	@Test void finalAuthorityReentryAndStateChangesCannotChargeOrPublish() {
		List<Consumer<Fixture>> changes = List.of(
			f -> { f.state.setNormsEnabled(false); f.state.setNormsEnabled(true); },
			f -> f.state.world().advanceDay(),
			f -> { f.data.removeMember(SPEAKER); f.data.registerMember(f.camp, SPEAKER, 0); },
			f -> f.person(LISTENER).restoreSocialState(f.person(LISTENER).grievance(), 6, f.person(LISTENER).previousSocialHours()),
			f -> f.state.norms().shareProposalNotice(f.state.norms().prepareProposalNotice(f.person(SPEAKER), f.person(LISTENER)).orElseThrow(), Map.of()));
		for (var change : changes) {
			Fixture f = fixture(); var ticket = f.prepare();
			assertFalse(SettlementProposalConversation.finish(ticket, () -> { change.accept(f); return true; }));
			assertEquals(0, ticket.sharedEventId);
		}
		Fixture f = fixture(); var ticket = f.prepare();
		assertTrue(SettlementProposalConversation.finish(ticket, () -> {
			assertFalse(SettlementProposalConversation.finish(ticket, () -> true)); return true;
		}));
		assertEquals(1, f.person(SPEAKER).socialHours()); assertEquals(1, f.person(LISTENER).socialHours());
	}

	@Test void existingSixHourBudgetSurvivesReloadAndResetsOnlyWithCoreDay() {
		Fixture f = fixture(); f.person(SPEAKER).restoreSocialState(f.person(SPEAKER).grievance(), 5, f.person(SPEAKER).previousSocialHours());
		assertTrue(SettlementProposalConversation.finish(f.prepare(), () -> true));
		assertEquals(6, f.person(SPEAKER).socialHours());
		SettlementSavedData restored = SettlementSavedData.load(f.save(), null);
		Settlement camp = restored.all().iterator().next();
		var state = restored.simulationOf(camp).orElseThrow();
		assertEquals(6, state.world().person(state.residentId(SPEAKER).orElseThrow()).socialHours());
		assertNull(SettlementProposalConversation.prepare(restored, camp, SPEAKER, OTHER));
		state.world().advanceDay();
		assertNull(SettlementProposalConversation.prepare(restored, camp, SPEAKER, OTHER));
		state.norms().startDay(state.world().people());
		assertNotNull(SettlementProposalConversation.prepare(restored, camp, SPEAKER, OTHER));
	}

	private static Fixture fixture() {
		SettlementSavedData data = new SettlementSavedData(); Settlement camp = data.getOrCreate(BlockPos.ZERO, "Notices", 0);
		var state = data.getOrCreateSimulation(camp);
		for (UUID id : List.of(SPEAKER, LISTENER, OTHER)) {
			data.registerMember(camp, id, 0); state.mapResident(id, false, 0);
			state.world().person(state.residentId(id).orElseThrow()).initializeAge(Person.AgeBand.ADULT, 0);
		}
		state.setSurvivalEnabled(true, 0); state.setNormsEnabled(true); state.setSkillsEnabled(false);
		Fixture f = new Fixture(data, camp, state);
		Person speaker = f.person(SPEAKER);
		speaker.setTrust(f.person(LISTENER).id(), 20); speaker.setTrust(f.person(OTHER).id(), 20);
		long origin = MeetingTestEvents.record(state.world(), EventType.SHORTAGE_CONFLICT, List.of(speaker.id()), Map.of(), List.of());
		var proposal = NormProposal.restore("agenda", state.campOwner().id(), Norm.NEED_FIRST_COMMON_STORE,
			speaker.id(), state.world().day(), origin, 2, 0, 0, NormProposal.Status.OPEN, Map.of(speaker.id(), NormProposal.Position.OPPOSE));
		state.world().registerProposal(proposal);
		long source = MeetingTestEvents.record(state.world(), EventType.NORM_PROPOSED, List.of(speaker.id()),
			Map.of("proposalId", "agenda", "normId", proposal.normId(), "cap", "2", "revision", "0"), List.of(origin));
		speaker.learnInformation(new SocialInformation("notice", SocialInformation.Kind.NORM_PROPOSAL,
			speaker.id(), "agenda", source, state.world().day(), null, 1));
		return f;
	}
	private record Fixture(SettlementSavedData data, Settlement camp, SettlementSimulationState state) {
		Person person(UUID id) { return state.world().person(state.residentId(id).orElseThrow()); }
		CompoundTag save() { return data.save(new CompoundTag(), null); }
		SettlementProposalConversation.Ticket prepare() { return SettlementProposalConversation.prepare(data, camp, SPEAKER, LISTENER); }
	}
}
