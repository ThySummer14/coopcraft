import com.thysummer.coopcraft.civilization.simulation.*;
import java.util.*;
import java.util.stream.Collectors;

/** Bounded research harness. No game code, random input, replenishment, or day advancement. */
public final class RequestOrderExperiments {
    private static final StorageOwner COMMON = StorageOwner.camp("camp");
    private static final List<String> IDS = List.of("A", "B", "C");
    private static final int[] HUNGER = {15, 50, 100};
    private static final int CAP = 3;
    private record Stock(ResourceType type, int quantity, int expiry) {}
    private record Outcome(String signature, String initialStock, String actualOrder) {}

    public static void main(String[] args) {
        String csv = run();
        check(csv.equals(run()), "Deterministic full replay differs");
        System.out.print(csv);
        System.err.println("PASS: 12 scenarios; 48 rows; conservation, ledger, cumulative cap, real norm charges, identical stock, input reversal, deterministic replay");
    }

    private static String run() {
        StringBuilder csv = new StringBuilder("scenario,row,day,cap,governance,input_order,actual_order,person,initial_hunger,initial_need,received_units,food_nutrition,hunger_relief,hunger_after,remaining_need,nutrition_beyond_relief,supervision_hours,initial_stock_units,initial_stock_nutrition,final_stock_units,final_stock_nutrition,consumed_by_type,core_calls,revisit_units,norm_events,target_reached_people,max_remaining_need\n");
        List<List<Stock>> inventories = List.of(
            List.of(new Stock(ResourceType.WILD_FRUIT, 4, 1)),
            List.of(new Stock(ResourceType.WILD_FRUIT, 2, 1), new Stock(ResourceType.GAME, 2, 2)));
        for (int i = 0; i < inventories.size(); i++) {
            String group = i == 0 ? "fruit4" : "fruit2_game2";
            String stock = null;
            for (String mode : List.of("arrival_active", "priority_inactive", "priority_active")) {
                Outcome forward = trial(csv, group, inventories.get(i), mode, IDS);
                Outcome reverse = trial(csv, group, inventories.get(i), mode, List.of("C", "B", "A"));
                if (stock == null) stock = forward.initialStock();
                check(stock.equals(forward.initialStock()) && stock.equals(reverse.initialStock()), "Inventory control differs");
                if (mode.startsWith("priority")) {
                    check(forward.signature().equals(reverse.signature()), "Core priority depends on input permutation");
                    check(forward.actualOrder().equals(reverse.actualOrder()), "Core sorted order differs");
                }
            }
        }
        return csv.toString();
    }

