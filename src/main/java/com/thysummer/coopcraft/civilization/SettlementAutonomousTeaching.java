package com.thysummer.coopcraft.civilization;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/** Loaded-only, single-server-thread adapter for the ephemeral two-resident lesson. */
final class SettlementAutonomousTeaching {
	private SettlementAutonomousTeaching() {}
	static final class Session {
		final ServerLevel level;
		final SettlementSavedData data;
		final Settlement camp;
		final BlockPos station;
		final Villager teacher, learner;
		final SettlementSimulationState state;
		final Object stationToken, teacherMember, learnerMember, teacherPerson, learnerPerson;
		final long day, coreDay, mode;
		SettlementTeachingSessions.Pair<Session> pair;
		Session(ServerLevel level, SettlementSavedData data, Settlement camp, Villager teacher, Villager learner) {
			this.level = level; this.data = data; this.camp = camp; this.teacher = teacher; this.learner = learner;
			station = data.getCraftingStation(camp).orElse(null); stationToken = data.craftingStationBindingToken(camp);
			state = data.simulationOf(camp).orElse(null); day = level.getDayTime() / Level.TICKS_PER_DAY;
			coreDay = state.world().day(); mode = state.foodRequestModeVersion();
			teacherMember = data.memberIdentity(teacher.getUUID()); learnerMember = data.memberIdentity(learner.getUUID());
			teacherPerson = SettlementTeachingScene.person(data, camp, teacher.getUUID());
			learnerPerson = SettlementTeachingScene.person(data, camp, learner.getUUID());
		}
		String phase() { return pair == null ? "closed" : pair.phase(); }
	}

