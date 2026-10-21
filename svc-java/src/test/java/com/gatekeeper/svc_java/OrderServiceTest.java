package com.gatekeeper.svc_java;

import com.gatekeeper.svc_java.client.InventoryClient;
import com.gatekeeper.svc_java.client.InventoryClient.ReserveResponse;
import com.gatekeeper.svc_java.event.OrderPlaced;
import com.gatekeeper.svc_java.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OrderServiceTest {
    private final InventoryClient inventory = mock(InventoryClient.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final OrderService service = new OrderService(inventory, rabbit);

    private HttpStatus statusOf(Runnable r) {
        return (HttpStatus) assertThrows(ResponseStatusException.class, r::run).getStatusCode();
    }

    @Test
    void reservesThenPublishesEvent() {
        when(inventory.reserve(anyString(), any(), anyString())).thenReturn(new ReserveResponse(true, 4));

        OrderPlaced order = service.place("u", "sku1", 1, "rid");

        assertEquals(order, service.find(order.orderId()));
        verify(rabbit).convertAndSend(OrderPlaced.EXCHANGE, OrderPlaced.ROUTING_KEY, order);
    }

    @Test
    void outOfStockIs409AndPublishesNothing() {
        when(inventory.reserve(anyString(), any(), anyString())).thenReturn(new ReserveResponse(false, 0));

        assertEquals(HttpStatus.CONFLICT, statusOf(() -> service.place("u", "sku1", 1, "rid")));
        verifyNoInteractions(rabbit);
    }

    @Test
    void inventoryFailureIs503AndPublishesNothing() {
        when(inventory.reserve(anyString(), any(), anyString())).thenThrow(new RuntimeException("down"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, statusOf(() -> service.place("u", "sku1", 1, "rid")));
        verifyNoInteractions(rabbit);
    }

    @Test
    void brokerFailureDoesNotFailTheOrder() {
        when(inventory.reserve(anyString(), any(), anyString())).thenReturn(new ReserveResponse(true, 4));
        doThrow(new RuntimeException("broker down")).when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));

        OrderPlaced order = service.place("u", "sku1", 1, "rid");

        assertNotNull(service.find(order.orderId()));
    }
}
