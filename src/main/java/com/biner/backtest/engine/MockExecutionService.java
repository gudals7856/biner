package com.biner.backtest.engine;

import com.biner.market.event.CandleClosedEvent;
import com.biner.order.domain.Position;
import com.biner.strategy.domain.TradeSignal;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * 백테스트(과거 데이터 시뮬레이션) 시 실제 거래소를 대신하여 가상으로 주문을 체결하고
 * 잔고, 수익률, 승률 등을 계산하는 모의 체결 서비스입니다.
 */
@Slf4j
@Service
@Getter
public class MockExecutionService {

    // 가상 초기 자본금 (1만 달러)
    private final BigDecimal initialBalance = new BigDecimal("10000.00");
    // 현재 잔고
    private BigDecimal currentBalance = new BigDecimal("10000.00");
    // 가상 거래 수수료율 (바이낸스 시장가 기준 0.04%)
    private final BigDecimal feeRate = new BigDecimal("0.0004");
    // 슬리피지(Slippage) 비율 (시장가 주문 시 불리하게 체결되는 현상을 시뮬레이션, 0.05%)
    private final BigDecimal slippageRate = new BigDecimal("0.0005");

    // 리스크 관리(손익절) 설정
    // 손절(Stop Loss) 비율: 평단가 대비 2% 손실 시 청산
    private final BigDecimal stopLossPercent = new BigDecimal("0.02");
    // 익절(Take Profit) 비율: 평단가 대비 4% 수익 시 청산
    private final BigDecimal takeProfitPercent = new BigDecimal("0.04");

    // 물타기(DCA) 설정
    // 최대 분할 매수 횟수 (최초 진입 1회 + 물타기 3회)
    private final int maxDcaCount = 4;
    // 물타기 발동 조건: 평단가 대비 1% 하락할 때마다 물타기 실행
    private final BigDecimal dcaDropPercent = new BigDecimal("0.01");

    // 현재 보유 중인 가상 포지션 (null이면 무포지션)
    private Position currentPosition = null;
    // 현재 포지션에 대한 진입(물타기 포함) 횟수
    private int dcaCount = 0;

    // 백테스트 리포팅을 위한 통계 변수들
    private int tradeCount = 0;       // 총 거래 횟수
    private int winCount = 0;         // 승리(수익 발생) 횟수
    private BigDecimal peakBalance = new BigDecimal("10000.00"); // 자산 최고점 (MDD 계산용)
    private BigDecimal maxDrawdown = BigDecimal.ZERO;            // 최대 낙폭 (MDD: Maximum Drawdown)

    public Optional<Position> getCurrentPosition() {
        return Optional.ofNullable(currentPosition);
    }

    /**
     * 백테스트를 새로 시작할 때 모든 상태와 통계를 초기화합니다.
     */
    public void reset() {
        currentBalance = initialBalance;
        currentPosition = null;
        dcaCount = 0;
        tradeCount = 0;
        winCount = 0;
        peakBalance = initialBalance;
        maxDrawdown = BigDecimal.ZERO;
        log.info("백테스트 상태가 초기화되었습니다. 시작 잔고: ${}", initialBalance);
    }

    /**
     * 백테스트 리플레이 엔진에서 매 캔들이 마감될 때마다 발행하는 이벤트를 수신합니다.
     * 여기서는 보유 중인 포지션이 있을 경우, 캔들의 고가(high)/저가(low)를 확인하여
     * 설정된 손절가 또는 익절가를 터치했는지 검사하고 강제 청산을 실행합니다.
     *
     * @param candle 캔들 마감 이벤트 객체
     */
    @EventListener
    public void onCandleClosed(CandleClosedEvent candle) {
        if (currentPosition == null || !candle.symbol().contains("BACKTEST")) return;

        BigDecimal avgEntryPrice = currentPosition.getEntryPrice();
        BigDecimal low = candle.low();
        BigDecimal high = candle.high();

        if (currentPosition.getSide() == Position.PositionSide.LONG) {
            BigDecimal stopLossPrice = avgEntryPrice.multiply(BigDecimal.ONE.subtract(stopLossPercent));
            BigDecimal takeProfitPrice = avgEntryPrice.multiply(BigDecimal.ONE.add(takeProfitPercent));

            if (low.compareTo(stopLossPrice) <= 0) {
                log.info("🚨 [가상 손절] 롱 포지션 손절가 터치: {}", stopLossPrice);
                closePosition(stopLossPrice, "Stop-Loss");
            } else if (high.compareTo(takeProfitPrice) >= 0) {
                log.info("🎉 [가상 익절] 롱 포지션 익절가 터치: {}", takeProfitPrice);
                closePosition(takeProfitPrice, "Take-Profit");
            }
        } else {
            BigDecimal stopLossPrice = avgEntryPrice.multiply(BigDecimal.ONE.add(stopLossPercent));
            BigDecimal takeProfitPrice = avgEntryPrice.multiply(BigDecimal.ONE.subtract(takeProfitPercent));

            if (high.compareTo(stopLossPrice) >= 0) {
                log.info("🚨 [가상 손절] 숏 포지션 손절가 터치: {}", stopLossPrice);
                closePosition(stopLossPrice, "Stop-Loss");
            } else if (low.compareTo(takeProfitPrice) <= 0) {
                log.info("🎉 [가상 익절] 숏 포지션 익절가 터치: {}", takeProfitPrice);
                closePosition(takeProfitPrice, "Take-Profit");
            }
        }
    }

