package com.biner.order.service;

import com.biner.order.client.BinanceFuturesClient;
import com.biner.order.domain.Position;
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
import java.util.Optional;

/**
 * 포지션과 관련된 모든 비즈니스 로직과 상태 관리를 책임지는 '두뇌' 역할을 하는 서비스입니다.
 * TradeSignal을 받아 신규/물타기 여부를 판단하고, 평균 단가를 계산하며, DB에 포지션을 저장/수정합니다.
 * 최종적으로 실제 주문 실행을 위한 구체적인 이벤트를 발행합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionService {

    private final PositionRepository positionRepository;
    private final BinanceFuturesClient binanceClient;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 전략 엔진에서 발생한 TradeSignal을 수신하여 포지션 관리 로직을 시작합니다.
     */
    @EventListener
    @Transactional
    public void handleTradeSignal(TradeSignal signal) {
        if (signal.symbol().contains("BACKTEST")) {
            return;
        }

        log.info("포지션 서비스가 트레이드 신호를 수신했습니다: {}", signal);

        Position.PositionSide targetSide = signal.type() == TradeSignal.SignalType.LONG
                ? Position.PositionSide.LONG : Position.PositionSide.SHORT;

        try {
            Optional<Position> existingPositionOpt = positionRepository.findBySymbolAndStatus(signal.symbol(), Position.PositionStatus.OPEN);

            // 물타기(DCA) 시나리오: 기존 포지션과 같은 방향의 신호가 온 경우
            if (existingPositionOpt.isPresent() && existingPositionOpt.get().getSide() == targetSide) {
                // 1. 기존에 걸려있던 OCO(손절/익절) 주문들을 모두 취소합니다.
                String cancelResponse = binanceClient.cancelAllOpenOrders(signal.symbol());
                log.info("DCA를 위해 기존 OCO 주문을 취소했습니다: {}", cancelResponse);
            }

            // 2. 실제 진입/추가 매수를 위한 시장가 주문 이벤트를 발행합니다.
            String side = targetSide == Position.PositionSide.LONG ? "BUY" : "SELL";
            BigDecimal quantity = signal.amount().setScale(3, RoundingMode.DOWN);

            eventPublisher.publishEvent(new PlaceOrderEvent(signal.symbol(), side, "MARKET", quantity, null));
            log.info("시장가 주문 실행 이벤트 발행: {} {} {}", side, quantity, signal.symbol());

            // 3. 주문이 체결되었다고 가정하고, DB의 포지션 상태를 업데이트합니다.
            Position positionToSave;
            if (existingPositionOpt.isPresent() && existingPositionOpt.get().getSide() == targetSide) {
                // 물타기 로직: 평균 단가 및 수량 재계산
                Position existing = existingPositionOpt.get();
                BigDecimal oldTotalValue = existing.getEntryPrice().multiply(existing.getAmount());
                BigDecimal addedValue = signal.price().multiply(quantity);
                BigDecimal newTotalAmount = existing.getAmount().add(quantity);
                BigDecimal newAvgPrice = oldTotalValue.add(addedValue).divide(newTotalAmount, 8, RoundingMode.HALF_UP);
                int newDcaCount = existing.getDcaCount() + 1;

                existing.updateDca(newAvgPrice, newTotalAmount, newDcaCount);
                positionToSave = existing;
                log.info("DCA 처리 완료. 포지션 정보 업데이트: {}", positionToSave);
            } else {
                // 신규 진입 로직
                positionToSave = Position.builder()
                        .symbol(signal.symbol())
                        .side(targetSide)
                        .entryPrice(signal.price())
                        .amount(quantity)
                        .leverage(1)
                        .dcaCount(1)
                        .build();
                log.info("신규 포지션 생성: {}", positionToSave);
            }

            Position savedPosition = positionRepository.save(positionToSave);

            // 4. 포지션 상태 변경이 완료되었음을 알리는 이벤트를 발행하여 OCO 주문 설정을 요청합니다.
            eventPublisher.publishEvent(new PositionChangedEvent(savedPosition));
            log.info("포지션 변경 이벤트 발행. OCO 주문 설정이 요청됩니다.");

        } catch (Exception e) {
            log.error("포지션 관리 중 심각한 오류 발생", e);
            // TODO: 오류 발생 시 알림 등의 추가 처리
        }
    }
}
