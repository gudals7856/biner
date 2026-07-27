package com.biner.order.client;

import com.biner.order.client.dto.AccountBalanceResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * 바이낸스 선물 거래소 API와 직접 통신하는 클라이언트입니다.
 */
@Slf4j
@Component
public class BinanceFuturesClient {

    private final WebClient webClient;

    @Value("${binance.api.key:dummy}")
    private String apiKey;

    @Value("${binance.api.secret:dummy}")
    private String apiSecret;

    @Value("${binance.api.url:https://testnet.binancefuture.com}")
    private String baseUrl;

    public BinanceFuturesClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    /**
     * 특정 심볼의 레버리지 배율을 설정합니다.
     * @param symbol 심볼 (예: "BTCUSDT")
     * @param leverage 레버리지 배율 (1 ~ 125)
     * @return API 응답 문자열
     */
    @RateLimiter(name = "binanceApi")
    @CircuitBreaker(name = "binanceApi")
    @Retry(name = "binanceApi")
    public String setLeverage(String symbol, int leverage) {
        long timestamp = Instant.now().toEpochMilli();
        String queryString = String.format("symbol=%s&leverage=%d&timestamp=%d", symbol, leverage, timestamp);
        String signature = generateSignature(queryString, apiSecret);
        queryString += "&signature=" + signature;

        log.info("레버리지 설정 요청: {} {}x", symbol, leverage);

        return webClient.post()
                .uri(baseUrl + "/fapi/v1/leverage?" + queryString)
                .header("X-MBX-APIKEY", apiKey)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    /**
     * 바이낸스 계좌의 자산 정보를 조회합니다.
     * @return 계좌 정보 응답 객체
     */
    @RateLimiter(name = "binanceApi")
    @CircuitBreaker(name = "binanceApi")
    @Retry(name = "binanceApi")
    public AccountBalanceResponse getAccountBalance() {
        long timestamp = Instant.now().toEpochMilli();
        String queryString = "timestamp=" + timestamp;
        String signature = generateSignature(queryString, apiSecret);
        queryString += "&signature=" + signature;

        log.debug("바이낸스 계좌 정보 조회 요청...");

        return webClient.get()
                .uri(baseUrl + "/fapi/v2/account?" + queryString)
                .header("X-MBX-APIKEY", apiKey)
                .retrieve()
                .bodyToMono(AccountBalanceResponse.class)
                .block();
    }

    @RateLimiter(name = "binanceApi")
    @CircuitBreaker(name = "binanceApi", fallbackMethod = "orderFallback")
    @Retry(name = "binanceApi")
    public String placeOrder(String symbol, String side, String type, BigDecimal quantity, BigDecimal price) {
        long timestamp = Instant.now().toEpochMilli();
        
        String queryString = String.format("symbol=%s&side=%s&type=%s&quantity=%s&timestamp=%d", 
                symbol, side, type, quantity.toPlainString(), timestamp);
                
        if (price != null) {
            if ("STOP_MARKET".equals(type) || "TAKE_PROFIT_MARKET".equals(type)) {
                queryString += "&stopPrice=" + price.toPlainString();
            } else {
                queryString += "&price=" + price.toPlainString();
            }
        }

        String signature = generateSignature(queryString, apiSecret);
        queryString += "&signature=" + signature;

        log.info("바이낸스로 주문 전송: URL={}?{}", baseUrl + "/fapi/v1/order", queryString);

        return webClient.post()
                .uri(baseUrl + "/fapi/v1/order?" + queryString)
                .header("X-MBX-APIKEY", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    @RateLimiter(name = "binanceApi")
    @CircuitBreaker(name = "binanceApi", fallbackMethod = "cancelFallback")
    @Retry(name = "binanceApi")
    public String cancelAllOpenOrders(String symbol) {
        long timestamp = Instant.now().toEpochMilli();
        String queryString = String.format("symbol=%s&timestamp=%d", symbol, timestamp);
        String signature = generateSignature(queryString, apiSecret);
        queryString += "&signature=" + signature;

        log.info("심볼에 대한 모든 대기 주문 취소: {}", symbol);

        return webClient.delete()
                .uri(baseUrl + "/fapi/v1/allOpenOrders?" + queryString)
                .header("X-MBX-APIKEY", apiKey)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    /**
     * 사용자 데이터 스트림 웹소켓에 연결하기 위한 Listen Key를 발급받습니다.
     * @return Listen Key 문자열
     */
    @RateLimiter(name = "binanceApi")
    @CircuitBreaker(name = "binanceApi")
    public String getListenKey() {
        long timestamp = Instant.now().toEpochMilli();
        String queryString = "timestamp=" + timestamp;
        String signature = generateSignature(queryString, apiSecret);
        queryString += "&signature=" + signature;

        log.info("Listen Key 발급 요청...");

        String response = webClient.post()
                .uri(baseUrl + "/fapi/v1/listenKey?" + queryString)
                .header("X-MBX-APIKEY", apiKey)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        
        try {
            return new ObjectMapper().readTree(response).get("listenKey").asText();
        } catch (Exception e) {
            throw new RuntimeException("Listen Key 파싱 실패", e);
        }
    }

    /**
     * Listen Key를 사용하여 사용자 데이터 스트림 웹소켓에 연결합니다.
     * @param listenKey 발급받은 Listen Key
     * @param messageConsumer 메시지를 처리할 콜백 함수
     */
    public void connectToUserDataStream(String listenKey, Consumer<String> messageConsumer) {
        try {
            String webSocketUrl = baseUrl.replace("https", "wss").replace("http", "ws") + "/ws/" + listenKey;
            log.info("사용자 데이터 웹소켓에 연결합니다: {}", webSocketUrl);

            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.WebSocket.Builder builder = client.newWebSocketBuilder();
            builder.buildAsync(java.net.URI.create(webSocketUrl), new java.net.http.WebSocket.Listener() {
                @Override
                public void onOpen(java.net.http.WebSocket webSocket) {
                    log.info("웹소켓 연결 성공!");
                    webSocket.request(1);
                }

                @Override
                public java.util.concurrent.CompletionStage<?> onText(java.net.http.WebSocket webSocket, CharSequence data, boolean last) {
                    messageConsumer.accept(data.toString());
                    webSocket.request(1);
                    return null;
                }

                @Override
                public java.util.concurrent.CompletionStage<?> onClose(java.net.http.WebSocket webSocket, int statusCode, String reason) {
                    log.warn("웹소켓 연결이 종료되었습니다. 상태코드: {}, 원인: {}", statusCode, reason);
                    // TODO: 연결 종료 시 재연결 로직 호출
                    return null;
                }

                @Override
                public void onError(java.net.http.WebSocket webSocket, Throwable error) {
                    log.error("웹소켓 오류 발생", error);
                }
            }).join();

        } catch (Exception e) {
            throw new RuntimeException("웹소켓 연결 중 오류 발생", e);
        }
    }

    public String orderFallback(String symbol, String side, String type, BigDecimal quantity, BigDecimal price, Throwable t) {
        log.error("주문 실행 Fallback 발동. 심볼={}, 방향={}. 원인: {}", symbol, side, t.getMessage());
        return "FALLBACK_TRIGGERED";
    }

    public String cancelFallback(String symbol, Throwable t) {
        log.error("주문 취소 Fallback 발동. 심볼={}. 원인: {}", symbol, t.getMessage());
        return "FALLBACK_TRIGGERED";
    }

    private String generateSignature(String data, String key) {
        try {
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256");
            sha256_HMAC.init(secret_key);
            byte[] bytes = sha256_HMAC.doFinal(data.getBytes("UTF-8"));
            
            StringBuilder hash = new StringBuilder();
            for (byte b : bytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hash.append('0');
                hash.append(hex);
            }
            return hash.toString();
        } catch (Exception e) {
            throw new RuntimeException("메시지 서명 불가", e);
        }
    }
}