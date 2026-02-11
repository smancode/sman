package com.smancode.sman.model.message

/**
 * Message 扩展函数
 *
 * 提供函数调用方式，兼容 Java 风格调用
 */

/**
 * 是否为用户消息（函数调用方式）
 */
fun Message.isUserMessage(): Boolean = role == Role.USER

/**
 * 是否为助手消息（函数调用方式）
 */
fun Message.isAssistantMessage(): Boolean = role == Role.ASSISTANT

/**
 * 是否为系统消息（函数调用方式）
 */
fun Message.isSystemMessage(): Boolean = role == Role.SYSTEM
