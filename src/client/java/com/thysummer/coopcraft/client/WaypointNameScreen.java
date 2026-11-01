package com.thysummer.coopcraft.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 给新路径点起名字的小窗。
 *
 * <p>坐标不用填 —— 服务端直接取玩家脚下的方块位置。
 */
public class WaypointNameScreen extends Screen {
	private static final int MAX_LENGTH = 24;

	private EditBox nameField;

	public WaypointNameScreen() {
		super(Component.translatable("coopcraft.waypoint.new_title"));
	}

	@Override
	protected void init() {
		nameField = new EditBox(font, width / 2 - 100, height / 2 - 20, 200, 20,
			Component.translatable("coopcraft.waypoint.name_hint"));
		nameField.setMaxLength(MAX_LENGTH);
		nameField.setHint(Component.translatable("coopcraft.waypoint.name_hint"));
		addRenderableWidget(nameField);
		setInitialFocus(nameField);

		addRenderableWidget(Button.builder(Component.translatable("coopcraft.waypoint.confirm"), button -> confirm())
			.bounds(width / 2 - 102, height / 2 + 10, 100, 20)
			.build());
		addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
			.bounds(width / 2 + 2, height / 2 + 10, 100, 20)
			.build());
	}

	private void confirm() {
		String name = nameField.getValue().strip();
		if (!name.isEmpty()) {
			WaypointClient.requestAdd(name);
			onClose();
		}
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == 257 || keyCode == 335) {
			confirm();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(font, title, width / 2, height / 2 - 50, 0xFFFFFF);
		super.render(graphics, mouseX, mouseY, partialTick);
	}
}
