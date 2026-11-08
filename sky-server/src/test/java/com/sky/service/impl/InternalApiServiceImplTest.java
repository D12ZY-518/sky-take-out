package com.sky.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import com.sky.dto.TicketCreateDTO;
import com.sky.entity.CustomerServiceTicket;
import com.sky.entity.Orders;
import com.sky.exception.TicketBusinessException;
import com.sky.mapper.InternalApiMapper;

class InternalApiServiceImplTest {

    private InternalApiMapper mapper;
    private InternalApiServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(InternalApiMapper.class);
        service = new InternalApiServiceImpl();
        ReflectionTestUtils.setField(service, "internalApiMapper", mapper);
        when(mapper.getByNumber("20260917123000002"))
                .thenReturn(Orders.builder().id(2L).number("20260917123000002").userId(1L).build());
    }

    private TicketCreateDTO request(String content) {
        TicketCreateDTO dto = new TicketCreateDTO();
        dto.setUserId(1L);
        dto.setRequestId("send-0001");
        dto.setCategory("配送");
        dto.setContent(content);
        dto.setOrderNumber("20260917123000002");
        return dto;
    }

    @Test
    void firstInsertReturnsNewTicketNumber() {
        String ticketNo = service.createTicket(request("配送超时"));
        org.junit.jupiter.api.Assertions.assertTrue(ticketNo.matches("TK[0-9a-f]{30}"));
        verify(mapper).insertTicket(any(CustomerServiceTicket.class));
    }

    @Test
    void retryAfterCommittedInsertReturnsOriginalTicketNumber() {
        doThrow(new DuplicateKeyException("duplicate request id"))
                .when(mapper).insertTicket(any(CustomerServiceTicket.class));
        CustomerServiceTicket previous = CustomerServiceTicket.builder()
                .ticketNo("TKoriginal").userId(1L).requestId("send-0001")
                .category("配送").content("配送超时")
                .orderNumber("20260917123000002").build();
        // 第一次查暂无记录；insert 时另一个请求已提交，唯一键冲突后回查。
        when(mapper.getTicketByRequestId(1L, "send-0001")).thenReturn(null, previous);
        assertEquals("TKoriginal", service.createTicket(request("配送超时")));
        verify(mapper).insertTicket(any(CustomerServiceTicket.class));
    }

    @Test
    void sameRequestIdWithDifferentPayloadIsConflict() {
        doThrow(new DuplicateKeyException("duplicate request id"))
                .when(mapper).insertTicket(any(CustomerServiceTicket.class));
        CustomerServiceTicket previous = CustomerServiceTicket.builder()
                .ticketNo("TKoriginal").userId(1L).requestId("send-0001")
                .category("配送").content("配送超时")
                .orderNumber("20260917123000002").build();
        when(mapper.getTicketByRequestId(1L, "send-0001")).thenReturn(previous);
        assertThrows(IllegalArgumentException.class,
                () -> service.createTicket(request("完全不同的诉求")));
    }

    @Test
    void unrelatedUniqueCollisionMustNotBeReportedAsSuccess() {
        doThrow(new DuplicateKeyException("ticket number collision"))
                .when(mapper).insertTicket(any(CustomerServiceTicket.class));
        assertThrows(DuplicateKeyException.class, () -> service.createTicket(request("配送超时")));
    }

    @Test
    void anotherUsersOrderMustBeRejectedBeforeInsert() {
        when(mapper.getByNumber("20260917123000002"))
                .thenReturn(Orders.builder().id(2L).userId(2L).build());
        TicketBusinessException error = assertThrows(TicketBusinessException.class,
                () -> service.createTicket(request("配送超时")));
        assertEquals("未找到可关联的订单，请核对订单号。", error.getMessage());
        verify(mapper, never()).insertTicket(any(CustomerServiceTicket.class));
    }

    @Test
    void missingOrderUsesSameRejectionAndDoesNotInsert() {
        when(mapper.getByNumber("20260917123000002")).thenReturn(null);
        TicketBusinessException error = assertThrows(TicketBusinessException.class,
                () -> service.createTicket(request("配送超时")));
        assertEquals("未找到可关联的订单，请核对订单号。", error.getMessage());
        verify(mapper, never()).insertTicket(any(CustomerServiceTicket.class));
    }

    @Test
    void orderWithoutOwnerMustNotBeAssociated() {
        when(mapper.getByNumber("20260917123000002")).thenReturn(Orders.builder().id(2L).build());
        assertThrows(TicketBusinessException.class, () -> service.createTicket(request("配送超时")));
        verify(mapper, never()).insertTicket(any(CustomerServiceTicket.class));
    }

    @Test
    void nullOrNonPositiveUserCannotCreateTicketEvenWithoutOrder() {
        for (Long userId : new Long[] {null, 0L, -1L}) {
            TicketCreateDTO dto = request("通用投诉");
            dto.setUserId(userId);
            dto.setOrderNumber(null);
            assertThrows(TicketBusinessException.class, () -> service.createTicket(dto));
        }
        verify(mapper, never()).insertTicket(any(CustomerServiceTicket.class));
    }

    @Test
    void noAssociatedOrderIsAllowedAndBlankIsStoredAsNull() {
        for (String number : new String[] {null, "", "   "}) {
            TicketCreateDTO dto = request("通用投诉");
            dto.setOrderNumber(number);
            service.createTicket(dto);
        }
        ArgumentCaptor<CustomerServiceTicket> captured = ArgumentCaptor.forClass(CustomerServiceTicket.class);
        verify(mapper, org.mockito.Mockito.times(3)).insertTicket(captured.capture());
        for (CustomerServiceTicket ticket : captured.getAllValues()) assertNull(ticket.getOrderNumber());
        verify(mapper, never()).getByNumber(anyString());
    }

    @Test
    void associatedOrderIsNormalizedBeforeCheckingAndStoring() {
        TicketCreateDTO dto = request("配送超时");
        dto.setOrderNumber(" 20260917123000002 ");
        service.createTicket(dto);
        ArgumentCaptor<CustomerServiceTicket> captured = ArgumentCaptor.forClass(CustomerServiceTicket.class);
        verify(mapper).insertTicket(captured.capture());
        assertEquals("20260917123000002", captured.getValue().getOrderNumber());
        verify(mapper).getByNumber("20260917123000002");
    }

    @Test
    void committedRetryReturnsReceiptWithoutDependingOnCurrentOrderExistence() {
        CustomerServiceTicket previous = CustomerServiceTicket.builder()
                .ticketNo("TKoriginal").userId(1L).requestId("send-0001")
                .category("配送").content("配送超时").orderNumber("20260917123000002").build();
        when(mapper.getTicketByRequestId(1L, "send-0001")).thenReturn(previous);
        when(mapper.getByNumber("20260917123000002")).thenReturn(null);
        assertEquals("TKoriginal", service.createTicket(request("配送超时")));
        verify(mapper, never()).insertTicket(any(CustomerServiceTicket.class));
        verify(mapper, never()).getByNumber(anyString());
    }

    @Test
    void sameIdCannotBeReboundToAnotherOrder() {
        CustomerServiceTicket previous = CustomerServiceTicket.builder()
                .ticketNo("TKoriginal").userId(1L).requestId("send-0001")
                .category("配送").content("配送超时").orderNumber("original-order").build();
        when(mapper.getTicketByRequestId(1L, "send-0001")).thenReturn(previous);
        assertThrows(IllegalArgumentException.class, () -> service.createTicket(request("配送超时")));
        verify(mapper, never()).insertTicket(any(CustomerServiceTicket.class));
    }
}
