package com.biner.strategy.indicator.bollinger;

import com.biner.market.event.CandleClosedEvent;
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
// @Component // 싱글톤으로 관리하지 않으므로 제거. Factory에서 new로 생성합니다.
public class OneMinuteBollingerStrategy implements BollingerTouchStrategy {

  // 볼린저 밴드 계산에 사용할 캔들 개수 (기간)
  private static final int BAR_COUNT = 20;
  // 표준편차 승수. 1분봉은 변동성이 크므로 일반적인 2.0보다 약간 낮은 1.9를 사용하여 터치 기회를 늘립니다.
  private static final double DEVIATION_MULTIPLIER = 1.9;

  private final BarSeries series;
  private final BollingerBandsLowerIndicator bbLower;

  /**
   * 생성자에서 BarSeries를 받아 필요한 지표들을 미리 생성하고 캐싱합니다.
   * 이렇게 하면 isTouched()가 호출될 때마다 지표를 새로 계산하는 비효율을 막을 수 있습니다.
   * @param series 분석할 시계열 데이터
   */
  public OneMinuteBollingerStrategy(BarSeries series) {
    this.series = series;
    ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
    SMAIndicator sma = new SMAIndicator(closePrice, BAR_COUNT);
    BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma);
    StandardDeviationIndicator sd = new StandardDeviationIndicator(closePrice, BAR_COUNT);
    this.bbLower = new BollingerBandsLowerIndicator(bbMiddle, sd, series.numOf(DEVIATION_MULTIPLIER));
  }

  /**
   * 1분봉 캔들이 볼린저 밴드 하단을 터치했는지 판단합니다.
   * @param series 시계열 캔들 데이터
   * @param currentCandle 가장 최근 마감된 캔들
   * @return 현재 캔들의 종가 또는 저가가 하단 밴드 값보다 작거나 같으면 true
   */
  @Override
  public boolean isTouched(BarSeries series, CandleClosedEvent currentCandle) {
    // 1. 생성자에서 미리 계산된 지표의 마지막 값을 가져옵니다.
    Num lowerBandNum = bbLower.getValue(series.getEndIndex());
    BigDecimal lowerBandValue = new BigDecimal(lowerBandNum.toString());

    // 2. 현재 캔들의 종가와 저가를 가져옵니다.
    BigDecimal currentClose = currentCandle.close();
    BigDecimal currentLow = currentCandle.low();

    // 3. 현재 캔들의 종가 또는 저가가 하단 밴드에 닿거나 그 아래로 내려갔는지 확인합니다.
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