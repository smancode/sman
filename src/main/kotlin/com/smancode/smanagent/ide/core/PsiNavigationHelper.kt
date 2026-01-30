package com.smancode.smanagent.ide.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMember
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * PSI 导航助手 - 跨平台的代码跳转
 * <p>
 * 使用 IntelliJ PSI API 进行导航，避免文件路径兼容性问题
 */
object PsiNavigationHelper {

    private val logger = LoggerFactory.getLogger(PsiNavigationHelper::class.java)

    /**
     * 跳转到类
     * @param project IntelliJ 项目
     * @param className 类名（支持短名称和全限定名）
     * @return 是否成功跳转
     */
    fun navigateToClass(project: Project, className: String): Boolean {
        var targetClass: PsiClass? = null

        ApplicationManager.getApplication().runReadAction {
            val scope = GlobalSearchScope.allScope(project)
            targetClass = JavaPsiFacade.getInstance(project).findClass(className, scope)

            if (targetClass == null) {
                val classes = PsiShortNamesCache.getInstance(project)
                    .getClassesByName(className, scope)

                if (classes.isNotEmpty()) {
                    targetClass = classes.firstOrNull()
                }
            }
        }

        if (targetClass != null) {
            ApplicationManager.getApplication().invokeLater {
                if (targetClass?.isValid == true) {
                    targetClass?.navigate(true)
                    logger.info("成功跳转到类: {}", className)
                }
            }
            return true
        }

        logger.warn("未找到类: {}", className)
        return false
    }

    /**
     * 跳转到方法
     * @param project IntelliJ 项目
     * @param methodName 方法名
     * @return 是否成功跳转
     */
    fun navigateToMethod(project: Project, methodName: String): Boolean {
        var targetMethod: com.intellij.psi.PsiMethod? = null

        ApplicationManager.getApplication().runReadAction {
            val scope = GlobalSearchScope.projectScope(project)
            val pureName = methodName.removeSuffix("()")

            val methods = PsiShortNamesCache.getInstance(project)
                .getMethodsByName(pureName, scope)

            if (methods.isNotEmpty()) {
                targetMethod = methods[0]
            }
        }

        if (targetMethod != null) {
            ApplicationManager.getApplication().invokeLater {
                if (targetMethod?.isValid == true) {
                    targetMethod?.navigate(true)
                    logger.info("成功跳转到方法: {}", methodName)
                }
            }
            return true
        }

        logger.warn("未找到方法: {}", methodName)
        return false
    }

    /**
     * 跳转到指定位置（支持类、方法、字段、行号）
     * <p>
     * 支持的格式：
     * - ClassName.method - 方法
     * - ClassName:42 - 类的第42行
     * - ClassName - 类
     *
     * @param project IntelliJ 项目
     * @param location 位置描述
     * @return 是否成功跳转
     */
    fun navigateToLocation(project: Project, location: String): Boolean {
        val parts = location.split(":")
        val fullPath = parts[0].substringBefore('(').trim()
        val lineNumber = if (parts.size > 1) parts[1].toIntOrNull() else null

        // 优先尝试作为 FQN 类名跳转
        val fqnClass = tryFindClassByFQN(project, fullPath)
        if (fqnClass != null) {
            return navigateToClassWithLine(project, fqnClass, fullPath, lineNumber)
        }

        // 没有点号，可能是纯类名
        val lastDotIndex = fullPath.lastIndexOf('.')
        if (lastDotIndex == -1) {
            logger.debug("未检测到点号，尝试作为类名跳转: {}", fullPath)
            return navigateToClass(project, fullPath)
        }

        // 检查是否是文件扩展名
        val className = fullPath.substring(0, lastDotIndex)
        val methodName = fullPath.substring(lastDotIndex + 1)
        if (isFileExtension(methodName)) {
            logger.debug("检测到文件扩展名，作为类跳转: {}", className)
            return navigateToClass(project, className)
        }

        // 尝试跳转到方法或字段
        val (targetElement, fallbackClass) = findMethodOrField(project, className, methodName)

        // 执行导航
        return performNavigation(project, targetElement, fallbackClass, location, className, lineNumber)
    }

    /**
     * 尝试通过 FQN 查找类
     */
    private fun tryFindClassByFQN(project: Project, fullPath: String): PsiClass? {
        var targetClass: PsiClass? = null
        ApplicationManager.getApplication().runReadAction {
            val scope = GlobalSearchScope.allScope(project)
            targetClass = JavaPsiFacade.getInstance(project).findClass(fullPath, scope)
        }
        return targetClass
    }

