package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.ResourceBatch;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
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
		if (!player.isShiftKeyDown()) {
			describe(serverPlayer, villager, settlement.get(), data);
		}
		return InteractionResult.PASS;
	}

	static OfferResult offerFood(ServerPlayer player, Villager villager, Settlement settlement, SettlementSavedData data) {
		if (!villager.isAlive()) {
			return refuse(player, villager, "not_alive");
		}
		if (player.isSpectator()) {
			return refuse(player, villager, "spectator");
		}
		if (player.distanceToSqr(villager) > OFFER_DISTANCE_SQUARED) {
			return refuse(player, villager, "too_far");
		}
		ItemStack held = player.getMainHandItem();
		if (!isFood(held)) {
			return refuse(player, villager, "not_food");
		}
		if (!held.getComponentsPatch().isEmpty()) {
			return refuse(player, villager, "unsupported_food");
		}
		Component itemDisplayName = held.getHoverName();
		String itemName = held.getItem().getDescriptionId();

		ServerLevel level = (ServerLevel) villager.level();
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		String personId = state.residentId(villager.getUUID()).orElse(null);
		if (personId == null) {
			state.mapResident(villager.getUUID(), villager.isBaby(), data.needsOf(settlement, villager.getUUID()).satiety());
			personId = state.residentId(villager.getUUID()).orElseThrow();
			data.setDirty();
		}
		Person person = state.world().person(personId);
		long careRequestId = state.careFoodRequest(villager.getUUID(), level.getDayTime() / Level.TICKS_PER_DAY).orElse(0L);
		Map<String, String> giftContext = careRequestId > 0
			? Map.of("careRequestId", Long.toString(careRequestId)) : Map.of();
		List<Long> careCauses = careRequestId > 0 ? List.of(careRequestId) : List.of();
		boolean survival = state.survivalEnabled();
		boolean supportedFood = isSupportedFood(held, survival);
		Container stockpile = survival ? null : SettlementStockpile.getStockpile(level, data, settlement);
		if (!survival && stockpile == null) {
			return refuse(player, villager, "no_stockpile");
		}

		String playerActor = PLAYER_ACTOR_PREFIX + player.getUUID();
		int trust = person.trustIn(playerActor);
		VillagerPersonality personality = data.personalityOf(settlement, villager.getUUID());
		VillagerInteractionDecision.Decision decision = VillagerInteractionDecision.decide(
			satietyOf(data, settlement, state, villager),
			data.registeredCount(settlement),
			communalFoodUnits(state, stockpile),
			trust,
			personality.sociability(),
			supportedFood
		);
		if (!decision.accepted()) {
			state.world().recordGiftInteraction(
				personId, playerActor, false, itemName, player.getScoreboardName(), decision.reason(), trust,
				giftContext, careCauses
			);
			data.setDirty();
			return refuse(player, villager, decision.reason());
		}

		int moved;
		long requestId = careRequestId;
		long importEventId = 0;
		if (survival) {
			ResourceType resourceType = MinecraftStockpileAdapter.resourceType(held).orElseThrow();
			if (requestId == 0) {
				requestId = state.world().recordShareRequest(personId, playerActor, Math.max(1, person.hunger()));
			}
			Map<String, String> transferContext = new java.util.LinkedHashMap<>();
			transferContext.put("activity", careRequestId > 0 ? "care_food_gift" : "player_gift");
			transferContext.put("playerName", player.getScoreboardName());
			transferContext.put("villagerId", villager.getUUID().toString());
			if (careRequestId > 0) {
				transferContext.put("careRequestId", Long.toString(careRequestId));
			}
			moved = MinecraftStockpileAdapter.importToSimulation(
				player.getInventory(), state.world(), state.campOwner(), resourceType, 1,
				List.of(personId, playerActor), transferContext, List.of(requestId)
			);
			if (moved > 0) {
				importEventId = state.world().ledger().events().getLast().eventId();
			}
		} else {
			ItemStack removed = removeOneFood(player.getInventory(), held.getItem());
			if (removed.isEmpty()) {
				return refuse(player, villager, "transfer_failed");
			}
			moved = SettlementStockpile.insert(stockpile, removed);
			if (moved == 0) {
				player.getInventory().placeItemBackInInventory(removed);
				return refuse(player, villager, "stockpile_full");
			}
		}
		if (moved == 0) {
			state.world().recordGiftInteraction(
				personId, playerActor, false, itemName, player.getScoreboardName(), "transfer_failed", trust,
				giftContext, giftCauses(requestId, importEventId)
			);
			data.setDirty();
			return refuse(player, villager, "transfer_failed");
		}

		person.recordAidFrom(playerActor);
		SettlementCoordination.requestReplan(data, settlement);
		state.world().recordGiftInteraction(
			personId, playerActor, true, itemName, player.getScoreboardName(), decision.reason(), person.trustIn(playerActor),
			giftContext, giftCauses(requestId, importEventId)
		);
		data.setDirty();
		player.displayClientMessage(Component.translatable(
			"coopcraft.interaction.accepted", nameOf(villager), itemDisplayName
		), false);
		return new OfferResult(true, decision.reason(), moved);
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
		Container stockpile = SettlementStockpile.getStockpile(level, data, settlement);
		player.sendSystemMessage(Component.translatable(
			"coopcraft.interaction.inspect_food",
			communalFoodUnits(state, stockpile),
			(long) Math.max(1, data.registeredCount(settlement)) * VillagerInteractionDecision.FOOD_RESERVE_UNITS_PER_MEMBER
		));
		if (state != null) {
			long day = level.getDayTime() / Level.TICKS_PER_DAY;
			boolean careRequest = state.careFoodRequest(villager.getUUID(), day).isPresent();
			boolean foodAvailable = state.survivalEnabled()
				? SettlementSimulationService.hasFood(data, settlement)
				: SettlementNeeds.hasFood(villager.getInventory(), stockpile);
			if (careRequest && !foodAvailable) {
				player.sendSystemMessage(Component.translatable("coopcraft.interaction.care_food_request", name));
			}
		}
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

	private static List<Long> giftCauses(long requestEventId, long importEventId) {
		List<Long> causes = new java.util.ArrayList<>();
		if (requestEventId > 0) {
			causes.add(requestEventId);
		}
		if (importEventId > 0 && importEventId != requestEventId) {
			causes.add(importEventId);
		}
		return causes;
	}

	private static Component nameOf(Villager villager) {
		return villager.getCustomName() == null
			? Component.literal(VillagerNames.fullName(villager.getUUID())) : villager.getCustomName();
	}

	private static Component reason(String reason) {
		return Component.translatable("coopcraft.interaction.reason." + reason);
	}

	private static boolean isFood(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		if (Villager.FOOD_POINTS.getOrDefault(stack.getItem(), 0) > 0) {
			return true;
		}
		return MinecraftStockpileAdapter.resourceType(stack).filter(ResourceType::isFood).isPresent();
	}

	private static boolean isSupportedFood(ItemStack stack, boolean survival) {
		return survival
			? MinecraftStockpileAdapter.resourceType(stack).filter(ResourceType::isFood).isPresent()
			: Villager.FOOD_POINTS.getOrDefault(stack.getItem(), 0) > 0;
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

	private static long communalFoodUnits(SettlementSimulationState state, Container stockpile) {
		if (state != null && state.survivalEnabled()) {
			return state.world().batchesAt(state.campOwner()).stream()
				.filter(batch -> batch.resourceType().isFood())
				.mapToLong(ResourceBatch::quantity)
				.sum();
		}
		return SettlementStockpile.communalFoodPoints(stockpile);
	}

	private static ItemStack removeOneFood(Container inventory, net.minecraft.world.item.Item item) {
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(item) && stack.getComponentsPatch().isEmpty()) {
				return inventory.removeItem(slot, 1);
			}
		}
		return ItemStack.EMPTY;
	}

	record OfferResult(boolean accepted, String reason, int moved) {
	}
}
