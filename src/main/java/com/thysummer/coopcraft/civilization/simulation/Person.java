package com.thysummer.coopcraft.civilization.simulation;

import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class Person {
	private static final int MAX_INFORMATION = 32;

	private final String id;
	private String campId;
	private final String householdId;
	private AgeBand ageBand;
	private long ageDays;
	private boolean alive = true;
	private long deathDay = -1;
	private int careHours;
	private int learningHours;
	private final int riskPreference;
	private final EnumMap<WorkType, Double> skills = new EnumMap<>(WorkType.class);
	private final Set<WorkType> knownTechniques = new LinkedHashSet<>();
	private final Map<String, Integer> trust = new HashMap<>();
	private final Map<String, Integer> reciprocity = new HashMap<>();
	private final Set<String> observedNorms = new LinkedHashSet<>();
	private final Set<String> knownRoutes = new LinkedHashSet<>();
	private final Map<String, SocialInformation> information = new LinkedHashMap<>();
	private final Map<String, CampKnowledge> campKnowledge = new LinkedHashMap<>();
	private int health = 100;
	private int energy = 100;
	private int hunger;
	private long hungerDays;
	private int hungerStreak;
	private int grievance;
	private int socialHours;
	private int previousSocialHours;

	public Person(String id, String campId, String householdId, AgeBand ageBand, int hunger, int riskPreference) {
		if (id.isBlank() || campId.isBlank() || householdId.isBlank()) {
			throw new IllegalArgumentException("Person, camp and household IDs cannot be blank");
		}
		if (hunger < 0 || hunger > 100 || riskPreference < 0 || riskPreference > 100) {
			throw new IllegalArgumentException("Hunger and risk preference must be within 0..100");
		}
		this.id = id;
		this.campId = campId;
		this.householdId = householdId;
		this.ageBand = ageBand;
		this.ageDays = switch (ageBand) {
			case INFANT -> 360;
			case CHILD -> 8 * 360;
			case ADOLESCENT -> 15 * 360;
			case ADULT -> 30 * 360;
			case ELDER -> 65 * 360;
		};
		this.hunger = hunger;
		this.riskPreference = riskPreference;
		for (WorkType workType : WorkType.values()) {
			skills.put(workType, 1.0);
		}
	}

	public String id() {
		return id;
	}

	public String campId() {
		return campId;
	}

	public String householdId() {
		return householdId;
	}

	public AgeBand ageBand() {
		return ageBand;
	}

	public long ageDays() {
		return ageDays;
	}

	public boolean isAlive() {
		return alive;
	}

	public long deathDay() {
		return deathDay;
	}

	public boolean canWork() {
		return alive && health > 20 && (ageBand == AgeBand.ADULT || ageBand == AgeBand.ELDER || ageBand == AgeBand.ADOLESCENT);
	}

	public boolean canMakeDecisions() {
		return alive && health > 20 && (ageBand == AgeBand.ADULT || ageBand == AgeBand.ELDER);
	}

	public boolean canProvideCare() {
		return alive && health > 20 && ageBand != AgeBand.INFANT && ageBand != AgeBand.CHILD;
	}

	void initializeAge(AgeBand band, long days) {
		if (days < 0) {
			throw new IllegalArgumentException("Age cannot be negative");
		}
		ageBand = band;
		ageDays = days;
	}

	void advanceAgeDays() {
		ageDays++;
	}

	boolean updateAgeBand(AgeBand newBand) {
		if (ageBand == newBand) {
			return false;
		}
		ageBand = newBand;
		return true;
	}

	void adjustHealth(int delta) {
		health = Math.max(0, Math.min(100, health + delta));
	}

	void markDead(long day) {
		if (alive) {
			alive = false;
			deathDay = day;
		}
	}

	public int careHours() {
		return careHours;
	}

	void assignCareHours(int hours) {
		if (hours <= 0 || careHours + hours > 6) {
			throw new IllegalArgumentException("Invalid care time reservation");
		}
		careHours += hours;
	}

	public int health() {
		return health;
	}

	public int energy() {
		return energy;
	}

	public int hunger() {
		return hunger;
	}

	public long hungerDays() {
		return hungerDays;
	}

	public int riskPreference() {
		return riskPreference;
	}

	public void setSkill(WorkType workType, double skill) {
		if (!Double.isFinite(skill) || skill <= 0) {
			throw new IllegalArgumentException("Skill must be finite and positive");
		}
		skills.put(workType, skill);
	}

	public double skill(WorkType workType) {
		return skills.get(workType);
	}

	public void learnTechnique(WorkType workType) {
		knownTechniques.add(workType);
	}

	public boolean knowsTechnique(WorkType workType) {
		return knownTechniques.contains(workType);
	}

	public Set<WorkType> knownTechniques() {
		return Collections.unmodifiableSet(knownTechniques);
	}

	public void setTrust(String otherPersonId, int value) {
		checkRelationshipValue(value);
		trust.put(otherPersonId, value);
	}

	public int trustIn(String otherPersonId) {
		return trust.getOrDefault(otherPersonId, 0);
	}

	public void adjustTrust(String otherPersonId, int delta) {
		trust.put(otherPersonId, clampRelationship(trustIn(otherPersonId) + delta));
	}

	public int reciprocityWith(String otherPersonId) {
		return reciprocity.getOrDefault(otherPersonId, 0);
	}

	public void recordAidFrom(String donorId) {
		reciprocity.merge(donorId, 1, Integer::sum);
		adjustTrust(donorId, 2);
		grievance = Math.max(0, grievance - 1);
	}

	public void observeNorm(String normId) {
		if (normId.isBlank()) {
			throw new IllegalArgumentException("Norm ID cannot be blank");
		}
		observedNorms.add(normId);
	}

	public boolean knowsNorm(String normId) {
		return observedNorms.contains(normId);
	}

	public Set<String> observedNorms() {
		return Collections.unmodifiableSet(observedNorms);
	}

	public void learnInformation(SocialInformation learned) {
		SocialInformation existing = information.get(learned.informationId());
		if (existing == null || existing.confidence() < learned.confidence()) {
			information.put(learned.informationId(), learned);
		}
		while (information.size() > MAX_INFORMATION) {
			String oldest = information.keySet().iterator().next();
			information.remove(oldest);
		}
	}

	public boolean knowsInformation(String informationId) {
		return information.containsKey(informationId);
	}

	public Optional<SocialInformation> information(String informationId) {
		return Optional.ofNullable(information.get(informationId));
	}

	public List<SocialInformation> information() {
		return information.values().stream()
			.sorted(Comparator.comparingLong(SocialInformation::learnedDay).thenComparing(SocialInformation::informationId))
			.toList();
	}

	public void learnCampRoute(String routeId) {
		if (routeId.isBlank()) {
			throw new IllegalArgumentException("Route ID cannot be blank");
		}
		knownRoutes.add(routeId);
	}

	public boolean knowsCampRoute(String routeId) {
		return knownRoutes.contains(routeId);
	}

	public Set<String> knownCampRoutes() {
		return Set.copyOf(knownRoutes);
	}

	public void learnCampKnowledge(CampKnowledge learned) {
		CampKnowledge existing = campKnowledge.get(learned.campId());
		if (existing == null || existing.reportedDay() < learned.reportedDay()
			|| existing.reportedDay() == learned.reportedDay() && existing.confidence() < learned.confidence()) {
			campKnowledge.put(learned.campId(), learned);
		}
	}

	public Optional<CampKnowledge> campKnowledge(String campId) {
		return Optional.ofNullable(campKnowledge.get(campId));
	}

	public List<CampKnowledge> campKnowledge() {
		return campKnowledge.values().stream().sorted(Comparator.comparing(CampKnowledge::campId)).toList();
	}

	void moveToCamp(String newCampId) {
		if (newCampId.isBlank()) {
			throw new IllegalArgumentException("Camp ID cannot be blank");
		}
		campId = newCampId;
	}

	public int grievance() {
		return grievance;
	}

	void increaseGrievance(int amount) {
		if (amount < 0) {
			throw new IllegalArgumentException("Grievance increase cannot be negative");
		}
		grievance = Math.min(100, grievance + amount);
	}

	void startDay() {
		previousSocialHours = socialHours;
		socialHours = 0;
		careHours = 0;
		learningHours = 0;
	}

	public int learningHours() {
		return learningHours;
	}

	void assignLearningHours(int hours) {
		if (hours <= 0 || learningHours + hours > 6) {
			throw new IllegalArgumentException("Invalid learning time reservation");
		}
		learningHours += hours;
	}

	public int socialHours() {
		return socialHours;
	}

	public int previousSocialHours() {
		return previousSocialHours;
	}

	void addSocialHours(int hours) {
		if (hours <= 0 || socialHours + hours > 6) {
			throw new IllegalArgumentException("Invalid social time reservation");
		}
		socialHours += hours;
	}

	public void setHunger(int value) {
		if (value < 0 || value > 100) {
			throw new IllegalArgumentException("Hunger must be within 0..100");
		}
		hunger = value;
	}

	void increaseHunger(int amount) {
		if (amount < 0) {
			throw new IllegalArgumentException("Hunger increase cannot be negative");
		}
		hunger = Math.min(100, hunger + amount);
	}

	void consumeNutrition(long nutrition) {
		if (nutrition < 0) {
			throw new IllegalArgumentException("Nutrition cannot be negative");
		}
		hunger = (int) Math.max(0, hunger - Math.min(Integer.MAX_VALUE, nutrition));
	}

	void spendEnergy(int amount) {
		if (amount < 0 || amount > energy) {
			throw new IllegalArgumentException("Insufficient energy");
		}
		energy -= amount;
	}

	void rest() {
		energy = 100;
	}

	void recordHungerDay() {
		hungerDays++;
		hungerStreak++;
	}

	void recordFoodAccessDay() {
		hungerStreak = 0;
	}

	public int hungerStreak() {
		return hungerStreak;
	}

	private static void checkRelationshipValue(int value) {
		if (value < 0 || value > 100) {
			throw new IllegalArgumentException("Trust must be within 0..100");
		}
	}

	private static int clampRelationship(int value) {
		return Math.max(0, Math.min(100, value));
	}

	public enum AgeBand {
		INFANT,
		CHILD,
		ADOLESCENT,
		ADULT,
		ELDER
	}
}
