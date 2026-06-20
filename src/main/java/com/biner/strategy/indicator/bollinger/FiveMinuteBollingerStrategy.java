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
public class FiveMinuteBollingerStrategy implements BollingerTouchStrategy {

  private static final int BAR_COUNT = 20;
  private static final double DEVIATION_MULTIPLIER = 2.0;
  private static final double BUFFER_PERCENT = 0.01; // 1% 버퍼

  @Override
  public boolean isTouched(BarSeries series, CandleClosedEvent currentCandle) {
    ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
    SMAIndicator sma = new SMAIndicator(closePrice, BAR_COUNT);
    StandardDeviationIndicator sd = new StandardDeviationIndicator(closePrice, BAR_COUNT);
    BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma);
    BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, sd, series.numOf(DEVIATION_MULTIPLIER));

    double lowerBandValue = bbLower.getValue(series.getEndIndex()).doubleValue();
    double buffer = lowerBandValue * BUFFER_PERCENT;

    double currentClose = currentCandle.close().doubleValue();
    double currentLow = currentCandle.low().doubleValue();

    // 5분봉은 1% 버퍼를 더한 값과 비교
    return currentClose <= (lowerBandValue + buffer) || currentLow <= (lowerBandValue + buffer);
  }

  @Override
  public String getSupportedInterval() {
    return "5m";
  }
}