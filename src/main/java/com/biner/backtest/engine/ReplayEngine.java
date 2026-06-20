package com.biner.backtest.engine;

import com.biner.market.domain.Candle;
import com.biner.market.event.CandleClosedEvent;
import com.biner.market.repository.CandleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
public class ReplayEngine {

    private final CandleRepository candleRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ReplayEngine(CandleRepository candleRepository, ApplicationEventPublisher eventPublisher) {
        this.candleRepository = candleRepository;
        this.eventPublisher = eventPublisher;
    }

    public void runBacktest(String symbol, Instant start, Instant end) {
        log.info("Starting backtest for {} from {} to {}", symbol, start, end);
        
        List<Candle> historicalCandles = candleRepository.findBySymbolAndOpenTimeBetweenOrderByOpenTimeAsc(symbol, start, end);
        log.info("Loaded {} candles for backtesting", historicalCandles.size());

        for (Candle candle : historicalCandles) {
            CandleClosedEvent event = new CandleClosedEvent(
                candle.getSymbol(),
                candle.getOpenTime(),
                candle.getCloseTime(),
                candle.getOpen(),
                candle.getHigh(),
                candle.getLow(),
                candle.getClose(),
                candle.getVolume()
            );
            
            // 리플레이 이벤트 발행 (가짜 실시간 스트림)
            eventPublisher.publishEvent(event);
        }
        
        log.info("Backtest replay completed for {}", symbol);
        // TODO: 수익률 계산 및 리포팅 출력 로직 호출
    }
}
