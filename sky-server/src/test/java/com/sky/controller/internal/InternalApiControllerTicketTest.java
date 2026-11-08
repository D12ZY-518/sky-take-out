package com.sky.controller.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.sky.dto.TicketCreateDTO;
import com.sky.result.Result;
import com.sky.service.InternalApiService;
import com.sky.service.impl.InternalApiServiceImpl;
import com.sky.mapper.InternalApiMapper;
import com.sky.entity.Orders;
import com.sky.handler.GlobalExceptionHandler;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class InternalApiControllerTicketTest {
    private MockMvc endpointWithOrder(Orders order) {
        InternalApiMapper mapper = mock(InternalApiMapper.class);
        when(mapper.getByNumber("order-1")).thenReturn(order);
        InternalApiServiceImpl service = new InternalApiServiceImpl();
        ReflectionTestUtils.setField(service, "internalApiMapper", mapper);
        InternalApiController controller = new InternalApiController();
        ReflectionTestUtils.setField(controller, "internalApiService", service);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void conflictingRetryRetainsUnknownOriginalResult() {
        InternalApiService service = mock(InternalApiService.class);
        when(service.createTicket(any())).thenThrow(new IllegalArgumentException("requestId conflict"));
        InternalApiController controller = new InternalApiController();
        ReflectionTestUtils.setField(controller, "internalApiService", service);
        TicketCreateDTO dto = new TicketCreateDTO();
        dto.setUserId(1L);
        dto.setRequestId("send-0001");
        dto.setCategory("配送");
        dto.setContent("重试时模型改写了内容");
        Result<Map<String, String>> result = controller.createTicket(dto);
        assertEquals(0, result.getCode());
        assertEquals("unknown", result.getData() == null ? null : result.getData().get("outcome"));
    }

    @Test
    void foreignAndMissingOrdersReturnSameKnownBusinessRejection() throws Exception {
        for (Orders order : new Orders[] {null, Orders.builder().id(2L).userId(2L).build()}) {
            endpointWithOrder(order).perform(post("/internal/tickets")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":1,\"requestId\":\"send-0001\",\"orderNumber\":\"order-1\",\"category\":\"配送\",\"content\":\"少送菜\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.msg").value("未找到可关联的订单，请核对订单号。"))
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    @Test
    void ownedOrderStillReturnsTicketNumberThroughHttp() throws Exception {
        endpointWithOrder(Orders.builder().id(1L).userId(1L).build())
                .perform(post("/internal/tickets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1,\"requestId\":\"send-0001\",\"orderNumber\":\"order-1\",\"category\":\"配送\",\"content\":\"少送菜\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.ticketNo").value(org.hamcrest.Matchers.matchesPattern("TK[0-9a-f]{30}")));
    }
}
