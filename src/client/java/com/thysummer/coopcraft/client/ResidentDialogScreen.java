package com.thysummer.coopcraft.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.thysummer.coopcraft.net.ResidentDialogActionPayload.Action;
import com.thysummer.coopcraft.net.ResidentDialogPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Scrollable resident details above fixed actions; no inventory or client-side transactions. */
public final class ResidentDialogScreen extends Screen {
    private ResidentDialogPayload data;
    private final UUID session;
    private final List<FormattedCharSequence> lines = new ArrayList<>();
    private final List<Button> actions = new ArrayList<>();
    private double scroll;
    private int contentHeight;
    private Button up;
    private Button down;

    public ResidentDialogScreen(ResidentDialogPayload data) {
        super(data.snapshot().title());
        this.data = data; this.session = data.sessionId();
    }
    public UUID sessionId() { return session; }
    public void update(ResidentDialogPayload packet) {
        data = packet;
        scroll = 0; // Put the server result at the top after an action or refresh.
        rebuildWidgets();
    }
    private int left() { return Math.max(4, (width - 540) / 2); }
    private int bodyWidth() { return Math.max(24, width - 2 * left()); }
    private int bottom() { return Math.max(9, height - 92); }
    private int viewportHeight() { return bottom() - 8; }
    private void paragraph(Component text) {
        lines.addAll(font.split(text, Math.max(16, bodyWidth() - 12)));
        lines.add(FormattedCharSequence.EMPTY);
    }
    @Override protected void init() {
        lines.clear(); actions.clear();
        paragraph(data.snapshot().title());
        paragraph(Component.translatable("coopcraft.resident_dialog.result", data.message()));
        paragraph(Component.translatable("coopcraft.resident_dialog.current_information"));
        for (Component line : data.snapshot().lines()) paragraph(line);
        paragraph(data.snapshot().giftHint());
        paragraph(data.snapshot().meetingHint());
        paragraph(Component.translatable("coopcraft.resident_dialog.meaning"));
        paragraph(Component.translatable("coopcraft.resident_dialog.gift"));
        addAction("gift", Action.GIFT);
        addAction("invite", Action.INVITE);
        addAction("cancel", Action.CANCEL_INVITE);
        addAction("refresh", Action.REFRESH);
        contentHeight = lines.size() * (font.lineHeight + 1);
        int buttonY = Math.max(0, height - 26);
        int closeWidth = Math.max(24, Math.min(120, width / 3));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
            .bounds((width - closeWidth) / 2, buttonY, closeWidth, 20).build());
        up = addRenderableWidget(Button.builder(Component.literal("▲"), button -> moveScroll(-viewportHeight() / 2.0))
            .bounds(left(), buttonY, 24, 20).build());
        down = addRenderableWidget(Button.builder(Component.literal("▼"), button -> moveScroll(viewportHeight() / 2.0))
            .bounds(width - left() - 24, buttonY, 24, 20).build());
        layout();
    }
    private void addAction(String key, Action action) {
        Component label = Component.translatable("coopcraft.resident_dialog." + key);
        int index = actions.size();
        int buttonWidth = (bodyWidth() - 4) / 2;
        Button button = Button.builder(label, ignored -> ResidentDialogScreens.action(session, action))
            .bounds(left() + (index % 2) * (buttonWidth + 4), height - 74 + (index / 2) * 24, buttonWidth, 20)
            .tooltip(Tooltip.create(label)).build();
        actions.add(addRenderableWidget(button));
    }
    private void layout() {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - viewportHeight())));
        boolean ready = !ResidentDialogScreens.pending(session);
        boolean[] enabled = { data.snapshot().canGift(), data.snapshot().canInvite(), data.snapshot().canCancel(), true };
        for (int i = 0; i < actions.size(); i++) {
            Button button = actions.get(i);
            button.active = ready && enabled[i];
        }
        if (up != null) up.active = scroll > 0;
        if (down != null) down.active = scroll < contentHeight - viewportHeight();
    }
    private void moveScroll(double amount) { scroll += amount; layout(); }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        moveScroll(-verticalAmount * 24);
        return true;
    }
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Scroll the details without requiring a mouse wheel; actions remain fixed and tab-accessible.
        if (keyCode == 266 || keyCode == 267) { moveScroll((keyCode == 266 ? -1 : 1) * viewportHeight()); return true; }
        if (keyCode == 268) { moveScroll(-contentHeight); return true; }
        if (keyCode == 269) { moveScroll(contentHeight); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        layout();
        graphics.fill(0, 0, width, height, 0xFF181818);
        graphics.enableScissor(left(), 8, left() + bodyWidth(), bottom());
        int y = 8 - (int) scroll;
        for (FormattedCharSequence line : lines) {
            if (y + font.lineHeight >= 8 && y < bottom()) graphics.drawString(font, line, left() + 6, y, 0xEEEEEE);
            y += font.lineHeight + 1;
        }
        graphics.disableScissor();
        super.render(graphics, mouseX, mouseY, partialTick);
        if (ResidentDialogScreens.pending(session)) {
            graphics.drawCenteredString(font, Component.translatable("coopcraft.resident_dialog.pending"), width / 2, Math.max(0, height - 88), 0xFFD080);
        }
    }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() {
        ResidentDialogScreens.close(session);
        super.onClose();
    }
    @Override public void removed() { ResidentDialogScreens.close(session); }
}
