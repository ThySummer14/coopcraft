package com.thysummer.coopcraft.waystation;

import java.util.Optional;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.thysummer.coopcraft.CoopCraft;
import com.thysummer.coopcraft.civilization.Settlement;
import com.thysummer.coopcraft.civilization.SettlementSavedData;
import com.thysummer.coopcraft.civilization.SettlementHousing;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * 驿站石与驿站网络的服务端入口。
 *
 * <p>驿站石本身只负责交互；网络数据放在 {@link WaystationSavedData}，
 * 以主世界存档为唯一事实源。
 */
public final class Waystations {
	public static final Block WAYSTATION_STONE = Registry.register(
		BuiltInRegistries.BLOCK,
		CoopCraft.id("waystation_stone"),
		new WaystationBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.STONE_BRICKS).requiresCorrectToolForDrops())
	);
	public static final Item WAYSTATION_STONE_ITEM = Registry.register(
		BuiltInRegistries.ITEM,
		CoopCraft.id("waystation_stone"),
		new BlockItem(WAYSTATION_STONE, new Item.Properties())
	);

	private static final int INTERACT_RADIUS = 8;
	private static final int ACTIVATION_RADIUS = 48;
	private static final int TELEPORT_RADIUS = 8;

	private Waystations() {
	}

	public static void register() {
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS)
			.register(entries -> entries.accept(WAYSTATION_STONE_ITEM));
	}

	public static LiteralArgumentBuilder<CommandSourceStack> command() {
		return Commands.literal("waystation")
			.then(Commands.literal("list")
				.executes(context -> {
					ServerPlayer player = context.getSource().getPlayer();
					if (player == null) {
						context.getSource().sendFailure(Component.translatable("coopcraft.waystation.player_only"));
						return 0;
					}
					sendList(player, WaystationSavedData.get(context.getSource().getServer()));
					return 1;
				})
			)
			.then(Commands.literal("tp")
				.then(Commands.argument("id", IntegerArgumentType.integer(1))
					.executes(context -> teleport(
						context.getSource().getPlayerOrException(),
						IntegerArgumentType.getInteger(context, "id")
					))
				)
			)
			.then(Commands.literal("remove")
				.then(Commands.argument("id", IntegerArgumentType.integer(1))
					.executes(context -> remove(
						context.getSource().getPlayerOrException(),
						IntegerArgumentType.getInteger(context, "id")
					))
				)
			);
	}

	/**
	 * 右击驿站石：未激活则尝试绑定聚落，已激活则打开全服列表。
	 */
	static void interact(ServerPlayer player, ServerLevel level, BlockPos pos) {
		if (!level.getBlockState(pos).is(WAYSTATION_STONE)) {
			return;
		}
		if (player.distanceToSqr(pos.getCenter()) > INTERACT_RADIUS * INTERACT_RADIUS) {
			player.sendSystemMessage(Component.translatable("coopcraft.waystation.too_far"));
			return;
		}

		WaystationSavedData data = WaystationSavedData.get(level.getServer());
		String dimension = level.dimension().location().toString();
		Optional<WaystationSavedData.Entry> existing = data.findAt(dimension, pos);
		if (existing.isEmpty()) {
			if (!activate(player, level, pos, data)) {
				return;
			}
		}

		sendList(player, data);
	}

	private static boolean activate(ServerPlayer player, ServerLevel level, BlockPos pos, WaystationSavedData data) {
		SettlementSavedData settlements = SettlementSavedData.get(level);
		Optional<Settlement> found = settlements.find(pos);
		if (found.isEmpty()) {
			player.sendSystemMessage(Component.translatable("coopcraft.waystation.no_settlement", Integer.toString(ACTIVATION_RADIUS)));
			return false;
		}

		Settlement settlement = found.get();
		SettlementHousing.reconcile(level, settlements, settlement);
		if (settlements.housingCapacity(settlement) <= 0) {
			player.sendSystemMessage(Component.translatable("coopcraft.waystation.need_house"));
			return false;
		}
		if (data.isFull()) {
			player.sendSystemMessage(Component.translatable("coopcraft.waystation.full", Integer.toString(WaystationSavedData.MAX_WAYSTATIONS)));
			return false;
		}

		WaystationSavedData.Entry entry = data.add(
			stationName(data, settlement),
			level.dimension().location().toString(),
			pos,
			settlement.id(),
			settlement.name(),
			player.getUUID(),
			player.getScoreboardName()
		);
		player.sendSystemMessage(Component.translatable("coopcraft.waystation.activated", entry.name()));
		return true;
	}

	private static String stationName(WaystationSavedData data, Settlement settlement) {
		long count = data.entries().stream()
			.filter(entry -> entry.settlementId().equals(settlement.id()))
			.count();
		String base = settlement.name() + "驿站";
		return count == 0 ? base : base + " " + (count + 1);
	}

	static void unregister(ServerLevel level, BlockPos pos) {
		WaystationSavedData data = WaystationSavedData.get(level.getServer());
		data.findAt(level.dimension().location().toString(), pos).ifPresent(entry -> {
			data.remove(entry.id());
			CoopCraft.LOGGER.debug("Removed waystation {} because its block was removed", entry.name());
		});
	}

	private static int teleport(ServerPlayer player, int id) {
		WaystationSavedData data = WaystationSavedData.get(player.level().getServer());
		WaystationSavedData.Entry entry = data.find(id).orElse(null);
		if (entry == null) {
			player.sendSystemMessage(Component.translatable("coopcraft.waystation.missing"));
			return 0;
		}
		if (!data.hasNear(player.level().dimension().location().toString(), player.blockPosition(), TELEPORT_RADIUS)) {
			player.sendSystemMessage(Component.translatable("coopcraft.waystation.not_near"));
			return 0;
		}

		MinecraftServer server = player.level().getServer();
		ResourceLocation dimension = ResourceLocation.tryParse(entry.dimension());
		if (dimension == null) {
			player.sendSystemMessage(Component.translatable("coopcraft.waystation.dimension_missing"));
			return 0;
		}
		ServerLevel targetLevel = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
		if (targetLevel == null) {
			player.sendSystemMessage(Component.translatable("coopcraft.waystation.dimension_missing"));
			return 0;
		}
		if (!targetLevel.getBlockState(entry.pos()).is(WAYSTATION_STONE)) {
			data.remove(entry.id());
			player.sendSystemMessage(Component.translatable("coopcraft.waystation.missing"));
			return 0;
		}

		BlockPos target = entry.pos().above();
		if (!targetLevel.getBlockState(target).isAir() || !targetLevel.getBlockState(target.above()).isAir()) {
			player.sendSystemMessage(Component.translatable("coopcraft.waystation.blocked"));
			return 0;
		}

		player.teleportTo(targetLevel, target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
			player.getYRot(), player.getXRot());
		player.sendSystemMessage(Component.translatable("coopcraft.waystation.teleported", entry.name()));
		return 1;
	}

	private static int remove(ServerPlayer player, int id) {
		WaystationSavedData data = WaystationSavedData.get(player.level().getServer());
		WaystationSavedData.Entry entry = data.find(id).orElse(null);
		if (entry == null) {
			player.sendSystemMessage(Component.translatable("coopcraft.waystation.missing"));
			return 0;
		}
		if (!entry.owner().equals(player.getUUID()) && !player.hasPermissions(2)) {
			player.sendSystemMessage(Component.translatable("coopcraft.waystation.no_permission"));
			return 0;
		}
		data.remove(id);
		player.sendSystemMessage(Component.translatable("coopcraft.waystation.removed", entry.name()));
		return 1;
	}

	private static void sendList(ServerPlayer player, WaystationSavedData data) {
		if (data.entries().isEmpty()) {
			player.sendSystemMessage(Component.translatable("coopcraft.waystation.empty")
				.withStyle(ChatFormatting.GRAY));
			return;
		}

		player.sendSystemMessage(Component.translatable("coopcraft.waystation.list_header", data.entries().size())
			.withStyle(ChatFormatting.GOLD));

		for (WaystationSavedData.Entry entry : data.entries()) {
			Component coords = Component.literal(entry.pos().getX() + ", " + entry.pos().getY() + ", " + entry.pos().getZ())
				.withStyle(style -> style
					.withColor(ChatFormatting.AQUA)
					.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD,
						entry.pos().getX() + " " + entry.pos().getY() + " " + entry.pos().getZ()))
					.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
						Component.translatable("coopcraft.coords.copy")))
				);

			Component teleport = Component.translatable("coopcraft.waystation.teleport_button")
				.withStyle(style -> style
					.withColor(ChatFormatting.GREEN)
					.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
						"/coop waystation tp " + entry.id()))
					.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
						Component.translatable("coopcraft.waystation.teleport_hover", entry.name())))
				);

			Component remove = Component.translatable("coopcraft.waystation.remove_button")
				.withStyle(style -> style
					.withColor(ChatFormatting.DARK_RED)
					.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
						"/coop waystation remove " + entry.id()))
					.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
						Component.translatable("coopcraft.waystation.remove_hover", entry.name())))
				);

			Component line = Component.translatable(
				"coopcraft.waystation.list_entry",
				Component.literal(entry.name()).withStyle(ChatFormatting.WHITE),
				Component.literal(entry.settlementName()).withStyle(ChatFormatting.GRAY),
				dimensionName(entry.dimension()),
				coords,
				teleport,
				remove
			).withStyle(ChatFormatting.GRAY);

			player.sendSystemMessage(line);
		}
	}

	private static Component dimensionName(String dimension) {
		return switch (dimension) {
			case "minecraft:overworld" -> Component.translatable("coopcraft.dimension.overworld");
			case "minecraft:the_nether" -> Component.translatable("coopcraft.dimension.the_nether");
			case "minecraft:the_end" -> Component.translatable("coopcraft.dimension.the_end");
			default -> Component.literal(dimension);
		};
	}
}
