package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.NormSystem;
import com.thysummer.coopcraft.civilization.simulation.Person;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Explicit, server-thread-only meetings. Reading this service never creates simulation state. */
public final class SettlementMeetings {
	private SettlementMeetings() {}

	public record Result(String status, long coreDay, long lastMeetingDay, String location,
		NormSystem.MeetingResult discussion, SettlementMeetingFeedback.Snapshot feedback) {
		public Result(String status, long coreDay, long lastMeetingDay, String location, NormSystem.MeetingResult discussion) {
			this(status, coreDay, lastMeetingDay, location, discussion, SettlementMeetingFeedback.inspect(null, discussion));
		}
		public boolean held() { return status.equals("held"); }
	}

	/** The actual adapter and unit tests use the same complete attendance predicate. */
	record ResidentPresence(boolean registered, boolean mapped, boolean alive, boolean adult,
		boolean loaded, boolean entityTicking, boolean sameLevel, boolean active, double distanceSquared) {
		boolean eligible() {
			return registered && mapped && alive && adult && loaded && entityTicking && sameLevel && active
				&& Double.isFinite(distanceSquared) && distanceSquared >= 0 && distanceSquared <= 36;
		}
	}

	record OrganizerPresence(boolean alive, boolean spectator, boolean sameLevel, double distanceSquared) {
		boolean eligible() {
			return alive && !spectator && sameLevel && Double.isFinite(distanceSquared)
				&& distanceSquared >= 0 && distanceSquared <= 16;
		}
	}

	record Inspection(Result result, SettlementSimulationState state, List<Person> active, List<Person> present,
		NormSystem.MeetingReview review) {}

	/** Retained only by the server-side dialog session; clients cannot supply the stamp. */
	static final class Preview {
		private final Result result;
		private final ReviewStamp stamp;
		private Preview(Result result, ReviewStamp stamp) { this.result = result; this.stamp = stamp; }
		Result result() { return result; }
	}

	record SceneIdentity(Object level, Object organizer, Object binding, BlockPos store,
		Object container, Object partner, List<Object> residents, long gameDay) {
		SceneIdentity { residents = List.copyOf(residents); }
	}
	private record ReviewStamp(SettlementSavedData data, Settlement settlement, SettlementSimulationState state,
		long modeVersion, long lastSurvivalDay, SceneIdentity scene, List<Person> present, NormSystem.MeetingReview review,
		SettlementMeetingFeedback.Agenda agenda) {}

	static Preview preview(ServerLevel level, SettlementSavedData data, Settlement settlement, ServerPlayer player) {
		Inspection inspection = inspectScene(level, data, settlement, player);
		return preview(data, settlement, inspection, sceneIdentity(level, data, settlement, player, inspection));
	}

	static Result holdReviewed(ServerLevel level, SettlementSavedData data, Settlement settlement,
		ServerPlayer player, Preview expected) {
		Inspection inspection = inspectScene(level, data, settlement, player);
		return holdReviewed(data, settlement, inspection,
			sceneIdentity(level, data, settlement, player, inspection), expected);
	}

	// Production and tests share the pre-mutation review gate and actual meeting execution.
	static Preview preview(SettlementSavedData data, Settlement settlement, Inspection inspection, SceneIdentity scene) {
		if (!inspection.result().status().equals("ready")) return new Preview(inspection.result(), null);
		SettlementSimulationState state = inspection.state();
		return new Preview(inspection.result(), new ReviewStamp(data, settlement, state, state.foodRequestModeVersion(),
			state.lastSurvivalDay(), scene, List.copyOf(inspection.present()), inspection.review(), inspection.result().feedback().agenda().orElse(null)));
	}

	static Result holdReviewed(SettlementSavedData data, Settlement settlement, Inspection inspection,
		SceneIdentity scene, Preview expected) {
		if (!inspection.result().status().equals("ready")) return inspection.result();
		Preview current = preview(data, settlement, inspection, scene);
		if (expected == null || expected.stamp == null || !expected.stamp.equals(current.stamp)) {
			return blocked("review_changed", inspection.state(), inspection.result().location()).result();
		}
		return execute(data, inspection);
	}

	/** Membership records have value equality, but remove/rejoin must invalidate a prior review. */
	static record ResidentIdentity(SettlementMember member, Object entity) {
		@Override public boolean equals(Object other) {
			return other instanceof ResidentIdentity identity && member == identity.member && entity == identity.entity;
		}
		@Override public int hashCode() {
			return 31 * System.identityHashCode(member) + System.identityHashCode(entity);
		}
	}

	private static SceneIdentity sceneIdentity(ServerLevel level, SettlementSavedData data, Settlement settlement,
		ServerPlayer player, Inspection inspection) {
		if (!inspection.result().status().equals("ready")) return null;
		BlockPos store = data.getStockpile(settlement).orElseThrow();
		var block = level.getBlockState(store);
		Object partner = block.getBlock() instanceof ChestBlock && block.getValue(ChestBlock.TYPE) != ChestType.SINGLE
			? level.getBlockEntity(store.relative(ChestBlock.getConnectedDirection(block))) : null;
		List<Object> entities = data.membersOf(settlement).stream()
			.map(member -> (Object) new ResidentIdentity(member, level.getEntity(member.villagerId()))).toList();
		return new SceneIdentity(level, player, data.stockpileBindingToken(settlement), store,
			level.getBlockEntity(store), partner, entities, level.getDayTime() / Level.TICKS_PER_DAY);
	}

