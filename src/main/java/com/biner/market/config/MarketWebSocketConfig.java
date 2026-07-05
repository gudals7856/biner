package com.biner.market.config;

import com.biner.market.websocket.BinanceKlineWebSocketHandler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.web.socket.client.WebSocketConnectionManager;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

/**
 * 바이낸스 실시간 시세(Kline/Candlestick) 데이터 수신을 위한 WebSocket 연결을 설정하는 클래스입니다.
 * 이 설정은 'backtest' 프로필이 활성화되지 않았을 때만 동작합니다.
 */
@Configuration
@Profile("!backtest") // 'backtest' 프로필이 아닐 때만 이 설정 파일을 활성화합니다.
@RequiredArgsConstructor
public class MarketWebSocketConfig {

    private static final Logger log = LoggerFactory.getLogger(MarketWebSocketConfig.class);

    // application.properties 또는 실행 인자(--binance.kline.interval=5m)로 주입받습니다. 기본값은 "1m"입니다.
    @Value("${binance.kline.interval:1m}")
    private String klineInterval;

    // WebSocket 기본 URL 템플릿. %s 부분은 'btcusdt@kline_1m'과 같은 스트림 이름으로 대체됩니다.
    private static final String BINANCE_WS_URL_TEMPLATE = "wss://stream.binance.com:9443/ws/%s";

    // WebSocket 서버로부터 메시지를 수신하고 처리할 핸들러
    private final BinanceKlineWebSocketHandler klineWebSocketHandler;

    /**
     * 의존성 주입이 완료된 후, klineWebSocketHandler에 설정된 시간봉(interval) 값을 전달합니다.
     */
    @PostConstruct
    public void init() {
        klineWebSocketHandler.setKlineInterval(klineInterval);
    }

    /**
     * Spring Boot 애플리케이션이 준비 완료되면(@EventListener(ApplicationReadyEvent.class))
     * 바이낸스 WebSocket 서버에 연결을 시작합니다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void connectToBinanceWebSocket() {
        try {
            // 설정된 시간봉 값으로 전체 WebSocket URL을 동적으로 생성합니다.
            // 현재는 "btcusdt" 심볼을 사용합니다.
            String streamName = String.format("btcusdt@kline_%s", klineInterval);
            String webSocketUrl = String.format(BINANCE_WS_URL_TEMPLATE, streamName);

            log.info("실시간 모드: 바이낸스 WebSocket 연결을 시작합니다. URL: {}", webSocketUrl);

            StandardWebSocketClient client = new StandardWebSocketClient();

            // WebSocket 연결을 관리하는 매니저를 생성합니다.
            // 이 매니저는 연결이 끊어졌을 때 자동으로 재연결을 시도하는 등 연결 생명주기를 관리해줍니다.
            WebSocketConnectionManager connectionManager = new WebSocketConnectionManager(
                    client,
                    klineWebSocketHandler,
                    webSocketUrl
            );

            // 애플리케이션 시작 시 자동으로 연결을 시작하도록 설정합니다.
            connectionManager.setAutoStartup(true);
            connectionManager.start();

            log.info("WebSocket 연결 관리자가 성공적으로 시작되었습니다. 구독 대상: {}", streamName);
        } catch (Exception e) {
            log.error("WebSocket 연결 관리자 시작 실패", e);
        }
    }
}
