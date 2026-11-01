package com.thysummer.coopcraft.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * 进入新群系（或跨维度）时，在屏幕上方淡入一段地名。
 *
 * <p>和「旅行者称号」类模组是同一类体验，但只读取原版数据，不发包、不存档：
 * 群系名直接取 {@code biome.<namespace>.<path>} 的本地化键，维度名取
 * {@code dimension.<namespace>.<path>}，原版与数据包添加的群系都能显示中文。
 *
 * <p>防刷屏：同一群系不重复弹，且两次提示至少间隔 {@link #COOLDOWN_MS} 毫秒。
 */
public final class BiomeTitles {
	private static final int CHECK_INTERVAL_TICKS = 10;
	private static final int FADE_TICKS = 10;
	private static final int HOLD_TICKS = 40;
	private static final long COOLDOWN_MS = 4_000L;
	private static final int TITLE_COLOR = 0xFFFFF4D6;
	private static final int SUBTITLE_COLOR = 0xFFB8C4D8;

	private static ResourceLocation currentBiome;
	private static ResourceLocation currentDimension;

	private static String title = "";
	private static String subtitle = "";
	private static int age;
	private static int life;
	private static int tickCounter;
	private static long lastShownAt;

	private BiomeTitles() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(BiomeTitles::tick);
		HudRenderCallback.EVENT.register((graphics, tickCounter) -> render(graphics));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
	}

	private static void reset() {
		currentBiome = null;
		currentDimension = null;
		title = "";
		subtitle = "";
		life = 0;
		tickCounter = 0;
	}

	private static void tick(Minecraft client) {
		if (client.level == null || client.player == null) {
			reset();
			return;
		}

		if (life > 0) {
			age++;
			if (age >= life) {
				life = 0;
			}
		}

		if (++tickCounter % CHECK_INTERVAL_TICKS != 0) {
			return;
		}

		ResourceKey<Level> dimension = client.level.dimension();
		ResourceLocation dimensionId = dimension.location();
		boolean dimensionChanged = currentDimension != null && !dimensionId.equals(currentDimension);

		Holder<Biome> holder = client.level.getBiome(client.player.blockPosition());
		ResourceLocation biomeId = holder.unwrapKey().map(key -> key.location()).orElse(null);

		if (biomeId == null) {
			return;
		}

		boolean biomeChanged = !biomeId.equals(currentBiome);
		currentDimension = dimensionId;
		currentBiome = biomeId;

		if (!biomeChanged && !dimensionChanged) {
			return;
		}

		long now = System.currentTimeMillis();
		if (lastShownAt != 0L && now - lastShownAt < COOLDOWN_MS) {
			return;
		}
		lastShownAt = now;

		if (dimensionChanged) {
			show(dimensionName(dimensionId), translate("dimension." + dimensionId.getNamespace() + "." + dimensionId.getPath()));
		} else {
			show(translate("biome." + biomeId.getNamespace() + "." + biomeId.getPath()), null);
		}
	}

	private static void show(String nextTitle, String nextSubtitle) {
		title = nextTitle;
		subtitle = nextSubtitle == null ? "" : nextSubtitle;
		age = 0;
		life = FADE_TICKS + HOLD_TICKS + FADE_TICKS;
	}

	/** 维度没有本地化键时退回到手写的短名，避免把 {@code the_nether} 直接摆到屏幕上。 */
	private static String dimensionName(ResourceLocation id) {
		String key = "dimension." + id.getNamespace() + "." + id.getPath();
		String name = translate(key);
		if (!name.equals(key)) {
			return name;
		}
		return switch (id.getPath()) {
			case "overworld" -> "主世界";
			case "the_nether" -> "下界";
			case "the_end" -> "末地";
			default -> id.getPath();
		};
	}

	private static String translate(String key) {
		return I18n.exists(key) ? I18n.get(key) : key;
	}

	private static void render(GuiGraphics graphics) {
		if (life <= 0 || title.isEmpty()) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		if (client.font == null) {
			return;
		}

		float progress = (float) age;
		float fadeIn = Math.min(1.0F, progress / FADE_TICKS);
		float fadeOut = Math.min(1.0F, (life - progress) / FADE_TICKS);
		float alpha = Math.max(0.0F, Math.min(fadeIn, fadeOut));
		if (alpha <= 0.0F) {
			return;
		}

		int screenWidth = graphics.guiWidth();
		int screenHeight = graphics.guiHeight();
		int baseY = Math.max(24, screenHeight / 6);

		graphics.pose().pushPose();
		graphics.pose().translate(screenWidth / 2.0F, baseY, 0.0F);
		graphics.pose().scale(2.0F, 2.0F, 1.0F);

		int titleWidth = client.font.width(title);
		graphics.drawString(client.font, title, -titleWidth / 2, 0, withAlpha(TITLE_COLOR, alpha), true);

		if (!subtitle.isEmpty()) {
			graphics.pose().pushPose();
			graphics.pose().scale(0.5F, 0.5F, 1.0F);
			int subtitleWidth = client.font.width(subtitle);
			graphics.drawString(client.font, subtitle, -subtitleWidth, 14, withAlpha(SUBTITLE_COLOR, alpha), true);
			graphics.pose().popPose();
		}

		graphics.pose().popPose();
	}

	private static int withAlpha(int color, float alpha) {
		int a = Math.round(((color >>> 24) & 0xFF) * alpha);
		return (a << 24) | (color & 0x00FFFFFF);
	}
}
