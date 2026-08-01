package com.biner.backtest.engine;

import com.biner.common.config.TradingProperties;
import com.biner.order.domain.Position;
import com.biner.strategy.domain.TradeSignal;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * [재작성] 백테스트 시 가상 주문을 처리하고 자산을 관리하는 모의 체결 서비스입니다.
 * 오직 StrategyEngine이 발행하는 TradeSignal에만 반응하며, 스스로 판단하지 않습니다.
 */
@Slf4j
@Service
@Profile("backtest") // 백테스트 프로필에서만 활성화
@Getter
@RequiredArgsConstructor
public class MockExecutionService {

    private final TradingProperties tradingProperties;

    // --- 설정 값 ---
    private final BigDecimal initialBalance = new BigDecimal("10000.00");
    private final BigDecimal feeRate = new BigDecimal("0.0004");
    private final BigDecimal slippageRate = new BigDecimal("0.0005");

    // --- 상태 변수 ---
    private BigDecimal currentBalance;
    private Position currentPosition;
    private int dcaCount;

    // --- 통계 변수 ---
    private int tradeCount;
    private int winCount;
    private BigDecimal peakBalance;
    private BigDecimal maxDrawdown;

    public Optional<Position> getCurrentPosition() {
        return Optional.ofNullable(currentPosition);
    }

    public int getDcaCount() {
        return this.dcaCount;
    }

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
     * StrategyEngine이 발행한 매매 신호를 받아 가상 주문을 처리합니다.
     * 실제 운영의 PositionService와 OrderExecutionService의 역할을 흉내 냅니다.
     */
    @EventListener
    public void handleTradeSignal(TradeSignal signal) {
        if (!signal.symbol().contains("BACKTEST")) {
            return; // 백테스트용 신호가 아니면 무시
        }

        // 청산 신호 처리
        if (signal.type() == TradeSignal.SignalType.CLOSE_LONG || signal.type() == TradeSignal.SignalType.CLOSE_SHORT) {
            if (currentPosition != null) {
                log.debug("[가상] OCO 주문 취소 로직을 건너뜁니다 (해당 없음).");
                closePosition(signal.price(), signal.reason());
            }
        }
        // 진입 또는 물타기 신호 처리
        else {
            // 물타기 시나리오
            if (currentPosition != null && currentPosition.getSide().toString().equals(signal.type().toString())) {
                log.debug("[가상] DCA를 위한 OCO 주문 취소 로직을 건너뜁니다 (해당 없음).");
                handleDca(signal);
            }
            // 신규 진입 시나리오
            else if (currentPosition == null) {
                log.debug("[가상] 레버리지 설정 로직을 건너뜁니다 (해당 없음).");
                openPosition(signal);
            }
        }
    }

    private void openPosition(TradeSignal signal) {
        BigDecimal executionPrice = applySlippage(signal.price(), signal.type());
        BigDecimal positionValue = currentBalance.multiply(signal.amount()); // 신규 진입은 signal.amount() 사용 (보통 고정값)
        BigDecimal fee = positionValue.multiply(feeRate);
        currentBalance = currentBalance.subtract(fee);

        Position.PositionSide side = (signal.type() == TradeSignal.SignalType.LONG) ? Position.PositionSide.LONG : Position.PositionSide.SHORT;
        BigDecimal coinAmount = positionValue.divide(executionPrice, 8, RoundingMode.HALF_UP);

        currentPosition = Position.builder()
                .symbol(signal.symbol())
                .side(side)
                .entryPrice(executionPrice)
                .amount(coinAmount)
                .leverage(tradingProperties.getGeneral().getLeverage())
                .dcaCount(1)
                .build();
        dcaCount = 1;
        log.info("🟢 [가상 신규 진입] 방향: {}, 체결가: {}, 수량: {}", side, executionPrice, coinAmount);
    }

    private void handleDca(TradeSignal signal) {
        if (dcaCount >= tradingProperties.getDca().getMaxCount()) {
            return;
        }

        BigDecimal executionPrice = applySlippage(signal.price(), signal.type());
        // [버그 수정] tradingProperties가 아닌, StrategyEngine이 계산해준 signal.amount()를 사용합니다.
        BigDecimal positionValue = currentBalance.multiply(signal.amount());
        BigDecimal fee = positionValue.multiply(feeRate);
        currentBalance = currentBalance.subtract(fee);

        BigDecimal addedCoinAmount = positionValue.divide(executionPrice, 8, RoundingMode.HALF_UP);

        BigDecimal oldTotalValue = currentPosition.getEntryPrice().multiply(currentPosition.getAmount());
        BigDecimal newTotalValue = executionPrice.multiply(addedCoinAmount);
        BigDecimal totalAmount = currentPosition.getAmount().add(addedCoinAmount);
        BigDecimal newAvgPrice = oldTotalValue.add(newTotalValue).divide(totalAmount, 8, RoundingMode.HALF_UP);

        dcaCount++;
        currentPosition.updateDca(newAvgPrice, totalAmount, dcaCount, tradingProperties.getDca().getMaxCount());
        log.info("💧 [가상 DCA {}/{}] 물타기 체결. 체결가: {}, 새 평균단가: {}, 총 수량: {}",
                dcaCount, tradingProperties.getDca().getMaxCount(), executionPrice.setScale(2, RoundingMode.HALF_UP), newAvgPrice.setScale(2, RoundingMode.HALF_UP), totalAmount);
    }

    private void closePosition(BigDecimal price, String reason) {
        BigDecimal executionPrice = applySlippage(price, (currentPosition.getSide() == Position.PositionSide.LONG) ? TradeSignal.SignalType.CLOSE_LONG : TradeSignal.SignalType.CLOSE_SHORT);
        BigDecimal entryPrice = currentPosition.getEntryPrice();
        BigDecimal amount = currentPosition.getAmount();

        BigDecimal pnl = (currentPosition.getSide() == Position.PositionSide.LONG)
                ? executionPrice.subtract(entryPrice).multiply(amount)
                : entryPrice.subtract(executionPrice).multiply(amount);

        BigDecimal fee = executionPrice.multiply(amount).multiply(feeRate);
        currentBalance = currentBalance.add(pnl).subtract(fee);
        tradeCount++;

        if (pnl.compareTo(BigDecimal.ZERO) > 0) {
            winCount++;
        }

        updateDrawdown();

        log.info("🔴 [가상 포지션 종료 - {}] 청산가: {}, 손익: ${}, 남은 잔고: ${}",
                reason, executionPrice, pnl.setScale(2, RoundingMode.HALF_UP), currentBalance.setScale(2, RoundingMode.HALF_UP));

        currentPosition = null;
        dcaCount = 0;
    }

    private BigDecimal applySlippage(BigDecimal price, TradeSignal.SignalType type) {
        BigDecimal slippageFactor = (type == TradeSignal.SignalType.LONG)
                ? BigDecimal.ONE.add(slippageRate)
                : BigDecimal.ONE.subtract(slippageRate);
        return price.multiply(slippageFactor);
    }

    private void updateDrawdown() {
        if (currentBalance.compareTo(peakBalance) > 0) {
            peakBalance = currentBalance;
        } else {
            BigDecimal drawdown = peakBalance.subtract(currentBalance).divide(peakBalance, 4, RoundingMode.HALF_UP);
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
        }
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