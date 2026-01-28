package com.smancode.smanagent.model.session

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

    constructor()

    constructor(projectKey: String, projectPath: String, description: String?) {
        this.projectKey = projectKey
        this.projectPath = projectPath
        this.description = description
    }
}
