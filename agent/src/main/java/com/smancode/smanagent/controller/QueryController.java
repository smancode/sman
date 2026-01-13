package com.smancode.smanagent.controller;

import org.springframework.web.bind.annotation.*;

/**
 * 知识查询控制器
 *
 * 提供纯查询服务，不做 LLM 推理
 */
@RestController
@RequestMapping("/api/query")
public class QueryController {

    /**
     * 语义搜索
     */
    @PostMapping("/search")
    public Object search(@RequestBody String request) {
        // TODO: 实现语义搜索
        return null;
    }

    /**
     * 业务图谱查询
     */
    @PostMapping("/graph")
    public Object graph(@RequestBody String request) {
        // TODO: 实现图谱查询
        return null;
    }

    /**
     * 业务规则查询
     */
    @PostMapping("/rules")
    public Object rules(@RequestBody String request) {
        // TODO: 实现规则查询
        return null;
    }

    /**
     * 历史案例查询
     */
    @PostMapping("/cases")
    public Object cases(@RequestBody String request) {
        // TODO: 实现案例查询
        return null;
    }

    /**
     * 代码依赖查询
     */
    @PostMapping("/dependencies")
    public Object dependencies(@RequestBody String request) {
        // TODO: 实现依赖查询
        return null;
    }
}
