package com.biner.market.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 5분봉 캔들 마감 시 발행되는 이벤트
 */
public record CandleClosedEvent(
    String symbol,
    Instant openTime,
    Instant closeTime,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    BigDecimal volume
) {}