    /**
     * 跳转到类（支持行号）
     */
    private fun navigateToClassWithLine(
        project: Project,
        targetClass: PsiClass,
        fullPath: String,
        lineNumber: Int?
    ): Boolean {
        ApplicationManager.getApplication().invokeLater {
            if (!targetClass.isValid) return@invokeLater

            if (lineNumber != null && lineNumber > 0) {
                val file = targetClass.containingFile?.virtualFile
                if (file != null) {
                    OpenFileDescriptor(project, file, lineNumber - 1, 0).navigate(true)
                    logger.info("成功跳转到类 {} 的第 {} 行", fullPath, lineNumber)
                }
            } else {
                targetClass.navigate(true)
                logger.info("成功跳转到类: {}", fullPath)
            }
        }
        return true
    }

    /**
     * 检查是否为文件扩展名
     */
    private fun isFileExtension(name: String): Boolean {
        val fileExtensions = setOf(
            "java", "kt", "class", "groovy", "scala", "xml", "json",
            "md", "yml", "yaml", "properties", "txt", "sql"
        )
        return name.lowercase() in fileExtensions
    }

    /**
     * 查找方法或字段
     * @return Pair<目标元素, 备选类>
     */
    private fun findMethodOrField(
        project: Project,
        className: String,
        methodName: String
    ): Pair<PsiElement?, PsiClass?> {
        var targetElement: PsiElement? = null
        var fallbackClass: PsiClass? = null

        ApplicationManager.getApplication().runReadAction {
            val scope = GlobalSearchScope.allScope(project)

            // 1. 尝试查找方法
            targetElement = findMethod(project, scope, className, methodName)

            // 2. 如果方法没找到，尝试查找字段
            if (targetElement == null) {
                targetElement = findField(project, scope, className, methodName)
            }

            // 3. 设置备选类
            val member = targetElement as? PsiMember
            if (member != null) {
                fallbackClass = member.containingClass
            }

            // 4. 如果仍然没找到，尝试查找类作为备选
            if (targetElement == null && fallbackClass == null) {
                fallbackClass = findClassByName(project, scope, className)
            }
        }

        return Pair(targetElement, fallbackClass)
    }

    /**
     * 查找方法
     */
    private fun findMethod(
        project: Project,
        scope: GlobalSearchScope,
        className: String,
        methodName: String
    ): PsiElement? {
        val allMethods = PsiShortNamesCache.getInstance(project)
            .getMethodsByName(methodName, scope)
            .toList()

        if (allMethods.isEmpty()) return null

        // 唯一匹配
        if (allMethods.size == 1) return allMethods[0]

        // 多个匹配：过滤和排序
        val projectMethods = allMethods.filter {
            val vFile = it.containingFile?.virtualFile
            vFile != null && GlobalSearchScope.projectScope(project).contains(vFile)
        }
        val methodsToCheck = if (projectMethods.isNotEmpty()) projectMethods else allMethods

        // 尝试精确匹配类名
        val matchedMethods = matchMethodsByClassName(methodsToCheck, className)
        if (matchedMethods.isNotEmpty()) return matchedMethods[0]

        // 尝试继承关系匹配
        val inheritanceMatches = matchMethodsByInheritance(methodsToCheck, project, scope, className)
        if (inheritanceMatches.isNotEmpty()) return inheritanceMatches[0]

        // 兜底：返回第一个方法（优先项目源码）
        return methodsToCheck.firstOrNull()
    }

    /**
     * 按类名匹配方法
     */
    private fun matchMethodsByClassName(
        methods: List<com.intellij.psi.PsiMethod>,
        className: String
    ): List<com.intellij.psi.PsiMethod> {
        val capitalizedClassName = className.capitalizeFirstChar()

        return methods.filter {
            val cls = it.containingClass
            cls?.name == className || cls?.qualifiedName == className ||
            cls?.name == capitalizedClassName || cls?.qualifiedName == capitalizedClassName ||
            (cls?.name == "Companion" && (
                cls.containingClass?.name == className ||
                cls.containingClass?.qualifiedName == className ||
                cls.containingClass?.name == capitalizedClassName ||
                cls.containingClass?.qualifiedName == capitalizedClassName
            ))
        }
    }

