package com.thysummer.coopcraft.client;

import java.util.List;

import com.thysummer.coopcraft.net.SettlementOverviewPayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public class SettlementScreen extends Screen {
	private static final int PRIMARY_TEXT = 0xE0E0E0;
	private static final int SECONDARY_TEXT = 0xC8C8C8;

	private final SettlementOverviewPayload data;
	private Page page = Page.OVERVIEW;
	private int entryIndex;
	private Button overviewTab;
	private Button eventsTab;
	private Button residentsTab;
	private Button previousButton;
	private Button nextButton;

	public SettlementScreen(SettlementOverviewPayload data) {
		super(Component.translatable("coopcraft.settlement.title", data.name()));
		this.data = data;
	}

	@Override
	protected void init() {
		int left = panelLeft();
		int right = panelRight();
		int top = panelTop();
		int bottom = panelBottom();
		int tabWidth = (right - left - 28) / 3;
		overviewTab = addRenderableWidget(Button.builder(
			Component.translatable("coopcraft.settlement.tab.overview"), button -> selectPage(Page.OVERVIEW)
		).bounds(left + 12, top + 28, tabWidth, 20).build());
		eventsTab = addRenderableWidget(Button.builder(
			Component.translatable("coopcraft.settlement.tab.events"), button -> selectPage(Page.EVENTS)
		).bounds(left + 16 + tabWidth, top + 28, tabWidth, 20).build());
		residentsTab = addRenderableWidget(Button.builder(
			Component.translatable("coopcraft.settlement.tab.residents"), button -> selectPage(Page.RESIDENTS)
		).bounds(left + 20 + tabWidth * 2, top + 28, tabWidth, 20).build());
		previousButton = addRenderableWidget(Button.builder(
			Component.translatable("coopcraft.settlement.previous"), button -> moveEntry(-1)
		).bounds(left + 12, bottom - 30, 80, 20).build());
		nextButton = addRenderableWidget(Button.builder(
			Component.translatable("coopcraft.settlement.next"), button -> moveEntry(1)
		).bounds(right - 92, bottom - 30, 80, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
			.bounds(width / 2 - 50, height - 36, 100, 20)
			.build());
		updateButtons();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, width, height, 0xFF101010);
		drawPanel(graphics);
		updateButtons();
		switch (page) {
			case OVERVIEW -> drawOverview(graphics);
			case EVENTS -> drawEvent(graphics);
			case RESIDENTS -> drawResident(graphics);
		}
		if (page != Page.OVERVIEW) {
			graphics.drawCenteredString(
				font,
				Component.literal(entryCount() == 0 ? "0 / 0" : entryIndex + 1 + " / " + entryCount()),
				width / 2,
				panelBottom() - 24,
				SECONDARY_TEXT
			);
		}
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
	}

	private void drawPanel(GuiGraphics graphics) {
		int left = panelLeft();
		int right = panelRight();
		int top = panelTop();
		int bottom = panelBottom();
		graphics.fill(left, top, right, bottom, 0xFF181818);
		graphics.fill(left, top, right, top + 1, 0xFF8B7355);
		graphics.fill(left, bottom - 1, right, bottom, 0xFF8B7355);
		graphics.fill(left, top, left + 1, bottom, 0xFF8B7355);
		graphics.fill(right - 1, top, right, bottom, 0xFF8B7355);
		graphics.fill(left + 1, top + 24, right - 1, top + 25, 0xFF5C5142);
		graphics.drawCenteredString(font, title, width / 2, top + 8, 0xFFFFFF);
	}

	private void drawOverview(GuiGraphics graphics) {
		int x = panelLeft() + 12;
		int y = contentTop();
		int maxY = panelBottom() - 8;
		int textWidth = panelWidth() - 24;
		BlockPos center = BlockPos.of(data.center());
		y = drawWrapped(graphics, Component.translatable("coopcraft.settlement.founded", data.foundedDay()), x, y, textWidth, maxY, PRIMARY_TEXT) + 4;
		y = drawWrapped(graphics, Component.translatable(
			"coopcraft.settlement.center", center.getX(), center.getY(), center.getZ()
		), x, y, textWidth, maxY, PRIMARY_TEXT) + 10;
		y = drawWrapped(graphics, Component.translatable(
			"coopcraft.settlement.population", data.totalResidents(), data.registeredResidents()
		), x, y, textWidth, maxY, PRIMARY_TEXT) + 4;
		y = drawWrapped(graphics, Component.translatable("coopcraft.settlement.housing", data.housingCapacity()), x, y, textWidth, maxY, PRIMARY_TEXT) + 4;
		Component hearth = data.hearth() < 0
			? Component.translatable("coopcraft.settlement.hearth_none")
			: Component.literal(BlockPos.of(data.hearth()).toShortString());
		y = drawWrapped(graphics, Component.translatable("coopcraft.settlement.hearth", hearth), x, y, textWidth, maxY, PRIMARY_TEXT) + 4;
		y = drawWrapped(graphics, Component.translatable(
			"coopcraft.settlement.stockpile", data.communalFoodPoints(), data.communalResourceCount()
		), x, y, textWidth, maxY, PRIMARY_TEXT) + 10;
		y = drawWrapped(graphics, Component.translatable(
			"coopcraft.settlement.social_form", Component.translatable(data.society().form().translationKey())
		), x, y, textWidth, maxY, PRIMARY_TEXT) + 4;
		y = drawWrapped(graphics, Component.translatable(
			"coopcraft.settlement.ownership", Component.translatable(data.society().ownership().translationKey())
		), x, y, textWidth, maxY, PRIMARY_TEXT) + 4;
		y = drawWrapped(graphics, Component.translatable(
			"coopcraft.settlement.labor", Component.translatable(data.society().labor().translationKey())
		), x, y, textWidth, maxY, PRIMARY_TEXT) + 4;
		drawWrapped(graphics, Component.translatable(
			"coopcraft.settlement.distribution", Component.translatable(data.society().distribution().translationKey())
		), x, y, textWidth, maxY, PRIMARY_TEXT);
	}

	private void drawEvent(GuiGraphics graphics) {
		List<SettlementOverviewPayload.SimulationEventInfo> events = data.simulationEvents();
		int x = panelLeft() + 12;
		int y = contentTop();
		int maxY = panelBottom() - 38;
		int textWidth = panelWidth() - 24;
		if (events.isEmpty()) {
			drawWrapped(graphics, Component.translatable("coopcraft.settlement.simulation_empty"), x, y, textWidth, maxY, PRIMARY_TEXT);
			return;
		}
		SettlementOverviewPayload.SimulationEventInfo event = events.get(entryIndex);
		Component summary = event.resourceType().equals("NONE")
			? Component.translatable(
				"coopcraft.settlement.simulation_event_social",
				event.day(), Component.translatable("coopcraft.simulation.event." + event.eventType())
			)
			: Component.translatable(
				"coopcraft.settlement.simulation_event",
				event.day(),
				Component.translatable("coopcraft.simulation.event." + event.eventType()),
				Component.translatable("coopcraft.simulation.resource." + event.resourceType()),
				event.quantity()
			);
		y = drawWrapped(graphics, summary, x, y, textWidth, maxY, PRIMARY_TEXT) + 10;
		for (SettlementOverviewPayload.SimulationEventDetail detail : event.details()) {
			Component value = detail.value().startsWith("coopcraft.")
				|| detail.value().startsWith("item.")
				|| detail.value().startsWith("block.")
				? Component.translatable(detail.value()) : Component.literal(detail.value());
			y = drawWrapped(graphics, Component.translatable(detail.label(), value), x, y, textWidth, maxY, SECONDARY_TEXT) + 5;
			if (y >= maxY) {
				break;
			}
		}
	}

	private void drawResident(GuiGraphics graphics) {
		List<SettlementOverviewPayload.ResidentInfo> residents = data.residents();
		int x = panelLeft() + 12;
		int y = contentTop();
		int maxY = panelBottom() - 38;
		int textWidth = panelWidth() - 24;
		if (residents.isEmpty()) {
			drawWrapped(graphics, Component.translatable("coopcraft.settlement.empty"), x, y, textWidth, maxY, PRIMARY_TEXT);
			return;
		}
		SettlementOverviewPayload.ResidentInfo resident = residents.get(entryIndex);
		Component name = resident.customName().isEmpty()
			? Component.translatable("entity.minecraft.villager")
			: Component.literal(resident.customName());
		Component profession = Component.translatable("entity.minecraft.villager.profession." + resident.profession());
		if (resident.child()) {
			profession = Component.translatable("coopcraft.settlement.child", profession);
		}
		if (resident.leader()) {
			profession = Component.translatable("coopcraft.settlement.leader", profession);
		}
		String position = resident.x() + ", " + resident.y() + ", " + resident.z();
		y = drawWrapped(graphics, Component.translatable(
			"coopcraft.settlement.resident", name, profession, position
		), x, y, textWidth, maxY, PRIMARY_TEXT) + 6;
		y = drawWrapped(graphics, Component.translatable(
			"coopcraft.settlement.resident_role",
			Component.translatable("coopcraft.work_role." + resident.role())
		), x, y, textWidth, maxY, SECONDARY_TEXT) + 6;
		y = drawWrapped(graphics, Component.translatable(
			"coopcraft.settlement.resident_summary",
			Component.translatable("coopcraft.personality." + resident.archetype()),
			resident.diligence(),
			resident.selfishness(),
			resident.sociability(),
			resident.satiety()
		), x, y, textWidth, maxY, SECONDARY_TEXT) + 10;
		StringBuilder traits = new StringBuilder();
		for (String trait : resident.traits()) {
			if (!traits.isEmpty()) {
				traits.append(" · ");
			}
			traits.append(Component.translatable("coopcraft.trait." + trait).getString());
		}
		y = drawWrapped(graphics, traits.isEmpty()
			? Component.translatable("coopcraft.settlement.no_traits")
			: Component.translatable("coopcraft.settlement.trait_list", traits.toString()), x, y, textWidth, maxY, SECONDARY_TEXT) + 5;
		int extra = data.totalResidents() - residents.size();
		if (extra > 0 && entryIndex == residents.size() - 1) {
			drawWrapped(graphics, Component.translatable("coopcraft.settlement.more", extra), x, y, textWidth, maxY, SECONDARY_TEXT);
		}
	}

	private int drawWrapped(
		GuiGraphics graphics,
		Component text,
		int x,
		int y,
		int textWidth,
		int maxY,
		int color
	) {
		for (FormattedCharSequence line : font.split(text, textWidth)) {
			if (y + font.lineHeight > maxY) {
				break;
			}
			graphics.drawString(font, line, x, y, color);
			y += font.lineHeight;
		}
		return y;
	}

	private void selectPage(Page selected) {
		page = selected;
		entryIndex = 0;
		updateButtons();
	}

	private void moveEntry(int delta) {
		int count = entryCount();
		if (count > 0) {
			entryIndex = Math.max(0, Math.min(count - 1, entryIndex + delta));
		}
		updateButtons();
	}

	private int entryCount() {
		return switch (page) {
			case OVERVIEW -> 0;
			case EVENTS -> data.simulationEvents().size();
			case RESIDENTS -> data.residents().size();
		};
	}

	private void updateButtons() {
		if (overviewTab == null) {
			return;
		}
		overviewTab.active = page != Page.OVERVIEW;
		eventsTab.active = page != Page.EVENTS;
		residentsTab.active = page != Page.RESIDENTS;
		int count = entryCount();
		entryIndex = count == 0 ? 0 : Math.min(entryIndex, count - 1);
		previousButton.visible = page != Page.OVERVIEW && entryIndex > 0;
		nextButton.visible = page != Page.OVERVIEW && entryIndex + 1 < count;
	}

	private int panelWidth() {
		return Math.min(460, width - 24);
	}

	private int panelLeft() {
		return (width - panelWidth()) / 2;
	}

	private int panelRight() {
		return panelLeft() + panelWidth();
	}

	private int panelTop() {
		return 12;
	}

	private int panelBottom() {
		return height - 48;
	}

	private int contentTop() {
		return panelTop() + 58;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private enum Page {
		OVERVIEW,
		EVENTS,
		RESIDENTS
	}
}
