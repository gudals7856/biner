package com.biner.backtest.engine;

import com.biner.market.event.CandleClosedEvent;
import com.biner.order.domain.Position;
import com.biner.strategy.domain.TradeSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
@Service
public class MockExecutionService {

    private final BigDecimal initialBalance = new BigDecimal("10000.00"); // 1만 달러 시작
    private BigDecimal currentBalance = new BigDecimal("10000.00");
    private final BigDecimal feeRate = new BigDecimal("0.0004"); // 바이낸스 시장가 수수료 0.04%
    private final BigDecimal slippageRate = new BigDecimal("0.0005"); // 슬리피지 0.05%
    
    // 리스크 관리 설정
    private final BigDecimal stopLossPercent = new BigDecimal("0.02"); // 2% 손절 (생명줄)
    private final BigDecimal takeProfitPercent = new BigDecimal("0.04"); // 4% 익절 (목표가)
    private final int maxDcaCount = 4; // 최대 분할 매수 횟수 (최초 1회 + 물타기 3회)
    private final BigDecimal dcaDropPercent = new BigDecimal("0.01"); // 평단가 대비 1% 떨어질 때마다 물타기

    private Position currentPosition = null;
    private int dcaCount = 0; // 현재 진입 횟수
    
    // 리포팅용 통계
    private int tradeCount = 0;
    private int winCount = 0;
    private BigDecimal peakBalance = new BigDecimal("10000.00");
    private BigDecimal maxDrawdown = BigDecimal.ZERO;

    public void reset() {
        currentBalance = initialBalance;
        currentPosition = null;
        dcaCount = 0;
        tradeCount = 0;
        winCount = 0;
        peakBalance = initialBalance;
        maxDrawdown = BigDecimal.ZERO;
    }

    /**
     * 매 캔들마다 가격을 감시하여 스탑로스(-2%) 및 익절(+4%)을 처리합니다.
     */
    @EventListener
    public void onCandleClosed(CandleClosedEvent candle) {
        if (currentPosition == null) return;

        BigDecimal avgEntryPrice = currentPosition.getEntryPrice();
        BigDecimal low = candle.low();
        BigDecimal high = candle.high();

        if (currentPosition.getSide() == Position.PositionSide.LONG) {
            BigDecimal stopLossPrice = avgEntryPrice.multiply(BigDecimal.ONE.subtract(stopLossPercent));
            BigDecimal takeProfitPrice = avgEntryPrice.multiply(BigDecimal.ONE.add(takeProfitPercent));

            // 스탑로스 터치 (생명줄 발동)
            if (low.compareTo(stopLossPrice) <= 0) {
                log.info("🚨 [STOP LOSS] Long position stopped out at {}", stopLossPrice);
                closePosition(stopLossPrice, "Stop-Loss");
            } 
            // 익절 터치
            else if (high.compareTo(takeProfitPrice) >= 0) {
                log.info("🎉 [TAKE PROFIT] Long position took profit at {}", takeProfitPrice);
                closePosition(takeProfitPrice, "Take-Profit");
            }
        } else {
            // Short 포지션 로직 (현재는 Long 위주로 진행되므로 생략 가능하나 대칭으로 구현)
            BigDecimal stopLossPrice = avgEntryPrice.multiply(BigDecimal.ONE.add(stopLossPercent));
            BigDecimal takeProfitPrice = avgEntryPrice.multiply(BigDecimal.ONE.subtract(takeProfitPercent));

            if (high.compareTo(stopLossPrice) >= 0) {
                log.info("🚨 [STOP LOSS] Short position stopped out at {}", stopLossPrice);
                closePosition(stopLossPrice, "Stop-Loss");
            } else if (low.compareTo(takeProfitPrice) <= 0) {
                log.info("🎉 [TAKE PROFIT] Short position took profit at {}", takeProfitPrice);
                closePosition(takeProfitPrice, "Take-Profit");
            }
        }
    }

    /**
     * 전략 엔진에서 온 매매 신호를 처리합니다 (신규 진입, DCA 물타기, 스위칭)
     */
    @EventListener
    public void executeMockOrder(TradeSignal signal) {
        BigDecimal currentPrice = signal.price();
        Position.PositionSide signalSide = signal.type() == TradeSignal.SignalType.LONG ? Position.PositionSide.LONG : Position.PositionSide.SHORT;

        // 1. 기존 포지션과 반대 신호가 오면 전량 청산 (스위칭)
        if (currentPosition != null && currentPosition.getSide() != signalSide) {
            log.info("🔄 [SWITCHING] Opposite signal received. Closing current position.");
            closePosition(currentPrice, "Signal-Switch");
        }

        // 2. 신규 진입 또는 DCA (분할 매수)
        if (currentPosition == null) {
            openPosition(signal, currentPrice);
        } else if (currentPosition.getSide() == signalSide) {
            handleDca(signal, currentPrice);
        }
    }

