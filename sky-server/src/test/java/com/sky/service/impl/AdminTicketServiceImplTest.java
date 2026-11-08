package com.sky.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.sky.entity.CustomerServiceTicket;
import com.sky.exception.TicketBusinessException;
import com.sky.mapper.AdminTicketMapper;

class AdminTicketServiceImplTest {
    private AdminTicketMapper mapper;
    private AdminTicketServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(AdminTicketMapper.class);
        service = new AdminTicketServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);
    }

    @Test
    void pendingCanMoveToProcessing() {
        CustomerServiceTicket ticket = new CustomerServiceTicket();
        ticket.setStatus(CustomerServiceTicket.PENDING);
        when(mapper.getById(10L)).thenReturn(ticket);
        when(mapper.updateStatus(10L, 1, 2)).thenReturn(1);
        assertDoesNotThrow(() -> service.updateStatus(10L, 2));
        verify(mapper).updateStatus(10L, 1, 2);
    }

    @Test
    void identicalRetryDoesNotWriteAgain() {
        CustomerServiceTicket ticket = new CustomerServiceTicket();
        ticket.setStatus(CustomerServiceTicket.PROCESSING);
        when(mapper.getById(10L)).thenReturn(ticket);
        assertDoesNotThrow(() -> service.updateStatus(10L, 2));
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never())
                .updateStatus(10L, 1, 2);
    }

    @Test
    void cannotSkipProcessing() {
        CustomerServiceTicket ticket = new CustomerServiceTicket();
        ticket.setStatus(CustomerServiceTicket.PENDING);
        when(mapper.getById(10L)).thenReturn(ticket);
        assertThrows(TicketBusinessException.class, () -> service.updateStatus(10L, 3));
    }

    @Test
    void concurrentUpdateMustBeReported() {
        CustomerServiceTicket ticket = new CustomerServiceTicket();
        ticket.setStatus(CustomerServiceTicket.PROCESSING);
        when(mapper.getById(10L)).thenReturn(ticket);
        when(mapper.updateStatus(10L, 2, 3)).thenReturn(0);
        assertThrows(TicketBusinessException.class, () -> service.updateStatus(10L, 3));
    }

    @Test
    void invalidStateRejectedBeforeDatabaseRead() {
        assertThrows(TicketBusinessException.class, () -> service.updateStatus(10L, 1));
        verifyNoInteractions(mapper);
    }
}
