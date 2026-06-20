package com.biner.order.service;

import com.biner.order.client.BinanceFuturesClient;
import com.biner.order.domain.Position;
import com.biner.order.repository.PositionRepository;
import com.biner.strategy.domain.TradeSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@Slf4j
@Service
public class OrderExecutionService {

    private final PositionRepository positionRepository;
    private final BinanceFuturesClient binanceClient;

    private static final BigDecimal STOP_LOSS_PERCENT = new BigDecimal("0.02"); // 2% 생명줄
    private static final BigDecimal TAKE_PROFIT_PERCENT = new BigDecimal("0.04"); // 4% 익절 목표

    public OrderExecutionService(PositionRepository positionRepository, BinanceFuturesClient binanceClient) {
        this.positionRepository = positionRepository;
        this.binanceClient = binanceClient;
    }

    @EventListener
    @Transactional
    public void onTradeSignal(TradeSignal signal) {
        if (signal.symbol().contains("BACKTEST")) {
            return;
        }

        log.info("Received TradeSignal for Execution: {}", signal);

        Position.PositionSide targetSide = signal.type() == TradeSignal.SignalType.LONG ? 
                Position.PositionSide.LONG : Position.PositionSide.SHORT;

        try {
            Optional<Position> existingPositionOpt = positionRepository.findBySymbolAndSideNot(signal.symbol(), Position.PositionSide.NONE);
            
            // 1. [TODO 완료] 기존에 걸어두었던 미체결 주문(스탑로스, 익절)을 전부 취소합니다.
            // 물타기 시 수량과 평단가가 달라지므로, 옛날 정보로 걸려있는 주문을 없애야 합니다.
            if (existingPositionOpt.isPresent()) {
                String cancelResponse = binanceClient.cancelAllOpenOrders(signal.symbol());
                log.info("Cancelled all previous open orders for DCA: {}", cancelResponse);
            }

            // 2. 시장가 진입 주문 (MARKET)
            String side = targetSide == Position.PositionSide.LONG ? "BUY" : "SELL";
            BigDecimal quantity = signal.amount().setScale(3, RoundingMode.DOWN);

            String response = binanceClient.placeOrder(
                    signal.symbol(),
                    side,
                    "MARKET", 
                    quantity,
                    null
            );

            log.info("Binance Entry Order Response: {}", response);
            if ("FALLBACK_TRIGGERED".equals(response)) {
                log.warn("Order failed via fallback, aborting further execution.");
                return;
            }

            // 3. 로컬 DB 업데이트 (dcaCount 증가 로직 추가)
            BigDecimal newAvgPrice = signal.price();
            BigDecimal newTotalAmount = quantity;
            int currentDcaCount = 0;

            if (existingPositionOpt.isPresent()) {
                Position existing = existingPositionOpt.get();
                if (existing.getSide() == targetSide) {
                    BigDecimal oldTotalValue = existing.getEntryPrice().multiply(existing.getAmount());
                    BigDecimal addedValue = signal.price().multiply(quantity);
                    newTotalAmount = existing.getAmount().add(quantity);
                    newAvgPrice = oldTotalValue.add(addedValue).divide(newTotalAmount, 8, RoundingMode.HALF_UP);
                    
                    // [TODO 완료] 물타기 횟수 + 1
                    currentDcaCount = existing.getDcaCount() + 1;
                    
                    positionRepository.delete(existing);
                    log.info("DCA Processed. New Avg Price: {}, DCA Count: {}", newAvgPrice, currentDcaCount);
                }
            }

            Position position = Position.builder()
                .symbol(signal.symbol())
                .side(targetSide)
                .entryPrice(newAvgPrice)
                .amount(newTotalAmount)
                .leverage(1)
                .dcaCount(currentDcaCount) // 엔티티에 저장
                .build();
            positionRepository.save(position);


            // 4. 안전장치 (OCO 스탑로스 & 익절) 새롭게 세팅
            String oppositeSide = targetSide == Position.PositionSide.LONG ? "SELL" : "BUY";
            
            BigDecimal stopFactor = targetSide == Position.PositionSide.LONG ? 
                    BigDecimal.ONE.subtract(STOP_LOSS_PERCENT) : BigDecimal.ONE.add(STOP_LOSS_PERCENT);
            BigDecimal stopPrice = newAvgPrice.multiply(stopFactor).setScale(2, RoundingMode.HALF_UP);
            
            BigDecimal tpFactor = targetSide == Position.PositionSide.LONG ? 
                    BigDecimal.ONE.add(TAKE_PROFIT_PERCENT) : BigDecimal.ONE.subtract(TAKE_PROFIT_PERCENT);
            BigDecimal takeProfitPrice = newAvgPrice.multiply(tpFactor).setScale(2, RoundingMode.HALF_UP);

            String stopResponse = binanceClient.placeOrder(
                    signal.symbol(),
                    oppositeSide,
                    "STOP_MARKET", 
                    newTotalAmount,
                    stopPrice
            );
            log.info("Binance STOP-LOSS Order Placed at {}: Response {}", stopPrice, stopResponse);

            String tpResponse = binanceClient.placeOrder(
                    signal.symbol(),
                    oppositeSide,
                    "TAKE_PROFIT_MARKET", 
                    newTotalAmount,
                    takeProfitPrice
            );
            log.info("Binance TAKE-PROFIT Order Placed at {}: Response {}", takeProfitPrice, tpResponse);

        } catch (Exception e) {
            log.error("Fatal error during order execution", e);
        }
    }
}
