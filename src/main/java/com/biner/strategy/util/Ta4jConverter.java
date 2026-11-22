package com.biner.strategy.util;

import com.biner.common.util.Intervals;
import com.biner.market.event.CandleClosedEvent;
import org.ta4j.core.Bar;
import org.ta4j.core.BaseBar;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 내부 캔들 이벤트(CandleClosedEvent)를 ta4j의 Bar 객체로 변환하는 유틸리티 클래스입니다.
 */
public final class Ta4jConverter {

  private static final ZoneId UTC = ZoneId.of("UTC");

  private Ta4jConverter() {
  }

  /**
   * CandleClosedEvent를 ta4j의 Bar 객체로 변환합니다.
   * 캔들 길이는 이벤트의 interval 값("1m", "5m" 등)에서 계산합니다.
   */
  public static Bar convert(CandleClosedEvent event) {
    ZonedDateTime endTime = event.closeTime().atZone(UTC);
    return new BaseBar(
      Intervals.toDuration(event.interval()),
      endTime,
      event.open(),
      event.high(),
      event.low(),
      event.close(),
      event.volume()
    );
  }
}
