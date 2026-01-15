package com.smancode.smanagent.ide.service

fun main() {
    val list = (1..20).toList()

    println("验证 subList 的行为：")
    println()

    println("list.size = ${list.size}")
    println("list = ${list.take(5)}...")
    println()

    println("subList(0, 5) = ${list.subList(0, 5)}")
    println("subList(0, 5).size = ${list.subList(0, 5).size}")
    println("→ 返回索引 0,1,2,3,4，共 5 个元素")
    println()

    println("subList(0, 10) = ${list.subList(0, 10).take(3)}...")
    println("subList(0, 10).size = ${list.subList(0, 10).size}")
    println("→ 返回索引 0-9，共 10 个元素")
    println()

    println("subList(0, 20) = ${list.subList(0, 20).take(3)}...")
    println("subList(0, 20).size = ${list.subList(0, 20).size}")
    println("→ 返回索引 0-19，共 20 个元素（全部）")
    println()

    println("结论：subList(from, to) 的 to 参数是『不包含的上界索引』")
    println("      所以 subList(0, 10) 返回索引 [0, 10)，即 0-9，共 10 个元素")
}
