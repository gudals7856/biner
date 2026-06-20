package com.biner.strategy.indicator.bollinger;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class BollingerStrategyFactory {

  // "1m" -> OneMinuteBollingerStrategy, "5m" -> FiveMinuteBollingerStrategy
  private final Map<String, BollingerTouchStrategy> strategyMap;

  // 스프링이 List<BollingerTouchStrategy>에 모든 구현체를 자동으로 주입해 줍니다.
  public BollingerStrategyFactory(List<BollingerTouchStrategy> strategies) {
    this.strategyMap = strategies.stream()
      .collect(Collectors.toMap(
        BollingerTouchStrategy::getSupportedInterval,
        strategy -> strategy
      ));
  }

  /**
   * 요청한 타임프레임에 맞는 전략을 반환합니다.
   */
  public BollingerTouchStrategy getStrategy(String interval) {
    BollingerTouchStrategy strategy = strategyMap.get(interval);
    if (strategy == null) {
      // 지원하지 않는 타임프레임일 경우 기본값(예: 5분봉)을 반환하거나 예외를 던집니다.
      throw new IllegalArgumentException("지원하지 않는 타임프레임입니다: " + interval);
    }
    return strategy;
  }
}