    private static Outcome trial(StringBuilder csv, String group, List<Stock> stocks, String mode, List<String> input) {
        SimulationWorld world = new SimulationWorld();
        world.registerCamp(new Camp("camp", "Request-order research"));
        for (int i = 0; i < IDS.size(); i++) {
            String id = IDS.get(i);
            world.registerHousehold(new Household("h" + id, "camp"));
            world.registerPerson(new Person(id, "camp", "h" + id, Person.AgeBand.ADULT, HUNGER[i], 50));
        }
        boolean active = !mode.equals("priority_inactive");
        if (active) {
            // Exogenous initial condition, not a claim of endogenous adoption.
            world.registerNorm(new Norm(Norm.NEED_FIRST_COMMON_STORE, "camp", CAP,
                Set.of("A", "B"), Set.of(), Set.copyOf(IDS), 50, Norm.Status.ACTIVE, List.of()));
        }
        for (Stock stock : stocks) {
            world.createInitialBatch(stock.type(), stock.quantity(), 50, COMMON, 0, stock.expiry());
            world.assertConservation();
        }
        String initialStock = world.batchesAt(COMMON).stream().map(b ->
            b.batchId() + ":" + b.resourceType() + ":" + b.quantity() + ":" + b.expiryDay())
            .collect(Collectors.joining("|"));
        long initialUnits = units(world), initialNutrition = world.availableNutrition(COMMON);
        NormSystem norms = new NormSystem(world, SimulationConfig.defaults());
        SurvivalSystem survival = new SurvivalSystem(world, SimulationConfig.defaults());
        List<Person> incoming = input.stream().map(world::person).toList();
        List<Person> ordered = mode.startsWith("priority") ? norms.prioritizeFoodRequests(incoming) : incoming;
        String order = ordered.stream().map(Person::id).collect(Collectors.joining("|"));
        Map<String, Integer> allocated = new LinkedHashMap<>(), calls = new LinkedHashMap<>(), revisit = new LinkedHashMap<>();
        Map<String, Long> nutrition = new LinkedHashMap<>();
        Map<String, List<String>> consumed = new LinkedHashMap<>();
        for (String id : IDS) {
            allocated.put(id, 0); calls.put(id, 0); revisit.put(id, 0); nutrition.put(id, 0L); consumed.put(id, new ArrayList<>());
        }
        // Each person gets one meal with the remaining allowance; a second round tests
        // same-day accounting without changing order, stock, hunger inputs, or day.
        int positiveMeals = 0;
        for (int round = 0; round < 2; round++) {
            for (Person person : ordered) {
                String id = person.id();
                world.assertConservation();
                int remaining = CAP - allocated.get(id);
                int coreRemaining = norms.limitCommonAllocation(person, CAP);
                check(coreRemaining == (active ? remaining : CAP), "Real norm cumulative allowance mismatch");
                int allowance = Math.min(remaining, coreRemaining);
                if (allowance == 0) continue; // No zero-limit core call or fabricated refusal.
                long beforeUnits = units(world), beforeNutrition = world.availableNutrition(COMMON);
                int beforeHunger = person.hunger(), offset = world.ledger().events().size();
                var meal = survival.eatFromCommon(person, allowance);
                calls.merge(id, 1, Integer::sum);
                List<SimulationEvent> events = List.copyOf(world.ledger().events().subList(offset, world.ledger().events().size()));
                var flows = events.stream().filter(e -> e.type() == EventType.CONSUME).flatMap(e -> e.inputs().stream()).toList();
                check(beforeUnits - units(world) == meal.units(), "Stock units mismatch");
                check(beforeNutrition - world.availableNutrition(COMMON) == meal.nutrition(), "Stock nutrition mismatch");
                check(flows.stream().mapToLong(ResourceFlow::quantity).sum() == meal.units(), "Consumption units mismatch");
                check(flows.stream().mapToLong(f -> f.quantity() * f.nutritionPerUnit()).sum() == meal.nutrition(), "Consumption nutrition mismatch");
                check(beforeHunger >= person.hunger() && beforeHunger - person.hunger() <= meal.nutrition(), "Relief invariant");
                if (meal.units() > 0) {
                    check(world.ledger().event(meal.requestEventId()).orElseThrow().type() == EventType.SHARE_REQUEST, "Request evidence missing");
                    check(world.ledger().event(meal.allocationEventId()).orElseThrow().type() == EventType.SHARE, "Allocation evidence missing");
                    // Match the game service: one record per positive meal, using its returned
                    // last request/allocation IDs even when the meal spans multiple batches.
                    positiveMeals++;
                    norms.recordCommonAllocation(person, meal.units(), meal.requestEventId(), meal.allocationEventId());
                    allocated.merge(id, meal.units(), Integer::sum);
                    nutrition.merge(id, meal.nutrition(), Long::sum);
                    if (round == 1) revisit.merge(id, meal.units(), Integer::sum);
                    flows.forEach(f -> consumed.get(id).add(f.resourceType() + ":" + f.quantity()));
                }
                world.assertConservation();
                check(allocated.get(id) <= CAP, "Harness cumulative cap exceeded");
                check(norms.limitCommonAllocation(person, CAP) == (active ? CAP - allocated.get(id) : CAP), "Post-meal norm allowance mismatch");
            }
        }
        long normEvents = world.ledger().events().stream().filter(e -> e.type() == EventType.NORM_ENFORCED).count();
        check(world.ledger().events().stream().noneMatch(e -> e.type() == EventType.NORM_VIOLATION), "Unexpected violation");
        check(normEvents == (active ? positiveMeals : 0), "Enforcement events mismatch");
        check(world.person("A").socialHours() == (active ? 1 : 0)
            && world.person("B").socialHours() == (active ? 1 : 0) && world.person("C").socialHours() == 0, "Supervisor charges mismatch");
        check(revisit.values().stream().mapToInt(Integer::intValue).sum() == 0, "Second round bypassed cap or target");
        check(world.day() == 0, "Unexpected time advancement");
        long totalUnits = allocated.values().stream().mapToInt(Integer::intValue).sum();
        long totalNutrition = nutrition.values().stream().mapToLong(Long::longValue).sum();
        check(initialUnits == totalUnits + units(world), "Aggregate units mismatch");
        check(initialNutrition == totalNutrition + world.availableNutrition(COMMON), "Aggregate nutrition mismatch");
        String scenario = group + "_" + mode + "_" + String.join("", input);
        StringBuilder signature = new StringBuilder();
        int totalAfter = 0, totalNeed = 0, totalHours = 0, totalCalls = 0, reached = 0, maxNeed = 0;
        for (int i = 0; i < IDS.size(); i++) {
            String id = IDS.get(i); Person p = world.person(id);
            int relief = HUNGER[i] - p.hunger(), need = Math.max(0, p.hunger() - 5);
            String metrics = String.join(",", id, "" + HUNGER[i], "" + Math.max(0, HUNGER[i] - 5),
                "" + allocated.get(id), "" + nutrition.get(id), "" + relief, "" + p.hunger(), "" + need,
                "" + (nutrition.get(id) - relief), "" + p.socialHours(), "" + initialUnits, "" + initialNutrition,
                "" + units(world), "" + world.availableNutrition(COMMON), String.join("|", consumed.get(id)),
                "" + calls.get(id), "" + revisit.get(id), "", "" + (p.hunger() <= 5 ? 1 : 0), "" + need);
            append(csv, scenario, "person", active, input, order, metrics);
            signature.append(metrics).append('\n');
            totalAfter += p.hunger(); totalNeed += need; totalHours += p.socialHours(); totalCalls += calls.get(id);
            reached += p.hunger() <= 5 ? 1 : 0; maxNeed = Math.max(maxNeed, need);
        }
        int initialHunger = Arrays.stream(HUNGER).sum(), initialNeed = Arrays.stream(HUNGER).map(h -> Math.max(0, h - 5)).sum();
        append(csv, scenario, "total", active, input, order, String.join(",", "ALL", "" + initialHunger, "" + initialNeed,
            "" + totalUnits, "" + totalNutrition, "" + (initialHunger - totalAfter), "" + totalAfter, "" + totalNeed,
            "" + (totalNutrition - (initialHunger - totalAfter)), "" + totalHours, "" + initialUnits, "" + initialNutrition,
            "" + units(world), "" + world.availableNutrition(COMMON), "", "" + totalCalls, "0", "" + normEvents, "" + reached, "" + maxNeed));
        return new Outcome(signature.toString(), initialStock, order);
    }
    private static void append(StringBuilder csv, String scenario, String row, boolean active, List<String> input, String order, String metrics) {
        csv.append(String.join(",", scenario, row, "0", "" + CAP, active ? "active_recorded" : "absent_record_called",
            String.join("|", input), order, metrics)).append('\n');
    }
    private static long units(SimulationWorld world) {
        return world.batchesAt(COMMON).stream().filter(b -> b.resourceType().isFood()).mapToLong(ResourceBatch::quantity).sum();
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
