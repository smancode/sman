package com.smancode.smanagent.controller;

import com.smancode.smanagent.model.*;
import com.smancode.smanagent.service.BusinessGraphService;
import com.smancode.smanagent.dto.AnalyzeRequest;
import com.smancode.smanagent.dto.GraphQueryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 业务图谱查询控制器
 *
 * 提供业务术语查询、代码映射、关系分析等接口
 */
@RestController
@RequestMapping("/api/graph")
public class BusinessGraphController {

    private static final Logger logger = LoggerFactory.getLogger(BusinessGraphController.class);

    @Autowired
    private BusinessGraphService businessGraphService;

    /**
     * 分析项目，构建业务图谱
     *
     * @param request 分析请求
     * @return 构建结果
     */
    @PostMapping("/analyze")
    public Object analyzeProject(@RequestBody AnalyzeRequest request) {
        if (request.getProjectPath() == null || request.getProjectPath().trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 projectPath 参数");
        }

        logger.info("开始分析项目: {}", request.getProjectPath());

        businessGraphService.analyzeProject(request.getProjectPath());

        return businessGraphService.getGraphStats();
    }

    /**
     * 业务问答接口
     *
     * 用户问题示例："同一个客户的同一个账单日的不同借据的批扣顺序是什么样的？"
     * 返回：
     * 1. 业务术语解释（客户、账单日、借据、批扣）
     * 2. 代码映射关系（哪些类、方法、字段）
     * 3. 关系路径（调用链、依赖关系）
     * 4. 缺失信息提示（需要进一步查看代码的部分）
     */
    @PostMapping("/ask")
    public BusinessAnalysis ask(@RequestBody GraphQueryRequest request) {
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 question 参数");
        }

        logger.info("处理业务问题: {}", request.getQuestion());

        return businessGraphService.ask(request.getQuestion());
    }

    /**
     * 查询术语关系
     *
     * 获取指定术语在业务图谱中的关系路径
     */
    @GetMapping("/relations")
    public Object queryTermRelations(
            @RequestParam String term,
            @RequestParam(defaultValue = "2") int maxDepth) {

        if (term == null || term.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 term 参数");
        }

        return businessGraphService.queryTermRelations(term, maxDepth);
    }

    /**
     * 根据术语查询代码
     *
     * 查询与业务术语相关的代码元素（类、方法、字段）
     */
    @GetMapping("/code/by-term")
    public Object queryCodeByTerm(@RequestParam String term) {
        if (term == null || term.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 term 参数");
        }

        return businessGraphService.queryCodeByTerm(term);
    }

    /**
     * 查询方法调用链
     *
     * 获取指定方法的完整调用链
     */
    @GetMapping("/code/call-chain")
    public Object queryCallChain(@RequestParam String methodName) {
        if (methodName == null || methodName.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 methodName 参数");
        }

        return businessGraphService.queryCallChain(methodName);
    }

    /**
     * 获取术语列表
     *
     * 获取系统识别的所有业务术语
     */
    @GetMapping("/terms")
    public Object getTerms(
            @RequestParam(required = false) Double minConfidence,
            @RequestParam(required = false, defaultValue = "false") boolean topByFreq) {

        return businessGraphService.getTerms(minConfidence, topByFreq);
    }

    /**
     * 获取图谱统计信息
     */
    @GetMapping("/stats")
    public Object getGraphStats() {
        return businessGraphService.getGraphStats();
    }

    /**
     * 分析请求
     */
    public static class AnalyzeRequest {
        private String projectPath;

        public String getProjectPath() {
            return projectPath;
        }

        public void setProjectPath(String projectPath) {
            this.projectPath = projectPath;
        }
    }
}
