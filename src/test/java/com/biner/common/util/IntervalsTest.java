package com.biner.common.util;

import com.biner.market.event.CandleClosedEvent;
import com.biner.strategy.util.Ta4jConverter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntervalsTest {

  @Test
  void 캔들_간격_문자열을_Duration으로_변환한다() {
    assertThat(Intervals.toDuration("1m")).isEqualTo(Duration.ofMinutes(1));
    assertThat(Intervals.toDuration("5m")).isEqualTo(Duration.ofMinutes(5));
    assertThat(Intervals.toDuration("4h")).isEqualTo(Duration.ofHours(4));
    assertThat(Intervals.toDuration("1d")).isEqualTo(Duration.ofDays(1));
  }

  @Test
  void 잘못된_간격은_예외() {
    assertThatThrownBy(() -> Intervals.toDuration("5x")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Intervals.toDuration("m")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void Ta4j_변환시_이벤트의_간격을_사용한다() {
    BigDecimal p = BigDecimal.TEN;
    CandleClosedEvent event = new CandleClosedEvent("BTCUSDT", Instant.parse("2026-01-01T00:00:00Z"),
      Instant.parse("2026-01-01T00:00:59.999Z"), p, p, p, p, p, "1m");
    assertThat(Ta4jConverter.convert(event).getTimePeriod()).isEqualTo(Duration.ofMinutes(1));
  }
}
