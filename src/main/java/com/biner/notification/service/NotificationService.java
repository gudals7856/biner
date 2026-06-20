package com.biner.notification.service;

import com.biner.order.domain.Position;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Service
public class NotificationService {

    private final WebClient webClient;

    @Value("${telegram.bot.token:dummy}")
    private String botToken;

    @Value("${telegram.chat.id:dummy}")
    private String chatId;

    public NotificationService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    @Async("notificationTaskExecutor")
    public void sendTradeNotification(Position position, String message) {
        String text = String.format("🚀 [Trade Alert] %s\nSymbol: %s\nSide: %s\nEntry: %s\nAmount: %s", 
                message, position.getSymbol(), position.getSide(), position.getEntryPrice(), position.getAmount());
        sendMessage(text);
    }
    
    @Async("notificationTaskExecutor")
    public void sendErrorNotification(String errorMessage, Throwable ex) {
        String text = String.format("🚨 [CRITICAL ERROR] 🚨\n%s\nDetails: %s", 
                errorMessage, ex.getMessage());
        sendMessage(text);
    }

    private void sendMessage(String text) {
        if ("dummy".equals(botToken)) {
            log.info("Telegram notification skipped (dummy token): \n{}", text);
            return;
        }

        try {
            String url = String.format("https://api.telegram.org/bot%s/sendMessage?chat_id=%s&text=%s", 
                    botToken, chatId, text);
            
            webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
                    
            log.info("Telegram notification sent successfully.");
        } catch (Exception e) {
            log.error("Failed to send Telegram notification", e);
        }
    }
}
