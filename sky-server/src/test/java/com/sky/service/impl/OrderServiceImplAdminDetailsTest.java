package com.sky.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.vo.OrderVO;

class OrderServiceImplAdminDetailsTest {
    private OrderMapper orderMapper;
    private OrderDetailMapper detailMapper;
    private OrderServiceImpl service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        detailMapper = mock(OrderDetailMapper.class);
        service = new OrderServiceImpl();
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "orderDetailMapper", detailMapper);
    }

    @Test
    void staffCanReadOrderAndItsDetails() {
        Orders order = new Orders();
        order.setId(2L);
        order.setUserId(1L);
        OrderDetail detail = new OrderDetail();
        detail.setOrderId(2L);
        when(orderMapper.getById(2L)).thenReturn(order);
        when(detailMapper.getByOrderId(2L)).thenReturn(List.of(detail));

        OrderVO result = service.adminDetails(2L);
        assertEquals(2L, result.getId());
        assertEquals(1L, result.getUserId());
        assertEquals(1, result.getOrderDetailList().size());
    }

    @Test
    void missingOrderIsNotReturned() {
        assertThrows(OrderBusinessException.class, () -> service.adminDetails(999L));
    }

    @Test
    void missingDetailIsNotReturnedAsCompleteOrder() {
        Orders order = new Orders();
        order.setId(2L);
        when(orderMapper.getById(2L)).thenReturn(order);
        when(detailMapper.getByOrderId(2L)).thenReturn(List.of());
        assertThrows(OrderBusinessException.class, () -> service.adminDetails(2L));
    }
}
