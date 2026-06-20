package com.biner.market.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "candles", indexes = {
    @Index(name = "idx_symbol_open_time", columnList = "symbol, open_time", unique = true)
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Candle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "open_time", nullable = false)
    private Instant openTime;

    @Column(name = "close_time", nullable = false)
    private Instant closeTime;

    @Column(name = "open_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal open;

    @Column(name = "high_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal high;

    @Column(name = "low_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal low;

    @Column(name = "close_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal close;

    @Column(name = "volume", nullable = false, precision = 20, scale = 8)
    private BigDecimal volume;

    @Builder
    public Candle(String symbol, Instant openTime, Instant closeTime, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal volume) {
        this.symbol = symbol;
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }
}
