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

/**
 * 바이낸스 선물 거래소 API와 직접 통신하는 클라이언트입니다.
 * 주문 생성, 주문 취소 등의 기능을 수행하며, API 호출 시 안정성을 위해
 * Resilience4j 라이브러리를 사용한 서킷 브레이커, 재시도, 호출 수 제한 패턴이 적용되어 있습니다.
 */
@Slf4j
@Component
public class BinanceFuturesClient {

    // 비동기 HTTP 통신을 위한 리액티브 웹 클라이언트
    private final WebClient webClient;

    // application.yml 또는 환경변수에서 주입받는 바이낸스 API 키
    @Value("${binance.api.key:dummy}")
    private String apiKey;

    // application.yml 또는 환경변수에서 주입받는 바이낸스 API 시크릿 키
    @Value("${binance.api.secret:dummy}")
    private String apiSecret;

    // application.yml 또는 환경변수에서 주입받는 바이낸스 API 기본 URL (테스트넷 또는 메인넷)
    @Value("${binance.api.url:https://testnet.binancefuture.com}")
    private String baseUrl;

    public BinanceFuturesClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    /**
     * 바이낸스에 신규 주문을 전송합니다.
     * Resilience4j 애노테이션 설명:
     * - @RateLimiter: "binanceApi" 설정에 따라 API 호출 수를 제어합니다. (DDOS 방지)
     * - @CircuitBreaker: "binanceApi" 설정에 따라 API 호출 실패가 일정 횟수 누적되면 일시적으로 API 호출을 차단(OPEN)하고,
     *                   지정된 시간이 지나면 다시 시도(HALF-OPEN)합니다. 실패 시 orderFallback 메서드가 대신 실행됩니다.
     * - @Retry: "binanceApi" 설정에 따라 API 호출 실패 시 지정된 횟수만큼 재시도합니다.
     *
     * @param symbol   거래할 코인 심볼 (예: "BTCUSDT")
     * @param side     주문 방향 ("BUY" 또는 "SELL")
     * @param type     주문 유형 ("MARKET", "LIMIT", "STOP_MARKET" 등)
     * @param quantity 주문 수량
     * @param price    주문 가격 (시장가 주문 시에는 null)
     * @return API 호출 결과 문자열
     */
    @RateLimiter(name = "binanceApi")
    @CircuitBreaker(name = "binanceApi", fallbackMethod = "orderFallback")
    @Retry(name = "binanceApi")
    public String placeOrder(String symbol, String side, String type, BigDecimal quantity, BigDecimal price) {
        // 바이낸스 API는 타임스탬프를 요구합니다.
        long timestamp = Instant.now().toEpochMilli();
        
        // API 요청에 필요한 파라미터들을 쿼리 스트링 형태로 만듭니다.
        String queryString = String.format("symbol=%s&side=%s&type=%s&quantity=%s&timestamp=%d", 
                symbol, side, type, quantity.toPlainString(), timestamp);
                
        // 가격 정보가 있는 경우 (LIMIT, STOP_MARKET 등) 쿼리 스트링에 추가합니다.
        if (price != null) {
            // 손절/익절 주문의 경우 'stopPrice' 파라미터를 사용해야 합니다.
            if ("STOP_MARKET".equals(type) || "TAKE_PROFIT_MARKET".equals(type)) {
                queryString += "&stopPrice=" + price.toPlainString();
            } else {
                queryString += "&price=" + price.toPlainString();
            }
        }

        // API 시크릿 키를 사용하여 요청 파라미터에 대한 서명을 생성합니다.
        String signature = generateSignature(queryString, apiSecret);
        queryString += "&signature=" + signature;

        log.info("바이낸스로 주문 전송: URL={}?{}", baseUrl + "/fapi/v1/order", queryString);

        // WebClient를 사용하여 POST 요청을 보냅니다.
        // block()을 사용하여 동기적으로 결과를 기다립니다.
        // 예외 발생 시 Resilience4j가 이를 감지하고 fallback 메서드를 호출할 수 있도록, 예외를 밖으로 던져야 합니다.
        return webClient.post()
                .uri(baseUrl + "/fapi/v1/order?" + queryString)
                .header("X-MBX-APIKEY", apiKey) // API 키를 헤더에 추가
                .contentType(MediaType.APPLICATION_JSON)
                .retrieve() // 응답 수신
                .bodyToMono(String.class) // 응답 본문을 String으로 변환
                .block(); // 동기 처리
    }

