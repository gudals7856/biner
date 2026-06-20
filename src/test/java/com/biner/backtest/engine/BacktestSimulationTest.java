package com.biner.backtest.engine;

import com.biner.market.event.CandleClosedEvent;
import com.biner.strategy.engine.StrategyEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

// 실제 바이낸스 API 호출(OrderExecutionService)이 동작하지 않도록 테스트 프로파일(속성)을 덮어씌웁니다.
// 이렇게 하면 불필요한 401 Unauthorized 에러 로그가 찍히는 것을 막을 수 있습니다.
@SpringBootTest
@TestPropertySource(properties = {
    "spring.main.allow-bean-definition-overriding=true",
    "binance.api.key=dummy_test_key",
    "binance.api.secret=dummy_test_secret"
})
class BacktestSimulationTest {

    @Autowired
    private StrategyEngine strategyEngine;

    @Autowired
    private MockExecutionService mockExecutionService;

    @BeforeEach
    void setUp() {
        mockExecutionService.reset();
    }

    @Test
    @DisplayName("가상 과거 데이터를 주입하여 DCA(물타기), 스탑로스, 리포팅 등 전체 백테스트 파이프라인 검증")
    void runFullBacktestSimulation() throws InterruptedException {
        String symbol = "BTCUSDT_BACKTEST";
        Instant time = Instant.parse("2024-01-01T00:00:00Z");
        
        BigDecimal price = new BigDecimal("50000.00");

        // 1. 200개의 상승장 캔들 주입 (지표 활성화 및 첫 매수 타점 생성)
        for (int i = 0; i < 200; i++) {
            price = price.add(new BigDecimal("10.00")); // 꾸준히 상승
            
            if (i == 199) {
                // 마지막 캔들에서 푹 찍어서 매수 타점(RSI 과매도, 볼밴 하단 터치) 생성
                price = price.subtract(new BigDecimal("200.00")); 
            }

            CandleClosedEvent event = createEvent(symbol, time, price, price.subtract(new BigDecimal("300")));
            strategyEngine.onCandleClosed(event); // 전략 엔진이 신호 발행 -> MockExecution이 수신
            mockExecutionService.onCandleClosed(event); // 가격 감시 (스탑로스/익절)
            time = time.plus(5, ChronoUnit.MINUTES);
        }

        // 2. 추가 하락장 캔들 주입 (DCA 물타기 유도)
        for (int i = 0; i < 5; i++) {
            price = price.subtract(new BigDecimal("150.00")); // 계속 떨어짐
            
            CandleClosedEvent event = createEvent(symbol, time, price, price.subtract(new BigDecimal("100")));
            strategyEngine.onCandleClosed(event); 
            mockExecutionService.onCandleClosed(event);
            time = time.plus(5, ChronoUnit.MINUTES);
        }
        
        // 3. 지옥의 하락장 주입 (스탑로스 -2% 유도)
        price = price.subtract(new BigDecimal("2000.00"));
        CandleClosedEvent hellEvent = createEvent(symbol, time, price, price.subtract(new BigDecimal("200")));
        mockExecutionService.onCandleClosed(hellEvent);

        // 결과 리포트 출력
        mockExecutionService.printReport();
    }

    private CandleClosedEvent createEvent(String symbol, Instant time, BigDecimal close, BigDecimal low) {
        return new CandleClosedEvent(
                symbol,
                time,
                time.plus(5, ChronoUnit.MINUTES),
                close, 
                close.add(new BigDecimal("10")), 
                low,
                close,
                new BigDecimal("100")
        );
    }
}
