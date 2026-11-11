package com.thysummer.coopcraft.civilization;

/** Player administrative work target, not a ration, reservation or negotiated norm. */
public enum FoodReserveTarget {
	NORMAL("normal", 8), HIGH("high", 16);

	private final String id;
	private final int pointsPerMember;
	FoodReserveTarget(String id, int pointsPerMember) {
		this.id = id;
		this.pointsPerMember = pointsPerMember;
	}
	public String id() { return id; }
	public int pointsPerMember() { return pointsPerMember; }
	public static FoodReserveTarget byId(String id) {
		return "high".equals(id) ? HIGH : NORMAL;
	}
}
