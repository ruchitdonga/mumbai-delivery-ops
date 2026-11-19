package com.dispatch.delivery.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Optional admin token for every state-changing request (POST) under /api. Reads stay open for dashboards.
 * Set ADMIN_TOKEN to enable; clients send it as the X-Admin-Token header. The server also binds to 127.0.0.1 by
 * default (SERVER_ADDRESS), so without a token only this computer can reach it.
 */
@Configuration
public class AdminTokenGuard implements WebMvcConfigurer, HandlerInterceptor {

    private final byte[] token;

    public AdminTokenGuard(@Value("${admin.token:}") String token) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
        if (token.isBlank()) LoggerFactory.getLogger(AdminTokenGuard.class)
                .warn("ADMIN_TOKEN is not set: admin actions are open to anyone who can reach this server (localhost only by default)");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if (token.length == 0 || !"POST".equals(req.getMethod())) return true;
        String sent = req.getHeader("X-Admin-Token");
        if (sent != null && MessageDigest.isEqual(token, sent.getBytes(StandardCharsets.UTF_8))) return true; // constant-time compare
        res.setStatus(401);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"Admin token required (X-Admin-Token header)\"}");
        return false;
    }
}
