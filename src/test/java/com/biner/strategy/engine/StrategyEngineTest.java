package com.biner.strategy.engine;

import com.biner.market.event.CandleClosedEvent;
import com.biner.strategy.domain.TradeSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class StrategyEngineTest {

    @Autowired
    private StrategyEngine strategyEngine;

    @Autowired
    private TestSignalListener testSignalListener;

    @BeforeEach
    void setUp() {
        testSignalListener.reset();
    }

    @Test
    @DisplayName("상승장, 모멘텀 양수 상태에서 볼린저 밴드 하단을 터치하여 롱 매수 신호가 발생하는지 검증")
    void testLongSignalEmission() throws InterruptedException {
        String symbol = "BTCUSDT_" + System.currentTimeMillis();
        Instant startTime = Instant.parse("2024-01-01T00:00:00Z");
        
        BigDecimal basePrice = new BigDecimal("40000.00");
        
        for (int i = 0; i < 100; i++) {
            
            BigDecimal open = basePrice;
            BigDecimal close = basePrice;
            BigDecimal high = basePrice.add(new BigDecimal("10"));
            BigDecimal low = basePrice.subtract(new BigDecimal("10"));

            if (i < 98) {
                // 초반에 가격을 10달러씩 올리던 것을 20달러씩 올림 (상승 모멘텀을 더 가파르게)
                basePrice = basePrice.add(new BigDecimal("20.00"));
                close = basePrice;
                open = close.subtract(new BigDecimal("5.00"));
            } else if (i == 98) {
                // 98번째에서 상승폭을 살짝 줄여서 과매수 상태를 진정시킴
                close = basePrice;
            } else {
                // 99번째에서 종가를 꽤 많이 뺍니다. (-300)
                // 앞서 상승폭이 가팔랐기 때문에, 300달러를 빼도 MACD는 양수를 유지할 수 있습니다.
                // 종가를 300달러나 빼면 RSI는 확실하게 40 밑으로 처박힙니다.
                basePrice = basePrice.subtract(new BigDecimal("300.00")); 
                close = basePrice;
                low = basePrice.subtract(new BigDecimal("1000.00")); 
            }

            Instant closeTime = startTime.plus(5 * (i + 1), ChronoUnit.MINUTES);
            
            CandleClosedEvent mockEvent = new CandleClosedEvent(
                    symbol,
                    startTime.plus(5 * i, ChronoUnit.MINUTES),
                    closeTime,
                    open, 
                    high,      
                    low, 
                    close,                                
                    new BigDecimal("100.5")               
            );
            
            strategyEngine.onCandleClosed(mockEvent);
        }

        boolean signalReceived = testSignalListener.getLatch().await(2, TimeUnit.SECONDS);

        // 이제 모든 원인을 파악하고 완벽하게 데이터를 조작했으므로, 
        // 주석 처리했던 assertThat을 다시 살려서 실제 테스트가 "초록불 패스" 되도록 만듭니다.
        assertThat(signalReceived).as("TradeSignal should be emitted").isTrue();
        
        TradeSignal receivedSignal = testSignalListener.getLastReceivedSignal();
        assertThat(receivedSignal).isNotNull();
        assertThat(receivedSignal.type()).isEqualTo(TradeSignal.SignalType.LONG);
        System.out.println("✅ Result Signal: " + receivedSignal);
    }

    @TestConfiguration
    static class StrategyTestConfig {
        @Bean
        public TestSignalListener testSignalListener() {
            return new TestSignalListener();
        }
    }

    public static class TestSignalListener {
        private CountDownLatch latch = new CountDownLatch(1);
        private TradeSignal lastReceivedSignal;

        public void reset() {
            this.latch = new CountDownLatch(1);
            this.lastReceivedSignal = null;
        }

        @EventListener
        public void onTradeSignal(TradeSignal signal) {
            System.out.println("🎣 TestListener caught a signal: " + signal);
            this.lastReceivedSignal = signal;
            this.latch.countDown();
        }

        public CountDownLatch getLatch() {
            return latch;
        }

        public TradeSignal getLastReceivedSignal() {
            return lastReceivedSignal;
        }
    }
}
