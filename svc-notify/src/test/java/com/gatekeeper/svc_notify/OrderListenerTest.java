package com.gatekeeper.svc_notify;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderListenerTest {
    private final OrderListener listener = new OrderListener();

    @Test
    void validEventIsHandled() {
        assertDoesNotThrow(() -> listener.onOrderPlaced(new OrderListener.OrderPlaced("o1", "u", "sku1", 1, "rid")));
    }

    @Test
    void eventWithoutUserThrowsSoItReachesTheDlq() {
        assertThrows(IllegalArgumentException.class,
                () -> listener.onOrderPlaced(new OrderListener.OrderPlaced("o1", null, "sku1", 1, "rid")));
    }
}
