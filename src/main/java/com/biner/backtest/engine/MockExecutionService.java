package com.biner.backtest.engine;

import com.biner.market.event.CandleClosedEvent;
import com.biner.order.domain.Position;
import com.biner.strategy.domain.TradeSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 백테스트(과거 데이터 시뮬레이션) 시 실제 거래소를 대신하여 가상으로 주문을 체결하고
 * 잔고, 수익률, 승률 등을 계산하는 모의 체결 서비스입니다.
 */
@Slf4j
@Service
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
        if (currentPosition == null) return; // 보유 포지션이 없으면 패스

        BigDecimal avgEntryPrice = currentPosition.getEntryPrice();
        BigDecimal low = candle.low();
        BigDecimal high = candle.high();

        if (currentPosition.getSide() == Position.PositionSide.LONG) {
            // 롱 포지션: 손절가는 평단가보다 낮고, 익절가는 평단가보다 높습니다.
            BigDecimal stopLossPrice = avgEntryPrice.multiply(BigDecimal.ONE.subtract(stopLossPercent));
            BigDecimal takeProfitPrice = avgEntryPrice.multiply(BigDecimal.ONE.add(takeProfitPercent));

            // 저가(low)가 손절가보다 작거나 같다면 손절 터치로 간주
            if (low.compareTo(stopLossPrice) <= 0) {
                log.info("🚨 [가상 손절] 롱 포지션 손절가 터치: {}", stopLossPrice);
                closePosition(stopLossPrice, "Stop-Loss");
            } 
            // 고가(high)가 익절가보다 크거나 같다면 익절 터치로 간주
            else if (high.compareTo(takeProfitPrice) >= 0) {
                log.info("🎉 [가상 익절] 롱 포지션 익절가 터치: {}", takeProfitPrice);
                closePosition(takeProfitPrice, "Take-Profit");
            }
        } else {
            // 숏 포지션 로직: 롱과 반대로 가격이 오르면 손실, 내리면 수익입니다.
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
        // 실제 API 호출 방지를 위해 BACKTEST가 포함된 신호만 처리할 수도 있으나, 
        // MockExecutionService 자체가 백테스트 전용이므로 신호를 받아 처리합니다.
        BigDecimal currentPrice = signal.price();
        Position.PositionSide signalSide = signal.type() == TradeSignal.SignalType.LONG ? Position.PositionSide.LONG : Position.PositionSide.SHORT;

        // 1. 기존 포지션이 있는데, 반대 방향의 신호가 온 경우 -> 스위칭(기존 포지션 청산 후 반대로 진입)을 위해 기존 포지션을 종료합니다.
        if (currentPosition != null && currentPosition.getSide() != signalSide) {
            log.info("🔄 [포지션 스위칭] 반대 방향 신호 수신. 기존 포지션을 종료합니다.");
            closePosition(currentPrice, "Signal-Switch");
        }

        // 2. 포지션 진입 로직
        if (currentPosition == null) {
            // 포지션이 없으면 신규 진입
            openPosition(signal, currentPrice);
        } else if (currentPosition.getSide() == signalSide) {
            // 이미 같은 방향의 포지션이 있다면 물타기(DCA) 조건 확인
            handleDca(signal, currentPrice);
        }
    }

    /**
     * 물타기(DCA) 조건을 확인하고 가상으로 추가 매수를 실행합니다.
     */
    private void handleDca(TradeSignal signal, BigDecimal currentPrice) {
        if (dcaCount >= maxDcaCount) {
            return; // 최대 진입 횟수에 도달하면 추가 매수하지 않음
        }

        BigDecimal avgEntryPrice = currentPosition.getEntryPrice();
        
        // 방향에 따라 물타기 조건 확인 (설정된 % 이상 손실 중일 때만 물타기)
        boolean shouldDca = false;
        if (currentPosition.getSide() == Position.PositionSide.LONG) {
            // 롱: 현재가가 (평단가 - 1%) 이하일 때
            BigDecimal dcaTriggerPrice = avgEntryPrice.multiply(BigDecimal.ONE.subtract(dcaDropPercent));
            shouldDca = currentPrice.compareTo(dcaTriggerPrice) <= 0;
        } else {
            // 숏: 현재가가 (평단가 + 1%) 이상일 때
            BigDecimal dcaTriggerPrice = avgEntryPrice.multiply(BigDecimal.ONE.add(dcaDropPercent));
            shouldDca = currentPrice.compareTo(dcaTriggerPrice) >= 0;
        }

        if (shouldDca) {
            // 슬리피지를 적용하여 실제 체결가격을 불리하게 만듭니다.
            BigDecimal slippageFactor = currentPosition.getSide() == Position.PositionSide.LONG ? 
                    BigDecimal.ONE.add(slippageRate) : BigDecimal.ONE.subtract(slippageRate);
            BigDecimal executionPrice = currentPrice.multiply(slippageFactor);

            // 잔고를 바탕으로 추가 매수할 가치 계산 (signal.amount()는 % 비중)
            BigDecimal positionValue = currentBalance.multiply(signal.amount()); 
            // 수수료 차감
            BigDecimal fee = positionValue.multiply(feeRate);
            currentBalance = currentBalance.subtract(fee);

            // 추가로 획득한 코인 수량
            BigDecimal addedCoinAmount = positionValue.divide(executionPrice, 8, RoundingMode.HALF_UP);

            // 새로운 평균 단가 계산: (기존 총 가치 + 추가 매수 가치) / 새로운 총 수량
            BigDecimal oldTotalValue = currentPosition.getEntryPrice().multiply(currentPosition.getAmount());
            BigDecimal newTotalValue = executionPrice.multiply(addedCoinAmount);
            BigDecimal totalAmount = currentPosition.getAmount().add(addedCoinAmount);
            BigDecimal newAvgPrice = oldTotalValue.add(newTotalValue).divide(totalAmount, 8, RoundingMode.HALF_UP);

            // 가상 포지션 객체 업데이트
            currentPosition = Position.builder()
                    .symbol(signal.symbol())
                    .side(currentPosition.getSide())
                    .entryPrice(newAvgPrice)
                    .amount(totalAmount)
                    .leverage(1)
                    .build();
            
            dcaCount++; // 진입 횟수 증가
            log.info("💧 [가상 DCA {}/{}] 물타기 체결. 새 평균단가: {}, 총 수량: {}", dcaCount, maxDcaCount, newAvgPrice, totalAmount);
        }
    }

    /**
     * 신규 포지션에 가상으로 진입합니다.
     */
    private void openPosition(TradeSignal signal, BigDecimal price) {
        // 슬리피지 적용
        BigDecimal slippageFactor = signal.type() == TradeSignal.SignalType.LONG ? 
                BigDecimal.ONE.add(slippageRate) : BigDecimal.ONE.subtract(slippageRate);
        BigDecimal executionPrice = price.multiply(slippageFactor);

        // 투입 금액 및 수수료 계산
        BigDecimal positionValue = currentBalance.multiply(signal.amount());
        BigDecimal fee = positionValue.multiply(feeRate);
        currentBalance = currentBalance.subtract(fee);

        // 코인 수량 계산 및 포지션 객체 생성
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

    /**
     * 현재 보유 중인 가상 포지션을 종료(청산)하고, 손익(PnL)을 계산하여 잔고에 반영합니다.
     * 
     * @param currentPrice 청산 기준 가격 (이 가격에 슬리피지가 적용됨)
     * @param reason 청산 사유 (예: "Stop-Loss", "Take-Profit", "Signal-Switch")
     */
    private void closePosition(BigDecimal currentPrice, String reason) {
        if (currentPosition == null) return;

        // 청산 시에도 슬리피지 적용 (불리하게 팔리도록 시뮬레이션)
        BigDecimal slippageFactor = currentPosition.getSide() == Position.PositionSide.LONG ? 
                BigDecimal.ONE.subtract(slippageRate) : BigDecimal.ONE.add(slippageRate);
        BigDecimal executionPrice = currentPrice.multiply(slippageFactor);

        BigDecimal entryPrice = currentPosition.getEntryPrice();
        BigDecimal amount = currentPosition.getAmount();
        
        // 손익금(PnL) 계산
        BigDecimal pnl;
        if (currentPosition.getSide() == Position.PositionSide.LONG) {
            pnl = executionPrice.subtract(entryPrice).multiply(amount);
        } else {
            pnl = entryPrice.subtract(executionPrice).multiply(amount);
        }

        // 청산 수수료 계산
        BigDecimal positionValue = executionPrice.multiply(amount);
        BigDecimal fee = positionValue.multiply(feeRate);
        
        // 잔고 업데이트 (원금 + 손익 - 수수료)
        currentBalance = currentBalance.add(pnl).subtract(fee);
        tradeCount++;
        
        // 수익이 났다면 승리 카운트 증가
        if (pnl.compareTo(BigDecimal.ZERO) > 0) {
            winCount++;
        }

        // 최대 낙폭(MDD) 계산 및 트래킹
        // 현재 잔고가 이전 최고점보다 높으면 최고점 갱신
        if (currentBalance.compareTo(peakBalance) > 0) {
            peakBalance = currentBalance;
        } else {
            // 잔고가 최고점보다 낮다면, 낙폭(Drawdown)을 계산
            BigDecimal drawdown = peakBalance.subtract(currentBalance).divide(peakBalance, 4, RoundingMode.HALF_UP);
            // 기존 최대 낙폭보다 현재 낙폭이 크면 갱신
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
        }

        log.info("🔴 [가상 포지션 종료 - {}] 청산가: {}, 손익: ${}, 남은 잔고: ${}", 
                reason, executionPrice, pnl.setScale(2, RoundingMode.HALF_UP), currentBalance.setScale(2, RoundingMode.HALF_UP));
        
        // 포지션 상태 초기화
        currentPosition = null;
        dcaCount = 0;
    }

    /**
     * 백테스트가 모두 종료된 후 최종 수익률 및 통계를 콘솔에 출력합니다.
     */
    public void printReport() {
        // 투자수익률(ROI) 계산: (최종잔고 - 초기잔고) / 초기잔고 * 100
        BigDecimal roi = currentBalance.subtract(initialBalance)
                .divide(initialBalance, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
        
        // 승률 계산
        double winRate = tradeCount > 0 ? (double) winCount / tradeCount * 100 : 0.0;

        System.out.println("\n==================================================");
        System.out.println("📊 백테스트 결과 리포트 (Phase 4)");
        System.out.println("==================================================");
        System.out.println("💰 초기 자본금      : $" + initialBalance);
        System.out.println("💵 최종 잔고        : $" + currentBalance.setScale(2, RoundingMode.HALF_UP));
        System.out.println("📈 총 투자수익률(ROI) : " + roi + "%");
        System.out.println("📉 최대 낙폭(MDD)    : " + maxDrawdown.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP) + "%");
        System.out.println("✅ 승률             : " + String.format("%.2f", winRate) + "%");
        System.out.println("🔄 총 거래 횟수     : " + tradeCount);
        System.out.println("==================================================\n");
    }
}
