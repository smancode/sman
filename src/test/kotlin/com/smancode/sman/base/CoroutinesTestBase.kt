package com.smancode.sman.base

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * 协程测试基类
 *
 * 提供测试调度器
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class CoroutinesTestBase {

    protected val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    open fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    open fun tearDown() {
        Dispatchers.resetMain()
    }
}
