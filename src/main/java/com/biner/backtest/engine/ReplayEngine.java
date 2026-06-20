package com.biner.backtest.engine;

import com.biner.market.domain.Candle;
import com.biner.market.event.CandleClosedEvent;
import com.biner.market.repository.CandleRepository;
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
public class ReplayEngine {

    // 과거 캔들 데이터를 DB에서 조회하기 위한 리포지토리
    private final CandleRepository candleRepository;
    
    // 이벤트를 발생시켜 애플리케이션 내의 다른 컴포넌트(예: 전략 서비스)로 전달하기 위한 퍼블리셔
    private final ApplicationEventPublisher eventPublisher;

    public ReplayEngine(CandleRepository candleRepository, ApplicationEventPublisher eventPublisher) {
        this.candleRepository = candleRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 지정된 기간의 과거 데이터를 사용하여 백테스트를 실행합니다.
     *
     * @param symbol 백테스트할 코인 심볼 (예: "BTCUSDT")
     * @param start  백테스트 시작 시간
     * @param end    백테스트 종료 시간
     */
    public void runBacktest(String symbol, Instant start, Instant end) {
        log.info("{} 코인의 백테스트 시작. 기간: {} ~ {}", symbol, start, end);
        
        // 지정된 기간 동안의 캔들 데이터를 시간 순서대로(오름차순) DB에서 모두 가져옵니다.
        List<Candle> historicalCandles = candleRepository.findBySymbolAndOpenTimeBetweenOrderByOpenTimeAsc(symbol, start, end);
        log.info("백테스트를 위해 {} 개의 캔들 데이터를 불러왔습니다.", historicalCandles.size());

        // 불러온 캔들 데이터를 하나씩 순회합니다.
        for (Candle candle : historicalCandles) {
            // 마치 실시간으로 캔들이 마감된 것처럼 이벤트를 생성합니다.
            CandleClosedEvent event = new CandleClosedEvent(
                candle.getSymbol(),
                candle.getOpenTime(),
                candle.getCloseTime(),
                candle.getOpen(),
                candle.getHigh(),
                candle.getLow(),
                candle.getClose(),
                candle.getVolume(),
                "5m"
            );
            
            // 생성된 이벤트를 애플리케이션 내부에 발행합니다.
            // 이렇게 하면 실시간 트레이딩 로직(Strategy) 코드를 수정하지 않고도, 
            // 이 이벤트를 받아 마치 실시간처럼 전략을 테스트할 수 있습니다.
            eventPublisher.publishEvent(event);
        }
        
        log.info("{} 코인의 백테스트 리플레이(이벤트 발생)가 모두 완료되었습니다.", symbol);
        // TODO: 이벤트 발생이 모두 끝난 후, 가상 거래 내역을 바탕으로 최종 수익률을 계산하고 리포트를 출력하는 로직 추가 필요
    }
}
