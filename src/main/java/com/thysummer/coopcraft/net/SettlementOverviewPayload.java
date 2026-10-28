package com.thysummer.coopcraft.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.thysummer.coopcraft.CoopCraft;
import com.thysummer.coopcraft.civilization.PersonalityArchetype;
import com.thysummer.coopcraft.civilization.SocialOrganization;
import com.thysummer.coopcraft.civilization.VillagerTrait;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record SettlementOverviewPayload(
	String name,
	long center,
	long foundedDay,
	int totalResidents,
	int registeredResidents,
	int housingCapacity,
	int communalFoodPoints,
	int communalResourceCount,
	SocialOrganization society,
	List<ResidentInfo> residents,
	List<SimulationEventInfo> simulationEvents
) implements CustomPacketPayload {
	public static final int MAX_RESIDENTS = 12;
	public static final int MAX_SIMULATION_EVENTS = 3;
	public static final Type<SettlementOverviewPayload> TYPE = new Type<>(CoopCraft.id("settlement_overview"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SettlementOverviewPayload> STREAM_CODEC = StreamCodec.of(
		(buf, payload) -> payload.write(buf),
		SettlementOverviewPayload::read
	);

	public SettlementOverviewPayload {
		Objects.requireNonNull(society, "society");
		residents = List.copyOf(residents);
		simulationEvents = List.copyOf(simulationEvents);
		if (simulationEvents.size() > MAX_SIMULATION_EVENTS) {
			throw new IllegalArgumentException("Too many simulation events");
		}
	}

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeUtf(name);
		buf.writeLong(center);
		buf.writeVarLong(foundedDay);
		buf.writeVarInt(totalResidents);
		buf.writeVarInt(registeredResidents);
		buf.writeVarInt(housingCapacity);
		buf.writeVarInt(communalFoodPoints);
		buf.writeVarInt(communalResourceCount);
		buf.writeUtf(society.form().id());
		buf.writeUtf(society.ownership().id());
		buf.writeUtf(society.labor().id());
		buf.writeUtf(society.distribution().id());
		buf.writeVarInt(residents.size());
		for (ResidentInfo resident : residents) {
			buf.writeUtf(resident.customName());
			buf.writeUtf(resident.profession());
			buf.writeBoolean(resident.child());
			buf.writeVarInt(resident.x());
			buf.writeVarInt(resident.y());
			buf.writeVarInt(resident.z());
			buf.writeVarInt(resident.diligence());
			buf.writeVarInt(resident.selfishness());
			buf.writeVarInt(resident.sociability());
			buf.writeVarInt(resident.satiety());
			buf.writeUtf(resident.archetype());
			buf.writeVarInt(resident.traits().size());
			for (String trait : resident.traits()) {
				buf.writeUtf(trait);
			}
			buf.writeBoolean(resident.leader());
		}
		buf.writeVarInt(simulationEvents.size());
		for (SimulationEventInfo event : simulationEvents) {
			buf.writeVarLong(event.day());
			buf.writeUtf(event.eventType());
			buf.writeUtf(event.resourceType());
			buf.writeVarLong(event.quantity());
		}
	}

	private static SettlementOverviewPayload read(RegistryFriendlyByteBuf buf) {
		String name = buf.readUtf();
		long center = buf.readLong();
		long foundedDay = buf.readVarLong();
		int totalResidents = buf.readVarInt();
		int registeredResidents = buf.readVarInt();
		int housingCapacity = buf.readVarInt();
		int communalFoodPoints = buf.readVarInt();
		int communalResourceCount = buf.readVarInt();
		SocialOrganization society = SocialOrganization.fromIds(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf());
		int count = buf.readVarInt();
		if (count < 0 || count > MAX_RESIDENTS || totalResidents < count || registeredResidents < 0 || housingCapacity < 0
			|| communalFoodPoints < 0 || communalResourceCount < 0) {
			throw new IllegalArgumentException("Invalid settlement overview counts");
		}

		List<ResidentInfo> residents = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			String customName = buf.readUtf();
			String profession = buf.readUtf();
			boolean child = buf.readBoolean();
			int x = buf.readVarInt();
			int y = buf.readVarInt();
			int z = buf.readVarInt();
			int diligence = buf.readVarInt();
			int selfishness = buf.readVarInt();
			int sociability = buf.readVarInt();
			int satiety = buf.readVarInt();
			String archetype = buf.readUtf();
			int traitCount = buf.readVarInt();
			if (traitCount < 0 || traitCount > 4) {
				throw new IllegalArgumentException("Invalid resident trait count");
			}
			List<String> traits = new ArrayList<>(traitCount);
			for (int traitIndex = 0; traitIndex < traitCount; traitIndex++) {
				traits.add(buf.readUtf());
			}
			boolean leader = buf.readBoolean();
			residents.add(new ResidentInfo(
				customName, profession, child, x, y, z, diligence, selfishness, sociability, satiety, archetype, traits, leader
			));
		}
		int simulationEventCount = buf.readVarInt();
		if (simulationEventCount < 0 || simulationEventCount > MAX_SIMULATION_EVENTS) {
			throw new IllegalArgumentException("Invalid simulation event count");
		}
		List<SimulationEventInfo> simulationEvents = new ArrayList<>(simulationEventCount);
		for (int i = 0; i < simulationEventCount; i++) {
			simulationEvents.add(new SimulationEventInfo(buf.readVarLong(), buf.readUtf(), buf.readUtf(), buf.readVarLong()));
		}
		return new SettlementOverviewPayload(
			name, center, foundedDay, totalResidents, registeredResidents, housingCapacity,
			communalFoodPoints, communalResourceCount, society, residents, simulationEvents
		);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public record SimulationEventInfo(long day, String eventType, String resourceType, long quantity) {
		public SimulationEventInfo {
			if (day < 0 || eventType.isBlank() || resourceType.isBlank() || quantity < 0) {
				throw new IllegalArgumentException("Invalid simulation event summary");
			}
		}
	}

	public record ResidentInfo(
		String customName,
		String profession,
		boolean child,
		int x,
		int y,
		int z,
		int diligence,
		int selfishness,
		int sociability,
		int satiety,
		String archetype,
		List<String> traits,
		boolean leader
	) {
		public ResidentInfo {
			if (diligence < 0 || diligence > 100 || selfishness < 0 || selfishness > 100 || sociability < 0 || sociability > 100
				|| satiety < 0 || satiety > 100) {
				throw new IllegalArgumentException("Personality and needs values must be between 0 and 100");
			}
			PersonalityArchetype.byId(archetype);
			traits = List.copyOf(traits);
			if (traits.size() > 4) {
				throw new IllegalArgumentException("Too many resident traits");
			}
			for (String trait : traits) {
				VillagerTrait.byId(trait);
			}
		}
	}
}
