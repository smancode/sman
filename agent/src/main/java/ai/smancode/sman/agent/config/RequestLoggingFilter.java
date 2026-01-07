package ai.smancode.sman.agent.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 请求日志过滤器
 * 记录所有 HTTP 请求的详细信息，特别是请求体
 */
@Component
public class RequestLoggingFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String uri = httpRequest.getRequestURI();
        String method = httpRequest.getMethod();

        // 只记录 /api/claude-code/tools/execute 的请求
        if (uri.contains("/tools/execute")) {
            // 包装请求以便多次读取
            CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(httpRequest);

            // 读取请求体
            String body = StreamUtils.copyToString(cachedRequest.getInputStream(), StandardCharsets.UTF_8);

            log.info("========================================");
            log.info("📨 收到工具调用请求");
            log.info("URL: {} {}", method, uri);
            log.info("Content-Type: {}", httpRequest.getContentType());
            log.info("请求体: {}", body);
            log.info("========================================");

            // 传递包装后的请求
            chain.doFilter(cachedRequest, response);
        } else {
            // 其他请求直接放行
            chain.doFilter(request, response);
        }
    }
}
