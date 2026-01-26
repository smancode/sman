package com.smancode.smanagent.websocket;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket 配置
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AgentWebSocketHandler agentWebSocketHandler;

    public WebSocketConfig(AgentWebSocketHandler agentWebSocketHandler) {
        this.agentWebSocketHandler = agentWebSocketHandler;
    }

    /**
     * 配置 WebSocket 容器
     * 设置最大消息大小为 10MB，避免大消息导致连接关闭
     */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        // 最大文本消息大小（字节），默认 8KB = 8192
        // 设置为 10MB 以支持大文件内容传输
        container.setMaxTextMessageBufferSize(10 * 1024 * 1024);
        // 最大二进制消息大小
        container.setMaxBinaryMessageBufferSize(10 * 1024 * 1024);
        // 最大会话空闲超时（毫秒），30 分钟
        container.setMaxSessionIdleTimeout(30 * 60 * 1000L);
        return container;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentWebSocketHandler, "/ws/agent")
                .setAllowedOrigins("*"); // 允许所有来源，生产环境应限制
    }
}
