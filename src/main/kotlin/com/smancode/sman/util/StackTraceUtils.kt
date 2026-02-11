package com.smancode.sman.util

import java.io.PrintWriter
import java.io.StringWriter

/**
 * 堆栈跟踪工具类
 */
object StackTraceUtils {

    /**
     * 格式化异常堆栈跟踪
     *
     * @param throwable 异常
     * @return 格式化后的堆栈跟踪字符串
     */
    fun formatStackTrace(throwable: Throwable?): String {
        if (throwable == null) {
            return ""
        }

        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString()
    }

    /**
     * 获取异常根因
     *
     * @param throwable 异常
     * @return 根因异常
     */
    fun getRootCause(throwable: Throwable): Throwable {
        var cause: Throwable? = throwable
        while (cause?.cause != null) {
            cause = cause.cause
        }
        return cause ?: throwable
    }

    /**
     * 获取简洁的堆栈跟踪（只包含关键信息）
     *
     * @param throwable 异常
     * @param maxFrames 最大帧数
     * @return 简洁的堆栈跟踪
     */
    fun getCompactStackTrace(throwable: Throwable?, maxFrames: Int = 10): String {
        if (throwable == null) {
            return ""
        }

        val sb = StringBuilder()
        sb.append(throwable.javaClass.name)
        if (throwable.message != null) {
            sb.append(": ").append(throwable.message)
        }
        sb.append("\n")

        val stackTrace = throwable.stackTrace
        val framesToPrint = minOf(stackTrace.size, maxFrames)

        for (i in 0 until framesToPrint) {
            sb.append("\tat ").append(stackTrace[i]).append("\n")
        }

        if (stackTrace.size > maxFrames) {
            sb.append("\t... ").append(stackTrace.size - maxFrames).append(" more\n")
        }

        return sb.toString()
    }
}
