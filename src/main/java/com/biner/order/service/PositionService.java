package com.biner.order.service;

import com.biner.account.service.AccountService;
import com.biner.order.client.BinanceFuturesClient;
import com.biner.order.domain.Position;
import com.biner.order.event.OrderFilledEvent;
import com.biner.order.event.PlaceOrderEvent;
import com.biner.order.event.PositionChangedEvent;
import com.biner.order.repository.PositionRepository;
import com.biner.strategy.domain.TradeSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 포지션과 관련된 모든 비즈니스 로직과 상태 관리를 책임지는 '두뇌' 역할을 하는 서비스입니다.
 * TradeSignal을 받아 신규/물타기 여부를 판단하고, 계좌 잔고를 조회하여 주문 수량을 계산하며,
 * 최종적으로 실제 주문 실행을 위한 구체적인 이벤트를 발행합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionService {

    private final PositionRepository positionRepository;
    private final BinanceFuturesClient binanceClient;
    private final ApplicationEventPublisher eventPublisher;
    private final AccountService accountService; // 계좌 정보 조회를 위한 서비스

    private static final String QUOTE_ASSET = "USDT"; // 견적 자산 (주문 수량 계산 기준)

    // 동시성 제어를 위한 인-메모리 잠금
    private final Set<String> lockedSymbols = ConcurrentHashMap.newKeySet();
    // 처리 중인 신호를 저장하기 위한 맵 (최신 신호로 교체)
    private final Map<String, TradeSignal> pendingSignals = new ConcurrentHashMap<>();

    /**
     * 전략 엔진에서 발생한 TradeSignal을 수신하여 포지션 관리 로직을 시작합니다.
     */
    @EventListener
    @Transactional
    public void handleTradeSignal(TradeSignal signal) {
        if (signal.symbol().contains("BACKTEST")) {
            return;
        }

        if (!lockedSymbols.add(signal.symbol())) {
            log.info("[{}] 심볼은 현재 다른 주문이 처리 중이므로, 수신된 신호를 대기열에 추가합니다: {}", signal.symbol(), signal);
            pendingSignals.put(signal.symbol(), signal);
            return;
        }

        try {
            log.info("포지션 서비스가 트레이드 신호를 수신했습니다: {}", signal);
            Optional<Position> existingPositionOpt = positionRepository.findBySymbolAndStatus(signal.symbol(), Position.PositionStatus.OPEN);

            // 청산 신호 처리
            if (signal.type() == TradeSignal.SignalType.CLOSE_LONG || signal.type() == TradeSignal.SignalType.CLOSE_SHORT) {
                if (existingPositionOpt.isEmpty()) {
                    log.warn("청산 신호가 수신되었으나, DB에 해당 포지션이 없습니다: {}", signal);
                    return;
                }
                Position position = existingPositionOpt.get();
                String side = (signal.type() == TradeSignal.SignalType.CLOSE_LONG) ? "SELL" : "BUY";
                eventPublisher.publishEvent(new PlaceOrderEvent(signal.symbol(), side, "MARKET", position.getAmount(), null));
                log.info("포지션 청산 주문 실행 이벤트 발행: {} {} {}", side, position.getAmount(), signal.symbol());
            }
            // 신규 진입 또는 물타기 신호 처리
            else if (signal.type() == TradeSignal.SignalType.LONG || signal.type() == TradeSignal.SignalType.SHORT) {
                handleEntryOrDcaSignal(signal, existingPositionOpt);
            }

        } catch (Exception e) {
            log.error("포지션 관리 중 심각한 오류 발생", e);
        } finally {
            lockedSymbols.remove(signal.symbol());
            log.debug("[{}] 심볼 처리가 완료되어 잠금을 해제합니다.", signal.symbol());

            TradeSignal pending = pendingSignals.remove(signal.symbol());
            if (pending != null) {
                log.info("[{}] 심볼에 대한 대기 신호가 있어 재처리를 시작합니다: {}", pending.symbol(), pending);
                eventPublisher.publishEvent(pending);
            }
        }
    }

    private void handleEntryOrDcaSignal(TradeSignal signal, Optional<Position> existingPositionOpt) {
        BigDecimal availableBalance = accountService.getAvailableBalance(QUOTE_ASSET);
        if (availableBalance.compareTo(BigDecimal.TEN) < 0) {
            log.warn("가용 잔고({})가 부족하여 주문을 실행할 수 없습니다.", availableBalance);
            return;
        }

        BigDecimal investmentAmount = availableBalance.multiply(signal.amount());
        BigDecimal quantity = investmentAmount.divide(signal.price(), 3, RoundingMode.DOWN);
        log.info("주문 수량 계산 완료: 잔고 {} USDT * 비율 {}% = {} USDT / 현재가 {} = {}개",
                availableBalance.setScale(2, RoundingMode.DOWN),
                signal.amount().multiply(BigDecimal.valueOf(100)),
                investmentAmount.setScale(2, RoundingMode.DOWN),
                signal.price(),
                quantity);

        if (quantity.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("계산된 주문 수량이 0이므로 주문을 실행하지 않습니다.");
            return;
        }

        Position.PositionSide targetSide = (signal.type() == TradeSignal.SignalType.LONG) ? Position.PositionSide.LONG : Position.PositionSide.SHORT;

        if (existingPositionOpt.isPresent() && existingPositionOpt.get().getSide() == targetSide) {
            String cancelResponse = binanceClient.cancelAllOpenOrders(signal.symbol());
            log.info("DCA를 위해 기존 OCO 주문을 취소했습니다: {}", cancelResponse);
        }

        String side = (targetSide == Position.PositionSide.LONG) ? "BUY" : "SELL";
        eventPublisher.publishEvent(new PlaceOrderEvent(signal.symbol(), side, "MARKET", quantity, null));
        log.info("시장가 주문 실행 이벤트 발행: {} {} {}", side, quantity, signal.symbol());
    }

    /**
     * 실제 주문 체결(OrderFilledEvent)을 수신하여 DB의 포지션 상태를 업데이트합니다.
     * 이 메소드는 실제 체결된 데이터에 기반하므로 시스템의 데이터 정합성을 보장합니다.
     */
    @EventListener
    @Transactional
    public void handleOrderFilled(OrderFilledEvent event) {
        log.info("주문 체결 완료. DB 상태 업데이트 시작: {}", event);

        Optional<Position> existingPositionOpt = positionRepository.findBySymbolAndStatus(event.symbol(), Position.PositionStatus.OPEN);

        if (existingPositionOpt.isPresent()) {
            Position existing = existingPositionOpt.get();
            // 포지션 청산 로직 (반대 방향 주문 체결 시)
            if (existing.getSide() != event.side()) {
                existing.close();
                log.info("포지션 청산 완료: {}", existing);
                // OCO 주문 설정을 위한 이벤트를 발행하지 않음
                positionRepository.save(existing);
            }
            // 물타기 로직 (같은 방향 주문 체결 시)
            else {
                BigDecimal oldTotalValue = existing.getEntryPrice().multiply(existing.getAmount());
                BigDecimal addedValue = event.avgPrice().multiply(event.filledQty());
                BigDecimal newTotalAmount = existing.getAmount().add(event.filledQty());
                BigDecimal newAvgPrice = oldTotalValue.add(addedValue).divide(newTotalAmount, 8, RoundingMode.HALF_UP);
                int newDcaCount = existing.getDcaCount() + 1;

                existing.updateDca(newAvgPrice, newTotalAmount, newDcaCount);
                log.info("DCA 처리 완료. 포지션 정보 업데이트: {}", existing);

                Position savedPosition = positionRepository.save(existing);
                eventPublisher.publishEvent(new PositionChangedEvent(savedPosition));
                log.info("포지션 변경 이벤트 발행. OCO 주문 설정이 요청됩니다.");
            }
        }
        // 신규 진입 로직
        else {
            Position newPosition = Position.builder()
                    .symbol(event.symbol())
                    .side(event.side())
                    .entryPrice(event.avgPrice())
                    .amount(event.filledQty())
                    .leverage(1)
                    .dcaCount(1)
                    .build();
            log.info("신규 포지션 생성: {}", newPosition);

            Position savedPosition = positionRepository.save(newPosition);
            eventPublisher.publishEvent(new PositionChangedEvent(savedPosition));
            log.info("포지션 변경 이벤트 발행. OCO 주문 설정이 요청됩니다.");
        }
    }
}