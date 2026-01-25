package com.smancode.smanagent.util;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 堆栈跟踪工具类
 * <p>
 * 将异常堆栈转换为单行字符串，减少日志行数
 */
public class StackTraceUtils {

    private static final String STACK_TRACE_SEPARATOR = " <- ";

    /**
     * 将异常堆栈转换为单行字符串
     * <p>
     * 格式：异常消息 <- 类名.方法名(行号) <- 类名.方法名(行号) ...
     *
     * @param throwable 异常对象
     * @return 单行堆栈字符串
     */
    public static String formatStackTrace(Throwable throwable) {
        if (throwable == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        // 异常类型和消息
        String message = throwable.getMessage();
        sb.append(throwable.getClass().getSimpleName());
        if (message != null && !message.isEmpty()) {
            sb.append(": ").append(message);
        }

        // 堆栈跟踪元素
        StackTraceElement[] stackTrace = throwable.getStackTrace();
        if (stackTrace != null && stackTrace.length > 0) {
            sb.append(STACK_TRACE_SEPARATOR);
            for (int i = 0; i < stackTrace.length && i < 10; i++) {
                if (i > 0) {
                    sb.append(STACK_TRACE_SEPARATOR);
                }
                sb.append(stackTrace[i].toString());
            }
            if (stackTrace.length > 10) {
                sb.append(STACK_TRACE_SEPARATOR).append("...");
            }
        }

        // 处理 cause
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            sb.append(" [Caused by: ").append(formatStackTrace(cause)).append("]");
        }

        return sb.toString();
    }

    /**
     * 获取完整的堆栈跟踪字符串（多行）
     *
     * @param throwable 异常对象
     * @return 完整堆栈字符串
     */
    public static String getFullStackTrace(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        return sw.toString();
    }
}
