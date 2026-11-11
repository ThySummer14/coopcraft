package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Automatic wood/stone jobs require both explicit area authority and a current candidate. */
final class SettlementMaterialGathering {
	private SettlementMaterialGathering() {}
	interface Probe {
		SettlementSavedData currentData();
		boolean activeAdult(); // Includes current entity identity and unique dimension membership.
		boolean inBounds(BlockPos pos);
		boolean loaded(BlockPos pos);
		boolean ticking(BlockPos pos);
		double distanceSquared(BlockPos pos);
		BlockState state(BlockPos pos);
	}
	record Choice(SettlementResourceObservations.Observation observation, BlockState expected) {
		BlockPos position() { return observation.position(); }
	}
	static final class Ticket {
		final SettlementSavedData data;
		final Settlement camp;
		final UUID actor;
		final Object member;
		final SettlementGatheringAreas.Area area;
		final Choice choice;
		boolean cancelled;
		Ticket(SettlementSavedData data, Settlement camp, UUID actor, Choice choice) {
			this.data = data; this.camp = camp; this.actor = actor; this.choice = choice;
			member = data.memberIdentity(actor); area = data.gatheringAreaOf(camp).orElse(null);
		}
	}
	private static boolean actorAllowed(SettlementSavedData data, Settlement camp, UUID actor, Probe probe) {
		if (data == null || camp == null || actor == null || probe.currentData() != data
			|| !data.canonicalSettlement(camp) || !data.isMember(camp, actor) || !probe.activeAdult()) return false;
		var simulation = data.simulationOf(camp).orElse(null);
		if (simulation != null && simulation.survivalEnabled()) {
			String id = simulation.residentId(actor).orElse(null);
			if (id == null) return false;
			try {
				var person = simulation.world().person(id);
				if (!person.isAlive() || !person.campId().equals(simulation.campOwner().id())) return false;
			} catch (IllegalArgumentException missingPerson) { return false; }
		}
		return SettlementSimulationService.hasGatherTime(data, camp, actor);
	}
	private static boolean allowed(SettlementSavedData data, Settlement camp, UUID actor,
		SettlementResourceObservations.Observation observation, Probe probe) {
		BlockPos pos = observation.position();
		return observation.state() == SettlementResourceObservations.State.CANDIDATE
			&& data.canGatherMaterial(camp, pos, observation.kind()) && !data.resourceObservationsBlocked()
			&& actorAllowed(data, camp, actor, probe) && probe.distanceSquared(pos) <= 144
			&& !data.isResidentModificationBlocked(pos) && probe.inBounds(pos) && probe.loaded(pos) && probe.ticking(pos);
	}
	static boolean matches(SettlementResourceObservations.Kind kind, BlockState state) {
		return kind == SettlementResourceObservations.Kind.OAK_LOG ? state.is(Blocks.OAK_LOG)
			: kind == SettlementResourceObservations.Kind.STONE && (state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE));
	}
	/** Pure selection over at most 64 existing records. No discovery scans, reservations or inference of natural origin. */
	static List<Choice> candidates(SettlementSavedData data, Settlement camp, UUID actor, Probe probe, long now) {
		if (!actorAllowed(data, camp, actor, probe) || data.gatheringAreaOf(camp).isEmpty()) return List.of();
		List<Choice> result = new ArrayList<>();
		for (var observation : data.resourceObservationsOf(camp).stream().limit(SettlementResourceObservations.MAX_PER_SETTLEMENT).toList()) {
			if (!allowed(data, camp, actor, observation, probe) || !data.materialLeases().available(observation.position(), actor, now)) continue;
			BlockState state = probe.state(observation.position());
			if (matches(observation.kind(), state)) result.add(new Choice(observation, state));
		}
		result.sort(Comparator.comparingDouble(choice -> probe.distanceSquared(choice.position())));
		return List.copyOf(result);
	}
	static Ticket prepare(SettlementSavedData data, Settlement camp, UUID actor, Choice choice, Probe probe) {
		if (data == null || camp == null || actor == null || choice == null) return null;
		Ticket ticket = new Ticket(data, camp, actor, choice);
		return valid(ticket, probe) ? ticket : null;
	}
	private static boolean identityAllowed(Ticket ticket, Probe probe) {
		return ticket.member != null && ticket.data.memberIdentity(ticket.actor) == ticket.member
			&& ticket.area != null && ticket.data.gatheringAreaOf(ticket.camp).orElse(null) == ticket.area
			&& ticket.data.resourceObservationsOf(ticket.camp).stream().anyMatch(observation -> observation == ticket.choice.observation())
			&& allowed(ticket.data, ticket.camp, ticket.actor, ticket.choice.observation(), probe);
	}
	static boolean valid(Ticket ticket, Probe probe) {
		if (ticket == null || ticket.cancelled) return false;
		boolean valid = identityAllowed(ticket, probe)
			&& ticket.choice.expected().equals(probe.state(ticket.choice.position()))
			&& identityAllowed(ticket, probe); // Read callbacks cannot revoke then slip through the commit boundary.
		if (!valid) ticket.cancelled = true;
		return valid;
	}
	/** Standard non-reentrant server-thread SimpleContainer boundary; not crash atomic and no neighbor-update guarantee. */
	static int harvest(Ticket ticket, Probe probe, SimpleContainer inventory,
		Function<BlockState, List<ItemStack>> loot, Predicate<BlockState> destroy) {
		if (!valid(ticket, probe)) return 0;
		List<ItemStack> drops = loot.apply(ticket.choice.expected()).stream().map(ItemStack::copy).toList();
		if (!valid(ticket, probe)) return 0;
		if (!SettlementHarvestCommit.commit(inventory, drops, () -> valid(ticket, probe)
			&& destroy.test(ticket.choice.expected()))) return 0;
		ticket.cancelled = true; // Never replay even if destroy callback retains an identical block state.
		int count = 0;
		for (ItemStack drop : drops) {
			if (drop.isEmpty()) continue;
			SettlementSimulationService.recordCarriedProduction(ticket.data, ticket.camp, ticket.actor, inventory,
				drop.getItem(), drop.getCount(), "gather");
			count += drop.getCount();
		}
		return count;
	}
	static Probe probe(ServerLevel level, SettlementSavedData data, Settlement camp, Villager villager) {
		return probe(level, data, camp, villager, () -> true);
	}
	private static Probe probe(ServerLevel level, SettlementSavedData data, Settlement camp, Villager villager, BooleanSupplier coordinated) {
		SettlementObservationClaims claims = SettlementObservationClaims.capture(level, data);
		SettlementMember member = data.rawMember(villager.getUUID()).orElse(null);
		return new Probe() {
			public SettlementSavedData currentData() { return SettlementSavedData.get(level); }
			public boolean activeAdult() {
				return coordinated.getAsBoolean() && villager.level() == level && villager.isAlive() && !villager.isRemoved() && !villager.isBaby()
					&& level.getEntity(villager.getUUID()) == villager && inBounds(villager.blockPosition())
					&& loaded(villager.blockPosition()) && ticking(villager.blockPosition())
					&& claims.unique(data, camp, member);
			}
			public boolean inBounds(BlockPos pos) { return !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos); }
			public boolean loaded(BlockPos pos) { return level.isLoaded(pos); }
			public boolean ticking(BlockPos pos) { return level.isPositionEntityTicking(pos); }
			public double distanceSquared(BlockPos pos) { return villager.distanceToSqr(pos.getCenter()); }
			public BlockState state(BlockPos pos) { return level.getBlockState(pos); }
		};
	}
	static int harvest(ServerLevel level, Ticket ticket, Villager villager, SettlementMaterialLeases registry, SettlementMaterialLeases.Lease lease) {
		if (ticket == null || !villager.getUUID().equals(ticket.actor)) return 0;
		Probe probe = probe(level, ticket.data, ticket.camp, villager, () -> registry != null
			&& registry.valid(lease, SettlementSavedData.get(level), ticket.camp, villager.getUUID(), ticket.choice.position(), level.getGameTime()));
		BlockPos pos = ticket.choice.position();
		return harvest(ticket, probe, villager.getInventory(), state -> Block.getDrops(state, level, pos, level.getBlockEntity(pos)),
			state -> villager.distanceToSqr(pos.getCenter()) <= 9 && valid(ticket, probe) && level.destroyBlock(pos, false, villager));
	}
}