	public static Result inspect(ServerLevel level, SettlementSavedData data, Settlement settlement, ServerPlayer player) {
		return inspectScene(level, data, settlement, player).result();
	}

	public static Result hold(ServerLevel level, SettlementSavedData data, Settlement settlement, ServerPlayer player) {
		Inspection inspection = inspectScene(level, data, settlement, player);
		if (!inspection.result().status().equals("ready")) return inspection.result();
		return execute(data, inspection);
	}

	private static Result execute(SettlementSavedData data, Inspection inspection) {
		SettlementSimulationState state = inspection.state();
		// The command, entity lookup and deliberation are synchronous on the same server thread.
		NormSystem.MeetingResult discussion = state.norms().holdCommonStoreMeeting(inspection.active(),
			inspection.present(), state.campOwner().id(), inspection.result().location());
		if (discussion.held()) data.setDirty();
		return new Result(discussion.status(), state.world().day(), state.norms().lastMeetingDay(state.campOwner().id()),
			inspection.result().location(), discussion, SettlementMeetingFeedback.inspect(state, discussion));
	}

	static boolean dayReady(long gameDay, long lastSurvivalDay) { return gameDay <= lastSurvivalDay; }

	private static Inspection inspectScene(ServerLevel level, SettlementSavedData data, Settlement settlement, ServerPlayer player) {
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Meetings require the server thread");
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (!data.all().contains(settlement) || state == null || !state.survivalEnabled() || !state.normsEnabled()) {
			return blocked("disabled", state, "");
		}
		if (!dayReady(level.getDayTime() / Level.TICKS_PER_DAY, state.lastSurvivalDay())) {
			return blocked("day_pending", state, "");
		}
		BlockPos store = data.getStockpile(settlement).orElse(null);
		if (store == null || !level.isLoaded(store) || !level.isPositionEntityTicking(store)
			|| !(level.getBlockState(store).is(Blocks.CHEST) || level.getBlockState(store).is(Blocks.BARREL))
			|| !storeHalvesTicking(level, store) || SettlementStockpile.getStockpile(level, data, settlement) == null) {
			return blocked("store_unavailable", state, "");
		}
		String location = level.dimension().location() + " " + store.getX() + "," + store.getY() + "," + store.getZ();
		if (location.length() > 96) return blocked("store_unavailable", state, "");
		if (player == null || !new OrganizerPresence(player.isAlive() && !player.isRemoved(), player.isSpectator(),
			player.level() == level, player.distanceToSqr(store.getCenter())).eligible()) {
			return blocked("organizer_unavailable", state, location);
		}
		Map<UUID, Person> active = SettlementSimulationService.activeResidents(level, data, settlement, state);
		List<Person> present = active.entrySet().stream().filter(entry -> {
			if (!(level.getEntity(entry.getKey()) instanceof Villager villager)) return false;
			Person person = entry.getValue();
			return new ResidentPresence(data.isMember(settlement, entry.getKey()),
				state.residentId(entry.getKey()).filter(person.id()::equals).isPresent(),
				villager.isAlive() && !villager.isRemoved() && person.isAlive(), !villager.isBaby() && person.canMakeDecisions(),
				level.isLoaded(villager.blockPosition()), level.isPositionEntityTicking(villager.blockPosition()),
				villager.level() == level && person.campId().equals(state.campOwner().id()), active.containsKey(entry.getKey()),
				villager.distanceToSqr(store.getCenter())).eligible();
		}).map(Map.Entry::getValue).toList();
		List<Person> population = List.copyOf(active.values());
		NormSystem.MeetingReview review = state.norms().reviewCommonStoreMeeting(population, present, state.campOwner().id());
		NormSystem.MeetingResult discussion = review.result();
		Result result = new Result(discussion.status(), state.world().day(), state.norms().lastMeetingDay(state.campOwner().id()),
			location, discussion, SettlementMeetingFeedback.inspect(state, discussion));
		return new Inspection(result, state, population, present, review);
	}

	private static boolean storeHalvesTicking(ServerLevel level, BlockPos store) {
		var container = level.getBlockEntity(store);
		if (container == null || container.isRemoved() || container.getLevel() != level) return false;
		var block = level.getBlockState(store);
		if (!(block.getBlock() instanceof ChestBlock) || block.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return true;
		BlockPos partner = store.relative(ChestBlock.getConnectedDirection(block));
		if (!level.isLoaded(partner) || !level.isPositionEntityTicking(partner)) return false;
		var partnerContainer = level.getBlockEntity(partner);
		return partnerContainer != null && !partnerContainer.isRemoved() && partnerContainer.getLevel() == level;
	}

	private static Inspection blocked(String status, SettlementSimulationState state, String location) {
		long day = state == null ? 0 : state.world().day();
		long last = state == null ? Long.MIN_VALUE : state.norms().lastMeetingDay(state.campOwner().id());
		var discussion = new NormSystem.MeetingResult(status, "", 0, 0, 0, "");
		return new Inspection(new Result(status, day, last, location, discussion,
			SettlementMeetingFeedback.inspect(state, discussion)), state, List.of(), List.of(), null);
	}
}
