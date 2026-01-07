package ai.smancode.sman.agent.config;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.nio.charset.StandardCharsets;

/**
 * 全局异常处理器
 * 用于捕获和记录请求解析错误
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理 JSON 解析错误
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpServletRequest request) {

        // 打印详细信息
        log.error("========================================");
        log.error("❌ JSON 解析错误");
        log.error("请求 URL: {}", request.getRequestURL());
        log.error("请求 Method: {}", request.getMethod());
        log.error("Content-Type: {}", request.getContentType());

        // 尝试读取请求体
        try {
            // 注意：这里可能读取失败，因为流可能已经被消费
            log.error("错误详情: {}", ex.getMessage());
            if (ex.getCause() instanceof JsonMappingException) {
                JsonMappingException jme = (JsonMappingException) ex.getCause();
                log.error("JSON 映射错误: path={}", jme.getPath());
                if (jme.getCause() != null) {
                    log.error("根本原因: {}", jme.getCause().getMessage());
                }
            }
        } catch (Exception e) {
            log.error("读取错误详情失败: {}", e.getMessage());
        }

        log.error("完整异常:", ex);
        log.error("========================================");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("JSON 解析错误: " + ex.getMessage());
    }

    /**
     * 处理其他异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleException(Exception ex, HttpServletRequest request) {
        log.error("========================================");
        log.error("❌ 未处理的异常");
        log.error("请求 URL: {}", request.getRequestURL());
        log.error("异常类型: {}", ex.getClass().getName());
        log.error("异常消息: {}", ex.getMessage());
        log.error("完整异常:", ex);
        log.error("========================================");

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("服务器错误: " + ex.getMessage());
    }
}
