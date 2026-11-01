package com.thysummer.coopcraft.client;

import com.thysummer.coopcraft.net.TeamHealthPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * 屏幕左侧的队友血量板。
 *
 * <p>血条按各自的「最大生命」归一化，这样不同血上限的队友之间宽度可比；
 * 前 20 点画红、超出 20 点的部分画金，和玩家自己血条的金色心是同一套语义。
 */
public final class TeamHealthHud {
	private static final int PANEL_WIDTH = 108;
	private static final int ROW_HEIGHT = 14;
	private static final int PADDING = 4;
	private static final int BAR_WIDTH = 52;
	private static final int BAR_HEIGHT = 5;
	private static final int COLOR_BG = 0xB4101014;
	private static final int COLOR_BAR_BG = 0xFF2A2A32;
	private static final int COLOR_BASE_HEALTH = 0xFFE0353A;
	private static final int COLOR_EXTRA_HEALTH = 0xFFFFC24A;
	private static final int COLOR_TEXT = 0xFFE8E8F0;
	private static final int COLOR_TEXT_DIM = 0xFF9A9AA8;

	private static volatile List<TeamHealthPayload.Entry> entries = List.of();

	private TeamHealthHud() {
	}

	public static void update(List<TeamHealthPayload.Entry> next) {
		entries = next;
	}

	public static void clear() {
		entries = List.of();
	}

	public static void render(GuiGraphics graphics) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || entries.isEmpty()) {
			return;
		}

		// 自己的血在原版血条上已经有了，这里只列队友。
		String self = client.player.getScoreboardName();
		List<TeamHealthPayload.Entry> teammates = new ArrayList<>(entries.size());
		for (TeamHealthPayload.Entry entry : entries) {
			if (!entry.name().equals(self)) {
				teammates.add(entry);
			}
		}
		if (teammates.isEmpty()) {
			return;
		}

		int rows = teammates.size();
		int panelHeight = PADDING * 2 + rows * ROW_HEIGHT;
		int left = PADDING;
		int top = PADDING;

		graphics.fill(left, top, left + PANEL_WIDTH, top + panelHeight, COLOR_BG);

		int y = top + PADDING;
		for (TeamHealthPayload.Entry entry : teammates) {
			graphics.drawString(client.font, entry.name(), left + PADDING, y, COLOR_TEXT, false);

			String hp = entry.health() + "/" + entry.maxHealth();
			int hpWidth = client.font.width(hp);
			graphics.drawString(client.font, hp, left + PANEL_WIDTH - PADDING - hpWidth, y, COLOR_TEXT_DIM, false);

			int barLeft = left + PADDING;
			int barTop = y + 9;
			graphics.fill(barLeft, barTop, barLeft + BAR_WIDTH, barTop + BAR_HEIGHT, COLOR_BAR_BG);

			int maxHealth = Math.max(1, entry.maxHealth());
			int current = Math.max(0, Math.min(entry.health(), maxHealth));
			int baseHealth = Math.min(current, 20);

			int baseWidth = Math.round(BAR_WIDTH * (baseHealth / (float) maxHealth));
			int extraWidth = Math.round(BAR_WIDTH * ((current - baseHealth) / (float) maxHealth));

			if (baseWidth > 0) {
				graphics.fill(barLeft, barTop, barLeft + baseWidth, barTop + BAR_HEIGHT, COLOR_BASE_HEALTH);
			}
			if (extraWidth > 0) {
				graphics.fill(barLeft + baseWidth, barTop, barLeft + baseWidth + extraWidth, barTop + BAR_HEIGHT,
					COLOR_EXTRA_HEALTH);
			}

			y += ROW_HEIGHT;
		}
	}
}
