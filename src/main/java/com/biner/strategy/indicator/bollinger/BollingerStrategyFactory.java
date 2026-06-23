package com.biner.strategy.indicator.bollinger;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 다양한 시간 간격(Interval)에 맞는 볼린저 밴드 전략 객체를 생성하고 제공하는 팩토리 클래스입니다.
 * 예를 들어, "1m" 문자열을 받으면 1분봉에 맞는 볼린저 밴드 전략을, "5m"을 받으면 5분봉 전략을 반환합니다.
 * 이를 통해 StrategyEngine에서는 시간 간격에 따라 분기 처리할 필요 없이, 이 팩토리를 통해 적절한 전략을 주입받아 사용할 수 있습니다.
 */
@Component
public class BollingerStrategyFactory {

  // 시간 간격(예: "1m")을 키로, 해당 전략 구현체를 값으로 갖는 맵
  private final Map<String, BollingerTouchStrategy> strategyMap;

  /**
   * 생성자 주입을 통해 Spring 컨테이너에 등록된 모든 BollingerTouchStrategy 구현체들을 리스트로 받아옵니다.
   * @param strategies Spring이 자동으로 주입해주는 BollingerTouchStrategy의 모든 빈(Bean)
   */
  public BollingerStrategyFactory(List<BollingerTouchStrategy> strategies) {
    // 주입받은 전략 리스트를 스트림으로 변환하여 맵으로 만듭니다.
    this.strategyMap = strategies.stream()
      .collect(Collectors.toMap(
        BollingerTouchStrategy::getSupportedInterval, // 각 전략의 지원 시간 간격(예: "1m")을 키로 사용
        strategy -> strategy                          // 전략 객체 자체를 값으로 사용
      ));
  }

  /**
   * 요청된 시간 간격(interval)에 맞는 볼린저 밴드 전략 객체를 반환합니다.
   * @param interval 전략을 찾기 위한 시간 간격 문자열 (예: "1m", "5m")
   * @return 해당 시간 간격에 맞는 BollingerTouchStrategy 구현체
   * @throws IllegalArgumentException 지원하지 않는 시간 간격일 경우 예외 발생
   */
  public BollingerTouchStrategy getStrategy(String interval) {
    BollingerTouchStrategy strategy = strategyMap.get(interval);
    if (strategy == null) {
      // 맵에 해당 키가 없으면, 지원하지 않는 타임프레임이므로 예외를 발생시켜 문제를 즉시 알립니다.
      throw new IllegalArgumentException("지원하지 않는 타임프레임입니다: " + interval);
    }
    return strategy;
  }
}
