package com.biner.backtest.engine;

import com.biner.market.domain.Candle;
import com.biner.market.event.CandleClosedEvent;
import com.biner.market.repository.CandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * 과거 캔들을 DB에서 불러와 실시간처럼 차례대로 CandleClosedEvent를 발행하는 백테스트 엔진입니다.
 */
@Slf4j
@Service
@Profile("backtest")
@RequiredArgsConstructor
public class ReplayEngine {

  private final CandleRepository candleRepository;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * @param afterEachCandle 각 캔들 이벤트 처리 후 호출되는 훅 (평가 자산 갱신 등)
   */
  public void runBacktest(String symbol, String timeframe, Instant start, Instant end, Consumer<Candle> afterEachCandle) {
    String backtestSymbol = symbol + "_BACKTEST";
    log.info("{} ({}) 백테스트 시작. 기간: {} ~ {}", backtestSymbol, timeframe, start, end);

    List<Candle> candles = candleRepository.findBySymbolAndTimeframeAndOpenTimeBetween(symbol, timeframe, start, end);
    log.info("백테스트를 위해 {}개의 캔들 데이터를 불러왔습니다.", candles.size());
    if (candles.isEmpty()) {
      log.warn("캔들 데이터가 없습니다. dataloader 프로필로 {} {} 데이터를 먼저 적재했는지 확인하세요.", symbol, timeframe);
      return;
    }

    for (Candle candle : candles) {
      eventPublisher.publishEvent(new CandleClosedEvent(
        backtestSymbol,
        candle.getOpenTime(),
        candle.getCloseTime(),
        candle.getOpen(),
        candle.getHigh(),
        candle.getLow(),
        candle.getClose(),
        candle.getVolume(),
        candle.getTimeframe()
      ));
      afterEachCandle.accept(candle);
    }

    log.info("{} 백테스트 리플레이가 모두 완료되었습니다.", backtestSymbol);
  }
}
