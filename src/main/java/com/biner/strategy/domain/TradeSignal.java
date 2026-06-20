package com.biner.strategy.domain;

import java.math.BigDecimal;

public record TradeSignal(
    String symbol,
    SignalType type,
    BigDecimal price,
    BigDecimal amount,
    String reason
) {
    public enum SignalType {
        LONG, SHORT, CLOSE_LONG, CLOSE_SHORT
    }
}
