package com.thysummer.coopcraft.civilization;

import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import com.thysummer.coopcraft.civilization.simulation.NormSystem;
import com.thysummer.coopcraft.civilization.simulation.Person;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/** A loaded-only conversation between two already-present residents; never moves either actor. */
final class SettlementProposalConversation {
	private SettlementProposalConversation() { }

	/** Core snapshot. The caller must prove physical completion with fresh authority at finish. */
	static final class Ticket {
		final SettlementSavedData data;
		final Settlement camp;
		final UUID speakerId, listenerId;
		final SettlementSimulationState state;
		final Person speaker, listener;
		final Object speakerMember, listenerMember;
		final long day, mode;
		final NormSystem.ProposalNoticePlan plan;
		boolean used, finishing;
		long sharedEventId;
		Ticket(SettlementSavedData data, Settlement camp, UUID speakerId, UUID listenerId,
			SettlementSimulationState state, Person speaker, Person listener, NormSystem.ProposalNoticePlan plan) {
			this.data = data; this.camp = camp; this.speakerId = speakerId; this.listenerId = listenerId;
			this.state = state; this.speaker = speaker; this.listener = listener; this.plan = plan;
			speakerMember = data.memberIdentity(speakerId); listenerMember = data.memberIdentity(listenerId);
			day = state.world().day(); mode = state.foodRequestModeVersion();
		}
	}
	static Ticket prepare(SettlementSavedData data, Settlement camp, UUID speaker, UUID listener) {
		if (data == null || camp == null || speaker == null || listener == null || speaker.equals(listener)
			|| !data.canonicalSettlement(camp) || !data.isMember(camp, speaker) || !data.isMember(camp, listener)) return null;
		var state = data.simulationOf(camp).orElse(null);
		if (state == null || !state.survivalEnabled() || !state.normsEnabled()) return null;
		Person from = SettlementTeachingScene.person(data, camp, speaker), to = SettlementTeachingScene.person(data, camp, listener);
		if (from == null || to == null || from == to) return null;
		var plan = state.norms().prepareProposalNotice(from, to).orElse(null);
		return plan == null ? null : new Ticket(data, camp, speaker, listener, state, from, to, plan);
	}
	static boolean valid(Ticket t) {
		return t != null && !t.used && t.data.canonicalSettlement(t.camp)
			&& t.state == t.data.simulationOf(t.camp).orElse(null) && t.state.survivalEnabled() && t.state.normsEnabled()
			&& t.day == t.state.world().day() && t.mode == t.state.foodRequestModeVersion()
			&& t.speakerMember != null && t.listenerMember != null
			&& t.speakerMember == t.data.memberIdentity(t.speakerId) && t.listenerMember == t.data.memberIdentity(t.listenerId)
			&& t.data.isMember(t.camp, t.speakerId) && t.data.isMember(t.camp, t.listenerId)
			&& t.speaker == SettlementTeachingScene.person(t.data, t.camp, t.speakerId)
			&& t.listener == SettlementTeachingScene.person(t.data, t.camp, t.listenerId)
			&& t.state.norms().validProposalNotice(t.plan);
	}
	static boolean finish(Ticket t, BooleanSupplier physicalCompletion) {
		if (t == null || t.used || t.finishing) return false;
		t.finishing = true;
		try {
			if (physicalCompletion == null || !valid(t) || !physicalCompletion.getAsBoolean() || !valid(t)) return false;
			var result = t.state.norms().shareProposalNotice(t.plan, Map.of("settlement", t.camp.id().toString(),
				"speakerUuid", t.speakerId.toString(), "listenerUuid", t.listenerId.toString(),
				"source", "minecraft_proposal_conversation"));
			if (!result.shared()) return false;
			t.sharedEventId = result.sharedEventId();
			t.data.setDirty();
			return true;
		} finally { t.used = true; t.finishing = false; }
	}