    /**
     * 按继承关系匹配方法
     */
    private fun matchMethodsByInheritance(
        methods: List<com.intellij.psi.PsiMethod>,
        project: Project,
        scope: GlobalSearchScope,
        className: String
    ): List<com.intellij.psi.PsiMethod> {
        val resolvedClasses = resolveClasses(project, scope, className)
        if (resolvedClasses.isEmpty()) return emptyList()

        return methods.filter { method ->
            val methodClass = method.containingClass
            methodClass != null && resolvedClasses.any { reqClass ->
                reqClass.isInheritor(methodClass, true)
            }
        }
    }

    /**
     * 解析类（FQN + ShortName + 大写变体）
     */
    private fun resolveClasses(
        project: Project,
        scope: GlobalSearchScope,
        className: String
    ): List<PsiClass> {
        val resolvedClasses = mutableListOf<PsiClass>()
        val capitalizedClassName = className.capitalizeFirstChar()

        // 尝试 FQN
        val fqnClass = JavaPsiFacade.getInstance(project).findClass(className, scope)
        if (fqnClass != null) resolvedClasses.add(fqnClass)

        val fqnClassCap = JavaPsiFacade.getInstance(project).findClass(capitalizedClassName, scope)
        if (fqnClassCap != null && fqnClassCap != fqnClass) resolvedClasses.add(fqnClassCap)

        // 尝试 ShortName
        if (resolvedClasses.isEmpty()) {
            resolvedClasses.addAll(
                PsiShortNamesCache.getInstance(project).getClassesByName(className, scope)
            )
            if (className != capitalizedClassName) {
                resolvedClasses.addAll(
                    PsiShortNamesCache.getInstance(project).getClassesByName(capitalizedClassName, scope)
                )
            }
        }

        return resolvedClasses
    }

    /**
     * 查找字段
     */
    private fun findField(
        project: Project,
        scope: GlobalSearchScope,
        className: String,
        methodName: String
    ): com.intellij.psi.PsiField? {
        val propertyName = extractPropertyName(methodName)
        val allFields = PsiShortNamesCache.getInstance(project).getFieldsByName(propertyName, scope)

        if (allFields.isEmpty()) return null

        return if (allFields.size == 1) {
            allFields[0]
        } else {
            allFields.firstOrNull { it.containingClass?.name == className }
        }
    }

    /**
     * 从方法名提取属性名
     */
    private fun extractPropertyName(methodName: String): String = when {
        methodName.startsWith("get") && methodName.length > 3 ->
            methodName.substring(3).replaceFirstChar { it.lowercase() }
        methodName.startsWith("is") && methodName.length > 2 ->
            methodName.substring(2).replaceFirstChar { it.lowercase() }
        else -> methodName
    }

    /**
     * 按名称查找类
     */
    private fun findClassByName(project: Project, scope: GlobalSearchScope, className: String): PsiClass? {
        val fqnClass = JavaPsiFacade.getInstance(project).findClass(className, scope)
        if (fqnClass != null) return fqnClass

        val classes = PsiShortNamesCache.getInstance(project).getClassesByName(className, scope)
        if (classes.isEmpty()) return null

        // 优先源码类
        return classes.maxByOrNull { psiClass ->
            val vFile = psiClass.containingFile?.virtualFile
            vFile != null && GlobalSearchScope.projectScope(project).contains(vFile)
        }
    }

    /**
     * 执行导航
     */
    private fun performNavigation(
        project: Project,
        targetElement: PsiElement?,
        fallbackClass: PsiClass?,
        location: String,
        className: String,
        lineNumber: Int?
    ): Boolean {
        ApplicationManager.getApplication().invokeLater {
            if (lineNumber != null && lineNumber > 0 && fallbackClass?.isValid == true) {
                // 跳转到行号
                val file = fallbackClass.containingFile?.virtualFile
                if (file != null) {
                    OpenFileDescriptor(project, file, lineNumber - 1, 0).navigate(true)
                    logger.info("成功跳转到第 {} 行", lineNumber)
                }
            } else {
                // 跳转到目标元素或类
                if (targetElement?.isValid == true) {
                    (targetElement as? com.intellij.pom.Navigatable)?.navigate(true)
                    logger.info("成功跳转到方法/字段: {}", location)
                } else if (fallbackClass?.isValid == true) {
                    fallbackClass.navigate(true)
                    logger.info("成功跳转到类: {}", className)
                }
            }
        }

        val success = targetElement != null || fallbackClass != null
        if (!success) {
            logger.warn("无法跳转到: {}", location)
        }
        return success
    }

    /**
     * 首字母大写
     */
    private fun String.capitalizeFirstChar(): String = replaceFirstChar { char ->
        if (char.isLowerCase()) char.titlecase() else char.toString()
    }
}
