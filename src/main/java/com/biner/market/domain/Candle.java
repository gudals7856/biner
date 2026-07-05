package com.biner.market.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 데이터베이스의 'candles' 하이퍼테이블과 매핑되는 엔티티 클래스입니다.
 * TimescaleDB에 최적화된 구조로, open_time, symbol, timeframe을 복합 기본 키로 사용합니다.
 */
@Entity
@Table(name = "candles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Candle {

    // 복합 기본 키를 포함하는 @EmbeddedId
    @EmbeddedId
    private CandleId id;

    // 캔들 마감 시간
    @Column(name = "close_time", nullable = false)
    private Instant closeTime;

    // 시가 (캔들 시작 시점의 가격)
    @Column(name = "open_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal open;

    // 고가 (해당 캔들 시간 내 최고 가격)
    @Column(name = "high_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal high;

    // 저가 (해당 캔들 시간 내 최저 가격)
    @Column(name = "low_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal low;

    // 종가 (캔들 마감 시점의 최종 가격)
    @Column(name = "close_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal close;

    // 해당 캔들 시간 동안의 거래량
    @Column(name = "volume", nullable = false, precision = 20, scale = 8)
    private BigDecimal volume;

    /**
     * 빌더 패턴을 사용하여 Candle 객체를 생성하기 위한 생성자입니다.
     */
    @Builder
    public Candle(CandleId id, Instant closeTime, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal volume) {
        this.id = id;
        this.closeTime = closeTime;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    // 편의 메서드들
    public String getSymbol() {
        return this.id != null ? this.id.getSymbol() : null;
    }

    public Instant getOpenTime() {
        return this.id != null ? this.id.getOpenTime() : null;
    }

    public String getTimeframe() {
        return this.id != null ? this.id.getTimeframe() : null;
    }
}
