package com.sky.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sun.net.httpserver.HttpServer;

class PythonAgentClientTest {
    private HttpServer server;
    private PythonAgentClient client;
    private String responseBody;
    private int status;

    @BeforeEach
    void setUp() throws Exception {
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agent/chat", exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (java.io.OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        client = new PythonAgentClient();
        ReflectionTestUtils.setField(client, "pythonAgentBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(client, "internalToken", "test-token");
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    private JSONObject call() {
        return (JSONObject) JSON.toJSON(client.chat(1L, "session-1", "登记工单", "send-0001"));
    }

    @Test
    void preservesStructuredUnknownInsteadOfTreatingHttp200AsCompletion() {
        responseBody = "{\"reply\":\"结果未知\",\"toolCalls\":[\"create_ticket\"],\"outcome\":\"unknown\"}";
        assertEquals("unknown", call().getString("outcome"));
    }

    @Test
    void oldOrMalformedPythonContractMustNotAcknowledgeCompletion() {
        responseBody = "{\"reply\":\"没有状态的旧响应\",\"toolCalls\":[]}";
        assertEquals("unknown", call().getString("outcome"));
    }

    @Test
    void non200FallbackIsUnknown() {
        status = 503;
        responseBody = "{}";
        assertEquals("unknown", call().getString("outcome"));
    }

    @Test
    void preservesConfirmedCompletion() {
        responseBody = "{\"reply\":\"查到订单\",\"toolCalls\":[\"query_orders\"],\"outcome\":\"completed\"}";
        assertEquals("completed", call().getString("outcome"));
    }
}
