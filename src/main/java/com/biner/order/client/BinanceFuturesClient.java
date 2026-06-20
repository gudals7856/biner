package com.biner.order.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.time.Instant;

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

        log.info("Sending order to Binance: URL={}?{}", baseUrl + "/fapi/v1/order", queryString);

        // WebClient 요청 시 예외를 던지지 않고 밖으로 내보내야 Resilience4j가 정상적으로 에러를 잡아서 Fallback을 수행합니다.
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

        log.info("Canceling all open orders for symbol: {}", symbol);

        return webClient.delete()
                .uri(baseUrl + "/fapi/v1/allOpenOrders?" + queryString)
                .header("X-MBX-APIKEY", apiKey)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    // Spring AOP proxy 기반의 Resilience4j fallback 메서드는 
    // 반드시 원본 메서드와 "접근 제어자", "파라미터(마지막에 Throwable 추가)", "반환 타입"이 완벽하게 일치해야 합니다.
    // 기존에 private으로 되어 있어서 Resilience4j(AOP)가 외부에서 이 메서드를 찾지 못해 미사용(Unused) 처리되었던 것입니다.
    public String orderFallback(String symbol, String side, String type, BigDecimal quantity, BigDecimal price, Throwable t) {
        log.error("Order Execution Fallback Triggered. Symbol={}, Side={}. Reason: {}", symbol, side, t.getMessage());
        return "FALLBACK_TRIGGERED";
    }

    public String cancelFallback(String symbol, Throwable t) {
        log.error("Cancel Orders Fallback Triggered. Symbol={}. Reason: {}", symbol, t.getMessage());
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
            throw new RuntimeException("Unable to sign message", e);
        }
    }
}
