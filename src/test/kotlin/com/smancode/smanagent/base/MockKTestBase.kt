package com.smancode.smanagent.base

import io.mockk.MockKAnnotations
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * MockK 测试基类
 *
 * 自动初始化和清理 MockK
 */
abstract class MockKTestBase {

    @BeforeEach
    open fun setUp() {
        MockKAnnotations.init(this)
    }

    @AfterEach
    open fun tearDown() {
        unmockkAll()
    }
}
