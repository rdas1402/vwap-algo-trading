// EngineAware.java
package com.trading.strategy;

/**
 * EngineAware — optional interface for strategies that need a reference
 * to the TradingStrategyEngine (e.g. to call helper methods not on the
 * TradingStrategy interface).
 *
 * The StrategyRegistry checks for this interface automatically during
 * strategy instantiation and calls setEngineContext() if present.
 * You never need to wire this up manually in registerStrategies().
 *
 * Usage — in your strategy class:
 *
 *   public class Case2EMAVWAPPullbackStrategy
 *           implements TradingStrategy, EngineAware {
 *
 *       private TradingStrategyEngine engine;
 *
 *       @Override
 *       public void setEngineContext(TradingStrategyEngine engine) {
 *           this.engine = engine;
 *       }
 *   }
 */
public interface EngineAware {
    void setEngineContext(TradingStrategyEngine engine);
}
