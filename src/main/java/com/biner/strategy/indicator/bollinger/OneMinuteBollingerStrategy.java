package com.biner.strategy.indicator.bollinger;

import com.biner.market.event.CandleClosedEvent;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

@Component
public class OneMinuteBollingerStrategy implements BollingerTouchStrategy {

  private static final int BAR_COUNT = 20;
  private static final double DEVIATION_MULTIPLIER = 1.9; // 1분봉은 승수를 낮춤

  @Override
  public boolean isTouched(BarSeries series, CandleClosedEvent currentCandle) {
    ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
    SMAIndicator sma = new SMAIndicator(closePrice, BAR_COUNT);
    StandardDeviationIndicator sd = new StandardDeviationIndicator(closePrice, BAR_COUNT);
    BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma);

    BollingerBandsLowerIndicator bbLower =
      new BollingerBandsLowerIndicator(bbMiddle, sd, series.numOf(DEVIATION_MULTIPLIER));

    double lowerBandValue = bbLower.getValue(series.getEndIndex()).doubleValue();
    double currentClose = currentCandle.close().doubleValue();
    double currentLow = currentCandle.low().doubleValue();

    // 1분봉은 별도의 % 버퍼 없이 1.9 승수 밴드 하단 이하인지 검사
    return currentClose <= lowerBandValue || currentLow <= lowerBandValue;
  }

  @Override
  public String getSupportedInterval() {
    return "1m";
  }
}