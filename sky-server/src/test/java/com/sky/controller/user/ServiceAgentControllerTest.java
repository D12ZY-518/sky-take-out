package com.sky.controller.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sky.client.PythonAgentClient;
import com.sky.exception.UserNotLoginException;
import com.sky.handler.GlobalExceptionHandler;
import com.sky.security.AiUserResolver;
import com.sky.vo.ServiceChatVO;
import com.sky.service.InternalApiService;

class ServiceAgentControllerTest {

    private PythonAgentClient pythonAgentClient;
    private AiUserResolver aiUserResolver;
    private MockMvc mockMvc;
    private InternalApiService internalApiService;

    @BeforeEach
    void setUp() {
        pythonAgentClient = mock(PythonAgentClient.class);
        aiUserResolver = mock(AiUserResolver.class);
        internalApiService = mock(InternalApiService.class);
        ServiceAgentController controller = new ServiceAgentController(pythonAgentClient, aiUserResolver, internalApiService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void shouldReturn401WhenUserCannotBeResolved() throws Exception {
        when(aiUserResolver.resolve(any())).thenThrow(new UserNotLoginException("请先登录"));

        mockMvc.perform(post("/user/service/chat-python")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"我的订单\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("请先登录"));
    }

    @Test
    void shouldForwardResolvedUserAndDefaultSessionToPythonAgent() throws Exception {
        when(aiUserResolver.resolve(any())).thenReturn(42L);
        when(pythonAgentClient.chat(42L, "user-42", "我的订单", "send-0001"))
                .thenReturn(ServiceChatVO.builder()
                        .reply("查询成功")
                        .toolCalls(Collections.singletonList("query_orders"))
                        .build());

        mockMvc.perform(post("/user/service/chat-python")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"我的订单\",\"requestId\":\"send-0001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.reply").value("查询成功"))
                .andExpect(jsonPath("$.data.toolCalls[0]").value("query_orders"));

        verify(pythonAgentClient).chat(42L, "user-42", "我的订单", "send-0001");
    }

    @Test
    void shouldRejectInvalidRequestIdBeforeCallingPython() throws Exception {
        when(aiUserResolver.resolve(any())).thenReturn(42L);
        mockMvc.perform(post("/user/service/chat-python")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"转人工\",\"requestId\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void shouldRejectReceiptLookupWithoutIdentity() throws Exception {
        when(aiUserResolver.resolve(any())).thenThrow(new UserNotLoginException("请先登录"));
        mockMvc.perform(get("/user/service/ticket-result").param("requestId", "send-0001"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingReceiptMustNotClaimWriteFailureOrCompletion() throws Exception {
        when(aiUserResolver.resolve(any())).thenReturn(42L);
        mockMvc.perform(get("/user/service/ticket-result").param("requestId", "send-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("unknown"));
    }

    @Test
    void receiptUsesResolvedIdentityAndDoesNotCallModelAgain() throws Exception {
        when(aiUserResolver.resolve(any())).thenReturn(42L);
        when(internalApiService.findTicketNoByRequestId(42L, "send-0001")).thenReturn("TKoriginal");
        mockMvc.perform(get("/user/service/ticket-result").param("requestId", "send-0001")
                        .param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("completed"))
                .andExpect(jsonPath("$.data.reply").value(org.hamcrest.Matchers.containsString("TKoriginal")))
                .andExpect(jsonPath("$.data.toolCalls").isEmpty());
        verify(internalApiService).findTicketNoByRequestId(42L, "send-0001");
        verifyNoInteractions(pythonAgentClient);
    }

    @Test
    void receiptRejectsInvalidIdBeforeAccessingDatabase() throws Exception {
        when(aiUserResolver.resolve(any())).thenReturn(42L);
        mockMvc.perform(get("/user/service/ticket-result").param("requestId", "x"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verifyNoInteractions(internalApiService);
    }
}
