package com.biner.strategy.util;

import com.biner.market.event.CandleClosedEvent;
import org.ta4j.core.Bar;
import org.ta4j.core.BaseBar;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 캔들 이벤트를 오픈소스 퀀트 라이브러리(ta4j)의 Bar 객체로 변환하는 컨버터
 */
public class Ta4jConverter {
    
    public static Bar convert(CandleClosedEvent event) {
        // 바이낸스 시간(UTC 기준)을 ZonedDateTime으로 변환
        ZonedDateTime endTime = event.closeTime().atZone(ZoneId.of("UTC"));
        
        return new BaseBar(
                Duration.ofMinutes(5),
                endTime,
                event.open(),
                event.high(),
                event.low(),
                event.close(),
                event.volume()
        );
    }
}
