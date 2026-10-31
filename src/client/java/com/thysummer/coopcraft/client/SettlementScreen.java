package com.thysummer.coopcraft.client;

import java.util.List;

import com.thysummer.coopcraft.net.SettlementOverviewPayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public class SettlementScreen extends Screen {
	private static final int DISPLAYED_ROWS = 2;
	private static final int RESIDENT_ROW_HEIGHT = 36;

	private final SettlementOverviewPayload data;

	public SettlementScreen(SettlementOverviewPayload data) {
		super(Component.translatable("coopcraft.settlement.title", data.name()));
		this.data = data;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
			.bounds(width / 2 - 50, height - 38, 100, 20)
			.build());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(graphics, mouseX, mouseY, partialTick);
		int left = Math.max(20, width / 2 - 150);
		int top = Math.max(12, height / 2 - 155);
		BlockPos center = BlockPos.of(data.center());

		graphics.drawCenteredString(font, title, width / 2, top, 0xFFFFFF);
		graphics.drawString(font, Component.translatable("coopcraft.settlement.founded", data.foundedDay()), left, top + 24, 0xD0D0D0);
		graphics.drawString(font, Component.translatable(
			"coopcraft.settlement.center", center.getX(), center.getY(), center.getZ()
		), left, top + 38, 0xD0D0D0);
		graphics.drawString(font, Component.translatable("coopcraft.settlement.population", data.totalResidents(), data.registeredResidents()), left, top + 60, 0xFFFFFF);
		graphics.drawString(font, Component.translatable("coopcraft.settlement.housing", data.housingCapacity()), left, top + 74, 0xFFFFFF);
		graphics.drawString(font, Component.translatable(
			"coopcraft.settlement.stockpile", data.communalFoodPoints(), data.communalResourceCount()
		), left, top + 88, 0xFFFFFF);
		int eventLeft = Math.min(width - 150, left + 180);
		graphics.drawString(font, Component.translatable("coopcraft.settlement.simulation_events"), eventLeft, top + 112, 0xA0A0A0);
		List<SettlementOverviewPayload.SimulationEventInfo> simulationEvents = data.simulationEvents();
		if (simulationEvents.isEmpty()) {
			graphics.drawString(font, Component.translatable("coopcraft.settlement.simulation_empty"), eventLeft, top + 126, 0xD0D0D0);
		} else {
			for (int i = 0; i < simulationEvents.size(); i++) {
				SettlementOverviewPayload.SimulationEventInfo event = simulationEvents.get(i);
				graphics.drawString(font, Component.translatable(
					"coopcraft.settlement.simulation_event",
					event.day(),
					Component.translatable("coopcraft.simulation.event." + event.eventType()),
					Component.translatable("coopcraft.simulation.resource." + event.resourceType()),
					event.quantity()
				), eventLeft, top + 126 + i * 14, 0xD0D0D0);
			}
		}
		graphics.drawString(font, Component.translatable(
			"coopcraft.settlement.social_form", Component.translatable(data.society().form().translationKey())
		), left, top + 112, 0xFFFFFF);
		graphics.drawString(font, Component.translatable(
			"coopcraft.settlement.ownership", Component.translatable(data.society().ownership().translationKey())
		), left, top + 126, 0xD0D0D0);
		graphics.drawString(font, Component.translatable(
			"coopcraft.settlement.labor", Component.translatable(data.society().labor().translationKey())
		), left, top + 140, 0xD0D0D0);
		graphics.drawString(font, Component.translatable(
			"coopcraft.settlement.distribution", Component.translatable(data.society().distribution().translationKey())
		), left, top + 154, 0xD0D0D0);
		graphics.drawString(font, Component.translatable("coopcraft.settlement.residents"), left, top + 178, 0xA0A0A0);

		List<SettlementOverviewPayload.ResidentInfo> residents = data.residents();
		int residentsTop = top + 200;
		if (residents.isEmpty()) {
			graphics.drawString(font, Component.translatable("coopcraft.settlement.empty"), left, residentsTop, 0xD0D0D0);
		} else {
			int availableRows = Math.max(1, (height - 58 - residentsTop) / RESIDENT_ROW_HEIGHT);
			int displayed = Math.min(Math.min(DISPLAYED_ROWS, availableRows), residents.size());
			for (int i = 0; i < displayed; i++) {
				SettlementOverviewPayload.ResidentInfo resident = residents.get(i);
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
				int rowTop = residentsTop + i * RESIDENT_ROW_HEIGHT;
				graphics.drawString(
					font,
					Component.translatable("coopcraft.settlement.resident", name, profession, position),
					left,
					rowTop,
					0xE0E0E0
				);
				graphics.drawString(
					font,
					Component.translatable(
						"coopcraft.settlement.resident_summary",
						Component.translatable("coopcraft.personality." + resident.archetype()),
						resident.diligence(),
						resident.selfishness(),
						resident.sociability(),
						resident.satiety()
					),
					left,
					rowTop + 11,
					0xA0A0A0
				);
				StringBuilder traits = new StringBuilder();
				for (String trait : resident.traits()) {
					if (!traits.isEmpty()) {
						traits.append(" · ");
					}
					traits.append(Component.translatable("coopcraft.trait." + trait).getString());
				}
				graphics.drawString(
					font,
					traits.isEmpty()
						? Component.translatable("coopcraft.settlement.no_traits")
						: Component.translatable("coopcraft.settlement.trait_list", traits.toString()),
					left,
					rowTop + 22,
					0xA0A0A0
				);
			}

			int extra = data.totalResidents() - displayed;
			if (extra > 0) {
				graphics.drawString(
					font,
					Component.translatable("coopcraft.settlement.more", extra),
					left,
					residentsTop + displayed * RESIDENT_ROW_HEIGHT,
					0xA0A0A0
				);
			}
		}

		super.render(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
