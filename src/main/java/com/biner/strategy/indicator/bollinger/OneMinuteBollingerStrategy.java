package com.biner.strategy.indicator.bollinger;

import com.biner.market.event.CandleClosedEvent;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;

/**
 * 1분봉(1m) 차트에 특화된 볼린저 밴드 하단 터치 전략 구현체입니다.
 * 1분봉은 변동성이 크기 때문에, 다른 시간봉과 다른 파라미터(표준편차 승수 등)를 적용할 수 있습니다.
 */
@Component
public class OneMinuteBollingerStrategy implements BollingerTouchStrategy {

  // 볼린저 밴드 계산에 사용할 캔들 개수 (기간)
  private static final int BAR_COUNT = 20;
  // 표준편차 승수. 1분봉은 변동성이 크므로 일반적인 2.0보다 약간 낮은 1.9를 사용하여 터치 기회를 늘립니다.
  private static final double DEVIATION_MULTIPLIER = 1.9;

  /**
   * 1분봉 캔들이 볼린저 밴드 하단을 터치했는지 판단합니다.
   * @param series 시계열 캔들 데이터
   * @param currentCandle 가장 최근 마감된 캔들
   * @return 현재 캔들의 종가 또는 저가가 하단 밴드 값보다 작거나 같으면 true
   */
  @Override
  public boolean isTouched(BarSeries series, CandleClosedEvent currentCandle) {
    // 1. 기술적 지표 계산에 필요한 기본 데이터(종가)를 가져옵니다.
    ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
    
    // 2. 볼린저 밴드의 중심선(20일 이동평균선)을 계산합니다.
    SMAIndicator sma = new SMAIndicator(closePrice, BAR_COUNT);
    BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma);

    // 3. 종가의 표준편차를 계산합니다.
    StandardDeviationIndicator sd = new StandardDeviationIndicator(closePrice, BAR_COUNT);

    // 4. 중심선과 표준편차, 그리고 승수를 이용하여 하단 밴드를 계산합니다.
    BollingerBandsLowerIndicator bbLower =
      new BollingerBandsLowerIndicator(bbMiddle, sd, series.numOf(DEVIATION_MULTIPLIER));

    // 5. 마지막 캔들의 하단 밴드 값을 가져옵니다.(ta4j의 Num 타입을 BigDecimal로 안전하게 변환합니다.)
    Num lowerBandNum = bbLower.getValue(series.getEndIndex());
    BigDecimal lowerBandValue = new BigDecimal(lowerBandNum.toString());

    // 6. 현재 캔들의 종가와 저가를 가져옵니다.
    BigDecimal currentClose = currentCandle.close();
    BigDecimal currentLow = currentCandle.low();

    // 7. 현재 캔들의 종가 또는 저가가 하단 밴드에 닿거나 그 아래로 내려갔는지 확인합니다.
    // compareTo의 결과가 0(같음) 또는 -1(작음)이면 조건을 만족합니다.
    return currentClose.compareTo(lowerBandValue) <= 0 || currentLow.compareTo(lowerBandValue) <= 0;
  }

  /**
   * 이 전략이 "1m" 시간 간격을 지원함을 명시합니다.
   */
  @Override
  public String getSupportedInterval() {
    return "1m";
  }
}
