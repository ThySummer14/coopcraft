package com.thysummer.coopcraft.civilization;

import java.util.List;

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
			44,
			17,
			SocialOrganization.PRIMITIVE_COMMUNE,
			List.of(
				new SettlementOverviewPayload.ResidentInfo(
					"", "farmer", false, -8, 64, 20, 72, 35, 81, 66, "ambitious", List.of("greedy", "fast"), true
				),
				new SettlementOverviewPayload.ResidentInfo(
					"Ada", "librarian", true, -12, 64, 26, 48, 90, 22, 18, "honest", List.of("generous", "wise"), false
				)
			),
			List.of(
				new SettlementOverviewPayload.SimulationEventInfo(0, "EXTERNAL_IMPORT", "TUBERS", 5),
				new SettlementOverviewPayload.SimulationEventInfo(0, "EXTERNAL_EXPORT", "TUBERS", 2)
			)
		);
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		try {
			SettlementOverviewPayload.STREAM_CODEC.encode(buf, expected);
			assertEquals(expected, SettlementOverviewPayload.STREAM_CODEC.decode(buf));
		} finally {
			buf.release();
		}
	}
}
