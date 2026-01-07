package ai.smancode.sman.agent.ast;

import ai.smancode.sman.agent.config.ProjectConfigService;
import ai.smancode.sman.agent.models.SpoonModels.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtComment;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.code.CtJavaDoc;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Spoon AST 分析服务
 *
 * 功能：使用 Spoon 分析 Java 代码结构
 * 场景：提取类信息、方法签名等（用于向量索引生成）
 *
 * 注意：
 * - 此服务仅用于向量索引生成等批量分析任务
 * - 实时代码分析（如 call_chain）通过 IDE Plugin 的 PSI API 完成
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
@Service
public class SpoonAstService {

    private static final Logger logger = LoggerFactory.getLogger(SpoonAstService.class);

    @Autowired
    private ProjectConfigService projectConfigService;

    /**
     * 获取类信息（使用 Spoon AST 分析）
     *
     * @param projectKey 项目标识
     * @param className 类名（简单名称）
     * @return 类信息
     */
    public ClassInfo getClassInfo(String projectKey, String className) {
        try {
            // 获取项目路径
            String projectPath = projectConfigService.getProjectPath(projectKey);

            // 查找目标 Java 文件
            String javaFile = findJavaFile(projectPath, className);
            if (javaFile == null) {
                logger.warn("未找到 Java 文件: className={}", className);
                return null;
            }

            // 使用 Spoon 分析单个文件
            Launcher launcher = new Launcher();
            launcher.addInputResource(javaFile);
            launcher.getEnvironment().setNoClasspath(true);
            launcher.getEnvironment().setComplianceLevel(21);

            CtModel model = launcher.buildModel();

            // 查找目标类型（支持 class 和 interface）
            CtType<?> targetType = null;
            for (CtType<?> type : model.getAllTypes()) {
                if (type.getSimpleName().equals(className)) {
                    targetType = type;
                    break;
                }
            }

            if (targetType == null) {
                logger.warn("未找到类: {}", className);
                return null;
            }

            // 构建 ClassInfo
            ClassInfo classInfo = new ClassInfo();
            classInfo.setClassName(targetType.getSimpleName());
            classInfo.setRelativePath(getRelativePath(targetType, projectPath));

            // 判断类型
            if (targetType instanceof CtClass) {
                CtClass<?> ctClass = (CtClass<?>) targetType;
                classInfo.setType(ctClass.isInterface() ? "interface" : "class");
            } else if (targetType instanceof spoon.reflect.declaration.CtInterface) {
                classInfo.setType("interface");
            } else {
                classInfo.setType("other");
            }

            // 🔥 提取类注释（Javadoc）
            String classComment = extractJavadoc(targetType);
            classInfo.setClassComment(classComment);

            // 🔥 提取类注解
            List<String> classAnnotations = new ArrayList<>();
            for (CtAnnotation<?> annotation : targetType.getAnnotations()) {
                classAnnotations.add("@" + annotation.getAnnotationType().getSimpleName());
            }
            classInfo.setAnnotations(classAnnotations);

            // 提取父类（只有 class 才有）
            if (targetType instanceof CtClass) {
                CtClass<?> ctClass = (CtClass<?>) targetType;
                CtTypeReference<?> superClass = ctClass.getSuperclass();
                if (superClass != null) {
                    classInfo.setSuperClass(superClass.getSimpleName());
                }
            }

            // 提取接口
            List<String> interfaces = new ArrayList<>();
            if (targetType instanceof CtClass) {
                CtClass<?> ctClass = (CtClass<?>) targetType;
                for (CtTypeReference<?> iface : ctClass.getSuperInterfaces()) {
                    interfaces.add(iface.getSimpleName());
                }
            }
            classInfo.setInterfaces(interfaces);

            // 提取方法
            List<MethodInfo> methods = new ArrayList<>();
            for (CtMethod<?> method : targetType.getMethods()) {
                MethodInfo methodInfo = new MethodInfo();
                methodInfo.setName(method.getSimpleName());
                methodInfo.setReturnType(method.getType().getSimpleName());

                // 🔥 提取方法注释（Javadoc）
                String methodComment = extractJavadoc(method);
                methodInfo.setComment(methodComment);

                // 🔥 提取方法注解
                List<String> methodAnnotations = new ArrayList<>();
                for (CtAnnotation<?> annotation : method.getAnnotations()) {
                    methodAnnotations.add("@" + annotation.getAnnotationType().getSimpleName());
                }
                methodInfo.setAnnotations(methodAnnotations);

                // 🔥 提取方法源码
                String methodSourceCode = method.getBody() != null ? method.getBody().toString() : "";
                methodInfo.setSourceCode(methodSourceCode);

                // 提取参数
                List<String> parameters = new ArrayList<>();
                for (CtParameter<?> param : method.getParameters()) {
                    parameters.add(param.getType().getSimpleName());
                }
                methodInfo.setParameters(parameters);

                // 提取修饰符
                List<String> modifiers = new ArrayList<>();
                if (method.isPublic()) modifiers.add("public");
                else if (method.isPrivate()) modifiers.add("private");
                else if (method.isProtected()) modifiers.add("protected");
                if (method.isStatic()) modifiers.add("static");
                methodInfo.setModifiers(modifiers);

                methods.add(methodInfo);
            }
            classInfo.setMethods(methods);

            // 提取字段
            List<String> fields = new ArrayList<>();
            if (targetType instanceof CtClass) {
                CtClass<?> ctClass = (CtClass<?>) targetType;
                for (CtField<?> field : ctClass.getFields()) {
                    StringBuilder fieldStr = new StringBuilder();
                    if (field.isPublic()) fieldStr.append("public ");
                    else if (field.isPrivate()) fieldStr.append("private ");
                    else if (field.isProtected()) fieldStr.append("protected ");
                    if (field.isStatic()) fieldStr.append("static ");
                    fieldStr.append(field.getType().getSimpleName()).append(" ").append(field.getSimpleName());
                    fields.add(fieldStr.toString());
                }
            }
            classInfo.setFields(fields);

            logger.info("成功提取类信息: className={}, methods={}, fields={}, classComment={}, hasMethodComment={}",
                    className, methods.size(), fields.size(),
                    classComment != null && !classComment.isEmpty(),
                    methods.stream().anyMatch(m -> m.getComment() != null && !m.getComment().isEmpty()));

            return classInfo;

        } catch (Exception e) {
            logger.error("获取类信息失败: className={}, error={}", className, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 提取 Javadoc 注释
     */
    private String extractJavadoc(CtElement element) {
        try {
            CtElement docCommentHolder = element;
            String javadoc = docCommentHolder.getComments().stream()
                    .filter(c -> c instanceof CtJavaDoc)
                    .map(CtComment::getContent)
                    .findFirst()
                    .orElse(null);

            if (javadoc != null && !javadoc.isEmpty()) {
                // 清理 Javadoc 格式（去除多余的 * 和空格）
                return javadoc.replaceAll("(?m)^\\s*\\*", "").trim();
            }
            return null;
        } catch (Exception e) {
            // 忽略注释提取失败
            return null;
        }
    }

    /**
     * 查找 Java 文件（支持多模块项目）
     */
    private String findJavaFile(String projectPath, String className) {
        try {
            // 策略1: 扫描根目录的 src/main/java
            File rootSrcMainJava = new File(projectPath, "src/main/java");
            if (rootSrcMainJava.exists()) {
                String found = searchFileInDirectory(rootSrcMainJava, className + ".java");
                if (found != null) {
                    return found;
                }
            }

            // 策略2: 扫描所有子模块的 */src/main/java
            File projectDir = new File(projectPath);
            File[] subDirs = projectDir.listFiles(File::isDirectory);
            if (subDirs != null) {
                for (File subDir : subDirs) {
                    File moduleSrc = new File(subDir, "src/main/java");
                    if (moduleSrc.exists()) {
                        String found = searchFileInDirectory(moduleSrc, className + ".java");
                        if (found != null) {
                            return found;
                        }
                    }
                }
            }

            logger.warn("未找到 Java 文件: className={}, projectPath={}", className, projectPath);
            return null;

        } catch (Exception e) {
            logger.error("查找 Java 文件失败: className={}, error={}", className, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 在目录中递归搜索文件
     */
    private String searchFileInDirectory(File directory, String fileName) {
        try {
            // 使用 Files.walk 提高效率
            Path dirPath = directory.toPath();
            try (var stream = java.nio.file.Files.walk(dirPath)) {
                Optional<Path> found = stream
                    .filter(p -> p.toFile().isFile())
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .findFirst();

                if (found.isPresent()) {
                    return found.get().toString();
                }
            }
            return null;
        } catch (Exception e) {
            logger.error("搜索目录失败: directory={}, fileName={}, error={}", directory, fileName, e.getMessage());
            return null;
        }
    }

    /**
     * 获取类的相对路径
     */
    private String getRelativePath(CtType<?> ctType, String projectPath) {
        String fullPath = ctType.getPosition().getFile().toString();
        if (fullPath.startsWith(projectPath)) {
            return fullPath.substring(projectPath.length() + 1);
        }
        return fullPath;
    }

    /**
     * 分析项目
     *
     * @param projectPath 项目路径
     * @param projectKey 项目标识
     * @return 项目分析结果
     */
    public ProjectAnalysisResult analyzeProject(String projectPath, String projectKey) {
        logger.info("分析项目: projectPath={}, projectKey={}", projectPath, projectKey);

        long startTime = System.currentTimeMillis();

        try {
            // 使用 Spoon 分析项目
            Launcher launcher = new Launcher();
            launcher.addInputResource(projectPath);
            launcher.getEnvironment().setNoClasspath(true);
            launcher.getEnvironment().setComplianceLevel(21);

            CtModel model = launcher.buildModel();

            // 统计信息
            int totalClasses = 0;
            int totalMethods = 0;

            for (CtType<?> type : model.getAllTypes()) {
                if (type instanceof CtClass) {
                    totalClasses++;
                    totalMethods += type.getMethods().size();
                }
            }

            ProjectAnalysisResult result = new ProjectAnalysisResult();
            result.setProjectKey(projectKey);
            result.setTotalClasses(totalClasses);
            result.setTotalMethods(totalMethods);
            result.setBusinessModules(new ArrayList<>());  // TODO: 识别业务模块
            result.setAnalysisTime(System.currentTimeMillis() - startTime);

            logger.info("项目分析完成: classes={}, methods={}, time={}ms",
                    totalClasses, totalMethods, result.getAnalysisTime());

            return result;

        } catch (Exception e) {
            logger.error("项目分析失败: {}", e.getMessage(), e);

            ProjectAnalysisResult result = new ProjectAnalysisResult();
            result.setProjectKey(projectKey);
            result.setAnalysisTime(System.currentTimeMillis() - startTime);

            return result;
        }
    }

    /**
     * 获取方法源码
     *
     * @param projectKey 项目标识
     * @param className 类名
     * @param methodName 方法名
     * @return 方法源码
     */
    public String getMethodSource(String projectKey, String className, String methodName) {
        logger.info("获取方法源码: className={}, methodName={}", className, methodName);

        // TODO: 实现实际的方法源码提取
        return "// Method source code for " + methodName + "\n" +
               "public void " + methodName + "() {\n" +
               "    // TODO: Implement\n" +
               "}\n";
    }

    /**
     * 构建类路径映射
     *
     * @param projectPath 项目路径
     * @param projectKey 项目标识
     * @return 类路径映射结果
     */
    public ClassPathMappingResult buildClassPathMapping(String projectPath, String projectKey) {
        logger.info("构建类路径映射: projectPath={}", projectPath);

        ClassPathMappingResult result = new ClassPathMappingResult();
        result.setProjectKey(projectKey);
        result.setTotalClasses(0);
        result.setClassToPathMapping(new java.util.HashMap<>());
        result.setErrors(new ArrayList<>());
        result.setErrorCount(0);
        result.setCompleted(true);

        // TODO: 实现实际的类路径映射构建
        return result;
    }
}
