import com.thysummer.coopcraft.civilization.simulation.*;
import java.util.List;
import java.util.stream.Collectors;

/** Research harness only. No Minecraft, persistence, production, or policy changes. */
public final class RationExperiments {
    private static final ResourceType[] FOODS = {
        ResourceType.WILD_FRUIT, ResourceType.TUBERS, ResourceType.FISH, ResourceType.GAME
    };
    private static final StorageOwner COMMON = StorageOwner.camp("camp");
    private record Stock(ResourceType food, int units, long expiry) {}

    public static void main(String[] args) {
        String first = run();
        check(first.equals(run()), "Deterministic replay differs");
        System.out.print(first);
        System.err.println("PASS: conservation, meal accounting, daily cap, split equivalence, deterministic replay");
    }

    private static String run() {
        StringBuilder csv = new StringBuilder("scenario,day,visit,cap,allowance_before,initial_hunger,hunger_before,stock_order_before,stock_units_before,stock_nutrition_before,received_units,food_nutrition,hunger_relief,hunger_after,need_above_target_after,nutrition_beyond_relief,allocated_today,allowance_after,stock_units_after,stock_nutrition_after,consumed_by_type,status\n");
        for (ResourceType food : FOODS) {
            for (int hunger : new int[]{5, 6, 35, 65, 100}) {
                for (int stock : new int[]{0, 1, 12}) {
                    for (int cap : new int[]{2, 3, 4}) {
                        new Trial(csv, "equal_units_" + food + "_h" + hunger + "_s" + stock,
                            hunger, cap, new Stock(food, stock, food.shelfLifeDays())).meal(cap);
                    }
                }
            }
        }
        // Exactly 72 nutrition: 4 fruit versus 3 tubers, no fractional items.
        for (ResourceType food : new ResourceType[]{ResourceType.WILD_FRUIT, ResourceType.TUBERS}) {
            for (int cap : new int[]{2, 3, 4}) {
                new Trial(csv, "equal_nutrition_72_" + food, 100, cap,
                    new Stock(food, 72 / food.nutritionPerUnit(), food.shelfLifeDays())).meal(cap);
            }
        }
        // 2520 is an exact common multiple of 18, 24, 20 and 28.
        for (ResourceType food : FOODS) {
            new Trial(csv, "equal_nutrition_2520_" + food, 100, 3,
                new Stock(food, 2520 / food.nutritionPerUnit(), food.shelfLifeDays())).meal(3);
        }
        for (ResourceType food : FOODS) {
            Trial split = new Trial(csv, "split_same_day_" + food, 100, 3,
                new Stock(food, 12, food.shelfLifeDays()));
            split.meal(1);
            split.meal(3); // Harness clamps to remaining daily allowance of 2.
            split.meal(3); // Exhausted: do NOT call core with illegal zero maximumUnits.
            Trial once = new Trial(new StringBuilder(), "reference", 100, 3,
                new Stock(food, 12, food.shelfLifeDays()));
            once.meal(3);
            check(split.person.hunger() == once.person.hunger() && split.used == once.used
                && split.world.availableNutrition(COMMON) == once.world.availableNutrition(COMMON),
                "Split homogeneous meal differs from single meal");
        }
        // Same 6 items and 138 nutrition; only expiry order changes.
        new Trial(csv, "mixed_fruit_first", 100, 3,
            new Stock(ResourceType.WILD_FRUIT, 3, 1), new Stock(ResourceType.GAME, 3, 2)).meal(3);
        new Trial(csv, "mixed_game_first", 100, 3,
            new Stock(ResourceType.WILD_FRUIT, 3, 2), new Stock(ResourceType.GAME, 3, 1)).meal(3);
        // Same expiry: insertion fixes batch IDs, which are the core's tie breaker.
        new Trial(csv, "mixed_tie_fruit_first", 100, 3,
            new Stock(ResourceType.WILD_FRUIT, 3, 2), new Stock(ResourceType.GAME, 3, 2)).meal(3);
        new Trial(csv, "mixed_tie_game_first", 100, 3,
            new Stock(ResourceType.GAME, 3, 2), new Stock(ResourceType.WILD_FRUIT, 3, 2)).meal(3);
        return csv.toString();
    }