	static Session current(Villager resident, SettlementSavedData data) {
		if (!(resident.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return null;
		var pair = data.teachingSessions().find(resident.getUUID(), level.getGameTime());
		return pair != null && valid(pair.value) ? pair.value : null;
	}

	static Session poll(Villager learner, SettlementSavedData data, Settlement camp) {
		if (!(learner.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return null;
		var registry = data.teachingSessions(); long now = level.getGameTime();
		// Bounded registry sweep also releases the other participant when either actor becomes stale.
		registry.expire(now);
		for (var pair : registry.pairs()) if (!valid(pair.value)) registry.cancel(pair);
		var existing = registry.find(learner.getUUID(), now);
		if (existing != null) return existing.value;
		if (!registry.attempt(learner.getUUID(), now) || !placeReady(level, data, camp)
			|| !bodyReady(level, data, camp, learner) || !VillagerWorkGoal.readyForTeaching(learner)) return null;
		int checked = 0;
		for (Villager teacher : level.getEntitiesOfClass(Villager.class, learner.getBoundingBox().inflate(16))) {
			if (++checked > 16) break;
			if (teacher == learner || teacher.distanceToSqr(learner) > 256
				|| registry.find(teacher.getUUID(), now) != null || !bodyReady(level, data, camp, teacher)
				|| !VillagerWorkGoal.readyForTeaching(teacher)
				|| !SettlementCraftTeaching.inspect(data, camp, teacher.getUUID(), learner.getUUID()).status().equals("ready")) continue;
			Session session = new Session(level, data, camp, teacher, learner);
			session.pair = registry.reserve(teacher.getUUID(), learner.getUUID(), now, session);
			return session.pair == null ? null : session;
		}
		return null;
	}
	static boolean valid(Session session) {
		if (session == null) return false;
		boolean valid = session.data.teachingSessions().active(session.pair, session.level.getGameTime()) && eligible(session);
		if (!valid) cancel(session);
		return valid;
	}
	static boolean join(Session session, Villager resident) {
		if (session == null || resident != session.teacher && resident != session.learner || !valid(session)
			|| !VillagerWorkGoal.readyForTeaching(resident)) return false;
		return session.data.teachingSessions().join(session.pair, resident.getUUID(), session.level.getGameTime());
	}
	static void pulse(Session session, Villager resident, boolean safelyArrived) {
		if (!valid(session) || resident != session.teacher && resident != session.learner) return;
		if (VillagerWorkGoal.teachingSessionOf(resident) != session) { cancel(session); return; }
		boolean bothJoined = session.pair.teacherJoined && session.pair.learnerJoined;
		if (bothJoined && (VillagerWorkGoal.teachingSessionOf(session.teacher) != session
			|| VillagerWorkGoal.teachingSessionOf(session.learner) != session)) { cancel(session); return; }
		boolean together = safelyArrived && atStation(session, resident) && resident.hasLineOfSight(resident == session.teacher ? session.learner : session.teacher)
			&& !session.teacher.blockPosition().equals(session.learner.blockPosition());
		if (session.data.teachingSessions().pulse(session.pair, resident.getUUID(), session.level.getGameTime(), together)) {
			// The pair has already been consumed, so no second pulse or re-entry can award again.
			SettlementCraftTeaching.teach(session.data, session.camp, session.teacher.getUUID(), session.learner.getUUID(),
				() -> eligible(session) && atStation(session, session.teacher) && atStation(session, session.learner)
					&& session.teacher.hasLineOfSight(session.learner) && session.learner.hasLineOfSight(session.teacher)
					&& !session.teacher.blockPosition().equals(session.learner.blockPosition())
					&& VillagerWorkGoal.teachingSessionOf(session.teacher) == session
					&& VillagerWorkGoal.teachingSessionOf(session.learner) == session);
		}
	}
	static void cancel(Session session) { if (session != null) session.data.teachingSessions().cancel(session.pair); }
	static void cancelOnUnload(Villager resident) {
		if (resident.level() instanceof ServerLevel level) SettlementSavedData.get(level).teachingSessions().cancelResident(resident.getUUID());
	}
	private static boolean eligible(Session s) {
		return s.level.getServer().isSameThread() && s.level.getServer().getLevel(s.level.dimension()) == s.level
			&& SettlementSavedData.get(s.level) == s.data && placeReady(s.level, s.data, s.camp)
			&& s.data.simulationOf(s.camp).orElse(null) == s.state && s.state.world().day() == s.coreDay
			&& s.state.foodRequestModeVersion() == s.mode && s.level.getDayTime() / Level.TICKS_PER_DAY == s.day
			&& s.station.equals(s.data.getCraftingStation(s.camp).orElse(null)) && s.stationToken == s.data.craftingStationBindingToken(s.camp)
			&& s.teacherMember == s.data.memberIdentity(s.teacher.getUUID()) && s.learnerMember == s.data.memberIdentity(s.learner.getUUID())
			&& s.teacherPerson == SettlementTeachingScene.person(s.data, s.camp, s.teacher.getUUID())
			&& s.learnerPerson == SettlementTeachingScene.person(s.data, s.camp, s.learner.getUUID())
			&& bodyReady(s.level, s.data, s.camp, s.teacher) && bodyReady(s.level, s.data, s.camp, s.learner)
			&& VillagerWorkGoal.readyForTeaching(s.teacher) && VillagerWorkGoal.readyForTeaching(s.learner)
			&& SettlementCraftTeaching.inspect(s.data, s.camp, s.teacher.getUUID(), s.learner.getUUID()).status().equals("ready");
	}
	private static boolean placeReady(ServerLevel level, SettlementSavedData data, Settlement camp) {
		var state = data.simulationOf(camp).orElse(null);
		if (SettlementSavedData.get(level) != data || !data.canonicalSettlement(camp) || state == null
			|| !state.survivalEnabled() || !state.skillsEnabled() || !state.normsEnabled()) return false;
		long day = level.getDayTime() / Level.TICKS_PER_DAY;
		if (day < 0 || !SettlementMeetings.dayReady(day, state.lastSurvivalDay())) return false;
		BlockPos station = data.getCraftingStation(camp).orElse(null);
		return station != null && SettlementTeachingScene.available(level, station)
			&& ConstructionWorkshop.withinWorkRange(camp.center(), station) && level.getBlockState(station).is(Blocks.CRAFTING_TABLE)
			&& !data.protectedAreas().blocked() && !data.protectedAreas().contains(station);
	}
	private static boolean bodyReady(ServerLevel level, SettlementSavedData data, Settlement camp, Villager resident) {
		return resident.distanceToSqr(camp.center().getCenter()) <= 64 * 64
			&& SettlementTeachingScene.residentBodyReady(level, data, camp, resident)
			&& !data.protectedAreas().blocked() && !data.protectedAreas().contains(resident.blockPosition());
	}
	private static boolean safeCell(Session s, BlockPos pos) {
		return SettlementTeachingScene.available(s.level, pos) && !s.data.protectedAreas().contains(pos);
	}
	private static boolean atStation(Session s, Villager resident) {
		BlockPos pos = resident.blockPosition();
		return SettlementCargoApproach.arrived(s.station, resident.position(), new ConstructionApproach.Probe() {
			public boolean inBounds(BlockPos cell) { return safeCell(s, cell); }
			public boolean loaded(BlockPos cell) { return s.level.isLoaded(cell); }
			public boolean ticking(BlockPos cell) { return s.level.isPositionEntityTicking(cell); }
			public net.minecraft.world.level.block.state.BlockState state(BlockPos cell) { return s.level.getBlockState(cell); }
		}) && safeCell(s, pos) && safeCell(s, pos.above()) && safeCell(s, pos.below())
			&& Math.abs(pos.getX() - s.station.getX()) <= 1 && Math.abs(pos.getZ() - s.station.getZ()) <= 1
			&& Math.abs(pos.getY() - s.station.getY()) <= 1 && !pos.equals(s.station)
			&& SettlementTeachingScene.near(resident.distanceToSqr(s.station.getCenter()));
	}
}
