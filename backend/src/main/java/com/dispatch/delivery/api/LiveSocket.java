package com.dispatch.delivery.api;

import com.dispatch.delivery.simulation.SimulationEngine;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Pushes the live snapshot to every dashboard at ws://host/ws/live after each simulation tick. */
@Configuration
@EnableWebSocket
public class LiveSocket extends TextWebSocketHandler implements WebSocketConfigurer {

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final SimulationEngine engine;
    private final ObjectMapper mapper;

    public LiveSocket(SimulationEngine engine, ObjectMapper mapper) {
        this.engine = engine;
        this.mapper = mapper;
        engine.addListener(this::broadcast);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(this, "/ws/live").setAllowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession s) {
        sessions.add(s);
        send(s, mapper.writeValueAsString(engine.snapshot(false)));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
        sessions.remove(s);
    }

    void broadcast(Map<String, Object> snapshot) {
        if (sessions.isEmpty()) return;
        String json = mapper.writeValueAsString(snapshot);
        for (WebSocketSession s : sessions) send(s, json);
    }

    private void send(WebSocketSession s, String json) {
        synchronized (s) { // a session must not be written by two threads at once
            if (!s.isOpen()) {
                sessions.remove(s);
                return;
            }
            try {
                s.sendMessage(new TextMessage(json));
            } catch (IOException | IllegalStateException e) {
                sessions.remove(s);
            }
        }
    }
}
