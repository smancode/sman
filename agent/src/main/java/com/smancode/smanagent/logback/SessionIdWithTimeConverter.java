package com.smancode.smanagent.logback;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Logback 转换器：sessionId + 时间戳
 * <p>
 * 输出格式：[sessionId_HHmmss]
 * 如果 sessionId 为空，只输出时间戳：[_HHmmss]
 */
public class SessionIdWithTimeConverter extends ClassicConverter {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HHmmss");

    @Override
    public String convert(ILoggingEvent event) {
        String sessionId = event.getMDCPropertyMap().get("sessionId");
        String time = LocalTime.now().format(TIME_FORMATTER);

        if (sessionId == null || sessionId.isEmpty()) {
            return "_" + time;
        }

        return sessionId + "_" + time;
    }
}
