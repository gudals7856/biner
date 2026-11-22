package com.biner.common.util;

import java.time.Duration;

/**
 * 바이낸스 캔들 간격 문자열("1m", "5m", "1h", "1d" 등)을 Duration으로 변환하는 유틸리티입니다.
 * 시간봉 값을 코드에 하드코딩하지 않고 한 곳에서 해석하기 위해 사용합니다.
 */
public final class Intervals {

  private Intervals() {
  }

  public static Duration toDuration(String interval) {
    if (interval == null || interval.length() < 2) {
      throw new IllegalArgumentException("잘못된 캔들 간격입니다: " + interval);
    }
    long amount;
    try {
      amount = Long.parseLong(interval.substring(0, interval.length() - 1));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("잘못된 캔들 간격입니다: " + interval, e);
    }
    char unit = interval.charAt(interval.length() - 1);
    return switch (unit) {
      case 'm' -> Duration.ofMinutes(amount);
      case 'h' -> Duration.ofHours(amount);
      case 'd' -> Duration.ofDays(amount);
      case 'w' -> Duration.ofDays(amount * 7);
      default -> throw new IllegalArgumentException("지원하지 않는 캔들 간격 단위입니다: " + interval);
    };
  }
}
