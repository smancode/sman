package com.smancode.smanagent.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 优雅停机配置属性
 */
@Component
@ConfigurationProperties(prefix = "graceful-shutdown")
public class GracefulShutdownProperties {

    /**
     * 等待在途请求完成的最大时长
     */
    private Duration awaitTerminationTimeout = Duration.ofSeconds(30);

    public Duration getAwaitTerminationTimeout() {
        return awaitTerminationTimeout;
    }

    public void setAwaitTerminationTimeout(Duration awaitTerminationTimeout) {
        this.awaitTerminationTimeout = awaitTerminationTimeout;
    }

    public long getAwaitTerminationTimeoutMs() {
        return awaitTerminationTimeout.toMillis();
    }
}
