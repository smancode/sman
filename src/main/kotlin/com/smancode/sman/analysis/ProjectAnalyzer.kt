package com.smancode.sman.analysis

class ProjectAnalyzer(private val projectKey: String, private val projectPath: String) {
    fun analyzeL0Structure() = L0StructureAnalyzer(projectPath).analyze()
    fun analyzeL1Module(modulePath: String) = L1ModuleAnalyzer(projectPath, modulePath).analyze()
}
