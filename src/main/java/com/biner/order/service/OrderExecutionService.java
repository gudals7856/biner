package com.biner.order.service;

import com.biner.order.client.BinanceFuturesClient;
import com.biner.order.domain.Position;
import com.biner.order.event.OrderFilledEvent;
import com.biner.order.event.PlaceOrderEvent;
import com.biner.order.event.PositionChangedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${trading.risk-management.stop-loss-percent}")
    private BigDecimal stopLossPercent;

    @Value("${trading.risk-management.take-profit-percent}")
    private BigDecimal takeProfitPercent;

    /**
     * 단일 주문 실행 이벤트를 수신하여 바이낸스에 주문을 전송합니다.
     * @param event 주문에 필요한 모든 정보를 담고 있는 이벤트 객체
     */
    @EventListener
    public void executeOrder(PlaceOrderEvent event) {
        try {
            log.info("주문 실행 명령 수신: {}", event);
            String response = binanceClient.placeOrder(event.symbol(), event.side(), event.type(), event.quantity(), event.price());
            log.info("바이낸스 주문 응답: {}", response);

            // 주문 성공 응답을 파싱하여 실제 체결 정보를 담은 이벤트를 발행합니다.
            JsonNode rootNode = objectMapper.readTree(response);
            if (rootNode.has("orderId")) { // 응답에 orderId가 있으면 성공으로 간주
                String symbol = rootNode.get("symbol").asText();
                String sideStr = rootNode.get("side").asText();
                BigDecimal filledQty = new BigDecimal(rootNode.get("cumQty").asText());
                BigDecimal avgPrice = new BigDecimal(rootNode.get("avgPrice").asText());

                if (filledQty.compareTo(BigDecimal.ZERO) > 0) {
                    Position.PositionSide side = "BUY".equalsIgnoreCase(sideStr) ? Position.PositionSide.LONG : Position.PositionSide.SHORT;
                    OrderFilledEvent filledEvent = new OrderFilledEvent(symbol, side, filledQty, avgPrice);
                    eventPublisher.publishEvent(filledEvent);
                    log.info("주문 체결 이벤트 발행: {}", filledEvent);
                }
            } else {
                log.error("주문 실패 또는 예외적 응답: {}", response);
            }
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