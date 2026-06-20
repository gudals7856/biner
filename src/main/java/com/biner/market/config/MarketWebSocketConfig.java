package com.biner.market.config;

import com.biner.market.websocket.BinanceKlineWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.web.socket.client.WebSocketConnectionManager;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

@Configuration
public class MarketWebSocketConfig {

    private static final Logger log = LoggerFactory.getLogger(MarketWebSocketConfig.class);
    
    // 현물(Spot)이 성공한 것을 확인했으므로, 다시 선물(Futures)의 단일 스트림 URL로 복구합니다.
    // 이번에는 1분봉(1m)으로 선물 데이터를 받아보겠습니다.
    private static final String BINANCE_FUTURES_WS_URL = "wss://fstream.binance.com/ws/btcusdt@kline_1m";

    private final BinanceKlineWebSocketHandler klineWebSocketHandler;

    public MarketWebSocketConfig(BinanceKlineWebSocketHandler klineWebSocketHandler) {
        this.klineWebSocketHandler = klineWebSocketHandler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void connectToBinanceWebSocket() {
        try {
            log.info("Attempting to connect to Binance WebSocket at {}...", BINANCE_FUTURES_WS_URL);
            
            StandardWebSocketClient client = new StandardWebSocketClient();
            
            WebSocketConnectionManager connectionManager = new WebSocketConnectionManager(
                    client, 
                    klineWebSocketHandler, 
                    BINANCE_FUTURES_WS_URL
            );
            
            connectionManager.setAutoStartup(true);
            connectionManager.start();
            
            log.info("WebSocket connection manager started.");
        } catch (Exception e) {
            log.error("Failed to start WebSocket connection manager", e);
        }
    }
}
