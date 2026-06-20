package com.biner.order.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "positions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Position {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PositionSide side;

    @Column(name = "entry_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal entryPrice;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal amount;

    @Column(nullable = false)
    private int leverage;

    // DCA(분할 매수) 횟수를 정확히 트래킹하기 위한 필드 추가
    @Column(name = "dca_count", nullable = false)
    private int dcaCount = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public enum PositionSide {
        LONG, SHORT, NONE
    }

    @Builder
    public Position(String symbol, PositionSide side, BigDecimal entryPrice, BigDecimal amount, int leverage, int dcaCount) {
        this.symbol = symbol;
        this.side = side;
        this.entryPrice = entryPrice;
        this.amount = amount;
        this.leverage = leverage;
        this.dcaCount = dcaCount; // 초기화 시 주입
        this.createdAt = Instant.now();
    }
}
