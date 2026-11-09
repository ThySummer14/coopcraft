package com.thysummer.coopcraft.civilization.simulation;

/** Test-only lifecycle setup without running a server or mortality simulation. */
public final class FoodAccessTestPeople {
	private FoodAccessTestPeople() {}
	public static void markDead(Person person, long day) { person.markDead(day); }
}
