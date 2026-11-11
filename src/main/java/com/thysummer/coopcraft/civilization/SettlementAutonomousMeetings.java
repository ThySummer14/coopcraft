package com.thysummer.coopcraft.civilization;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.Person;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.function.Predicate;

/** An opt-in, at-store conversation. It neither summons residents nor moves them or their goods. */
final class SettlementAutonomousMeetings {
	private SettlementAutonomousMeetings() {}
	static final class Session {
		final ServerLevel level;
		final SettlementSavedData data;
		final Settlement camp;
		final SettlementSimulationState state;
		final BlockPos store;
		final List<Villager> participants;
		final Map<UUID, Person> population;
		final long optionVersion, modeVersion, coreDay, survivalDay;
		final SettlementMeetings.SceneIdentity scene;
		final SettlementMeetings.Inspection inspection;
		final SettlementMeetings.Preview preview;
		final SettlementCraftingStore.Binding physicalStore;
		SettlementCouncilSessions.Group<Session> group;
		Session(ServerLevel level, SettlementSavedData data, Settlement camp, List<Villager> participants,
			Map<UUID, Person> population, SettlementMeetings.Inspection inspection) {
			this.level = level; this.data = data; this.camp = camp; this.state = inspection.state();
			this.store = data.getStockpile(camp).orElseThrow(); this.participants = List.copyOf(participants);
			this.population = Map.copyOf(population); this.inspection = inspection;
			optionVersion = state.autonomousMeetingsVersion(); modeVersion = state.foodRequestModeVersion();
			coreDay = state.world().day(); survivalDay = state.lastSurvivalDay();
			physicalStore = SettlementCraftingStore.capture(level, store);
			scene = identity(level, data, camp);
			preview = SettlementMeetings.preview(data, camp, inspection, scene);
		}
		String phase() { return group == null ? "closed" : group.phase(); }
	}

