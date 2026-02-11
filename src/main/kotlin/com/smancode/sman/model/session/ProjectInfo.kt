package com.smancode.sman.model.session

/**
 * 项目信息
 */
class ProjectInfo {

    /**
     * 项目 Key
     */
    var projectKey: String? = null

    /**
     * 项目路径
     */
    var projectPath: String? = null

    /**
     * 项目描述
     */
    var description: String? = null

    /**
     * 用户配置的 RULES（会追加到 system prompt 后面）
     */
    var rules: String? = null

    constructor()

    constructor(projectKey: String, projectPath: String, description: String?) {
        this.projectKey = projectKey
        this.projectPath = projectPath
        this.description = description
    }

    constructor(projectKey: String, projectPath: String, description: String?, rules: String?) {
        this.projectKey = projectKey
        this.projectPath = projectPath
        this.description = description
        this.rules = rules
    }
}