    /**
     * 전략 로직에서 발생한 매매 신호(TradeSignal) 이벤트를 수신하여 가상 주문을 체결합니다.
     *
     * @param signal 전략 로직이 생성한 매매 신호 객체
     */
    @EventListener
    public void executeMockOrder(TradeSignal signal) {
        if (!signal.symbol().contains("BACKTEST")) return;

        BigDecimal currentPrice = signal.price();
        Position.PositionSide signalSide = signal.type() == TradeSignal.SignalType.LONG ? Position.PositionSide.LONG : Position.PositionSide.SHORT;

        if (currentPosition != null && currentPosition.getSide() != signalSide) {
            log.info("🔄 [포지션 스위칭] 반대 방향 신호 수신. 기존 포지션을 종료합니다.");
            closePosition(currentPrice, "Signal-Switch");
        }

        if (currentPosition == null) {
            openPosition(signal, currentPrice);
        } else if (currentPosition.getSide() == signalSide) {
            handleDca(signal, currentPrice);
        }
    }

    private void handleDca(TradeSignal signal, BigDecimal currentPrice) {
        if (dcaCount >= maxDcaCount) {
            return;
        }

        BigDecimal slippageFactor = currentPosition.getSide() == Position.PositionSide.LONG ?
                BigDecimal.ONE.add(slippageRate) : BigDecimal.ONE.subtract(slippageRate);
        BigDecimal executionPrice = currentPrice.multiply(slippageFactor);

        BigDecimal positionValue = currentBalance.multiply(signal.amount());
        BigDecimal fee = positionValue.multiply(feeRate);
        currentBalance = currentBalance.subtract(fee);

        BigDecimal addedCoinAmount = positionValue.divide(executionPrice, 8, RoundingMode.HALF_UP);

        BigDecimal oldTotalValue = currentPosition.getEntryPrice().multiply(currentPosition.getAmount());
        BigDecimal newTotalValue = executionPrice.multiply(addedCoinAmount);
        BigDecimal totalAmount = currentPosition.getAmount().add(addedCoinAmount);
        BigDecimal newAvgPrice = oldTotalValue.add(newTotalValue).divide(totalAmount, 8, RoundingMode.HALF_UP);

        currentPosition = Position.builder()
                .symbol(signal.symbol())
                .side(currentPosition.getSide())
                .entryPrice(newAvgPrice)
                .amount(totalAmount)
                .leverage(1)
                .build();

        dcaCount++;
        log.info("💧 [가상 DCA {}/{}] 물타기 체결. 새 평균단가: {}, 총 수량: {}", dcaCount, maxDcaCount, newAvgPrice, totalAmount);
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
        log.info("🟢 [가상 신규 진입] 방향: {}, 체결가: {}, 수량: {}", side, executionPrice, coinAmount);
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

        if (currentBalance.compareTo(peakBalance) > 0) {
            peakBalance = currentBalance;
        } else {
            BigDecimal drawdown = peakBalance.subtract(currentBalance).divide(peakBalance, 4, RoundingMode.HALF_UP);
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
        }

        log.info("🔴 [가상 포지션 종료 - {}] 청산가: {}, 손익: ${}, 남은 잔고: ${}",
                reason, executionPrice, pnl.setScale(2, RoundingMode.HALF_UP), currentBalance.setScale(2, RoundingMode.HALF_UP));

        currentPosition = null;
        dcaCount = 0;
    }

    public void printReport() {
        BigDecimal roi = currentBalance.subtract(initialBalance)
                .divide(initialBalance, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        double winRate = tradeCount > 0 ? (double) winCount / tradeCount * 100 : 0.0;

        System.out.println("\n==================================================");
        System.out.println("📊 백테스트 결과 리포트");
        System.out.println("==================================================");
        System.out.println("💰 초기 자본금      : $" + initialBalance);
        System.out.println("💵 최종 잔고        : $" + currentBalance.setScale(2, RoundingMode.HALF_UP));
        System.out.println("📈 총 투자수익률(ROI) : " + roi.setScale(2, RoundingMode.HALF_UP) + "%");
        System.out.println("📉 최대 낙폭(MDD)    : " + maxDrawdown.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP) + "%");
        System.out.println("✅ 승률             : " + String.format("%.2f", winRate) + "%");
        System.out.println("🔄 총 거래 횟수     : " + tradeCount);
        System.out.println("==================================================\n");
    }
}
