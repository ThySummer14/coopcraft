package com.thysummer.coopcraft.civilization.simulation;

public record TradeConfig(
	boolean enabled,
	int minimumMutualTrust,
	int foodReserveDays,
	double minimumReportConfidence
) {
	public TradeConfig {
		if (minimumMutualTrust < 0 || minimumMutualTrust > 100 || foodReserveDays <= 0
			|| !Double.isFinite(minimumReportConfidence) || minimumReportConfidence < 0 || minimumReportConfidence > 1) {
			throw new IllegalArgumentException("Invalid trade trust, reserve or report limits");
		}
	}

	public static TradeConfig disabled() {
		return new TradeConfig(false, 40, 2, 0.45);
	}

	public static TradeConfig enabledDefaults() {
		return new TradeConfig(true, 40, 2, 0.45);
	}
}
