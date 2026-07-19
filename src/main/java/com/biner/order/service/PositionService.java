package com.biner.order.service;

import com.biner.account.service.AccountService;
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

        try {
            // 1. 현재 계좌의 가용 잔고(USDT)를 조회합니다.
            BigDecimal availableBalance = accountService.getAvailableBalance(QUOTE_ASSET);
            if (availableBalance.compareTo(BigDecimal.TEN) < 0) { // 최소 주문 금액(예: 10 USDT) 체크
                log.warn("가용 잔고({})가 부족하여 주문을 실행할 수 없습니다.", availableBalance);
                return;
            }

            // 2. 신호에 담긴 '비율'과 '현재가'를 바탕으로 실제 주문할 '수량'을 계산합니다.
            BigDecimal investmentAmount = availableBalance.multiply(signal.amount()); // 투자할 금액 (USDT)
            BigDecimal quantity = investmentAmount.divide(signal.price(), 3, RoundingMode.DOWN); // (금액 / 가격) = 수량
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

            Position.PositionSide targetSide = signal.type() == TradeSignal.SignalType.LONG
                    ? Position.PositionSide.LONG : Position.PositionSide.SHORT;

            Optional<Position> existingPositionOpt = positionRepository.findBySymbolAndStatus(signal.symbol(), Position.PositionStatus.OPEN);

            // 3. 물타기(DCA) 시나리오일 경우, 기존 OCO 주문을 먼저 취소합니다.
            if (existingPositionOpt.isPresent() && existingPositionOpt.get().getSide() == targetSide) {
                String cancelResponse = binanceClient.cancelAllOpenOrders(signal.symbol());
                log.info("DCA를 위해 기존 OCO 주문을 취소했습니다: {}", cancelResponse);
            }

            // 4. 계산된 최종 '수량'으로 시장가 주문 이벤트를 발행합니다.
            String side = targetSide == Position.PositionSide.LONG ? "BUY" : "SELL";
            eventPublisher.publishEvent(new PlaceOrderEvent(signal.symbol(), side, "MARKET", quantity, null));
            log.info("시장가 주문 실행 이벤트 발행: {} {} {}", side, quantity, signal.symbol());

            // 5. 주문이 체결되었다고 가정하고, DB의 포지션 상태를 업데이트합니다.
            updatePositionState(signal, quantity, existingPositionOpt);

        } catch (Exception e) {
            log.error("포지션 관리 중 심각한 오류 발생", e);
        }
    }

    /**
     * 신호와 계산된 수량을 바탕으로 DB의 포지션 상태를 업데이트하고 이벤트를 발행합니다.
     */
    private void updatePositionState(TradeSignal signal, BigDecimal quantity, Optional<Position> existingPositionOpt) {
        Position positionToSave;
        Position.PositionSide targetSide = signal.type() == TradeSignal.SignalType.LONG
                ? Position.PositionSide.LONG : Position.PositionSide.SHORT;

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

        // 포지션 상태 변경이 완료되었음을 알리는 이벤트를 발행하여 OCO 주문 설정을 요청합니다.
        eventPublisher.publishEvent(new PositionChangedEvent(savedPosition));
        log.info("포지션 변경 이벤트 발행. OCO 주문 설정이 요청됩니다.");
    }
}
