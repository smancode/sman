package com.smancode.smanagent.dto;

/**
 * 业务图谱查询请求
 */
public class GraphQueryRequest {

    /**
     * 问题
     */
    private String question;

    public GraphQueryRequest() {
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }
}
