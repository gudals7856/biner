package com.biner.backtest.engine;

import com.biner.market.domain.Candle;
import com.biner.market.event.CandleClosedEvent;
import com.biner.market.repository.CandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 과거의 시장 데이터(Candle)를 불러와서, 마치 현재 실시간으로 발생하는 것처럼
 * 이벤트를 차례대로 발생시켜주는 백테스트(모의 투자) 엔진 클래스입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplayEngine {

    private final CandleRepository candleRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MockExecutionService mockExecutionService;

    /**
     * 지정된 기간의 과거 데이터를 사용하여 백테스트를 실행합니다.
     *
     * @param symbol    백테스트할 코인 심볼 (예: "BTCUSDT")
     * @param timeframe 백테스트할 시간봉 (예: "1m", "5m")
     * @param start     백테스트 시작 시간
     * @param end       백테스트 종료 시간
     */
    public void runBacktest(String symbol, String timeframe, Instant start, Instant end) {
        // 1. 백테스트 시작 전, 모의 체결 서비스를 초기화합니다.
        mockExecutionService.reset();

        String backtestSymbol = symbol + "_BACKTEST";
        log.info("{} ({}) 코인의 백테스트 시작. 기간: {} ~ {}", backtestSymbol, timeframe, start, end);

        // 2. 지정된 기간 동안의 캔들 데이터를 DB에서 가져옵니다.
        List<Candle> historicalCandles = candleRepository.findBySymbolAndTimeframeAndOpenTimeBetween(symbol, timeframe, start, end);
        log.info("백테스트를 위해 {} 개의 캔들 데이터를 불러왔습니다.", historicalCandles.size());

        // 3. 불러온 캔들 데이터를 하나씩 순회하며 이벤트를 발행합니다.
        for (Candle candle : historicalCandles) {
            CandleClosedEvent event = new CandleClosedEvent(
                backtestSymbol, // 백테스트용 심볼을 사용
                candle.getOpenTime(),
                candle.getCloseTime(),
                candle.getOpen(),
                candle.getHigh(),
                candle.getLow(),
                candle.getClose(),
                candle.getVolume(),
                candle.getTimeframe() // Candle 엔티티에서 timeframe 정보를 가져옴
            );
            
            // 4. 이벤트를 발행하여 StrategyEngine과 MockExecutionService가 수신하도록 합니다.
            eventPublisher.publishEvent(event);
        }
        
        log.info("{} 코인의 백테스트 리플레이가 모두 완료되었습니다.", backtestSymbol);

        // 5. 모든 리플레이가 끝난 후, 최종 결과 리포트를 출력합니다.
        mockExecutionService.printReport();
    }
}