	static final class Session {
		final ServerLevel level;
		final SettlementSavedData data;
		final Settlement camp;
		final Villager speaker, listener;
		final BlockPos station;
		final Object binding;
		final StoreIdentity store;
		final long worldDay;
		final Ticket ticket;
		SettlementProposalConversationSessions.Pair<Session> pair;
		Session(ServerLevel level, SettlementSavedData data, Settlement camp, Villager speaker, Villager listener, Ticket ticket) {
			this.level = level; this.data = data; this.camp = camp; this.speaker = speaker; this.listener = listener; this.ticket = ticket;
			station = data.getStockpile(camp).orElse(null); binding = data.stockpileBindingToken(camp);
			store = storeIdentity(level, data, station); worldDay = level.getDayTime() / Level.TICKS_PER_DAY;
		}
		String phase() { return ticket.sharedEventId > 0 ? "shared" : pair == null || pair.completed() ? "closed" : pair.phase(); }
	}
	static boolean claimed(Villager resident) {
		return resident.level() instanceof ServerLevel level && level.getServer().isSameThread()
			&& SettlementSavedData.get(level).proposalConversationSessions().find(resident.getUUID(), level.getGameTime()) != null;
	}
	static Session current(Villager resident, SettlementSavedData data) {
		if (!(resident.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return null;
		var pair = data.proposalConversationSessions().find(resident.getUUID(), level.getGameTime());
		return pair != null && valid(pair.value) ? pair.value : null;
	}
	static Session poll(Villager speaker, SettlementSavedData data, Settlement camp) {
		if (!(speaker.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return null;
		var registry = data.proposalConversationSessions(); long now = level.getGameTime();
		registry.expire(now);
		for (var pair : registry.pairs()) if (!eligible(pair.value)) registry.cancel(pair);
		var existing = registry.find(speaker.getUUID(), now);
		if (existing != null) return existing.value;
		BlockPos station = data.getStockpile(camp).orElse(null);
		if (!registry.attempt(speaker.getUUID(), now) || !placeReady(level, data, camp)
			|| !bodyReady(level, data, camp, station, speaker)) return null;
		int checked = 0;
		for (Villager listener : level.getEntitiesOfClass(Villager.class, speaker.getBoundingBox().inflate(4))) {
			if (++checked > 16) break;
			if (listener == speaker || registry.find(listener.getUUID(), now) != null
				|| !bodyReady(level, data, camp, station, listener) || !contact(level, speaker, listener)) continue;
			Ticket ticket = prepare(data, camp, speaker.getUUID(), listener.getUUID());
			if (ticket == null) continue;
			Session s = new Session(level, data, camp, speaker, listener, ticket);
			if (!eligible(s)) continue;
			s.pair = registry.reserve(speaker.getUUID(), listener.getUUID(), now, s);
			if (s.pair != null) return s;
		}
		return null;
	}
	static boolean valid(Session s) {
		if (s == null) return false;
		boolean ready = s.data.proposalConversationSessions().active(s.pair, s.level.getGameTime()) && eligible(s);
		if (!ready) cancel(s);
		return ready;
	}
	static boolean join(Session s, Villager resident) {
		return s != null && (resident == s.speaker || resident == s.listener) && valid(s)
			&& s.data.proposalConversationSessions().join(s.pair, resident.getUUID(), s.level.getGameTime());
	}
	static void pulse(Session s, Villager resident) {
		if (!valid(s) || resident != s.speaker && resident != s.listener) return;
		if (VillagerWorkGoal.proposalConversationSessionOf(resident) != s
			|| s.pair.joined(s.pair.speaker) && s.pair.joined(s.pair.listener) && !goalsMatch(s)) { cancel(s); return; }
		if (s.data.proposalConversationSessions().pulse(s.pair, resident.getUUID(), s.level.getGameTime(), true)) {
			// Completion consumed registry ownership, not the core ticket. Requiring active() here would reject every success.
			finish(s.ticket, () -> s.pair.completed() && goalsMatch(s) && eligible(s));
		}
	}
	static void cancel(Session s) { if (s != null) { s.data.proposalConversationSessions().cancel(s.pair); s.ticket.used = true; } }
	static void cancelOnUnload(Villager resident) {
		if (resident.level() instanceof ServerLevel level) SettlementSavedData.get(level).proposalConversationSessions().cancelResident(resident.getUUID());
	}
	private static boolean goalsMatch(Session s) {
		return VillagerWorkGoal.proposalConversationSessionOf(s.speaker) == s && VillagerWorkGoal.proposalConversationSessionOf(s.listener) == s;
	}
	private static boolean eligible(Session s) {
		return s.level.getServer().isSameThread() && s.level.getServer().getLevel(s.level.dimension()) == s.level
			&& SettlementSavedData.get(s.level) == s.data && valid(s.ticket) && placeReady(s.level, s.data, s.camp)
			&& s.worldDay == s.level.getDayTime() / Level.TICKS_PER_DAY
			&& s.station.equals(s.data.getStockpile(s.camp).orElse(null)) && s.binding == s.data.stockpileBindingToken(s.camp)
			&& s.store != null && s.store.equals(storeIdentity(s.level, s.data, s.station))
			&& bodyReady(s.level, s.data, s.camp, s.station, s.speaker) && bodyReady(s.level, s.data, s.camp, s.station, s.listener)
			&& contact(s.level, s.speaker, s.listener);
	}
	private static boolean placeReady(ServerLevel level, SettlementSavedData data, Settlement camp) {
		var state = data.simulationOf(camp).orElse(null);
		long day = level.getDayTime() / Level.TICKS_PER_DAY;
		BlockPos station = data.getStockpile(camp).orElse(null);
		return data.canonicalSettlement(camp) && state != null && state.survivalEnabled() && state.normsEnabled()
			&& day >= 0 && SettlementMeetings.dayReady(day, state.lastSurvivalDay()) && station != null
			&& ConstructionWorkshop.withinWorkRange(camp.center(), station) && storeIdentity(level, data, station) != null;
	}
	private record StoreIdentity(BlockState state, BlockEntity main, BlockPos partnerPosition, BlockState partnerState, BlockEntity partner) { }
	private static StoreIdentity storeIdentity(ServerLevel level, SettlementSavedData data, BlockPos pos) {
		if (pos == null || !safeCell(level, data, pos)) return null;
		BlockState state = level.getBlockState(pos);
		// Vanilla chest lookup inspects all horizontal neighbors and the space above both halves.
		if (state.getBlock() instanceof ChestBlock) {
			if (!safeCell(level, data, pos.above())) return null;
			for (Direction d : Direction.Plane.HORIZONTAL) if (!SettlementTeachingScene.available(level, pos.relative(d))) return null;
		}
		BlockEntity main = level.getBlockEntity(pos);
		if (!(main instanceof Container) || main.isRemoved() || main.getLevel() != level) return null;
		BlockPos partner = null; BlockState partnerState = null; BlockEntity other = null;
		if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			partner = pos.relative(ChestBlock.getConnectedDirection(state));
			if (!safeCell(level, data, partner) || !safeCell(level, data, partner.above())) return null;
			partnerState = level.getBlockState(partner); other = level.getBlockEntity(partner);
			if (!(other instanceof Container) || other.isRemoved() || other.getLevel() != level) return null;
		}
		if (state.getBlock() instanceof ChestBlock chest && ChestBlock.getContainer(chest, state, level, pos, false) == null) return null;
		return new StoreIdentity(state, main, partner, partnerState, other);
	}
	private static boolean safeCell(ServerLevel level, SettlementSavedData data, BlockPos pos) {
		return SettlementTeachingScene.available(level, pos) && !data.protectedAreas().blocked() && !data.protectedAreas().contains(pos);
	}
	private static boolean bodyReady(ServerLevel level, SettlementSavedData data, Settlement camp, BlockPos station, Villager v) {
		return SettlementTeachingScene.residentBodyReady(level, data, camp, v) && !v.isVehicle() && v.hurtTime == 0
			&& v.onGround() && !v.isInWaterOrBubble() && !v.isInLava() && v.getNavigation().isDone()
			&& VillagerWorkGoal.readyForProposalConversation(v) && SettlementCargoApproach.arrived(station, v.position(), new ConstructionApproach.Probe() {
				public boolean inBounds(BlockPos pos) { return safeCell(level, data, pos); }
				public boolean loaded(BlockPos pos) { return level.isLoaded(pos); }
				public boolean ticking(BlockPos pos) { return level.isPositionEntityTicking(pos); }
				public BlockState state(BlockPos pos) { return level.getBlockState(pos); }
			});
	}
	private static boolean contact(ServerLevel level, Villager speaker, Villager listener) {
		if (speaker.blockPosition().equals(listener.blockPosition()) || speaker.distanceToSqr(listener) > 16
			|| speaker.blockPosition().getY() != listener.blockPosition().getY()) return false;
		BlockPos from = BlockPos.containing(speaker.getEyePosition()), to = BlockPos.containing(listener.getEyePosition());
		BlockPos min = new BlockPos(Math.min(from.getX(), to.getX()) - 1, Math.min(from.getY(), to.getY()) - 1, Math.min(from.getZ(), to.getZ()) - 1);
		BlockPos max = new BlockPos(Math.max(from.getX(), to.getX()) + 1, Math.max(from.getY(), to.getY()) + 1, Math.max(from.getZ(), to.getZ()) + 1);
		for (BlockPos cell : BlockPos.betweenClosed(min, max)) if (!SettlementTeachingScene.available(level, cell)) return false;
		return speaker.hasLineOfSight(listener) && listener.hasLineOfSight(speaker);
	}
}
