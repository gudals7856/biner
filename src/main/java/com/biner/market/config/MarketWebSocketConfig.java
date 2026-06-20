package com.biner.market.config;

import com.biner.market.websocket.BinanceKlineWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.web.socket.client.WebSocketConnectionManager;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

/**
 * 바이낸스 실시간 시세(Kline/Candlestick) 데이터 수신을 위한 WebSocket 연결을 설정하는 클래스입니다.
 * Spring Boot 애플리케이션이 완전히 시작된 후에 WebSocket 연결을 시도합니다.
 */
@Configuration
public class MarketWebSocketConfig {

    private static final Logger log = LoggerFactory.getLogger(MarketWebSocketConfig.class);
    
    // 바이낸스 WebSocket 서버 주소.
    // 여러 코인을 구독하려면 /ws/btcusdt@kline_1m/ethusdt@kline_1m/... 와 같이 스트림 이름을 추가합니다.
    // 현재는 테스트를 위해 BTCUSDT 현물(spot) 1분봉 데이터를 구독합니다.
    private static final String BINANCE_SPOT_WS_URL = "wss://stream.binance.com:9443/ws/btcusdt@kline_1m";

    // WebSocket 서버로부터 메시지를 수신하고 처리할 핸들러
    private final BinanceKlineWebSocketHandler klineWebSocketHandler;

    public MarketWebSocketConfig(BinanceKlineWebSocketHandler klineWebSocketHandler) {
        this.klineWebSocketHandler = klineWebSocketHandler;
    }

    /**
     * Spring Boot 애플리케이션이 준비 완료되면(@EventListener(ApplicationReadyEvent.class))
     * 바이낸스 WebSocket 서버에 연결을 시작합니다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void connectToBinanceWebSocket() {
        try {
            log.info("바이낸스 WebSocket 연결 시도... URL: {}", BINANCE_SPOT_WS_URL);
            
            // 표준 WebSocket 클라이언트 구현체를 생성합니다.
            StandardWebSocketClient client = new StandardWebSocketClient();
            
            // WebSocket 연결을 관리하는 매니저를 생성합니다.
            // 이 매니저는 연결이 끊어졌을 때 자동으로 재연결을 시도하는 등 연결 생명주기를 관리해줍니다.
            WebSocketConnectionManager connectionManager = new WebSocketConnectionManager(
                    client,                 // 사용할 WebSocket 클라이언트
                    klineWebSocketHandler,  // 메시지를 처리할 핸들러
                    BINANCE_SPOT_WS_URL     // 접속할 서버 주소
            );
            
            // 애플리케이션 시작 시 자동으로 연결을 시작하도록 설정합니다.
            connectionManager.setAutoStartup(true);
            connectionManager.start(); // 연결 관리자 시작
            
            log.info("WebSocket 연결 관리자가 성공적으로 시작되었습니다.");
        } catch (Exception e) {
            log.error("WebSocket 연결 관리자 시작 실패", e);
        }
    }
}
