package com.thysummer.coopcraft.civilization;

import java.util.UUID;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.Person.AgeBand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** One synchronous scene, never a reusable preview ticket. All reads are loaded-only. */
final class SettlementTeachingScene {
	final ServerLevel level;
	final SettlementSavedData data;
	final Settlement camp;
	final SettlementSimulationState state;
	final BlockPos station;
	final Object stationToken;
	final Villager teacher, learner;
	final SettlementMember teacherMember, learnerMember;

	SettlementTeachingScene(ServerLevel level, SettlementSavedData data, Settlement camp, Villager teacher, Villager learner) {
		this.level = level; this.data = data; this.camp = camp;
		this.teacher = teacher; this.learner = learner;
		state = data.simulationOf(camp).orElse(null);
		station = data.getCraftingStation(camp).orElse(null);
		stationToken = data.craftingStationBindingToken(camp);
		teacherMember = data.rawMember(teacher.getUUID()).orElse(null);
		learnerMember = data.rawMember(learner.getUUID()).orElse(null);
	}

	String status(CommandSourceStack source, ServerPlayer player, boolean mutation) {
		if (!actualPlayer(source, player, level, mutation)) return "player_unavailable";
		if (level.getServer().getLevel(level.dimension()) != level || SettlementSavedData.get(level) != data
			|| !data.canonicalSettlement(camp) || data.simulationOf(camp).orElse(null) != state) return "stale";
		String place = placeStatus(level, data, camp, player);
		if (!place.equals("ready")) return place;
		if (!java.util.Objects.equals(station, data.getCraftingStation(camp).orElse(null))
			|| stationToken != data.craftingStationBindingToken(camp)) return "stale";
		if (teacherMember != data.rawMember(teacher.getUUID()).orElse(null)
			|| learnerMember != data.rawMember(learner.getUUID()).orElse(null)) return "stale";
		if (!residentReady(level, data, camp, station, teacher) || !residentReady(level, data, camp, station, learner)) return "resident_unavailable";
		return near(teacher.distanceToSqr(learner)) ? "ready" : "too_far";
	}

	boolean authority(CommandSourceStack source, ServerPlayer player) {
		try { return status(source, player, true).equals("ready"); }
		catch (RuntimeException incompleteRead) { return false; }
	}

	static String placeStatus(ServerLevel level, SettlementSavedData data, Settlement camp, ServerPlayer player) {
		var state = data.simulationOf(camp).orElse(null);
		if (state == null || !state.survivalEnabled() || !state.skillsEnabled() || !state.normsEnabled()) return "disabled";
		long day = level.getDayTime() / Level.TICKS_PER_DAY;
		if (day < 0 || !SettlementMeetings.dayReady(day, state.lastSurvivalDay())) return "day_pending";
		BlockPos station = data.getCraftingStation(camp).orElse(null);
		if (station == null || !available(level, station) || !ConstructionWorkshop.withinWorkRange(camp.center(), station)
			|| !level.getBlockState(station).is(Blocks.CRAFTING_TABLE)) return "station_unavailable";
		if (data.protectedAreas().blocked() || data.protectedAreas().contains(station)) return "protected";
		if (!available(level, player.blockPosition()) || !bounds(level, player.getBoundingBox())
			|| !near(player.distanceToSqr(station.getCenter()))) return "too_far";
		return "ready";
	}

	static boolean residentReady(ServerLevel level, SettlementSavedData data, Settlement camp, BlockPos station, Villager villager) {
		if (station == null || villager.level() != level || level.getEntity(villager.getUUID()) != villager
			|| !residentAllowed(villager.isAlive(), villager.isRemoved(), villager.isBaby(),
				villager.getHealth(), villager.getMaxHealth(), villager.isSleeping(), villager.isTrading(),
				villager.isNoAi(), villager.isPassenger(), villager.getTarget() != null,
				villager.isOnFire() || villager.hasEffect(MobEffects.POISON) || villager.hasEffect(MobEffects.WITHER)
					|| villager.getBrain().isActive(Activity.PANIC) || villager.getBrain().isActive(Activity.REST)
					|| villager.getBrain().hasMemoryValue(MemoryModuleType.HURT_BY)
					|| villager.getBrain().hasMemoryValue(MemoryModuleType.NEAREST_HOSTILE))
			|| !available(level, villager.blockPosition()) || !bounds(level, villager.getBoundingBox())
			|| !near(villager.distanceToSqr(station.getCenter()))
			|| !SettlementMembershipClaims.belongsTo(SettlementMembershipClaims.resolve(level, villager.getUUID()), level.dimension(), data, camp)) return false;
		Person person = person(data, camp, villager.getUUID());
		return person != null && person.isAlive() && person.health() == 100
			&& (person.ageBand() == AgeBand.ADULT || person.ageBand() == AgeBand.ELDER)
			&& data.needsOf(camp, villager.getUUID()).satiety() > SettlementNeeds.HUNGRY_AT
			&& person.hunger() < 100 - SettlementNeeds.HUNGRY_AT
			&& !SettlementSimulationService.hasPendingFoodRefusal(data, camp, villager.getUUID());
	}

	static Person person(SettlementSavedData data, Settlement camp, UUID id) {
		var state = data.simulationOf(camp).orElse(null);
		if (state == null) return null;
		String mapped = state.residentId(id).orElse(null);
		return state.world().people().stream().filter(p -> p.id().equals(mapped) && p.campId().equals(state.campOwner().id())).findFirst().orElse(null);
	}

	static boolean actualPlayer(CommandSourceStack source, ServerPlayer player, ServerLevel level, boolean mutation) {
		return level.getServer().isSameThread() && source.getEntity() == player && player.level() == level
			&& source.getLevel() == level && source.getPosition().equals(player.position())
			&& playerAllowed(mutation, source.hasPermission(2), player.hasPermissions(2),
				level.getServer().getPlayerList().getPlayer(player.getUUID()) == player
					&& player.connection != null && player.connection.isAcceptingMessages(), player.isAlive(), player.isRemoved(), player.isSpectator());
	}
	static boolean playerAllowed(boolean mutation, boolean sourceOp, boolean playerOp, boolean online, boolean alive, boolean removed, boolean spectator) {
		return (!mutation || sourceOp && playerOp) && online && alive && !removed && !spectator;
	}
	static boolean residentAllowed(boolean alive, boolean removed, boolean baby, double health, double maximum,
		boolean sleeping, boolean trading, boolean noAi, boolean passenger, boolean target, boolean urgent) {
		return alive && !removed && !baby && Double.isFinite(health) && Double.isFinite(maximum) && maximum > 0 && health >= maximum
			&& !sleeping && !trading && !noAi && !passenger && !target && !urgent;
	}
	static boolean near(double squared) { return Double.isFinite(squared) && squared >= 0 && squared <= 16; }
	private static boolean available(ServerLevel level, BlockPos pos) {
		return !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos)
			&& level.isLoaded(pos) && level.isPositionEntityTicking(pos);
	}
	private static boolean bounds(ServerLevel level, AABB box) {
		var border = level.getWorldBorder();
		return box.minY >= level.getMinBuildHeight() && box.maxY <= level.getMaxBuildHeight()
			&& box.minX >= border.getMinX() && box.maxX <= border.getMaxX() && box.minZ >= border.getMinZ() && box.maxZ <= border.getMaxZ();
	}
}
