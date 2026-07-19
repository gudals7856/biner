package com.biner.account.service;

import com.biner.order.client.BinanceFuturesClient;
import com.biner.order.client.dto.AccountBalanceResponse;
import com.biner.order.client.dto.AssetBalance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 사용자의 계좌 정보, 특히 자산 잔고와 관련된 조회를 담당하는 서비스입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final BinanceFuturesClient binanceClient;

    /**
     * 특정 자산(Asset)의 사용 가능한 잔고를 조회합니다.
     * @param assetSymbol 조회할 자산의 심볼 (예: "USDT")
     * @return 사용 가능한 잔고. 자산이 없거나 오류 발생 시 BigDecimal.ZERO를 반환합니다.
     */
    public BigDecimal getAvailableBalance(String assetSymbol) {
        try {
            AccountBalanceResponse response = binanceClient.getAccountBalance();
            if (response == null || response.assets() == null) {
                log.warn("계좌 잔고 정보를 가져올 수 없습니다. (응답 없음)");
                return BigDecimal.ZERO;
            }

            return response.assets().stream()
                    .filter(asset -> assetSymbol.equals(asset.asset()))
                    .findFirst()
                    .map(AssetBalance::availableBalance)
                    .orElseGet(() -> {
                        log.warn("{} 자산을 찾을 수 없습니다.", assetSymbol);
                        return BigDecimal.ZERO;
                    });

        } catch (Exception e) {
            log.error("{} 잔고 조회 중 오류가 발생했습니다.", assetSymbol, e);
            return BigDecimal.ZERO;
        }
    }
}
