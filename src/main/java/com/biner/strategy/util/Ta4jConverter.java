package com.biner.strategy.util;

import com.biner.market.event.CandleClosedEvent;
import org.ta4j.core.Bar;
import org.ta4j.core.BaseBar;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 애플리케이션 내부에서 사용하는 캔들 이벤트(CandleClosedEvent) 객체를
 * 기술적 분석 라이브러리인 'ta4j'에서 사용하는 'Bar' 객체로 변환하는 유틸리티 클래스입니다.
 * ta4j 라이브러리는 자체적인 Bar 인터페이스와 BaseBar 구현체를 사용하여 기술적 지표를 계산하므로,
 * 우리 시스템의 데이터 모델을 ta4j가 이해할 수 있는 형태로 변환해주는 과정이 필요합니다.
 */
public class Ta4jConverter {
    
    /**
     * CandleClosedEvent를 ta4j의 Bar 객체로 변환합니다.
     * @param event 변환할 캔들 마감 이벤트
     * @return ta4j 라이브러리에서 사용할 수 있는 Bar 객체
     */
    public static Bar convert(CandleClosedEvent event) {
        // ta4j의 Bar 객체는 시간 정보를 ZonedDateTime 타입으로 요구합니다.
        // 바이낸스에서 받은 타임스탬프(UTC 기준)를 ZonedDateTime 객체로 변환합니다.
        ZonedDateTime endTime = event.closeTime().atZone(ZoneId.of("UTC"));
        
        // BaseBar 객체를 생성하여 반환합니다.
        // BaseBar는 Bar 인터페이스의 기본 구현체입니다.
        return new BaseBar(
                Duration.ofMinutes(5), // 캔들의 시간 간격 (현재 5분봉으로 고정되어 있으나, 동적으로 변경 가능)
                endTime,               // 캔들 종료 시간
                event.open(),          // 시가
                event.high(),          // 고가
                event.low(),           // 저가
                event.close(),         // 종가
                event.volume()         // 거래량
        );
    }
}
