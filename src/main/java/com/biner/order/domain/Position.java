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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PositionStatus status;

    @Column(name = "entry_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal entryPrice;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal amount;

    @Column(nullable = false)
    private int leverage;

    @Column(name = "dca_count", nullable = false)
    private int dcaCount = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    public enum PositionSide {
        LONG, SHORT, NONE
    }

    public enum PositionStatus {
        OPEN,
        CLOSED
    }

    @Builder
    public Position(String symbol, PositionSide side, BigDecimal entryPrice, BigDecimal amount, int leverage, int dcaCount) {
        this.symbol = symbol;
        this.side = side;
        this.status = PositionStatus.OPEN;
        this.entryPrice = entryPrice;
        this.amount = amount;
        this.leverage = leverage;
        this.dcaCount = dcaCount;
        this.createdAt = Instant.now();
    }

    public void close() {
        this.status = PositionStatus.CLOSED;
        this.closedAt = Instant.now();
    }
    
    public void updateDca(BigDecimal newAvgPrice, BigDecimal newTotalAmount, int newDcaCount) {
        this.entryPrice = newAvgPrice;
        this.amount = newTotalAmount;
        this.dcaCount = newDcaCount;
    }
}
