package com.biner.order.service;

import com.biner.order.client.BinanceFuturesClient;
import com.biner.order.domain.Position;
import com.biner.order.repository.PositionRepository;
import com.biner.strategy.domain.TradeSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * 트레이딩 신호를 받아 실제 바이낸스 거래소에 주문을 실행하고,
 * 데이터베이스에 포지션 상태를 기록하는 핵심 서비스입니다.
 */
@Slf4j
@Service
public class OrderExecutionService {

    // 포지션 정보를 데이터베이스에 저장하고 조회하기 위한 리포지토리
    private final PositionRepository positionRepository;
    
    // 바이낸스 선물 거래소 API 호출을 위한 클라이언트
    private final BinanceFuturesClient binanceClient;

    private final BigDecimal stopLossPercent;
    private final BigDecimal takeProfitPercent;

    public OrderExecutionService(
            PositionRepository positionRepository,
            BinanceFuturesClient binanceClient,
            @Value("${trading.risk-management.stop-loss-percent}") String stopLossPercentStr,
            @Value("${trading.risk-management.take-profit-percent}") String takeProfitPercentStr
    ) {
        this.positionRepository = positionRepository;
        this.binanceClient = binanceClient;
        this.stopLossPercent = new BigDecimal(stopLossPercentStr);
        this.takeProfitPercent = new BigDecimal(takeProfitPercentStr);
    }

    /**
     * 전략 모듈에서 발생한 트레이드 신호(TradeSignal) 이벤트를 수신하여 주문을 실행합니다.
     * 
     * @param signal 매수/매도 시점, 가격, 수량 등의 정보를 담고 있는 트레이드 신호
     */
    @EventListener
    @Transactional
    public void onTradeSignal(TradeSignal signal) {
        // 백테스트(과거 데이터 모의 투자)용 신호인 경우, 실제 주문을 넣지 않고 무시합니다.
        if (signal.symbol().contains("BACKTEST")) {
            return;
        }

        log.info("주문 실행을 위한 트레이드 신호 수신: {}", signal);

        // 신호 타입이 LONG(매수)인지 SHORT(매도)인지 판단하여 목표 포지션 방향을 설정합니다.
        Position.PositionSide targetSide = signal.type() == TradeSignal.SignalType.LONG ? 
                Position.PositionSide.LONG : Position.PositionSide.SHORT;

        try {
            // 현재 동일한 코인(심볼)에 대해 열려있는(OPEN) 포지션이 있는지 DB에서 확인합니다.
            Optional<Position> existingPositionOpt = positionRepository.findBySymbolAndStatus(signal.symbol(), Position.PositionStatus.OPEN);

            // 물타기(DCA)를 위한 기존 열린 주문 취소 로직
            // 기존 포지션이 존재하고, 방향이 현재 신호의 방향과 같다면 (예: 롱 포지션 보유 중인데 또 롱 신호가 온 경우)
            if (existingPositionOpt.isPresent() && existingPositionOpt.get().getSide() == targetSide) {
                // 이전에 걸어두었던 손절/익절 등 대기 주문들을 모두 취소합니다. (새로운 평균단가에 맞춰 다시 걸기 위함)
                String cancelResponse = binanceClient.cancelAllOpenOrders(signal.symbol());
                log.info("물타기(DCA)를 위해 기존 대기 주문들 취소 완료: {}", cancelResponse);
            }

            // 거래소에 주문을 넣기 위한 방향 문자열 설정 ("BUY" 또는 "SELL")
            String side = targetSide == Position.PositionSide.LONG ? "BUY" : "SELL";
            
            // 수량은 소수점 3자리까지만 남기고 내림 처리합니다. (바이낸스 수량 규격에 맞춤)
            BigDecimal quantity = signal.amount().setScale(3, RoundingMode.DOWN);
            
            // 바이낸스 거래소에 시장가(MARKET)로 주문을 전송합니다.
            String response = binanceClient.placeOrder(signal.symbol(), side, "MARKET", quantity, null);
            log.info("바이낸스 진입 주문 응답: {}", response);
            
            // 주문 실패(Fallback 작동 등) 시 더 이상 진행하지 않고 종료합니다.
            if ("FALLBACK_TRIGGERED".equals(response)) return;

            Position positionToSave;
            // 물타기(DCA - Dollar Cost Averaging) 로직 처리
            if (existingPositionOpt.isPresent() && existingPositionOpt.get().getSide() == targetSide) {
                // 기존 포지션에 새로운 주문을 합쳐서 평균 단가와 총 수량을 업데이트합니다.
                Position existing = existingPositionOpt.get();
                
                // 기존 매수 금액 = 기존 평균단가 * 기존 수량
                BigDecimal oldTotalValue = existing.getEntryPrice().multiply(existing.getAmount());
                // 추가 매수 금액 = 이번 진입가 * 이번 수량
                BigDecimal addedValue = signal.price().multiply(quantity);
                // 새로운 총 수량 = 기존 수량 + 이번 수량
                BigDecimal newTotalAmount = existing.getAmount().add(quantity);
                // 새로운 평균단가 = (기존 매수 금액 + 추가 매수 금액) / 새로운 총 수량
                BigDecimal newAvgPrice = oldTotalValue.add(addedValue).divide(newTotalAmount, 8, RoundingMode.HALF_UP);
                
                // 물타기 횟수 증가
                int newDcaCount = existing.getDcaCount() + 1;
                
                // 엔티티 업데이트
                existing.updateDca(newAvgPrice, newTotalAmount, newDcaCount);
                positionToSave = existing;
                log.info("물타기(DCA) 완료. 포지션 정보 업데이트 됨: {}", positionToSave);
            } else {
                // 기존 포지션이 없거나 방향이 반대인 경우, 완전히 새로운 포지션 객체를 생성합니다.
                positionToSave = Position.builder()
                    .symbol(signal.symbol())
                    .side(targetSide)
                    .entryPrice(signal.price()) // 진입 가격
                    .amount(quantity)           // 수량
                    .leverage(1)                // 레버리지 (현재 1배 고정)
                    .dcaCount(1)                // 진입 횟수 (1회)
                    .build();
                log.info("새로운 포지션 생성됨: {}", positionToSave);
            }
            
            // 변경되거나 새로 생성된 포지션을 데이터베이스에 저장합니다.
            positionRepository.save(positionToSave);

            // 포지션 진입이 완료되었으므로, 위험 관리를 위한 손절/익절 주문을 세팅합니다.
            setupOcoOrders(positionToSave);

        } catch (Exception e) {
            log.error("주문 실행 중 치명적인 에러 발생", e);
        }
    }

