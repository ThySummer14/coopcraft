package com.thysummer.coopcraft.civilization.simulation;

/** Test-only access to model lifecycle transitions; never used by production observation. */
public final class FoodDayTestResidents {
	private FoodDayTestResidents() {}
	public static void kill(Person person) { person.markDead(0); }
	public static void move(Person person, String camp) { person.moveToCamp(camp); }
}
