package com.biner.market.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 데이터베이스의 'candles' 테이블과 매핑되는 엔티티 클래스입니다.
 * 캔들(봉) 데이터를 저장하며, 각 캔들은 특정 시간 동안의 가격 변동(시가, 고가, 저가, 종가)과 거래량을 나타냅니다.
 * 이 데이터는 기술적 분석 및 백테스팅에 사용됩니다.
 */
@Entity
@Table(name = "candles", indexes = {
    // 'symbol'과 'open_time' 컬럼에 복합 유니크 인덱스를 생성합니다.
    // 동일한 코인의 동일한 시작 시간을 가진 캔들이 중복 저장되는 것을 방지하여 데이터 무결성을 보장합니다.
    @Index(name = "idx_symbol_open_time", columnList = "symbol, open_time", unique = true)
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 스펙을 위한 기본 생성자
public class Candle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 코인 심볼 (예: "BTCUSDT")
    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    // 캔들 시작 시간
    @Column(name = "open_time", nullable = false)
    private Instant openTime;

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

    // 캔들 시간 간격 (예: "1m", "5m")
    @Column(name = "interval", nullable = false, precision = 20, scale = 8)
    private String interval;
    /**
     * 빌더 패턴을 사용하여 Candle 객체를 생성하기 위한 생성자입니다.
     */
    @Builder
    public Candle(String symbol, Instant openTime, Instant closeTime, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal volume, String interval) {
        this.symbol = symbol;
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.interval = interval;
    }
}
