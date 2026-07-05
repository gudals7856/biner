package com.biner.market.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * Candle 엔티티의 복합 기본 키(Composite Primary Key)를 정의하는 클래스입니다.
 * 복합 키는 open_time, symbol, timeframe 세 개의 컬럼으로 구성됩니다.
 *
 * @Embeddable 애노테이션은 이 객체가 다른 엔티티에 포함될 수 있음을 나타냅니다.
 * 복합 키 클래스는 반드시 Serializable 인터페이스를 구현해야 하며,
 * equals()와 hashCode() 메서드를 오버라이드하여 값 기반의 동등성을 보장해야 합니다.
 */
@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class CandleId implements Serializable {

    // 캔들 시작 시간
    @Column(name = "open_time", nullable = false)
    private Instant openTime;

    // 코인 심볼 (예: "BTCUSDT")
    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    // 시간봉 종류
    @Column(name = "timeframe", nullable = false, length = 10)
    private String timeframe;
}