	static boolean claimed(Villager resident, SettlementSavedData data) { return data.councilSessions().claimed(resident.getUUID()); }
	static Session current(Villager resident, SettlementSavedData data) {
		if (!(resident.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return null;
		var group = data.councilSessions().find(resident.getUUID(), level.getGameTime());
		return group != null && valid(group.value) ? group.value : null;
	}
	static Session poll(Villager initiator, SettlementSavedData data, Settlement camp) {
		if (!(initiator.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return null;
		var registry = data.councilSessions(); long now = level.getGameTime();
		registry.expire(now);
		for (var group : registry.groups()) if (!valid(group.value)) registry.cancel(group);
		Session current = current(initiator, data);
		if (current != null) return current;
		if (!placeReady(level, data, camp)) return null;
		var state = data.simulationOf(camp).orElseThrow();
		BlockPos store = data.getStockpile(camp).orElseThrow();
		Map<UUID, Person> population = SettlementSimulationService.activeResidents(level, data, camp, state);
		int adults = (int) population.values().stream().filter(Person::canMakeDecisions).count();
		if (adults < 2 || adults > 11 || !population.containsKey(initiator.getUUID())
			|| !candidate(level, data, camp, store, initiator)) return null;
		// Stable and bounded candidate examination; opinions/positions never affect selection.
		List<Villager> nearby = population.keySet().stream().sorted().map(level::getEntity)
			.filter(Villager.class::isInstance).map(Villager.class::cast)
			.filter(v -> v.distanceToSqr(store.getCenter()) <= 16).limit(16).toList();
		var participants = new java.util.ArrayList<Villager>(); participants.add(initiator);
		for (Villager resident : nearby) {
			if (participants.size() == 4) break;
			if (resident != initiator && candidate(level, data, camp, store, resident)
				&& participants.stream().allMatch(other -> visible(other, resident))) participants.add(resident);
		}
		participants.sort(Comparator.comparing(Villager::getUUID));
		if (!SettlementCouncilSessions.quorum(participants.size(), adults)) return null;
		var inspection = inspect(state, population, participants, location(level, store));
		if (!reviewReady(inspection, participants.size()) || !registry.attempt(camp.id(), now)) return null;
		Session session = new Session(level, data, camp, participants, population, inspection);
		if (session.physicalStore == null || !session.physicalStore.valid()) return null;
		session.group = registry.reserve(camp.id(), participants.stream().map(Villager::getUUID).toList(), adults, now, session);
		return session.group == null ? null : session;
	}
	static boolean valid(Session session) {
		if (session == null) return false;
		boolean valid = session.level.getServer().isSameThread()
			&& session.data.councilSessions().active(session.group, session.level.getGameTime()) && eligible(session);
		if (!valid) cancel(session);
		return valid;
	}
	static boolean join(Session session, Villager resident) {
		if (!valid(session) || !session.participants.contains(resident)
			|| !VillagerWorkGoal.readyForAutonomousMeeting(resident)) return false;
		return session.data.councilSessions().join(session.group, resident.getUUID(), session.level.getGameTime());
	}
	static void pulse(Session session, Villager resident, boolean safe) {
		if (!valid(session) || !session.participants.contains(resident)) return;
		if (VillagerWorkGoal.autonomousMeetingSessionOf(resident) != session) { cancel(session); return; }
		boolean allJoined = session.group.joined.size() == session.participants.size();
		if (allJoined && !ownsAll(session)) { cancel(session); return; }
		if (session.data.councilSessions().pulse(session.group, resident.getUUID(), session.level.getGameTime(), safe)) {
			// Ownership is consumed before the one shared meeting executor can mutate knowledge/hours/ledger.
			if (!eligible(session) || !ownsAll(session)) return;
			var population = SettlementSimulationService.activeResidents(session.level, session.data, session.camp, session.state);
			SettlementMeetings.holdReviewed(session.data, session.camp,
				inspect(session.state, population, session.participants, location(session.level, session.store)),
				identity(session.level, session.data, session.camp), session.preview);
		}
	}
	static void cancel(Session session) { if (session != null) session.data.councilSessions().cancel(session.group); }
	static void cancelOnUnload(Villager resident) {
		if (resident.level() instanceof ServerLevel level) SettlementSavedData.get(level).councilSessions().cancelResident(resident.getUUID());
	}
	private static boolean ownsAll(Session s) {
		return s.participants.stream().allMatch(v -> VillagerWorkGoal.autonomousMeetingSessionOf(v) == s);
	}
	private static boolean eligible(Session s) {
		if (s.physicalStore == null || !s.physicalStore.valid() || !placeReady(s.level, s.data, s.camp) || s.data.simulationOf(s.camp).orElse(null) != s.state
			|| s.state.autonomousMeetingsVersion() != s.optionVersion || s.state.foodRequestModeVersion() != s.modeVersion
			|| s.state.world().day() != s.coreDay || s.state.lastSurvivalDay() != s.survivalDay
			|| !s.scene.equals(identity(s.level, s.data, s.camp))) return false;
		Map<UUID, Person> population = SettlementSimulationService.activeResidents(s.level, s.data, s.camp, s.state);
		if (!s.population.equals(population)) return false;
		for (Villager resident : s.participants) {
			if (!bodyReady(s.level, s.data, s.camp, s.store, resident)
				|| !VillagerWorkGoal.readyForAutonomousMeeting(resident)
				|| s.data.teachingSessions().find(resident.getUUID(), s.level.getGameTime()) != null) return false;
			for (Villager other : s.participants) if (resident != other && !visible(resident, other)) return false;
		}
		var current = inspect(s.state, population, s.participants, location(s.level, s.store));
		return reviewReady(current, s.participants.size()) && s.inspection.review().equals(current.review())
			&& s.inspection.result().feedback().agenda().equals(current.result().feedback().agenda());
	}
	static boolean reviewReady(SettlementMeetings.Inspection inspection, int participants) {
		var result = inspection.result().discussion();
		return result.status().equals("ready") && inspection.review() != null && inspection.review().hasVerifiedSource()
			&& result.participants() == participants
			&& SettlementCouncilSessions.quorum(participants, result.adultCount());
	}
	static SettlementMeetings.Inspection inspect(SettlementSimulationState state, Map<UUID, Person> population,
		List<Villager> participants, String location) {
		return inspectPeople(state, List.copyOf(population.values()), participants.stream().map(v -> population.get(v.getUUID())).toList(), location);
	}
	/** Pure review seam shared with tests; the world adapter alone supplies verified attendance. */
	static SettlementMeetings.Inspection inspectPeople(SettlementSimulationState state, List<Person> population,
		List<Person> participants, String location) {
		var review = state.norms().reviewCommonStoreMeeting(population, participants, state.campOwner().id());
		var result = new SettlementMeetings.Result(review.result().status(), state.world().day(),
			state.norms().lastMeetingDay(state.campOwner().id()), location, review.result(),
			SettlementMeetingFeedback.inspect(state, review.result()));
		return new SettlementMeetings.Inspection(result, state, population, participants, review);
	}
	private static SettlementMeetings.SceneIdentity identity(ServerLevel level, SettlementSavedData data, Settlement camp) {
		BlockPos store = data.getStockpile(camp).orElseThrow();
		var block = level.getBlockState(store);
		Object partner = block.getBlock() instanceof ChestBlock && block.getValue(ChestBlock.TYPE) != ChestType.SINGLE
			? level.getBlockEntity(store.relative(ChestBlock.getConnectedDirection(block))) : null;
		return new SettlementMeetings.SceneIdentity(level, null, data.stockpileBindingToken(camp), store,
			level.getBlockEntity(store), partner, data.membersOf(camp).stream()
				.map(member -> (Object) new SettlementMeetings.ResidentIdentity(member, level.getEntity(member.villagerId()))).toList(),
			level.getDayTime() / Level.TICKS_PER_DAY);
	}
	private static boolean candidate(ServerLevel level, SettlementSavedData data, Settlement camp, BlockPos store, Villager resident) {
		return !data.councilSessions().claimed(resident.getUUID())
			&& data.teachingSessions().find(resident.getUUID(), level.getGameTime()) == null
			&& bodyReady(level, data, camp, store, resident) && VillagerWorkGoal.readyForAutonomousMeeting(resident);
	}
	private static boolean visible(Villager first, Villager second) {
		return first.level() instanceof ServerLevel level && second.level() == level
			&& !first.blockPosition().equals(second.blockPosition())
			&& sightLoaded(first.getEyePosition(), second.getEyePosition(), level::isLoaded)
			&& first.hasLineOfSight(second) && second.hasLineOfSight(first);
	}
	private static boolean bodyReady(ServerLevel level, SettlementSavedData data, Settlement camp, BlockPos store, Villager resident) {
		if (!SettlementTeachingScene.residentBodyReady(level, data, camp, resident) || !resident.onGround()
			|| resident.isInWaterOrBubble() || resident.isInLava() || !SettlementTeachingScene.near(resident.distanceToSqr(store.getCenter()))
			|| !safeCell(level, data, resident.blockPosition()) || !safeCell(level, data, resident.blockPosition().above())
			|| !safeCell(level, data, resident.blockPosition().below())) return false;
		if (!ConstructionSite.safeSupport(level.getBlockState(resident.blockPosition().below()))
			|| !sightLoaded(resident.getEyePosition(), store.getCenter(), level::isLoaded)) return false;
		Person person = SettlementTeachingScene.person(data, camp, resident.getUUID());
		if (person == null || person.socialHours() >= 6) return false;
		var ray = level.clip(new ClipContext(resident.getEyePosition(), store.getCenter(), ClipContext.Block.COLLIDER,
			ClipContext.Fluid.NONE, resident));
		return ray.getType() == HitResult.Type.MISS || ray.getBlockPos().equals(store);
	}
	private static boolean placeReady(ServerLevel level, SettlementSavedData data, Settlement camp) {
		var state = data.simulationOf(camp).orElse(null);
		if (!level.getServer().isSameThread() || level.getServer().getLevel(level.dimension()) != level
			|| SettlementSavedData.get(level) != data || !data.canonicalSettlement(camp) || state == null
			|| !state.survivalEnabled() || !state.normsEnabled() || !state.autonomousMeetingsAvailable() || !state.autonomousMeetingsEnabled()) return false;
		long day = level.getDayTime() / Level.TICKS_PER_DAY;
		if (day < 0 || !SettlementMeetings.dayReady(day, state.lastSurvivalDay())) return false;
		BlockPos store = data.getStockpile(camp).orElse(null);
		if (store == null || !safeCell(level, data, store) || !ConstructionWorkshop.withinWorkRange(camp.center(), store)) return false;
		var block = level.getBlockState(store);
		if (!(block.is(Blocks.CHEST) || block.is(Blocks.BARREL))) return false;
		var container = level.getBlockEntity(store);
		if (container == null || container.isRemoved() || container.getLevel() != level) return false;
		if (block.is(Blocks.CHEST) && block.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			BlockPos partner = store.relative(ChestBlock.getConnectedDirection(block));
			if (!safeCell(level, data, partner)) return false;
			var other = level.getBlockEntity(partner); var otherBlock = level.getBlockState(partner);
			if (other == null || other.isRemoved() || other.getLevel() != level || !otherBlock.is(Blocks.CHEST)
				|| otherBlock.getValue(ChestBlock.TYPE) != (block.getValue(ChestBlock.TYPE) == ChestType.LEFT ? ChestType.RIGHT : ChestType.LEFT)
				|| otherBlock.getValue(ChestBlock.FACING) != block.getValue(ChestBlock.FACING)
				|| !partner.relative(ChestBlock.getConnectedDirection(otherBlock)).equals(store)) return false;
		}
		return location(level, store).length() <= 96 && SettlementStockpile.getStockpile(level, data, camp) != null;
	}
	/** Preflight every chunk a short ray could touch, including numerical boundary neighbours. */
	static boolean sightLoaded(Vec3 from, Vec3 to, Predicate<BlockPos> loaded) {
		double distance = from.distanceToSqr(to);
		if (!Double.isFinite(distance) || distance > 100) return false;
		BlockPos a = BlockPos.containing(from), b = BlockPos.containing(to);
		int minX = (Math.min(a.getX(), b.getX()) - 1) >> 4, maxX = (Math.max(a.getX(), b.getX()) + 1) >> 4;
		int minZ = (Math.min(a.getZ(), b.getZ()) - 1) >> 4, maxZ = (Math.max(a.getZ(), b.getZ()) + 1) >> 4;
		for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
			if (!loaded.test(new BlockPos(x << 4, a.getY(), z << 4))) return false;
		}
		return true;
	}
	private static boolean safeCell(ServerLevel level, SettlementSavedData data, BlockPos pos) {
		return SettlementTeachingScene.available(level, pos) && !data.protectedAreas().blocked() && !data.protectedAreas().contains(pos);
	}
	private static String location(ServerLevel level, BlockPos store) {
		return level.dimension().location() + " " + store.getX() + "," + store.getY() + "," + store.getZ();
	}
}
