package com.smancode.sman.ide.util

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

object SessionIdGenerator {

    private val dateTimeFormatter = DateTimeFormatter.ofPattern("MMdd_HHmmss")

    private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    fun generate(): String {
        // 日期时间部分
        val dateTime = LocalDateTime.now().format(dateTimeFormatter)

        // 随机8位
        val random = (1..8)
            .map { CHARS.random() }
            .joinToString("")

        return "${dateTime}_$random"
    }
}
