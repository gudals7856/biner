package com.biner.backtest.engine;

import com.biner.common.config.TradingProperties;
import com.biner.market.domain.Candle;
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
 * 백테스트 시 가상 주문을 처리하고 자산을 관리하는 모의 체결 서비스입니다.
 * StrategyEngine이 발행하는 TradeSignal에만 반응하며, 스스로 판단하지 않습니다.
 *
 * <ul>
 *   <li>진입/청산 모두 수수료와 슬리피지를 차감합니다.</li>
 *   <li>MDD는 실현 잔고가 아닌 '평가 자산(잔고 + 미실현 손익)' 기준으로 매 캔들 계산합니다.
 *       보수적으로 롱은 캔들 저가, 숏은 캔들 고가로 평가합니다.</li>
 *   <li>승률은 수수료까지 반영한 순손익 기준입니다.</li>
 * </ul>
 */
@Slf4j
@Service
@Profile("backtest")
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
  /** 현재 포지션 진입에 지불한 누적 수수료 (순손익 계산용) */
  private BigDecimal positionEntryFees = BigDecimal.ZERO;

  // --- 통계 변수 ---
  private int tradeCount;
  private int winCount;
  private BigDecimal totalFees;
  private BigDecimal peakEquity;
  private BigDecimal maxDrawdown;

  public Optional<Position> getCurrentPosition() {
    return Optional.ofNullable(currentPosition);
  }

  public void reset() {
    currentBalance = initialBalance;
    currentPosition = null;
    dcaCount = 0;
    positionEntryFees = BigDecimal.ZERO;
    tradeCount = 0;
    winCount = 0;
    totalFees = BigDecimal.ZERO;
    peakEquity = initialBalance;
    maxDrawdown = BigDecimal.ZERO;
    log.info("백테스트 상태가 초기화되었습니다. 시작 잔고: ${}", initialBalance);
  }

  @EventListener
  public void handleTradeSignal(TradeSignal signal) {
    if (!signal.symbol().contains("BACKTEST")) {
      return;
    }

    if (signal.type() == TradeSignal.SignalType.CLOSE_LONG || signal.type() == TradeSignal.SignalType.CLOSE_SHORT) {
      if (currentPosition != null) {
        closePosition(signal.price(), signal.reason());
      }
      return;
    }

    if (currentPosition == null) {
      openPosition(signal);
    } else if (currentPosition.getSide().name().equals(signal.type().name())) {
      handleDca(signal);
    }
  }

  private void openPosition(TradeSignal signal) {
    BigDecimal executionPrice = applySlippage(signal.price(), signal.type());
    BigDecimal positionValue = currentBalance.multiply(signal.amount());
    BigDecimal fee = chargeFee(positionValue);

    Position.PositionSide side = (signal.type() == TradeSignal.SignalType.LONG)
      ? Position.PositionSide.LONG
      : Position.PositionSide.SHORT;
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
    positionEntryFees = fee;
    log.info("🟢 [가상 신규 진입] 방향: {}, 체결가: {}, 수량: {}", side, executionPrice, coinAmount);
  }

  private void handleDca(TradeSignal signal) {
    if (dcaCount >= tradingProperties.getDca().getMaxCount()) {
      return;
    }

    BigDecimal executionPrice = applySlippage(signal.price(), signal.type());
    BigDecimal positionValue = currentBalance.multiply(signal.amount());
    positionEntryFees = positionEntryFees.add(chargeFee(positionValue));

    BigDecimal addedCoinAmount = positionValue.divide(executionPrice, 8, RoundingMode.HALF_UP);
    BigDecimal oldTotalValue = currentPosition.getEntryPrice().multiply(currentPosition.getAmount());
    BigDecimal totalAmount = currentPosition.getAmount().add(addedCoinAmount);
    BigDecimal newAvgPrice = oldTotalValue.add(executionPrice.multiply(addedCoinAmount))
      .divide(totalAmount, 8, RoundingMode.HALF_UP);

    dcaCount++;
    currentPosition.updateDca(newAvgPrice, totalAmount, dcaCount, tradingProperties.getDca().getMaxCount());
    log.info("💧 [가상 DCA {}/{}] 체결가: {}, 새 평균단가: {}, 총 수량: {}",
      dcaCount, tradingProperties.getDca().getMaxCount(),
      executionPrice.setScale(2, RoundingMode.HALF_UP), newAvgPrice.setScale(2, RoundingMode.HALF_UP), totalAmount);
  }

  private void closePosition(BigDecimal price, String reason) {
    TradeSignal.SignalType closeType = (currentPosition.getSide() == Position.PositionSide.LONG)
      ? TradeSignal.SignalType.CLOSE_LONG
      : TradeSignal.SignalType.CLOSE_SHORT;
    BigDecimal executionPrice = applySlippage(price, closeType);
    BigDecimal amount = currentPosition.getAmount();

    BigDecimal pnl = unrealizedPnl(executionPrice);
    BigDecimal exitFee = chargeFee(executionPrice.multiply(amount));
    currentBalance = currentBalance.add(pnl);

    BigDecimal netPnl = pnl.subtract(exitFee).subtract(positionEntryFees);
    tradeCount++;
    if (netPnl.signum() > 0) {
      winCount++;
    }
    updateDrawdown(currentBalance);

    log.info("🔴 [가상 포지션 종료 - {}] 청산가: {}, 손익: ${} (수수료 포함 순손익: ${}), 남은 잔고: ${}",
      reason, executionPrice.setScale(2, RoundingMode.HALF_UP), pnl.setScale(2, RoundingMode.HALF_UP),
      netPnl.setScale(2, RoundingMode.HALF_UP), currentBalance.setScale(2, RoundingMode.HALF_UP));

    currentPosition = null;
    dcaCount = 0;
    positionEntryFees = BigDecimal.ZERO;
  }

  /**
   * 매 캔들마다 호출되어 평가 자산 기준 MDD를 갱신합니다.
   * 보수적으로 롱 포지션은 캔들 저가, 숏 포지션은 캔들 고가로 평가합니다.
   */
  public void markToMarket(Candle candle) {
    if (currentPosition == null) {
      updateDrawdown(currentBalance);
      return;
    }
    BigDecimal worstPrice = currentPosition.getSide() == Position.PositionSide.LONG ? candle.getLow() : candle.getHigh();
    updateDrawdown(currentBalance.add(unrealizedPnl(worstPrice)));
  }

  private BigDecimal unrealizedPnl(BigDecimal price) {
    BigDecimal diff = currentPosition.getSide() == Position.PositionSide.LONG
      ? price.subtract(currentPosition.getEntryPrice())
      : currentPosition.getEntryPrice().subtract(price);
    return diff.multiply(currentPosition.getAmount());
  }

  private BigDecimal chargeFee(BigDecimal notional) {
    BigDecimal fee = notional.multiply(feeRate);
    currentBalance = currentBalance.subtract(fee);
    totalFees = totalFees.add(fee);
    return fee;
  }

  private BigDecimal applySlippage(BigDecimal price, TradeSignal.SignalType type) {
    // 매수(롱 진입, 숏 청산)는 불리하게 더 비싸게, 매도(숏 진입, 롱 청산)는 더 싸게 체결된다고 가정합니다.
    boolean isBuy = type == TradeSignal.SignalType.LONG || type == TradeSignal.SignalType.CLOSE_SHORT;
    BigDecimal factor = isBuy ? BigDecimal.ONE.add(slippageRate) : BigDecimal.ONE.subtract(slippageRate);
    return price.multiply(factor);
  }

  private void updateDrawdown(BigDecimal equity) {
    if (equity.compareTo(peakEquity) > 0) {
      peakEquity = equity;
      return;
    }
    BigDecimal drawdown = peakEquity.subtract(equity).divide(peakEquity, 6, RoundingMode.HALF_UP);
    if (drawdown.compareTo(maxDrawdown) > 0) {
      maxDrawdown = drawdown;
    }
  }

  public void printReport() {
    BigDecimal roi = currentBalance.subtract(initialBalance)
      .divide(initialBalance, 6, RoundingMode.HALF_UP)
      .multiply(new BigDecimal("100"));
    double winRate = tradeCount > 0 ? (double) winCount / tradeCount * 100 : 0.0;

    System.out.println("\n==================================================");
    System.out.println("📊 백테스트 결과 리포트");
    System.out.println("==================================================");
    System.out.println("💰 초기 자본금        : $" + initialBalance);
    System.out.println("💵 최종 잔고          : $" + currentBalance.setScale(2, RoundingMode.HALF_UP)
      + (currentPosition != null ? " (미청산 포지션 있음)" : ""));
    System.out.println("📈 총 투자수익률(ROI) : " + roi.setScale(2, RoundingMode.HALF_UP) + "%");
    System.out.println("📉 최대 낙폭(MDD)     : " + maxDrawdown.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP) + "% (평가자산 기준)");
    System.out.println("✅ 승률(순손익 기준)  : " + String.format("%.2f", winRate) + "%");
    System.out.println("🔄 총 거래 횟수       : " + tradeCount);
    System.out.println("🧾 총 수수료          : $" + totalFees.setScale(2, RoundingMode.HALF_UP));
    System.out.println("==================================================\n");
  }
}
