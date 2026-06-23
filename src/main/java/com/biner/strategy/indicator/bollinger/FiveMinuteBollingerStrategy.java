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
 * 5분봉(5m) 차트에 특화된 볼린저 밴드 하단 터치 전략 구현체입니다.
 * 5분봉은 1분봉보다 안정적이므로, 표준적인 볼린저 밴드 파라미터를 사용하고,
 * 너무 잦은 매매를 방지하기 위해 약간의 버퍼(buffer)를 적용할 수 있습니다.
 */
@Component
public class FiveMinuteBollingerStrategy implements BollingerTouchStrategy {

  // 볼린저 밴드 기간 (20개 캔들)
  private static final int BAR_COUNT = 20;
  // 표준편차 승수 (일반적인 값인 2.0 사용)
  private static final double DEVIATION_MULTIPLIER = 2.0;
  // 터치 판단을 위한 버퍼. 밴드 하단선에 정확히 닿지 않고 근처까지만 와도 터치로 인정하기 위함. (0.5%)
  private static final double BUFFER_PERCENT = 0.005; 

  /**
   * 5분봉 캔들이 볼린저 밴드 하단을 터치했는지 판단합니다.
   * @param series 시계열 캔들 데이터
   * @param currentCandle 가장 최근 마감된 캔들
   * @return 현재 캔들의 종가 또는 저가가 (하단 밴드 + 버퍼) 값보다 작거나 같으면 true
   */
  @Override
  public boolean isTouched(BarSeries series, CandleClosedEvent currentCandle) {
    ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
    SMAIndicator sma = new SMAIndicator(closePrice, BAR_COUNT);
    BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma);
    StandardDeviationIndicator sd = new StandardDeviationIndicator(closePrice, BAR_COUNT);
    BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, sd, series.numOf(DEVIATION_MULTIPLIER));

    // 마지막 캔들의 하단 밴드 값을 가져옵니다.(ta4j의 Num 타입을 BigDecimal로 안전하게 변환합니다.)
    Num lowerBandNum = bbLower.getValue(series.getEndIndex());
    BigDecimal lowerBandValue = new BigDecimal(lowerBandNum.toString());
    
    // 버퍼 값을 계산합니다. (하단 밴드 값 * 0.5%)
    BigDecimal buffer = lowerBandValue.multiply(BigDecimal.valueOf(BUFFER_PERCENT));
    
    // 최종적으로 비교할 가격 = 하단 밴드 값 + 버퍼
    BigDecimal targetPrice = lowerBandValue.add(buffer);

    BigDecimal currentClose = currentCandle.close();
    BigDecimal currentLow = currentCandle.low();

    // 현재 캔들의 종가 또는 저가가 버퍼를 포함한 목표 가격보다 작거나 같은지 확인합니다.
    return currentClose.compareTo(targetPrice) <= 0 || currentLow.compareTo(targetPrice) <= 0;
  }

  /**
   * 이 전략이 "5m" 시간 간격을 지원함을 명시합니다.
   */
  @Override
  public String getSupportedInterval() {
    return "5m";
  }
}
