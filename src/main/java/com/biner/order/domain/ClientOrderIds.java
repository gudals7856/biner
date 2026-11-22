package com.biner.order.domain;

import java.util.UUID;

/**
 * 거래소에 전송하는 clientOrderId를 생성합니다.
 * 바이낸스 규칙: ^[.A-Z:/a-z0-9_-]{1,36}$
 * 접두사로 우리 시스템이 낸 주문과 그 용도를 식별합니다.
 */
public final class ClientOrderIds {

  /** 신규 진입 / 물타기 주문 */
  public static final String ENTRY_PREFIX = "bnE-";
  /** 전략적 청산 주문 */
  public static final String EXIT_PREFIX = "bnX-";
  /** 손절 보호 주문 */
  public static final String STOP_LOSS_PREFIX = "bnSL-";
  /** 익절 보호 주문 */
  public static final String TAKE_PROFIT_PREFIX = "bnTP-";

  private static final int MAX_LENGTH = 36;

  private ClientOrderIds() {
  }

  public static String newId(String prefix) {
    String random = UUID.randomUUID().toString().replace("-", "");
    return (prefix + random).substring(0, MAX_LENGTH);
  }

  public static boolean isEntry(String clientOrderId) {
    return clientOrderId != null && clientOrderId.startsWith(ENTRY_PREFIX);
  }
}
