package com.biner.strategy.engine;

import com.biner.common.config.TradingProperties;
import com.biner.market.event.CandleClosedEvent;
import com.biner.market.event.CandleHistoryLoadedEvent;
import com.biner.order.service.PositionProvider;
import com.biner.strategy.domain.TradeSignal;
import com.biner.strategy.indicator.bollinger.BollingerStrategyFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StrategyEngineTest {

  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  private ApplicationEventPublisher publisher;
  private PositionProvider positionProvider;
  private StrategyEngine engine;

  @BeforeEach
  void setUp() {
    publisher = mock(ApplicationEventPublisher.class);
    positionProvider = mock(PositionProvider.class);
    when(positionProvider.findOpenPosition(any())).thenReturn(Optional.empty());
    engine = new StrategyEngine(publisher, positionProvider, new BollingerStrategyFactory(), new TradingProperties());
  }

  private CandleClosedEvent candle(int index, double price) {
    BigDecimal p = BigDecimal.valueOf(price);
    Instant open = START.plusSeconds(60L * index);
    return new CandleClosedEvent("BTCUSDT", open, open.plusSeconds(60).minusMillis(1), p, p, p, p, BigDecimal.ONE, "1m");
  }

  @Test
  void 중복되거나_과거의_캔들은_시계열에_추가하지_않는다() {
    engine.onCandleClosed(candle(0, 100));
    engine.onCandleClosed(candle(1, 101));
    engine.onCandleClosed(candle(1, 101));
    engine.onCandleClosed(candle(0, 100));

    assertThat(engine.context(candle(0, 100)).series.getBarCount()).isEqualTo(2);
  }

  @Test
  void 워밍업_캔들로는_매매신호를_내지_않는다() {
    List<CandleClosedEvent> history = new ArrayList<>();
    for (int i = 0; i < 300; i++) {
      history.add(candle(i, 100 + Math.sin(i / 5.0) * 3));
    }
    engine.onHistoryLoaded(new CandleHistoryLoadedEvent("BTCUSDT", history));

    // 시계열은 maxBarCount(기본 200)개까지만 유지하지만, 300개 모두 순서대로 반영되어야 합니다.
    assertThat(engine.context(history.get(0)).series.getBarCount()).isEqualTo(200);
    assertThat(engine.context(history.get(0)).series.getEndIndex()).isEqualTo(299);
    verify(publisher, never()).publishEvent(any(TradeSignal.class));
    verify(positionProvider, never()).findOpenPosition(any());
  }

  @Test
  void 워밍업_후_첫_실시간_캔들부터_분석한다() {
    List<CandleClosedEvent> history = new ArrayList<>();
    for (int i = 0; i < 250; i++) {
      history.add(candle(i, 100 + i * 0.01));
    }
    engine.onHistoryLoaded(new CandleHistoryLoadedEvent("BTCUSDT", history));
    engine.onCandleClosed(candle(250, 102.5));

    verify(positionProvider).findOpenPosition("BTCUSDT");
  }

  @Test
  void 물타기_비중은_차수가_늘수록_줄어든다() {
    // 기본 진입 비중 0.20 → 2차 0.10, 3차 0.0667, 4차 0.05
    assertThat(engine.dcaAmountFor(1)).isEqualByComparingTo("0.10");
    assertThat(engine.dcaAmountFor(3)).isEqualByComparingTo("0.05");
  }
}