    private void handleDca(TradeSignal signal, BigDecimal currentPrice) {
        if (dcaCount >= maxDcaCount) {
            return; // 최대 분할 매수 횟수 도달 시 무시
        }

        BigDecimal avgEntryPrice = currentPosition.getEntryPrice();
        
        // 롱 포지션일 경우: 현재가가 평단가보다 dcaDropPercent(1%) 이상 떨어졌을 때만 추가 매수
        boolean shouldDca = false;
        if (currentPosition.getSide() == Position.PositionSide.LONG) {
            BigDecimal dcaTriggerPrice = avgEntryPrice.multiply(BigDecimal.ONE.subtract(dcaDropPercent));
            shouldDca = currentPrice.compareTo(dcaTriggerPrice) <= 0;
        } else {
            BigDecimal dcaTriggerPrice = avgEntryPrice.multiply(BigDecimal.ONE.add(dcaDropPercent));
            shouldDca = currentPrice.compareTo(dcaTriggerPrice) >= 0;
        }

        if (shouldDca) {
            // 슬리피지 적용
            BigDecimal slippageFactor = currentPosition.getSide() == Position.PositionSide.LONG ? 
                    BigDecimal.ONE.add(slippageRate) : BigDecimal.ONE.subtract(slippageRate);
            BigDecimal executionPrice = currentPrice.multiply(slippageFactor);

            // 추가 진입 금액 계산 (보유 잔고의 비중)
            BigDecimal positionValue = currentBalance.multiply(signal.amount()); 
            BigDecimal fee = positionValue.multiply(feeRate);
            currentBalance = currentBalance.subtract(fee);

            BigDecimal addedCoinAmount = positionValue.divide(executionPrice, 8, RoundingMode.HALF_UP);

            // 평단가(Average Entry Price) 재계산: (기존가치 + 신규가치) / 총수량
            BigDecimal oldTotalValue = currentPosition.getEntryPrice().multiply(currentPosition.getAmount());
            BigDecimal newTotalValue = executionPrice.multiply(addedCoinAmount);
            BigDecimal totalAmount = currentPosition.getAmount().add(addedCoinAmount);
            BigDecimal newAvgPrice = oldTotalValue.add(newTotalValue).divide(totalAmount, 8, RoundingMode.HALF_UP);

            // 포지션 객체 업데이트
            currentPosition = Position.builder()
                    .symbol(signal.symbol())
                    .side(currentPosition.getSide())
                    .entryPrice(newAvgPrice)
                    .amount(totalAmount)
                    .leverage(1)
                    .build();
            
            dcaCount++;
            log.info("💧 [DCA {}/{}] Averaged down. New Avg Price: {}, Total Amount: {}", dcaCount, maxDcaCount, newAvgPrice, totalAmount);
        }
    }

    private void openPosition(TradeSignal signal, BigDecimal price) {
        BigDecimal slippageFactor = signal.type() == TradeSignal.SignalType.LONG ? 
                BigDecimal.ONE.add(slippageRate) : BigDecimal.ONE.subtract(slippageRate);
        BigDecimal executionPrice = price.multiply(slippageFactor);

        BigDecimal positionValue = currentBalance.multiply(signal.amount());
        BigDecimal fee = positionValue.multiply(feeRate);
        currentBalance = currentBalance.subtract(fee);

        Position.PositionSide side = signal.type() == TradeSignal.SignalType.LONG ? Position.PositionSide.LONG : Position.PositionSide.SHORT;
        BigDecimal coinAmount = positionValue.divide(executionPrice, 8, RoundingMode.HALF_UP);

        currentPosition = Position.builder()
                .symbol(signal.symbol())
                .side(side)
                .entryPrice(executionPrice)
                .amount(coinAmount)
                .leverage(1)
                .build();
                
        dcaCount = 1;
        log.info("🟢 [OPEN] {} at {}. Amount: {}", side, executionPrice, coinAmount);
    }

    private void closePosition(BigDecimal currentPrice, String reason) {
        if (currentPosition == null) return;

        BigDecimal slippageFactor = currentPosition.getSide() == Position.PositionSide.LONG ? 
                BigDecimal.ONE.subtract(slippageRate) : BigDecimal.ONE.add(slippageRate);
        BigDecimal executionPrice = currentPrice.multiply(slippageFactor);

        BigDecimal entryPrice = currentPosition.getEntryPrice();
        BigDecimal amount = currentPosition.getAmount();
        
        BigDecimal pnl;
        if (currentPosition.getSide() == Position.PositionSide.LONG) {
            pnl = executionPrice.subtract(entryPrice).multiply(amount);
        } else {
            pnl = entryPrice.subtract(executionPrice).multiply(amount);
        }

        BigDecimal positionValue = executionPrice.multiply(amount);
        BigDecimal fee = positionValue.multiply(feeRate);
        
        currentBalance = currentBalance.add(pnl).subtract(fee);
        tradeCount++;
        if (pnl.compareTo(BigDecimal.ZERO) > 0) {
            winCount++;
        }

        // 최대 낙폭(MDD) 트래킹
        if (currentBalance.compareTo(peakBalance) > 0) {
            peakBalance = currentBalance;
        } else {
            BigDecimal drawdown = peakBalance.subtract(currentBalance).divide(peakBalance, 4, RoundingMode.HALF_UP);
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
        }

        log.info("🔴 [CLOSE - {}] Exit Price: {}, PnL: {}$, Balance: ${}", reason, executionPrice, pnl.setScale(2, RoundingMode.HALF_UP), currentBalance.setScale(2, RoundingMode.HALF_UP));
        currentPosition = null;
        dcaCount = 0;
    }

    public void printReport() {
        BigDecimal roi = currentBalance.subtract(initialBalance)
                .divide(initialBalance, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
        
        double winRate = tradeCount > 0 ? (double) winCount / tradeCount * 100 : 0.0;

        System.out.println("\n==================================================");
        System.out.println("📊 BACKTESTING REPORT (Phase 4)");
        System.out.println("==================================================");
        System.out.println("💰 Initial Balance  : $" + initialBalance);
        System.out.println("💵 Final Balance    : $" + currentBalance.setScale(2, RoundingMode.HALF_UP));
        System.out.println("📈 Total ROI        : " + roi + "%");
        System.out.println("📉 Max Drawdown(MDD): " + maxDrawdown.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP) + "%");
        System.out.println("✅ Win Rate         : " + String.format("%.2f", winRate) + "%");
        System.out.println("🔄 Total Trades     : " + tradeCount);
        System.out.println("==================================================\n");
    }
}