    private static final class Trial {
        final SimulationWorld world = new SimulationWorld();
        final Person person;
        final SurvivalSystem survival;
        final StringBuilder csv;
        final String name;
        final int initialHunger;
        final int cap;
        int used;
        int visit;

        Trial(StringBuilder csv, String name, int hunger, int cap, Stock... stocks) {
            this.csv = csv;
            this.name = name;
            this.initialHunger = hunger;
            this.cap = cap;
            world.registerCamp(new Camp("camp", "Research camp"));
            world.registerHousehold(new Household("household", "camp"));
            person = new Person("resident", "camp", "household", Person.AgeBand.ADULT, hunger, 50);
            world.registerPerson(person);
            survival = new SurvivalSystem(world, SimulationConfig.defaults());
            for (Stock stock : stocks) {
                if (stock.units > 0) {
                    world.createInitialBatch(stock.food, stock.units, 50, COMMON, 0, stock.expiry);
                    world.assertConservation();
                }
            }
        }

        void meal(int requestedMaximum) {
            world.assertConservation();
            int allowance = cap - used;
            int beforeHunger = person.hunger();
            long beforeUnits = units();
            long beforeNutrition = world.availableNutrition(COMMON);
            String order = world.batchesAt(COMMON).stream()
                .map(b -> b.batchId() + ":" + b.resourceType() + ":" + b.quantity() + ":expiry" + b.expiryDay())
                .collect(Collectors.joining("|"));
            int eventOffset = world.ledger().events().size();
            SurvivalSystem.MealResult meal = allowance > 0
                ? survival.eatFromCommon(person, Math.min(allowance, requestedMaximum))
                : new SurvivalSystem.MealResult(0, 0, 0, 0);
            world.assertConservation();
            used += meal.units();
            long afterUnits = units();
            long afterNutrition = world.availableNutrition(COMMON);
            List<SimulationEvent> events = world.ledger().events().subList(eventOffset, world.ledger().events().size());
            var consumed = events.stream().filter(e -> e.type() == EventType.CONSUME)
                .flatMap(e -> e.inputs().stream()).toList();
            check(beforeUnits - afterUnits == meal.units(), "Unit delta mismatch");
            check(beforeNutrition - afterNutrition == meal.nutrition(), "Nutrition delta mismatch");
            check(consumed.stream().mapToLong(ResourceFlow::quantity).sum() == meal.units(), "Ledger units mismatch");
            check(consumed.stream().mapToLong(f -> Math.multiplyExact(f.quantity(), f.nutritionPerUnit())).sum()
                == meal.nutrition(), "Ledger nutrition mismatch");
            check(allowance > 0 || events.isEmpty(), "Exhausted harness allowance must not write core events");
            check(used <= cap && person.hunger() <= beforeHunger, "Cap or hunger invariant violated");
            String status = allowance == 0 ? "harness_cap_exhausted"
                : person.hunger() <= 5 ? "target_reached"
                : afterNutrition == 0 ? "stock_exhausted" : "unit_limit_reached";
            csv.append(String.join(",", name, Long.toString(world.day()), Integer.toString(++visit),
                Integer.toString(cap), Integer.toString(allowance), Integer.toString(initialHunger),
                Integer.toString(beforeHunger), order, Long.toString(beforeUnits), Long.toString(beforeNutrition),
                Integer.toString(meal.units()), Long.toString(meal.nutrition()),
                Integer.toString(beforeHunger - person.hunger()), Integer.toString(person.hunger()),
                Integer.toString(Math.max(0, person.hunger() - 5)),
                Long.toString(meal.nutrition() - (beforeHunger - person.hunger())),
                Integer.toString(used), Integer.toString(cap - used), Long.toString(afterUnits),
                Long.toString(afterNutrition), consumed.stream()
                    .map(f -> f.resourceType() + ":" + f.quantity()).collect(Collectors.joining("|")), status)).append('\n');
        }
        long units() {
            return world.batchesAt(COMMON).stream().filter(b -> b.resourceType().isFood())
                .mapToLong(ResourceBatch::quantity).sum();
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
