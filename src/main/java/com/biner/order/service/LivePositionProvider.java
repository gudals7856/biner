package com.biner.order.service;

import com.biner.order.domain.Position;
import com.biner.order.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 실제 운영 환경에서 사용되는 PositionProvider 구현체입니다.
 * 데이터베이스에서 직접 포지션 정보를 조회합니다.
 */
@Service
@Profile("!backtest") // 'backtest' 프로필이 아닐 때만 활성화됩니다.
@RequiredArgsConstructor
public class LivePositionProvider implements PositionProvider {

    private final PositionRepository positionRepository;

    @Override
    public Optional<Position> findOpenPosition(String symbol) {
        return positionRepository.findBySymbolAndStatus(symbol, Position.PositionStatus.OPEN);
    }
}