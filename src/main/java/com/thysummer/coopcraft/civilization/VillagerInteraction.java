package com.thysummer.coopcraft.civilization;

import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;

public final class VillagerInteraction {
	private static final double OFFER_DISTANCE_SQUARED = 4.0 * 4.0;
	private static final String PLAYER_ACTOR_PREFIX = "minecraft-player:";

	private VillagerInteraction() {
	}

	/** Compatibility callback: Mob.interact must handle important items before our mobInteract hook. */
	public static InteractionResult onUseEntity(
		Player player, Level level, InteractionHand hand, Entity entity, EntityHitResult hitResult
	) {
		return InteractionResult.PASS;
	}

	/** Server-only entry, called after vanilla important-item interactions (name tags/spawn eggs).
	 * Vanilla interaction packets enforce reach; gift/invitation services retain their distance checks. */
	public static InteractionResult onMobInteract(Villager villager, Player player, InteractionHand hand) {
		if (!(villager.level() instanceof ServerLevel level)) return InteractionResult.PASS;
		var membership = SettlementMembershipClaims.resolve(level, villager.getUUID());
		boolean eligible = player instanceof ServerPlayer && player.level() == level
			&& level.getEntity(villager.getUUID()) == villager
			&& villager.isAlive() && !villager.isRemoved() && player.isAlive() && !player.isRemoved()
			&& !player.isSpectator() && !villager.isSleeping() && !villager.isTrading();
		ItemStack held = player.getItemInHand(hand);
		Route route = route(membership, level.dimension(), hand, held.is(Items.VILLAGER_SPAWN_EGG),
			eligible, player.isShiftKeyDown(), isFood(held), held.isEmpty());
		if (route == Route.PASS) return InteractionResult.PASS;
		if (route == Route.SILENT) return InteractionResult.CONSUME;
		ServerPlayer serverPlayer = (ServerPlayer) player;
		if (route == Route.ORPHAN || route == Route.CONFLICT || route == Route.UNAVAILABLE) {
			serverPlayer.sendSystemMessage(diagnostic(route, nameOf(villager)));
			return InteractionResult.CONSUME;
		}
		var claim = membership.uniqueClaim().orElseThrow();
		if (route == Route.AWAY) {
			serverPlayer.sendSystemMessage(awayMessage(nameOf(villager), claim, level.dimension()));
			return InteractionResult.CONSUME;
		}
		// Only a unique claim in this actual dimension may reach local world/inventory operations.
		switch (route) {
			case GIFT -> offerFood(serverPlayer, villager, claim.settlement(), claim.data());
			case INVITE -> {
				String result = VillagerWorkGoal.inviteToMeeting(serverPlayer, villager, claim.data(), claim.settlement());
				serverPlayer.sendSystemMessage(Component.translatable("coopcraft.interaction.meeting." + result, nameOf(villager)));
			}
			case DESCRIBE -> {
				if (!ResidentDialogService.open(serverPlayer, villager, claim.data(), claim.settlement())) {
					describe(serverPlayer, villager, claim.settlement(), claim.data());
				}
			}
			default -> throw new IllegalStateException("Unexpected resident interaction route: " + route);
		}
		return InteractionResult.CONSUME;
	}

	/** Identity is independent of hand, distance, stage, survival and production settings. */
	public static boolean blocksVanillaTrade(SettlementMembershipClaims.Resolution membership) {
		return membership.status() != SettlementMembershipClaims.Status.NONE;
	}

	/** Shared production decision seam. PASS is only safe for nonmembers or vanilla's egg exception. */
	static Route route(SettlementMembershipClaims.Resolution membership, ResourceKey<Level> currentDimension,
		InteractionHand hand, boolean villagerSpawnEgg, boolean eligible, boolean sneaking, boolean food, boolean empty) {
		if (!blocksVanillaTrade(membership) || villagerSpawnEgg) return Route.PASS;
		if (hand != InteractionHand.MAIN_HAND || !eligible) return Route.SILENT;
		return switch (membership.status()) {
			case NONE -> Route.PASS;
			case ORPHAN -> Route.ORPHAN;
			case CONFLICT -> Route.CONFLICT;
			case UNAVAILABLE -> Route.UNAVAILABLE;
			case UNIQUE -> membership.uniqueClaim().map(claim -> {
				if (!claim.dimension().equals(currentDimension)) return Route.AWAY;
				if (!sneaking) return Route.DESCRIBE;
				if (food) return Route.GIFT;
				return empty ? Route.INVITE : Route.SILENT;
			}).orElse(Route.UNAVAILABLE);
		};
	}

	static Component diagnostic(Route route, Component name) {
		String key = switch (route) {
			case ORPHAN -> "orphan";
			case CONFLICT -> "conflict";
			case UNAVAILABLE -> "unavailable";
			default -> throw new IllegalArgumentException("Not a membership diagnostic: " + route);
		};
		return Component.translatable("coopcraft.interaction.membership." + key, name);
	}

	static Component awayMessage(Component name, SettlementMembershipClaims.Claim claim, ResourceKey<Level> currentDimension) {
		return Component.translatable("coopcraft.interaction.membership.away", name, claim.settlement().name(),
			claim.dimension().location().toString(), currentDimension.location().toString());
	}

	enum Route { PASS, SILENT, DESCRIBE, GIFT, INVITE, AWAY, ORPHAN, CONFLICT, UNAVAILABLE }

