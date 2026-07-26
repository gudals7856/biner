package com.biner.order.service;

import com.biner.order.client.BinanceFuturesClient;
import com.biner.order.domain.Position;
import com.biner.order.event.OrderFilledEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.function.Consumer;

/**
 * 바이낸스 사용자 데이터 웹소켓 스트림을 구독하고,
 * 실시간 주문 체결 정보를 시스템 내부 이벤트로 발행하는 서비스입니다.
 * 이 클래스는 시스템의 '귀' 역할을 수행합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BinanceUserDataStreamListener {

    private final BinanceFuturesClient binanceClient;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 애플리케이션이 시작된 후 자동으로 웹소켓 리스닝을 시작합니다.
     */
    @PostConstruct
    public void startListening() {
        new Thread(() -> {
            try {
                log.info("바이낸스 사용자 데이터 스트림 리스너를 시작합니다...");
                // 1. 웹소켓 연결을 위한 Listen Key를 발급받습니다.
                String listenKey = binanceClient.getListenKey();
                log.info("Listen Key를 발급받았습니다: {}", listenKey);

                // 2. Listen Key를 사용하여 웹소켓에 연결하고, 메시지 핸들러를 등록합니다.
                binanceClient.connectToUserDataStream(listenKey, this::handleWebSocketMessage);

            } catch (Exception e) {
                log.error("바이낸스 사용자 데이터 스트림 연결에 실패했습니다.", e);
                // TODO: 연결 실패 시 일정 시간 후 재시도하는 로직 추가
            }
        }).start();
    }

    /**
     * 웹소켓으로부터 메시지를 수신할 때마다 호출되는 콜백 메소드입니다.
     * @param message 바이낸스로부터 수신된 JSON 형식의 원시 메시지
     */
    private void handleWebSocketMessage(String message) {
        try {
            log.debug("웹소켓 메시지 수신: {}", message);
            JsonNode rootNode = objectMapper.readTree(message);

            // 이벤트 타입이 'ORDER_TRADE_UPDATE'인지 확인합니다.
            if (rootNode.has("e") && "ORDER_TRADE_UPDATE".equals(rootNode.get("e").asText())) {
                JsonNode orderNode = rootNode.get("o");
                String orderStatus = orderNode.get("X").asText();

                // 주문 상태가 'FILLED'(완전 체결)일 때만 처리합니다.
                if ("FILLED".equals(orderStatus)) {
                    String symbol = orderNode.get("s").asText();
                    String sideStr = orderNode.get("S").asText();
                    BigDecimal filledQty = new BigDecimal(orderNode.get("z").asText());
                    BigDecimal avgPrice = new BigDecimal(orderNode.get("ap").asText());

                    // 체결 수량이 0보다 클 때만 (실제 체결이 있을 때만) 이벤트를 발행합니다.
                    if (filledQty.compareTo(BigDecimal.ZERO) > 0) {
                        Position.PositionSide side = "BUY".equalsIgnoreCase(sideStr) ? Position.PositionSide.LONG : Position.PositionSide.SHORT;
                        OrderFilledEvent filledEvent = new OrderFilledEvent(symbol, side, filledQty, avgPrice);

                        log.info("웹소켓을 통해 주문 체결(FILLED) 이벤트를 감지했습니다. 시스템 이벤트를 발행합니다: {}", filledEvent);
                        eventPublisher.publishEvent(filledEvent);
                    }
                }
            }
        } catch (Exception e) {
            log.error("웹소켓 메시지 처리 중 오류 발생", e);
        }
    }
}