    /**
     * 특정 심볼에 대해 열려있는 모든 대기 주문(손절/익절 등)을 취소합니다.
     *
     * @param symbol 주문을 취소할 코인 심볼
     * @return API 호출 결과 문자열
     */
    @RateLimiter(name = "binanceApi")
    @CircuitBreaker(name = "binanceApi", fallbackMethod = "cancelFallback")
    @Retry(name = "binanceApi")
    public String cancelAllOpenOrders(String symbol) {
        long timestamp = Instant.now().toEpochMilli();
        String queryString = String.format("symbol=%s&timestamp=%d", symbol, timestamp);
        String signature = generateSignature(queryString, apiSecret);
        queryString += "&signature=" + signature;

        log.info("심볼에 대한 모든 대기 주문 취소: {}", symbol);

        // WebClient를 사용하여 DELETE 요청을 보냅니다.
        return webClient.delete()
                .uri(baseUrl + "/fapi/v1/allOpenOrders?" + queryString)
                .header("X-MBX-APIKEY", apiKey)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    /**
     * placeOrder 메서드에서 API 호출 실패 시 실행되는 Fallback 메서드입니다.
     * Resilience4j의 서킷 브레이커가 OPEN 상태일 때 호출됩니다.
     * 
     * @param symbol 원본 메서드의 파라미터
     * @param side 원본 메서드의 파라미터
     * @param type 원본 메서드의 파라미터
     * @param quantity 원본 메서드의 파라미터
     * @param price 원본 메서드의 파라미터
     * @param t 발생한 예외 객체
     * @return 실패를 나타내는 고정된 문자열 "FALLBACK_TRIGGERED"
     */
    public String orderFallback(String symbol, String side, String type, BigDecimal quantity, BigDecimal price, Throwable t) {
        log.error("주문 실행 Fallback 발동. 심볼={}, 방향={}. 원인: {}", symbol, side, t.getMessage());
        return "FALLBACK_TRIGGERED";
    }

    /**
     * cancelAllOpenOrders 메서드에서 API 호출 실패 시 실행되는 Fallback 메서드입니다.
     * 
     * @param symbol 원본 메서드의 파라미터
     * @param t 발생한 예외 객체
     * @return 실패를 나타내는 고정된 문자열 "FALLBACK_TRIGGERED"
     */
    public String cancelFallback(String symbol, Throwable t) {
        log.error("주문 취소 Fallback 발동. 심볼={}. 원인: {}", symbol, t.getMessage());
        return "FALLBACK_TRIGGERED";
    }

    /**
     * 바이낸스 API 요청을 위한 HMAC-SHA256 서명을 생성합니다.
     *
     * @param data 서명할 데이터 (쿼리 스트링)
     * @param key  API 시크릿 키
     * @return 생성된 서명 문자열 (16진수)
     */
    private String generateSignature(String data, String key) {
        try {
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256");
            sha256_HMAC.init(secret_key);
            byte[] bytes = sha256_HMAC.doFinal(data.getBytes("UTF-8"));
            
            // 바이트 배열을 16진수 문자열로 변환합니다.
            StringBuilder hash = new StringBuilder();
            for (byte b : bytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hash.append('0');
                hash.append(hex);
            }
            return hash.toString();
        } catch (Exception e) {
            // 서명 생성 실패 시 런타임 예외를 발생시켜 시스템을 중단시킵니다. (치명적 오류)
            throw new RuntimeException("메시지 서명 불가", e);
        }
    }
}
