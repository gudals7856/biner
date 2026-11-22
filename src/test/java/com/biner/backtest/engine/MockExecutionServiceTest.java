package com.biner.backtest.engine;

import com.biner.common.config.TradingProperties;
import com.biner.market.domain.Candle;
import com.biner.market.domain.CandleId;
import com.biner.strategy.domain.TradeSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MockExecutionServiceTest {

  private static final String SYMBOL = "BTCUSDT_BACKTEST";

  private MockExecutionService service;

  @BeforeEach
  void setUp() {
    service = new MockExecutionService(new TradingProperties());
    service.reset();
  }

  private TradeSignal signal(TradeSignal.SignalType type, String price, String amount) {
    return new TradeSignal(SYMBOL, type, new BigDecimal(price), new BigDecimal(amount), "test");
  }

  private Candle candle(String low, String high) {
    return Candle.builder()
      .id(new CandleId(Instant.now(), "BTCUSDT", "5m"))
      .closeTime(Instant.now())
      .open(new BigDecimal(low))
      .high(new BigDecimal(high))
      .low(new BigDecimal(low))
      .close(new BigDecimal(low))
      .volume(BigDecimal.ONE)
      .build();
  }

  @Test
  void 진입과_청산에_수수료와_슬리피지가_차감된다() {
    service.handleTradeSignal(signal(TradeSignal.SignalType.LONG, "100", "1.0"));
    // 진입 수수료 10000 * 0.0004 = 4
    assertThat(service.getCurrentBalance()).isEqualByComparingTo("9996");
    // 진입가 = 100 * 1.0005
    assertThat(service.getCurrentPosition().orElseThrow().getEntryPrice()).isEqualByComparingTo("100.05");

    // 같은 가격에 청산하면 슬리피지 + 수수료만큼 손실이어야 합니다.
    service.handleTradeSignal(signal(TradeSignal.SignalType.CLOSE_LONG, "100", "1"));
    assertThat(service.getCurrentPosition()).isEmpty();
    assertThat(service.getCurrentBalance()).isLessThan(new BigDecimal("9996"));
    assertThat(service.getTradeCount()).isEqualTo(1);
    assertThat(service.getWinCount()).isZero();
  }

  @Test
  void 수수료를_빼면_손실인_거래는_승리로_세지_않는다() {
    service.handleTradeSignal(signal(TradeSignal.SignalType.LONG, "100", "1.0"));
    // 체결가 기준 손익은 아주 약간 플러스지만, 수수료를 빼면 마이너스
    service.handleTradeSignal(signal(TradeSignal.SignalType.CLOSE_LONG, "100.11", "1"));
    assertThat(service.getWinCount()).isZero();

    service.handleTradeSignal(signal(TradeSignal.SignalType.LONG, "100", "1.0"));
    service.handleTradeSignal(signal(TradeSignal.SignalType.CLOSE_LONG, "110", "1"));
    assertThat(service.getWinCount()).isEqualTo(1);
  }

  @Test
  void MDD는_미실현_손실까지_반영한다() {
    service.handleTradeSignal(signal(TradeSignal.SignalType.LONG, "100", "1.0"));
    // 보유 중 저가 80까지 하락 → 평가 자산 약 -20%
    service.markToMarket(candle("80", "101"));
    // 이후 본전 근처로 회복 후 청산
    service.handleTradeSignal(signal(TradeSignal.SignalType.CLOSE_LONG, "100.2", "1"));

    assertThat(service.getMaxDrawdown()).isGreaterThan(new BigDecimal("0.19"));
  }
}
