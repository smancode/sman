package ai.smancode.sman.agent.websocket;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket 配置
 *
 * 支持协议：
 * - /ws/agent/chat：AGENT_CHAT/AGENT_RESPONSE 消息类型（Claude Code 多轮对话）
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /**
     * WebSocket 容器配置
     * 设置缓冲区大小以支持大消息传输
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        // 文本消息缓冲区：10MB（相当于不限制）
        container.setMaxTextMessageBufferSize(10 * 1024 * 1024);
        // 二进制消息缓冲区：10MB
        container.setMaxBinaryMessageBufferSize(10 * 1024 * 1024);
        // 消息大小限制：10MB
        container.setMaxSessionIdleTimeout(300000L); // 5分钟超时
        return container;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Agent 协议端点（Claude Code 多轮对话）
        registry.addHandler(agentWebSocketHandler(), "/ws/agent/chat")
                .setAllowedOrigins("*");
    }

    @Bean
    public AgentWebSocketHandler agentWebSocketHandler() {
        return new AgentWebSocketHandler();
    }
}
