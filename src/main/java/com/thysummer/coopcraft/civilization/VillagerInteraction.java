package com.thysummer.coopcraft.civilization;

import java.util.Optional;

import com.thysummer.coopcraft.civilization.simulation.ResourceType;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;

public final class VillagerInteraction {
	private static final double OFFER_DISTANCE_SQUARED = 4.0 * 4.0;
	private static final String PLAYER_ACTOR_PREFIX = "minecraft-player:";

	private VillagerInteraction() {
	}

	public static InteractionResult onUseEntity(
		Player player,
		Level level,
		InteractionHand hand,
		Entity entity,
		EntityHitResult hitResult
	) {
		if (hand != InteractionHand.MAIN_HAND || !(entity instanceof Villager villager)) {
			return InteractionResult.PASS;
		}
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		SettlementSavedData data = SettlementSavedData.get(serverLevel);
		Optional<Settlement> settlement = data.findSettlementForVillager(villager.getUUID());
		if (settlement.isEmpty()) {
			return InteractionResult.PASS;
		}
		if (player.isShiftKeyDown() && isFood(serverPlayer.getMainHandItem())) {
			offerFood(serverPlayer, villager, settlement.get(), data);
			return InteractionResult.SUCCESS;
		}
		if (player.isShiftKeyDown() && serverPlayer.getMainHandItem().isEmpty()) {
			String result = VillagerWorkGoal.inviteToMeeting(serverPlayer, villager, data, settlement.get());
			serverPlayer.sendSystemMessage(Component.translatable("coopcraft.interaction.meeting." + result, nameOf(villager)));
			return InteractionResult.SUCCESS;
		}
		if (!player.isShiftKeyDown()) {
			describe(serverPlayer, villager, settlement.get(), data);
		}
		return InteractionResult.PASS;
	}

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
		ServerLevel level = (ServerLevel) villager.level();
		Component name = nameOf(villager);
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		int satiety = satietyOf(data, settlement, state, villager);
		player.sendSystemMessage(Component.translatable(
			"coopcraft.interaction.inspect",
			name,
			Component.translatable("coopcraft.work_role." + LaborAllocator.roleOf(level, data, settlement, villager.getUUID()).id()),
			satiety
		));
		VillagerWorkGoal.WorkStatus status = VillagerWorkGoal.statusOf(villager);
		Component action = Component.translatable("coopcraft.interaction.action." + status.action());
		if (status.blocker() != null) {
			player.sendSystemMessage(Component.translatable(
				"coopcraft.interaction.inspect_action_blocked",
				action,
				Component.translatable("coopcraft.interaction.blocker." + status.blocker())
			));
		} else if (status.target() != null) {
			player.sendSystemMessage(Component.translatable(
				"coopcraft.interaction.inspect_action_target", action, status.target().toShortString()
			));
		} else {
			player.sendSystemMessage(Component.translatable("coopcraft.interaction.inspect_action", action));
		}
		player.sendSystemMessage(Component.translatable("coopcraft.interaction.meeting.inspect_" + VillagerWorkGoal.invitationStatus(villager)));
		if (state != null && state.survivalEnabled() && state.normsEnabled()) {
			String resident = state.residentId(villager.getUUID()).orElse(null);
			var open = state.world().proposalsForCamp(state.campOwner().id()).stream()
				.filter(proposal -> proposal.status() == com.thysummer.coopcraft.civilization.simulation.NormProposal.Status.OPEN).toList();
			boolean known = resident != null && open.stream().anyMatch(proposal -> proposal.knownBy().contains(resident));
			player.sendSystemMessage(Component.translatable("coopcraft.interaction.meeting." +
				(open.isEmpty() ? "agenda_none" : known ? "agenda_known" : "agenda_unknown")));
		}
		player.sendSystemMessage(Component.translatable("coopcraft.interaction.meeting.hint"));
		Container stockpile = SettlementStockpile.getStockpile(level, data, settlement);
		player.sendSystemMessage(SettlementCommands.reservesMessage(settlement.name(),
			SettlementReserves.inspect(data, settlement, stockpile)));
		if (isFood(player.getMainHandItem())) {
			player.sendSystemMessage(Component.translatable("coopcraft.interaction.inspect_hint"));
		}
	}

	private static OfferResult refuse(ServerPlayer player, Villager villager, String reason) {
		player.displayClientMessage(Component.translatable(
			"coopcraft.interaction.refused", nameOf(villager), reason(reason)
		), false);
		return new OfferResult(false, reason, 0);
	}

	private static Component nameOf(Villager villager) {
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
