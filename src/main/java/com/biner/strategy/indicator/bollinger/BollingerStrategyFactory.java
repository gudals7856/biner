package com.biner.strategy.indicator.bollinger;

import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;

/**
 * 다양한 시간 간격(Interval)에 맞는 볼린저 밴드 전략 객체를 생성하고 제공하는 팩토리 클래스입니다.
 * 이 팩토리는 각 BarSeries에 맞는 '상태를 가지는' 전략 객체를 새로 생성합니다.
 * 이를 통해 각 전략 객체는 자신만의 지표(Indicator)를 캐싱하여 성능을 최적화할 수 있습니다.
 */
@Component
public class BollingerStrategyFactory {

  /**
   * 요청된 시간 간격(interval)과 시계열 데이터(series)에 맞는 볼린저 밴드 전략 객체를 '새로 생성'하여 반환합니다.
   * @param interval 전략을 찾기 위한 시간 간격 문자열 (예: "1m", "5m")
   * @param series   전략이 분석할 시계열 데이터
   * @return 해당 시간 간격과 시리즈에 맞는 새로운 BollingerTouchStrategy 구현체 인스턴스
   * @throws IllegalArgumentException 지원하지 않는 시간 간격일 경우 예외 발생
   */
  public BollingerTouchStrategy getStrategy(String interval, BarSeries series) {
    switch (interval) {
      case "1m":
        return new OneMinuteBollingerStrategy(series);
      case "5m":
        return new FiveMinuteBollingerStrategy(series);
      default:
        // 지원하지 않는 타임프레임이 요청되면 예외를 발생시켜 문제를 즉시 알립니다.
        throw new IllegalArgumentException("지원하지 않는 볼린저 밴드 전략 타임프레임입니다: " + interval);
    }
  }
}