package com.smancode.sman.analysis

import java.io.File

class L2EntryAnalyzer(private val projectPath: String) {
    fun analyze(): L2Result {
        return L2Result(
            restApis = emptyList(),
            controllers = findControllers(),
            services = findServices(),
            jobs = emptyList(),
            listeners = emptyList()
        )
    }

    private fun findControllers(): List<Controller> {
        val result = mutableListOf<Controller>()
        File(projectPath).walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".java") || it.name.endsWith(".kt")) }
            .filter { !it.absolutePath.contains("/build/") }
            .forEach { file ->
                try {
                    val content = file.readText()
                    if (content.contains("@RestController") || content.contains("@Controller")) {
                        result.add(Controller(file.nameWithoutExtension, emptyList()))
                    }
                } catch (e: Exception) { }
            }
        return result
    }

    private fun findServices(): List<ServiceEntry> {
        val result = mutableListOf<ServiceEntry>()
        File(projectPath).walkTopDown()
            .filter { it.isFile && it.name.endsWith(".java") }
            .filter { !it.absolutePath.contains("/build/") }
            .forEach { file ->
                try {
                    val content = file.readText()
                    if (content.contains("@Service") || content.contains("@Component")) {
                        result.add(ServiceEntry(file.nameWithoutExtension, ""))
                    }
                } catch (e: Exception) { }
            }
        return result
    }
}

data class L2Result(
    val restApis: List<RestApi>,
    val controllers: List<Controller>,
    val services: List<ServiceEntry>,
    val jobs: List<ScheduledJob>,
    val listeners: List<EventListener>
)
data class RestApi(val path: String, val method: String, val handler: String)
data class Controller(val name: String, val mappings: List<String>)
data class ServiceEntry(val name: String, val description: String)
data class ScheduledJob(val name: String, val cron: String)
data class EventListener(val event: String, val handler: String)
