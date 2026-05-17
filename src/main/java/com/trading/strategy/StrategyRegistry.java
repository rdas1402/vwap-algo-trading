// StrategyRegistry.java
package com.trading.strategy;

import java.util.*;

/**
 * StrategyRegistry — Single source of truth for strategy priority and registration.
 *
 * ┌─────────────────────────────────────────────────────────────┐
 *  HOW TO ADD A NEW STRATEGY:
 *    1. Create your class implementing TradingStrategy.
 *    2. Add ONE line to the REGISTRY list below.
 *    Done. No other file needs to change.
 *
 *  HOW TO CHANGE PRIORITY:
 *    Reorder the rows in REGISTRY. Top row = Priority 1 (highest).
 *
 *  HOW TO DISABLE A STRATEGY (without deleting it):
 *    Change its enabled flag to false.
 * └─────────────────────────────────────────────────────────────┘
 */
public class StrategyRegistry {

    // ═══════════════════════════════════════════════════════════════
    //  EDIT ONLY THIS SECTION
    //  Row order = execution priority  (top row runs first)
    // ═══════════════════════════════════════════════════════════════
    private static final List<StrategyEntry> REGISTRY = Arrays.asList(

        // P1 — highest priority
//        StrategyEntry.of("EMA/VWAP Pullback",        EMAVWAPPullbackStrategy.class,  true),

        // P2
        StrategyEntry.of("Bullish Engulfing",         BullishEngulfingStrategy.class, true),

        // P3
        StrategyEntry.of("Hammer Reversal",           HammerReversalStrategy.class,   true),

        // P4
        StrategyEntry.of("Morning Star",              MorningStarStrategy.class, true),

        // P5
        StrategyEntry.of("Breakout + Retest",         BreakoutRetestStrategy.class,   true),

        // P6
        StrategyEntry.of("VWAP Options (Reversal)",   VWAPStrategy.class,             true)


            // ── Paste a new line here to add another strategy ──────────
        // StrategyEntry.of("My New Strategy", MyNewStrategy.class, true)
    );
    // ═══════════════════════════════════════════════════════════════


    // ---------------------------------------------------------------
    //  Internal descriptor — do not edit below this line
    // ---------------------------------------------------------------

    public static class StrategyEntry {
        public final String                            name;
        public final Class<? extends TradingStrategy> strategyClass;
        public final boolean                           enabled;

        private StrategyEntry(String name,
                               Class<? extends TradingStrategy> strategyClass,
                               boolean enabled) {
            this.name          = name;
            this.strategyClass = strategyClass;
            this.enabled       = enabled;
        }

        public static StrategyEntry of(String name,
                                        Class<? extends TradingStrategy> strategyClass,
                                        boolean enabled) {
            return new StrategyEntry(name, strategyClass, enabled);
        }
    }

    /**
     * Build and return the ordered list of enabled strategy instances.
     * Priority is assigned automatically from list position (1 = top row).
     *
     * Strategies that implement {@link EngineAware} receive an engine
     * reference automatically — no manual wiring needed.
     *
     * @param engine  The running TradingStrategyEngine instance.
     * @return        Ordered, instantiated, ready-to-run strategies.
     */
    public static List<TradingStrategy> buildStrategies(TradingStrategyEngine engine) {
        List<TradingStrategy> result = new ArrayList<>();

        System.out.println("\n📋 Loading strategies from registry...");

        for (int i = 0; i < REGISTRY.size(); i++) {
            StrategyEntry entry    = REGISTRY.get(i);
            int           priority = i + 1;

            if (!entry.enabled) {
                System.out.println("   ⏭️  [P" + priority + "] "
                        + entry.name + " — DISABLED (skipped)");
                continue;
            }

            try {
                // Instantiate via no-arg constructor (standard for all strategies)
                TradingStrategy strategy =
                        entry.strategyClass.getDeclaredConstructor().newInstance();

                // Inject engine reference if the strategy needs one
                if (strategy instanceof EngineAware) {
                    ((EngineAware) strategy).setEngineContext(engine);
                }

                result.add(strategy);
                System.out.println("   ✅ [P" + priority + "] "
                        + entry.name
                        + "  (" + entry.strategyClass.getSimpleName() + ")");

            } catch (Exception e) {
                System.err.println("   ❌ [P" + priority + "] Failed to load "
                        + entry.strategyClass.getSimpleName()
                        + ": " + e.getMessage());
            }
        }

        System.out.println("📋 " + result.size() + " strateg"
                + (result.size() == 1 ? "y" : "ies") + " loaded.\n");
        return result;
    }

    /**
     * Print a startup summary table — useful for quick visual confirmation.
     */
    public static void printSummary() {
        System.out.println("\n╔══════════════════════════════════════════════════════════╗");
        System.out.println("║           STRATEGY REGISTRY  (execution order)          ║");
        System.out.println("╠══════╦══════════════════════════════════════╦════════════╣");
        System.out.println("║  Pri ║ Name                                 ║ Status     ║");
        System.out.println("╠══════╬══════════════════════════════════════╬════════════╣");
        for (int i = 0; i < REGISTRY.size(); i++) {
            StrategyEntry e = REGISTRY.get(i);
            System.out.printf("║  %-3d ║ %-36s ║ %-10s ║%n",
                    i + 1, e.name, e.enabled ? "ENABLED" : "DISABLED");
        }
        System.out.println("╚══════╩══════════════════════════════════════╩════════════╝\n");
    }
}