	static OfferResult offerFood(ServerPlayer player, Villager villager, Settlement settlement, SettlementSavedData data) {
		if (!villager.isAlive() || !player.isAlive()) {
			return refuse(player, villager, "not_alive");
		}
		if (player.isSpectator()) {
			return refuse(player, villager, "spectator");
		}
		if (player.distanceToSqr(villager) > OFFER_DISTANCE_SQUARED) {
			return refuse(player, villager, "too_far");
		}
		if (!(villager.level() instanceof ServerLevel level) || player.level() != level) {
			return refuse(player, villager, "wrong_level");
		}
		if (!data.isMember(settlement, villager.getUUID())) return refuse(player, villager, "not_member");
		int slot = player.getInventory().selected;
		ItemStack held = player.getMainHandItem().copy();
		Container stockpile = SettlementSimulationService.isSurvivalEnabled(data, settlement)
			? null : SettlementStockpile.getStockpile(level, data, settlement);
		OfferResult result = VillagerGiftService.offer(data, settlement, villager.getUUID(), villager.isBaby(),
			PLAYER_ACTOR_PREFIX + player.getUUID(), player.getScoreboardName(), player.getInventory(), slot,
			() -> player.getInventory().selected, held, stockpile);
		if (!result.accepted()) return refuse(player, villager, result.reason());
		player.displayClientMessage(Component.translatable(
			"coopcraft.interaction.accepted", nameOf(villager), held.getHoverName(), reason(result.reason())), false);
		return result;
	}

	private static void describe(ServerPlayer player, Villager villager, Settlement settlement, SettlementSavedData data) {
		inspectLines(player, villager, settlement, data).forEach(player::sendSystemMessage);
	}

	/** Shared read-only description for chat compatibility and the resident window. */
	static List<Component> inspectLines(ServerPlayer player, Villager villager, Settlement settlement, SettlementSavedData data) {
		List<Component> lines = new ArrayList<>();
		ServerLevel level = (ServerLevel) villager.level();
		Component name = Component.literal(nameOf(villager).getString());
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		int satiety = satietyOf(data, settlement, state, villager);
		lines.add(Component.translatable(
			"coopcraft.interaction.inspect",
			name,
			Component.translatable("coopcraft.work_role." + LaborAllocator.roleOf(level, data, settlement, villager.getUUID()).id()),
			satiety
		));
		VillagerWorkGoal.WorkStatus status = VillagerWorkGoal.statusOf(villager);
		Component action = Component.translatable("coopcraft.interaction.action." + status.action());
		if (status.blocker() != null) {
			lines.add(Component.translatable(
				"coopcraft.interaction.inspect_action_blocked",
				action,
				Component.translatable("coopcraft.interaction.blocker." + status.blocker())
			));
		} else if (status.target() != null) {
			lines.add(Component.translatable(
				"coopcraft.interaction.inspect_action_target", action, status.target().toShortString()
			));
		} else {
			lines.add(Component.translatable("coopcraft.interaction.inspect_action", action));
		}
		lines.add(Component.translatable("coopcraft.interaction.meeting.inspect_" + VillagerWorkGoal.invitationStatus(villager)));
		VillagerMeetingInquiry.inspect(data, settlement, villager.getUUID(), level.getDayTime() / Level.TICKS_PER_DAY)
			.forEach(lines::add);
		lines.add(Component.translatable("coopcraft.interaction.meeting.hint"));
		Container stockpile = SettlementStockpile.getStockpile(level, data, settlement);
		lines.add(SettlementCommands.reservesMessage(settlement.name(),
			SettlementReserves.inspect(data, settlement, stockpile)));
		if (isFood(player.getMainHandItem())) {
			lines.add(Component.translatable("coopcraft.interaction.inspect_hint"));
		}
		return List.copyOf(lines);
	}

	private static OfferResult refuse(ServerPlayer player, Villager villager, String reason) {
		player.displayClientMessage(Component.translatable(
			"coopcraft.interaction.refused", nameOf(villager), reason(reason)
		), false);
		return new OfferResult(false, reason, 0);
	}

	static Component nameOf(Villager villager) {
		return villager.getCustomName() == null
			? Component.literal(VillagerNames.fullName(villager.getUUID())) : villager.getCustomName();
	}

	private static Component reason(String reason) {
		return Component.translatable("coopcraft.interaction.reason." + reason);
	}

	static boolean isFood(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		if (SettlementFood.points(stack.getItem()) > 0) {
			return true;
		}
		return MinecraftStockpileAdapter.resourceType(stack).filter(ResourceType::isFood).isPresent();
	}

	static boolean isSupportedFood(ItemStack stack, boolean survival) {
		return survival
			? MinecraftStockpileAdapter.resourceType(stack).filter(ResourceType::isFood).isPresent()
			: SettlementFood.points(stack.getItem()) > 0;
	}

	private static int satietyOf(
		SettlementSavedData data,
		Settlement settlement,
		SettlementSimulationState state,
		Villager villager
	) {
		if (state != null && state.survivalEnabled()) {
			return state.residentId(villager.getUUID())
				.map(personId -> 100 - state.world().person(personId).hunger())
				.orElseGet(() -> data.needsOf(settlement, villager.getUUID()).satiety());
		}
		return data.needsOf(settlement, villager.getUUID()).satiety();
	}

	record OfferResult(boolean accepted, String reason, int moved) {
	}
}
