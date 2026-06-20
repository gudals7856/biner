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

    private static final BigDecimal STOP_LOSS_PERCENT = new BigDecimal("0.02");
    private static final BigDecimal TAKE_PROFIT_PERCENT = new BigDecimal("0.04");

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
            Optional<Position> existingPositionOpt = positionRepository.findBySymbolAndStatus(signal.symbol(), Position.PositionStatus.OPEN);

            if (existingPositionOpt.isPresent() && existingPositionOpt.get().getSide() == targetSide) {
                String cancelResponse = binanceClient.cancelAllOpenOrders(signal.symbol());
                log.info("Cancelled all previous open orders for DCA: {}", cancelResponse);
            }

            String side = targetSide == Position.PositionSide.LONG ? "BUY" : "SELL";
            BigDecimal quantity = signal.amount().setScale(3, RoundingMode.DOWN);
            String response = binanceClient.placeOrder(signal.symbol(), side, "MARKET", quantity, null);
            log.info("Binance Entry Order Response: {}", response);
            if ("FALLBACK_TRIGGERED".equals(response)) return;

            Position positionToSave;
            if (existingPositionOpt.isPresent() && existingPositionOpt.get().getSide() == targetSide) {
                Position existing = existingPositionOpt.get();
                BigDecimal oldTotalValue = existing.getEntryPrice().multiply(existing.getAmount());
                BigDecimal addedValue = signal.price().multiply(quantity);
                BigDecimal newTotalAmount = existing.getAmount().add(quantity);
                BigDecimal newAvgPrice = oldTotalValue.add(addedValue).divide(newTotalAmount, 8, RoundingMode.HALF_UP);
                int newDcaCount = existing.getDcaCount() + 1;
                
                existing.updateDca(newAvgPrice, newTotalAmount, newDcaCount);
                positionToSave = existing;
                log.info("DCA Processed. Position Updated: {}", positionToSave);
            } else {
                positionToSave = Position.builder()
                    .symbol(signal.symbol())
                    .side(targetSide)
                    .entryPrice(signal.price())
                    .amount(quantity)
                    .leverage(1)
                    .dcaCount(1)
                    .build();
                log.info("New Position Created: {}", positionToSave);
            }
            positionRepository.save(positionToSave);

            setupOcoOrders(positionToSave);

        } catch (Exception e) {
            log.error("Fatal error during order execution", e);
        }
    }

    private void setupOcoOrders(Position position) {
        String oppositeSide = position.getSide() == Position.PositionSide.LONG ? "SELL" : "BUY";
        BigDecimal stopFactor = position.getSide() == Position.PositionSide.LONG ? 
                BigDecimal.ONE.subtract(STOP_LOSS_PERCENT) : BigDecimal.ONE.add(STOP_LOSS_PERCENT);
        BigDecimal stopPrice = position.getEntryPrice().multiply(stopFactor).setScale(2, RoundingMode.HALF_UP);
        
        BigDecimal tpFactor = position.getSide() == Position.PositionSide.LONG ? 
                BigDecimal.ONE.add(TAKE_PROFIT_PERCENT) : BigDecimal.ONE.subtract(TAKE_PROFIT_PERCENT);
        BigDecimal takeProfitPrice = position.getEntryPrice().multiply(tpFactor).setScale(2, RoundingMode.HALF_UP);

        String stopResponse = binanceClient.placeOrder(position.getSymbol(), oppositeSide, "STOP_MARKET", position.getAmount(), stopPrice);
        log.info("Binance STOP-LOSS Order Placed at {}: Response {}", stopPrice, stopResponse);

        String tpResponse = binanceClient.placeOrder(position.getSymbol(), oppositeSide, "TAKE_PROFIT_MARKET", position.getAmount(), takeProfitPrice);
        log.info("Binance TAKE-PROFIT Order Placed at {}: Response {}", takeProfitPrice, tpResponse);
    }
}
