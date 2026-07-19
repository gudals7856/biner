package com.biner.order.client;

import com.biner.order.client.dto.AccountBalanceResponse;
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