    /**
     * 포지션 보호를 위해 손절(Stop Loss) 및 익절(Take Profit) 대기 주문을 바이낸스에 설정합니다.
     * 
     * @param position 주문 기준이 되는 현재 포지션 객체
     */
    private void setupOcoOrders(Position position) {
        // 손절/익절은 포지션을 종료하는 것이므로, 진입 방향과 반대 방향(SELL/BUY)으로 설정해야 합니다.
        String oppositeSide = position.getSide() == Position.PositionSide.LONG ? "SELL" : "BUY";
        
        // 손절가 계산 로직
        // 롱 포지션: 진입가 * (1 - 손절비율) -> 가격이 떨어지면 팝니다.
        // 숏 포지션: 진입가 * (1 + 손절비율) -> 가격이 오르면 삽니다.
        BigDecimal stopFactor = position.getSide() == Position.PositionSide.LONG ? 
                BigDecimal.ONE.subtract(stopLossPercent) : BigDecimal.ONE.add(stopLossPercent);
        BigDecimal stopPrice = position.getEntryPrice().multiply(stopFactor).setScale(2, RoundingMode.HALF_UP);
        
        // 익절가 계산 로직
        // 롱 포지션: 진입가 * (1 + 익절비율) -> 가격이 오르면 팝니다.
        // 숏 포지션: 진입가 * (1 - 익절비율) -> 가격이 떨어지면 삽니다.
        BigDecimal tpFactor = position.getSide() == Position.PositionSide.LONG ? 
                BigDecimal.ONE.add(takeProfitPercent) : BigDecimal.ONE.subtract(takeProfitPercent);
        BigDecimal takeProfitPrice = position.getEntryPrice().multiply(tpFactor).setScale(2, RoundingMode.HALF_UP);

        // 바이낸스에 시장가 손절(STOP_MARKET) 주문을 전송합니다.
        String stopResponse = binanceClient.placeOrder(position.getSymbol(), oppositeSide, "STOP_MARKET", position.getAmount(), stopPrice);
        log.info("바이낸스 손절(STOP-LOSS) 주문 전송 완료. 설정가 {}: 응답 {}", stopPrice, stopResponse);

        // 바이낸스에 시장가 익절(TAKE_PROFIT_MARKET) 주문을 전송합니다.
        String tpResponse = binanceClient.placeOrder(position.getSymbol(), oppositeSide, "TAKE_PROFIT_MARKET", position.getAmount(), takeProfitPrice);
        log.info("바이낸스 익절(TAKE-PROFIT) 주문 전송 완료. 설정가 {}: 응답 {}", takeProfitPrice, tpResponse);
    }
}
