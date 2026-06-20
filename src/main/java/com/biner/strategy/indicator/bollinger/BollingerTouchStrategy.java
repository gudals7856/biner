package com.biner.strategy.indicator.bollinger;

import com.biner.market.event.CandleClosedEvent;
import org.ta4j.core.BarSeries;

public interface BollingerTouchStrategy {
  /**
   * 현재 캔들이 볼린저 밴드 하단을 터치했는지 여부를 반환합니다.
   */
  boolean isTouched(BarSeries series, CandleClosedEvent currentCandle);

  /**
   * 이 전략이 담당할 타임프레임(예: "1m", "5m")을 반환합니다.
   */
  String getSupportedInterval();
}