package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.Norm;
import com.thysummer.coopcraft.civilization.simulation.NormProposal;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.net.SettlementOverviewPayload;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettlementOverviewPayloadTest {
	@Test
	void preservesOverviewThroughCodecRoundTrip() {
		BlockPos center = new BlockPos(-10, 65, 24);
		SettlementOverviewPayload expected = new SettlementOverviewPayload(
			"Testhaven",
			center.asLong(),
			12,
			2,
			2,
			1,
			center.offset(1, 0, 1).asLong(),
			44,
			17,
			SocialOrganization.PRIMITIVE_COMMUNE,
			SettlementStage.PRIMITIVE,
			List.of(
				new SettlementOverviewPayload.ResidentInfo(
					"", "farmer", "forager", false, -8, 64, 20, 72, 35, 81, 66, "ambitious", List.of("greedy", "fast"), true
				),
				new SettlementOverviewPayload.ResidentInfo(
					"Ada", "librarian", "generalist", true, -12, 64, 26, 48, 90, 22, 18, "honest", List.of("generous", "wise"), false,
					new SettlementOverviewPayload.ResidentObservation(
						true, List.of("Aren"), 1,
						List.of(new SettlementOverviewPayload.CareObservation("Aren", 2, false)), 1,
						true, 3, true, "build", "", center.asLong()
					)
				)
			),
			List.of(
				new SettlementOverviewPayload.SimulationEventInfo(
					0, "EXTERNAL_IMPORT", "TUBERS", 5,
					List.of(new SettlementOverviewPayload.SimulationEventDetail("coopcraft.simulation.detail.recipe", "sticks"))
				),
				new SettlementOverviewPayload.SimulationEventInfo(0, "EXTERNAL_EXPORT", "TUBERS", 2, List.of())
			),
			List.of("common_food_shortage", "construction_paused_food"),
			"food",
			CivilizationEras.PRIMITIVE,
			"coopcraft.milestone.common_stockpile",
			"new_camp"
		);
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		try {
			SettlementOverviewPayload.STREAM_CODEC.encode(buf, expected);
			assertEquals(expected, SettlementOverviewPayload.STREAM_CODEC.decode(buf));
		} finally {
			buf.release();
		}
	}

	@Test
	void birthEventUsesGeneratedNamesWithoutLoadedEntities() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(new BlockPos(4, 64, 8), "Testhaven", 0);
		UUID firstParent = UUID.randomUUID();
		UUID secondParent = UUID.randomUUID();
		UUID child = UUID.randomUUID();
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		state.mapResident(firstParent, false, 80);
		state.mapResident(secondParent, false, 80);
		state.mapResidentWithParents(child, 80, firstParent, secondParent);
		SimulationEvent birth = new SimulationEvent(
			1, 0, EventType.BIRTH,
			List.of(
				"minecraft-person:" + firstParent,
				"minecraft-person:" + secondParent,
				"minecraft-person:" + child
			),
			List.of(), List.of(), Map.of("source", "test"), List.of()
		);

		SettlementOverviewPayload.SimulationEventInfo info = Civilization.toSimulationEventInfo(null, data, settlement, birth);

		assertEquals(List.of(
			new SettlementOverviewPayload.SimulationEventDetail(
				"coopcraft.simulation.detail.parents",
				VillagerNames.fullName(firstParent) + ", " + VillagerNames.fullName(secondParent)
			),
			new SettlementOverviewPayload.SimulationEventDetail(
				"coopcraft.simulation.detail.child", VillagerNames.nameFor(data, settlement, child)
			)
		), info.details());
	}

	@Test
	void normEventDetailsExposeProposerCapAndPositions() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(new BlockPos(4, 64, 8), "Testhaven", 0);
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		state.mapResident(first, false, 80);
		state.mapResident(second, false, 80);
		String firstPerson = state.residentId(first).orElseThrow();
		String secondPerson = state.residentId(second).orElseThrow();
		NormProposal proposal = NormProposal.restore(
			"proposal-test", state.campOwner().id(), Norm.NEED_FIRST_COMMON_STORE, firstPerson, 0, 1, 2, 0,
			1, NormProposal.Status.OPEN,
			Map.of(firstPerson, NormProposal.Position.SUPPORT, secondPerson, NormProposal.Position.OPPOSE)
		);
		state.world().registerProposal(proposal);
		state.world().registerNorm(new Norm(
			Norm.NEED_FIRST_COMMON_STORE, state.campOwner().id(), 2,
			Set.of(firstPerson), Set.of(secondPerson), Set.of(firstPerson, secondPerson),
			40, Norm.Status.ACTIVE, List.of()
		));
		SimulationEvent event = new SimulationEvent(
			2, 0, EventType.NORM_ADOPTED, List.of(firstPerson), List.of(), List.of(),
			Map.of("proposalId", "proposal-test", "normId", Norm.NEED_FIRST_COMMON_STORE, "previousCap", "3", "newCap", "2"),
			List.of()
		);

		SettlementOverviewPayload.SimulationEventInfo info = Civilization.toSimulationEventInfo(null, data, settlement, event);

		assertEquals(List.of(
			new SettlementOverviewPayload.SimulationEventDetail(
				"coopcraft.simulation.detail.proposer", VillagerNames.fullName(first)
			),
			new SettlementOverviewPayload.SimulationEventDetail(
				"coopcraft.simulation.detail.rule", "coopcraft.simulation.norm.need-first-common-store"
			),
			new SettlementOverviewPayload.SimulationEventDetail("coopcraft.simulation.detail.cap", "3 → 2"),
			new SettlementOverviewPayload.SimulationEventDetail(
				"coopcraft.simulation.detail.supporters", VillagerNames.fullName(first)
			),
			new SettlementOverviewPayload.SimulationEventDetail(
				"coopcraft.simulation.detail.opponents", VillagerNames.fullName(second)
			)
		), info.details());
	}

	@Test
	void craftEventDetailsExposeRecipeAndSkillChange() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(new BlockPos(4, 64, 8), "Testhaven", 0);
		SimulationEvent event = new SimulationEvent(
			1, 0, EventType.SKILL_PRACTICED, List.of("person"), List.of(), List.of(),
			Map.of("recipe", "sticks", "workType", "CRAFT", "skillBefore", "1.0", "skillAfter", "1.01"), List.of()
		);

		SettlementOverviewPayload.SimulationEventInfo info = Civilization.toSimulationEventInfo(null, data, settlement, event);

		assertEquals(List.of(
			new SettlementOverviewPayload.SimulationEventDetail("coopcraft.simulation.detail.actor", "person"),
			new SettlementOverviewPayload.SimulationEventDetail("coopcraft.simulation.detail.recipe", "sticks"),
			new SettlementOverviewPayload.SimulationEventDetail("coopcraft.simulation.detail.work_type", "coopcraft.simulation.worktype.CRAFT"),
			new SettlementOverviewPayload.SimulationEventDetail("coopcraft.simulation.detail.skill", "1.0 → 1.01")
		), info.details());
	}

	@Test
	void nextStepPrioritizesOneBasicAction() {
		assertEquals("stockpile", Civilization.nextStep(false, List.of(), SettlementStage.PRIMITIVE, false, 0, 2, false, 0));
		assertEquals("food", Civilization.nextStep(true, List.of("food_emergency"), SettlementStage.PRIMITIVE, false, 0, 2, false, 0));
		assertEquals("hearth", Civilization.nextStep(true, List.of(), SettlementStage.PRIMITIVE, false, 2, 2, false, 0));
		assertEquals("housing", Civilization.nextStep(true, List.of(), SettlementStage.AGRICULTURAL, false, 1, 2, false, 0));
		assertEquals("crafting_station", Civilization.nextStep(true, List.of(), SettlementStage.AGRICULTURAL, false, 2, 2, false, 0));
		assertEquals("resources", Civilization.nextStep(true, List.of(), SettlementStage.AGRICULTURAL, false, 2, 2, true, 2));
		assertEquals("observe", Civilization.nextStep(true, List.of(), SettlementStage.AGRICULTURAL, false, 2, 2, true, 3));
	}
}
