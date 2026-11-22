package com.biner.strategy.engine;

import com.biner.common.config.TradingProperties;
import com.biner.market.event.CandleClosedEvent;
import com.biner.market.event.CandleHistoryLoadedEvent;
import com.biner.order.domain.Position;
import com.biner.order.service.PositionProvider;
import com.biner.strategy.domain.TradeSignal;
import com.biner.strategy.indicator.bollinger.BollingerStrategyFactory;
import com.biner.strategy.indicator.bollinger.BollingerTouchStrategy;
import com.biner.strategy.util.Ta4jConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.ichimoku.IchimokuKijunSenIndicator;
import org.ta4j.core.indicators.ichimoku.IchimokuTenkanSenIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 시장 데이터(캔들)를 바탕으로 기술적 지표를 계산하고,
 * 매수(진입) 또는 물타기(DCA), 청산(익절/손절) 시그널을 발생하는 전략 엔진입니다.
 *
 * <p>지표 객체는 심볼/시간봉별로 한 번만 생성하여 재사용합니다. (ta4j 지표는 내부적으로 계산값을 캐싱합니다)</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyEngine {

  /** 장기 추세 필터(이동평균) 기간. 이 개수만큼 캔들이 쌓여야 분석을 시작합니다. */
  static final int LONG_TERM_SMA_PERIOD = 200;

  private final ApplicationEventPublisher eventPublisher;
  private final PositionProvider positionProvider;
  private final BollingerStrategyFactory bollingerStrategyFactory;
  private final TradingProperties tradingProperties;

  /** 심볼+시간봉별 시계열 및 지표 */
  private final Map<String, SeriesContext> contexts = new ConcurrentHashMap<>();

  /**
   * 캔들 시계열과, 그 시계열 위에서 계산되는 지표 묶음입니다.
   */
  final class SeriesContext {
    final BarSeries series;
    final IchimokuTenkanSenIndicator tenkanSen;
    final IchimokuKijunSenIndicator kijunSen;
    final SMAIndicator longTermSma;
    final MACDIndicator macd;
    final EMAIndicator macdSignal;
    final RSIIndicator rsi;
    final BollingerBandsUpperIndicator bbUpper;
    final BollingerTouchStrategy lowerBandTouch;

    SeriesContext(String symbol, String interval) {
      BaseBarSeries newSeries = new BaseBarSeries(symbol + "_" + interval);
      int maxBarCount = Math.max(tradingProperties.getGeneral().getMaxBarCount(), LONG_TERM_SMA_PERIOD);
      newSeries.setMaximumBarCount(maxBarCount);
      this.series = newSeries;

      TradingProperties.Ichimoku ichimoku = tradingProperties.getIchimoku();
      TradingProperties.Macd macdProps = tradingProperties.getMacd();
      TradingProperties.Bollinger bollinger = tradingProperties.getBollinger();

      ClosePriceIndicator close = new ClosePriceIndicator(series);
      this.tenkanSen = new IchimokuTenkanSenIndicator(series, ichimoku.getTenkanSenPeriod());
      this.kijunSen = new IchimokuKijunSenIndicator(series, ichimoku.getKijunSenPeriod());
      this.longTermSma = new SMAIndicator(close, LONG_TERM_SMA_PERIOD);
      this.macd = new MACDIndicator(close, macdProps.getShortBarCount(), macdProps.getLongBarCount());
      this.macdSignal = new EMAIndicator(macd, macdProps.getSignalLineCount());
      this.rsi = new RSIIndicator(close, tradingProperties.getRsi().getBarCount());

      SMAIndicator bbSma = new SMAIndicator(close, bollinger.getBarCount());
      StandardDeviationIndicator sd = new StandardDeviationIndicator(close, bollinger.getBarCount());
      this.bbUpper = new BollingerBandsUpperIndicator(new BollingerBandsMiddleIndicator(bbSma), sd,
        series.numOf(bollinger.getDeviationMultiplier()));
      this.lowerBandTouch = bollingerStrategyFactory.getStrategy(interval, series);
    }

    /**
     * 캔들을 추가합니다. 이미 가진 캔들보다 이전이거나 같은 시각의 캔들은 무시합니다.
     *
     * @return 추가되었으면 true
     */
    boolean addBar(CandleClosedEvent event) {
      Bar bar = Ta4jConverter.convert(event);
      if (!series.isEmpty() && !bar.getEndTime().isAfter(series.getLastBar().getEndTime())) {
        return false;
      }
      series.addBar(bar);
      return true;
    }
  }

  // =================================================================
  // 이벤트 수신
  // =================================================================

  /**
   * 시작 시 불러온 과거 캔들로 지표를 워밍업합니다. 매매 신호는 발생시키지 않습니다.
   */
  @EventListener
  public void onHistoryLoaded(CandleHistoryLoadedEvent event) {
    int added = 0;
    for (CandleClosedEvent candle : event.candles()) {
      if (context(candle).addBar(candle)) {
        added++;
      }
    }
    log.info("[{}] 과거 캔들 {}개로 지표 워밍업 완료", event.symbol(), added);
  }

  /**
   * 캔들 마감 이벤트(실시간 WebSocket 또는 백테스트 ReplayEngine)를 수신하여 전략 분석을 수행합니다.
   */
  @EventListener
  public void onCandleClosed(CandleClosedEvent event) {
    SeriesContext ctx = context(event);
    if (!ctx.addBar(event)) {
      log.debug("[{}] 중복되거나 과거 시각의 캔들이므로 무시합니다: {}", event.symbol(), event.openTime());
      return;
    }

    if (ctx.series.getBarCount() < LONG_TERM_SMA_PERIOD) {
      log.info("캔들 데이터 수집 중... [{}]: {}/{}개", event.symbol(), ctx.series.getBarCount(), LONG_TERM_SMA_PERIOD);
      return;
    }

    Optional<Position> activePosition = positionProvider.findOpenPosition(event.symbol());
    if (activePosition.isPresent()) {
      handleExistingPosition(activePosition.get(), ctx, event);
    } else {
      handleNewPosition(event.symbol(), ctx, event);
    }
  }

  SeriesContext context(CandleClosedEvent event) {
    return contexts.computeIfAbsent(event.symbol() + "|" + event.interval(),
      k -> new SeriesContext(event.symbol(), event.interval()));
  }

  // =================================================================
  // 신규 진입
  // =================================================================

  /**
   * 신규 포지션(LONG) 진입 조건을 '점수제'로 판단하여 매수 신호를 발생시킵니다.
   */
  private void handleNewPosition(String symbol, SeriesContext ctx, CandleClosedEvent candle) {
    if (!isUptrend(ctx)) {
      log.debug("지표 상태 [{}]: 상승추세=false. 진입 조건 미충족.", symbol);
      return;
    }

    boolean isGoldenCross = isMacdGoldenCross(ctx);
    boolean isOversold = isOversold(ctx);
    boolean touchesLowerBand = ctx.lowerBandTouch.isTouched(ctx.series, candle);
    int score = (isGoldenCross ? 1 : 0) + (isOversold ? 1 : 0) + (touchesLowerBand ? 1 : 0);

    log.debug("지표 상태 [{}]: 상승추세=true, 선택조건 점수={}/3 (골든크로스={}, 과매도={}, BB터치={})",
      symbol, score, isGoldenCross, isOversold, touchesLowerBand);

    if (score >= 2) {
      TradeSignal signal = new TradeSignal(
        symbol,
        TradeSignal.SignalType.LONG,
        candle.close(),
        tradingProperties.getDca().getBaseEntryPercent(),
        "신규 LONG 진입 신호 (Score: " + score + ")"
      );
      log.debug("매수 신호 발생: {}", signal);
      eventPublisher.publishEvent(signal);
    }
  }

  // =================================================================
  // 보유 포지션 관리
  // =================================================================

  private void handleExistingPosition(Position position, SeriesContext ctx, CandleClosedEvent candle) {
    // 백테스트에서는 거래소 보호 주문이 없으므로 고가/저가로 손절·익절 체결을 흉내 냅니다.
    if (position.getSymbol().contains("BACKTEST") && checkForMockProtectionExit(position, candle)) {
      return;
    }

    // 0. 최대 물타기 횟수에 도달한 포지션은 본전 탈출을 최우선으로 시도합니다.
    if (position.isMaxDcaReached() && isAtOrAboveBreakEven(position, candle.close())) {
      log.debug("최대 물타기 포지션 본전 탈출 조건 충족!");
      publishExitSignal(position, candle.close(), "본전 탈출: 최대 물타기 도달");
      return;
    }

    // 1. 익절/손절 신호
    if (checkForExitSignal(position, ctx, candle)) {
      return;
    }

    // 2. 물타기
    if (tradingProperties.getStrategy().isDcaEnabled()) {
      checkForDcaSignal(position, ctx, candle);
    }
  }

  private boolean isAtOrAboveBreakEven(Position position, BigDecimal price) {
    int cmp = price.compareTo(position.getEntryPrice());
    return position.getSide() == Position.PositionSide.LONG ? cmp >= 0 : cmp <= 0;
  }

  private boolean checkForMockProtectionExit(Position position, CandleClosedEvent candle) {
    BigDecimal entryPrice = position.getEntryPrice();
    BigDecimal stopLossPercent = tradingProperties.getRiskManagement().getStopLossPercent();
    BigDecimal takeProfitPercent = tradingProperties.getRiskManagement().getTakeProfitPercent();

    if (position.getSide() == Position.PositionSide.LONG) {
      BigDecimal stopLossPrice = entryPrice.multiply(BigDecimal.ONE.subtract(stopLossPercent));
      BigDecimal takeProfitPrice = entryPrice.multiply(BigDecimal.ONE.add(takeProfitPercent));
      // 한 캔들 안에서 둘 다 닿으면 보수적으로 손절을 먼저 가정합니다.
      if (candle.low().compareTo(stopLossPrice) <= 0) {
        publishExitSignal(position, stopLossPrice, "손절: Hard Stop-Loss");
        return true;
      }
      if (candle.high().compareTo(takeProfitPrice) >= 0) {
        publishExitSignal(position, takeProfitPrice, "익절: Hard Take-Profit");
        return true;
      }
    } else if (position.getSide() == Position.PositionSide.SHORT) {
      BigDecimal stopLossPrice = entryPrice.multiply(BigDecimal.ONE.add(stopLossPercent));
      BigDecimal takeProfitPrice = entryPrice.multiply(BigDecimal.ONE.subtract(takeProfitPercent));
      if (candle.high().compareTo(stopLossPrice) >= 0) {
        publishExitSignal(position, stopLossPrice, "손절: Hard Stop-Loss");
        return true;
      }
      if (candle.low().compareTo(takeProfitPrice) <= 0) {
        publishExitSignal(position, takeProfitPrice, "익절: Hard Take-Profit");
        return true;
      }
    }
    return false;
  }

  /**
   * 익절/손절 조건을 확인합니다. 각 조건은 trading.strategy.* 설정으로 켜고 끌 수 있습니다.
   *
   * @return 청산 신호를 발행했으면 true
   */
  private boolean checkForExitSignal(Position position, SeriesContext ctx, CandleClosedEvent candle) {
    TradingProperties.Strategy strategy = tradingProperties.getStrategy();
    BigDecimal currentPrice = candle.close();
    int cmp = currentPrice.compareTo(position.getEntryPrice());

    if (cmp > 0) {
      if (strategy.isRsiOverboughtExitEnabled() && isRsiExitingOverbought(ctx)) {
        publishExitSignal(position, currentPrice, "익절: RSI 과매수 이탈");
        return true;
      }
      if (strategy.isBollingerUpperExitEnabled() && isPriceExitingUpperBand(ctx)) {
        publishExitSignal(position, currentPrice, "익절: 볼린저 밴드 복귀");
        return true;
      }
    } else if (cmp < 0 && strategy.isStrategicStopLossEnabled()) {
      if (isMacdDeadCross(ctx)) {
        publishExitSignal(position, currentPrice, "손절: MACD 데드크로스");
        return true;
      }
      if (isPriceBelowKijunSen(ctx)) {
        publishExitSignal(position, currentPrice, "손절: 기준선 하향 이탈");
        return true;
      }
    }
    return false;
  }

  /**
   * 물타기(DCA) 조건을 검사하고, 충족 시 신호를 발생시킵니다.
   */
  private void checkForDcaSignal(Position position, SeriesContext ctx, CandleClosedEvent candle) {
    if (position.isMaxDcaReached() || position.getSide() != Position.PositionSide.LONG) {
      return;
    }

    BigDecimal currentPrice = candle.close();
    BigDecimal dcaTriggerPrice = position.getEntryPrice()
      .multiply(BigDecimal.ONE.subtract(tradingProperties.getDca().getDcaDropPercent()));
    if (currentPrice.compareTo(dcaTriggerPrice) > 0) {
      return;
    }

    // 가격 조건 충족 후, 기술적으로도 반등 확률이 높은 지점(RSI 과매도 + BB 하단 터치)에서만 물을 탑니다.
    if (isOversold(ctx) && ctx.lowerBandTouch.isTouched(ctx.series, candle)) {
      BigDecimal dcaAmount = dcaAmountFor(position.getDcaCount());
      TradeSignal dcaSignal = new TradeSignal(
        position.getSymbol(),
        TradeSignal.SignalType.LONG,
        currentPrice,
        dcaAmount,
        "DCA(물타기) 신호"
      );
      log.debug("물타기 신호 발생. 다음 차수: {}/{}", position.getDcaCount() + 1, tradingProperties.getDca().getMaxCount());
      eventPublisher.publishEvent(dcaSignal);
    }
  }

  /**
   * 물타기 차수가 늘어날수록 비중을 줄입니다. (2차: 1/2, 3차: 1/3, 4차: 1/4)
   *
   * @param currentDcaCount 현재까지의 진입 횟수 (최초 진입 = 1)
   */
  BigDecimal dcaAmountFor(int currentDcaCount) {
    int nextOrder = currentDcaCount + 1;
    return tradingProperties.getDca().getBaseEntryPercent()
      .divide(BigDecimal.valueOf(nextOrder), 8, RoundingMode.HALF_UP);
  }

  private void publishExitSignal(Position position, BigDecimal exitPrice, String reason) {
    TradeSignal.SignalType type = (position.getSide() == Position.PositionSide.LONG)
      ? TradeSignal.SignalType.CLOSE_LONG
      : TradeSignal.SignalType.CLOSE_SHORT;
    TradeSignal exitSignal = new TradeSignal(position.getSymbol(), type, exitPrice, BigDecimal.ONE, reason);
    log.debug("포지션 청산 신호 발생: {}", exitSignal);
    eventPublisher.publishEvent(exitSignal);
  }

  // =================================================================
  // 지표 판단
  // =================================================================

  /** 전환선 >= 기준선 이고, 종가가 200 이평선 위에 있으면 상승 추세 */
  private boolean isUptrend(SeriesContext ctx) {
    int i = ctx.series.getEndIndex();
    boolean shortTerm = ctx.tenkanSen.getValue(i).isGreaterThanOrEqual(ctx.kijunSen.getValue(i));
    boolean longTerm = ctx.series.getLastBar().getClosePrice().isGreaterThan(ctx.longTermSma.getValue(i));
    return shortTerm && longTerm;
  }

  private boolean isMacdGoldenCross(SeriesContext ctx) {
    int i = ctx.series.getEndIndex();
    if (i < 1) {
      return false;
    }
    return ctx.macd.getValue(i - 1).isLessThan(ctx.macdSignal.getValue(i - 1))
      && ctx.macd.getValue(i).isGreaterThanOrEqual(ctx.macdSignal.getValue(i));
  }

  private boolean isMacdDeadCross(SeriesContext ctx) {
    int i = ctx.series.getEndIndex();
    if (i < 1) {
      return false;
    }
    return ctx.macd.getValue(i - 1).isGreaterThan(ctx.macdSignal.getValue(i - 1))
      && ctx.macd.getValue(i).isLessThanOrEqual(ctx.macdSignal.getValue(i));
  }

  private boolean isOversold(SeriesContext ctx) {
    return ctx.rsi.getValue(ctx.series.getEndIndex()).doubleValue() < tradingProperties.getRsi().getOversoldThreshold();
  }

  /** RSI가 과매수 기준선을 하향 돌파하면 true */
  private boolean isRsiExitingOverbought(SeriesContext ctx) {
    int i = ctx.series.getEndIndex();
    if (i < 1) {
      return false;
    }
    double threshold = tradingProperties.getRsi().getOverboughtThreshold();
    return ctx.rsi.getValue(i - 1).doubleValue() >= threshold && ctx.rsi.getValue(i).doubleValue() < threshold;
  }

  private boolean isPriceBelowKijunSen(SeriesContext ctx) {
    int i = ctx.series.getEndIndex();
    return ctx.series.getLastBar().getClosePrice().isLessThan(ctx.kijunSen.getValue(i));
  }

  /** 직전 종가가 볼린저 상단 밖에 있다가, 현재 종가가 상단 이하로 복귀하면 true */
  private boolean isPriceExitingUpperBand(SeriesContext ctx) {
    int i = ctx.series.getEndIndex();
    if (i < 1) {
      return false;
    }
    Num prevClose = ctx.series.getBar(i - 1).getClosePrice();
    Num currentClose = ctx.series.getBar(i).getClosePrice();
    return prevClose.isGreaterThan(ctx.bbUpper.getValue(i - 1))
      && currentClose.isLessThanOrEqual(ctx.bbUpper.getValue(i));
  }
}
