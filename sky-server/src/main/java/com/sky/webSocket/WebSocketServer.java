package com.sky.webSocket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Component
@ServerEndpoint("/ws/{sid}")
@Slf4j
public class WebSocketServer {

    // 存储连接：sid -> session（ConcurrentHashMap 高并发安全）
    private static final Map<String, Session> sessionMap = new ConcurrentHashMap<>();

    /**
     * 连接建立成功
     */
    @OnOpen
    public void onOpen(Session session, @PathParam("sid") String sid) {
        sessionMap.put(sid, session);
        log.info("websocket 连接建立:sid={}", sid);
    }

    /**
     * 收到消息
     */
    @OnMessage
    public void onMessage(String message, @PathParam("sid") String sid) {
        log.info("收到来自 sid={} 的消息：{}", sid, message);
    }

    /**
     * 连接关闭
     */
    @OnClose
    public void onClose(@PathParam("sid") String sid) {
        sessionMap.remove(sid);
        log.info("websocket 连接关闭:sid={}", sid);
    }

    /**
     * 连接异常
     */
    @OnError
    public void onError(Session session, Throwable error, @PathParam("sid") String sid) {
        log.error("websocket 连接异常 sid={}:{}", sid, error.getMessage());
    }

    /**
     * 群发消息给所有在线的客户端
     */
    public static void sendToAllClient(String message) {
        for (Session session : sessionMap.values()) {
            try {
                session.getBasicRemote().sendText(message);
            } catch (Exception e) {
                log.error("websocket 消息发送失败：{}", e.getMessage());
            }
        }
    }
}