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

        // 0. 优先判断 fullPath 本身是否就是全限定类名 (FQN)
        var targetClassDirect: PsiClass? = null
        ApplicationManager.getApplication().runReadAction {
            val scope = GlobalSearchScope.allScope(project)
            targetClassDirect = JavaPsiFacade.getInstance(project).findClass(fullPath, scope)
        }

        if (targetClassDirect != null) {
            ApplicationManager.getApplication().invokeLater {
                if (targetClassDirect?.isValid == true) {
                    if (lineNumber != null && lineNumber > 0) {
                        val file = targetClassDirect!!.containingFile?.virtualFile
                        if (file != null) {
                            OpenFileDescriptor(project, file, lineNumber - 1, 0).navigate(true)
                            logger.info("成功跳转到类 {} 的第 {} 行", fullPath, lineNumber)
                        }
                    } else {
                        targetClassDirect!!.navigate(true)
                        logger.info("成功跳转到类: {}", fullPath)
                    }
                }
            }
            return true
        }

        // 拆分 类名 和 方法名
        val lastDotIndex = fullPath.lastIndexOf('.')
        if (lastDotIndex == -1) {
            // 没有点号，可能是纯类名（如 FileFilterUtil），尝试直接跳转到类
            logger.info("未检测到点号，尝试作为类名跳转: {}", fullPath)
            return navigateToClass(project, fullPath)
        }

        val className = fullPath.substring(0, lastDotIndex)
        val methodName = fullPath.substring(lastDotIndex + 1)

        // 修复：如果 methodName 实际上是文件后缀，则直接作为类跳转
        val fileExtensions = setOf("java", "kt", "class", "groovy", "scala", "xml", "json", "md", "yml", "yaml", "properties", "txt", "sql")
        if (fileExtensions.contains(methodName.lowercase())) {
            logger.info("检测到文件扩展名，作为类跳转: {}", className)
            return navigateToClass(project, className)
        }

        var targetElement: PsiElement? = null
        var fallbackClass: PsiClass? = null

        ApplicationManager.getApplication().runReadAction {
            val scope = GlobalSearchScope.allScope(project)

            // === 策略核心：方法优先 ===

            // 1. 全网搜索方法
            val allMethodsArray = PsiShortNamesCache.getInstance(project).getMethodsByName(methodName, scope)
            val allMethods = allMethodsArray.toList()

            if (allMethods.isNotEmpty()) {
                if (allMethods.size == 1) {
                    // 唯一匹配 -> 直接命中
                    targetElement = allMethods[0]
                } else {
                    // 多个匹配 -> 类名过滤
                    // 优先找项目源码
                    val projectMethods = allMethods.filter {
                        val vFile = it.containingFile?.virtualFile
                        vFile != null && GlobalSearchScope.projectScope(project).contains(vFile)
                    }

                    val methodsToCheck = if (projectMethods.isNotEmpty()) projectMethods else allMethods

                    // 尝试匹配类名 (支持 ShortName 和 FQN)
                    val matchedMethods = methodsToCheck.filter {
                        val cls = it.containingClass
                        val capitalizedClassName = className.replaceFirstChar { char ->
                            if (char.isLowerCase()) char.titlecase() else char.toString()
                        }

                        cls?.name == className || cls?.qualifiedName == className ||
                        cls?.name == capitalizedClassName || cls?.qualifiedName == capitalizedClassName ||
                        (cls?.name == "Companion" && (
                            cls.containingClass?.name == className || cls.containingClass?.qualifiedName == className ||
                            cls.containingClass?.name == capitalizedClassName || cls.containingClass?.qualifiedName == capitalizedClassName
                        ))
                    }

                    if (matchedMethods.isNotEmpty()) {
                        targetElement = matchedMethods[0]
                    } else {
                        // 继承关系匹配
                        val resolvedClasses = mutableListOf<PsiClass>()
                        val fqnClass = JavaPsiFacade.getInstance(project).findClass(className, scope)
                        if (fqnClass != null) resolvedClasses.add(fqnClass)

                        val capitalizedClassName = className.replaceFirstChar { char ->
                            if (char.isLowerCase()) char.titlecase() else char.toString()
                        }
                        val fqnClassCap = JavaPsiFacade.getInstance(project).findClass(capitalizedClassName, scope)
                        if (fqnClassCap != null && fqnClassCap != fqnClass) resolvedClasses.add(fqnClassCap)

                        if (resolvedClasses.isEmpty()) {
                            resolvedClasses.addAll(PsiShortNamesCache.getInstance(project).getClassesByName(className, scope))
                            if (className != capitalizedClassName) {
                                resolvedClasses.addAll(PsiShortNamesCache.getInstance(project).getClassesByName(capitalizedClassName, scope))
                            }
                        }

                        if (resolvedClasses.isNotEmpty()) {
                            val inheritanceMatches = methodsToCheck.filter { method ->
                                val methodClass = method.containingClass
                                methodClass != null && resolvedClasses.any { reqClass ->
                                    reqClass.isInheritor(methodClass, true)
                                }
                            }
                            if (inheritanceMatches.isNotEmpty()) {
                                targetElement = inheritanceMatches[0]
                            }
                        }

                        // 兜底策略：如果还是没找到，返回找到的第一个方法 (优先项目源码)
                        if (targetElement == null && methodsToCheck.isNotEmpty()) {
                            targetElement = methodsToCheck[0]
                        }
                    }
                }
            }

            // 2. 如果方法没找到，尝试搜索字段/属性
            if (targetElement == null) {
                val propertyName = if (methodName.startsWith("get") && methodName.length > 3) {
                    methodName.substring(3).replaceFirstChar { it.lowercase() }
                } else if (methodName.startsWith("is") && methodName.length > 2) {
                    methodName.substring(2).replaceFirstChar { it.lowercase() }
                } else {
                    methodName
                }

                val allFields = PsiShortNamesCache.getInstance(project).getFieldsByName(propertyName, scope)
                if (allFields.isNotEmpty()) {
                    if (allFields.size == 1) {
                        targetElement = allFields[0]
                    } else {
                        val matchedFields = allFields.filter { it.containingClass?.name == className }
                        if (matchedFields.isNotEmpty()) targetElement = matchedFields[0]
                    }
                }
            }

            // 3. 如果目标元素已找到，设置 fallbackClass
            val finalTarget = targetElement
            if (finalTarget is PsiMember) {
                fallbackClass = finalTarget.containingClass
            }

            // 4. 如果仍然没找到，尝试仅查找类作为兜底
            if (targetElement == null && fallbackClass == null) {
                val fqnClass = JavaPsiFacade.getInstance(project).findClass(className, scope)
                if (fqnClass != null) {
                    fallbackClass = fqnClass
                } else {
                    val classes = PsiShortNamesCache.getInstance(project).getClassesByName(className, scope)
                    if (classes.isNotEmpty()) {
                        // 优先源码类
                        val sortedClasses = classes.sortedByDescending { psiClass ->
                            val vFile = psiClass.containingFile?.virtualFile
                            vFile != null && GlobalSearchScope.projectScope(project).contains(vFile)
                        }
                        fallbackClass = sortedClasses[0]
                    }
                }
            }
        }

        // 在 EDT 中执行导航
        ApplicationManager.getApplication().invokeLater {
            if (lineNumber != null && lineNumber > 0) {
                // 1. 优先跳转到行号
                if (fallbackClass?.isValid == true) {
                    val file = fallbackClass!!.containingFile?.virtualFile
                    if (file != null) {
                        OpenFileDescriptor(project, file, lineNumber - 1, 0).navigate(true)
                        logger.info("成功跳转到第 {} 行", lineNumber)
                    }
                }
            } else {
                // 2. 跳转到目标元素
                if (targetElement?.isValid == true) {
                    (targetElement as? com.intellij.pom.Navigatable)?.navigate(true)
                    logger.info("成功跳转到方法/字段: {}", location)
                } else if (fallbackClass?.isValid == true) {
                    fallbackClass!!.navigate(true)
                    logger.info("成功跳转到类（兜底）: {}", className)
                }
            }
        }

        val success = targetElement != null || fallbackClass != null
        if (!success) {
            logger.warn("无法跳转到: {}", location)
        }
        return success
    }
}
