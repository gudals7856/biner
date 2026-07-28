package com.biner.order.service;

import com.biner.order.domain.Position;

import java.util.Optional;

/**
 * 심볼에 해당하는 현재 열려있는 포지션 정보를 제공하는 역할을 정의하는 인터페이스입니다.
 * 실제 운영 환경과 백테스트 환경에서 각각 다른 구현체를 가집니다.
 */
public interface PositionProvider {

    /**
     * 지정된 심볼에 대해 현재 열려있는(OPEN) 포지션을 찾습니다.
     *
     * @param symbol 포지션을 찾을 심볼 (예: "BTCUSDT" 또는 "BTCUSDT_BACKTEST")
     * @return 존재하면 Optional<Position>, 없으면 Optional.empty()
     */
    Optional<Position> findOpenPosition(String symbol);
}