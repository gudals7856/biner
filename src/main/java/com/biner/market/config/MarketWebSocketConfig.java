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
    
    // [수정] 선물(fstream) 서버 대신, 접속이 확실하게 보장되는 현물(Spot) 서버 URL로 변경합니다.
    // 빠른 테스트를 위해 1분봉으로 설정합니다.
    private static final String BINANCE_SPOT_WS_URL = "wss://stream.binance.com:9443/ws/btcusdt@kline_1m";

    private final BinanceKlineWebSocketHandler klineWebSocketHandler;

    public MarketWebSocketConfig(BinanceKlineWebSocketHandler klineWebSocketHandler) {
        this.klineWebSocketHandler = klineWebSocketHandler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void connectToBinanceWebSocket() {
        try {
            log.info("Attempting to connect to Binance WebSocket at {}...", BINANCE_SPOT_WS_URL);
            
            StandardWebSocketClient client = new StandardWebSocketClient();
            
            WebSocketConnectionManager connectionManager = new WebSocketConnectionManager(
                    client, 
                    klineWebSocketHandler, 
                    BINANCE_SPOT_WS_URL
            );
            
            connectionManager.setAutoStartup(true);
            connectionManager.start();
            
            log.info("WebSocket connection manager started.");
        } catch (Exception e) {
            log.error("Failed to start WebSocket connection manager", e);
        }
    }
}
