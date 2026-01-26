package com.smancode.smanagent.common.service_discovery.impl;

import com.smancode.smanagent.common.service_discovery.api.HealthCheckService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 健康检查服务实现
 */
@Service
public class HealthCheckServiceImpl implements HealthCheckService {

    private static final Logger logger = LoggerFactory.getLogger(HealthCheckServiceImpl.class);

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public boolean checkHealth(String host, int port, int timeoutMs) {
        try {
            String url = String.format("http://%s:%d/actuator/health", host, port);

            // 使用 RestTemplate 发送请求
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            // 判断：状态码为 2xx 且响应包含 "UP"
            return response.getStatusCode().is2xxSuccessful() &&
                   response.getBody() != null &&
                   (response.getBody().contains("\"status\":\"UP\"") ||
                    response.getBody().contains("\"status\": \"UP\""));
        } catch (Exception e) {
            logger.debug("健康检查失败: host={}, port={}, error={}", host, port, e.getMessage());
            return false;
        }
    }
}
