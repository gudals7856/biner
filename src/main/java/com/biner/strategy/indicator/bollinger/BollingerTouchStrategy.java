package com.biner.strategy.indicator.bollinger;

import com.biner.market.event.CandleClosedEvent;
import org.ta4j.core.BarSeries;

/**
 * 볼린저 밴드 하단 터치 여부를 판단하는 전략에 대한 인터페이스입니다.
 * 다양한 시간 간격(1분봉, 5분봉 등)에 따라 볼린저 밴드 설정값(기간, 표준편차 등)이 달라질 수 있으므로,
 * 각 시간 간격에 맞는 전략을 이 인터페이스를 통해 구현하도록 강제합니다.
 * 이는 전략의 확장성과 유연성을 높여줍니다 (전략 패턴, Strategy Pattern).
 */
public interface BollingerTouchStrategy {
  
  /**
   * 주어진 캔들 데이터(BarSeries)와 가장 최근의 캔들 정보를 바탕으로,
   * 가격이 볼린저 밴드 하단을 '터치'했는지 여부를 판단합니다.
   * '터치'의 기준은 각 구현체에서 자유롭게 정의할 수 있습니다 (예: 종가가 하단선 아래, 저가가 하단선 아래 등).
   *
   * @param series 기술적 분석을 위한 시계열 캔들 데이터
   * @param currentCandle 가장 최근에 마감된 캔들 정보
   * @return 볼린저 밴드 하단을 터치했다고 판단되면 true, 아니면 false
   */
  boolean isTouched(BarSeries series, CandleClosedEvent currentCandle);

  /**
   * 이 전략 구현체가 어떤 시간 간격(Timeframe)을 지원하는지 식별하는 문자열을 반환합니다.
   * 이 값은 BollingerStrategyFactory에서 적절한 전략을 찾는 키(key)로 사용됩니다.
   *
   * @return 지원하는 시간 간격 문자열 (예: "1m", "5m", "1h")
   */
  String getSupportedInterval();
}
