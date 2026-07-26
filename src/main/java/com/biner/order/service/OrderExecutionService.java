package com.biner.order.service;

import com.biner.order.client.BinanceFuturesClient;
import com.biner.order.domain.Position;
import com.biner.order.event.PlaceOrderEvent;
import com.biner.order.event.PositionChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * PositionService로부터 받은 구체적인 주문 명령을 받아
 * 실제 거래소 API를 호출하는 '손과 발' 역할을 수행하는 서비스입니다.
 * 이 서비스는 어떠한 비즈니스 로직이나 판단도 하지 않습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExecutionService {

    private final BinanceFuturesClient binanceClient;

    @Value("${trading.risk-management.stop-loss-percent}")
    private BigDecimal stopLossPercent;

    @Value("${trading.risk-management.take-profit-percent}")
    private BigDecimal takeProfitPercent;

    /**
     * 단일 주문 실행 이벤트를 수신하여 바이낸스에 주문을 전송합니다.
     * 체결 결과에 대한 책임은 BinanceUserDataStreamListener에게 위임합니다.
     * @param event 주문에 필요한 모든 정보를 담고 있는 이벤트 객체
     */
    @EventListener
    public void executeOrder(PlaceOrderEvent event) {
        try {
            log.info("주문 실행 명령 수신: {}", event);
            String response = binanceClient.placeOrder(event.symbol(), event.side(), event.type(), event.quantity(), event.price());
            log.info("바이낸스 주문 응답: {}", response);
            // TODO: 주문 실패 시 ₩₩₩처리 로직 (예: 재시도, 알림)
        } catch (Exception e) {
            log.error("주문 실행 중 오류 발생: {}", event, e);
        }
    }

    /**
     * 포지션 변경 이벤트를 수신하여 OCO(손절/익절) 주문을 설정합니다.
     * @param event 변경된 포지션 정보를 담고 있는 이벤트 객체
     */
    @EventListener
    public void setupOcoOrders(PositionChangedEvent event) {
        Position position = event.position();
        log.info("OCO 주문 설정 명령 수신. 포지션: {}", position);

        try {
            String oppositeSide = position.getSide() == Position.PositionSide.LONG ? "SELL" : "BUY";

            // 손절가 계산
            BigDecimal stopFactor = position.getSide() == Position.PositionSide.LONG
                    ? BigDecimal.ONE.subtract(stopLossPercent)
                    : BigDecimal.ONE.add(stopLossPercent);
            BigDecimal stopPrice = position.getEntryPrice().multiply(stopFactor).setScale(2, RoundingMode.HALF_UP);

            // 익절가 계산
            BigDecimal tpFactor = position.getSide() == Position.PositionSide.LONG
                    ? BigDecimal.ONE.add(takeProfitPercent)
                    : BigDecimal.ONE.subtract(takeProfitPercent);
            BigDecimal takeProfitPrice = position.getEntryPrice().multiply(tpFactor).setScale(2, RoundingMode.HALF_UP);

            // 손절 주문 전송
            String stopResponse = binanceClient.placeOrder(position.getSymbol(), oppositeSide, "STOP_MARKET", position.getAmount(), stopPrice);
            log.info("바이낸스 손절(STOP-LOSS) 주문 전송 완료. 설정가 {}: 응답 {}", stopPrice, stopResponse);

            // 익절 주문 전송
            String tpResponse = binanceClient.placeOrder(position.getSymbol(), oppositeSide, "TAKE_PROFIT_MARKET", position.getAmount(), takeProfitPrice);
            log.info("바이낸스 익절(TAKE-PROFIT) 주문 전송 완료. 설정가 {}: 응답 {}", takeProfitPrice, tpResponse);

        } catch (Exception e) {
            log.error("OCO 주문 설정 중 오류 발생. 포지션: {}", position, e);
        }
    }
}