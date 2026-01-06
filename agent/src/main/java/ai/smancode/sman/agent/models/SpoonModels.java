package ai.smancode.sman.agent.models;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Spoon AST 分析数据模型
 */
public class SpoonModels {

    /**
     * 类信息
     */
    public static class ClassInfo {
        private String className;
        private String relativePath;
        private String type;
        private List<MethodInfo> methods;
        private List<String> fields;
        private String superClass;
        private List<String> interfaces;
        private String sourceCode;
        private String classComment;         // 类注释（Javadoc）
        private List<String> annotations;     // 类注解

        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }

        public String getRelativePath() { return relativePath; }
        public void setRelativePath(String relativePath) { this.relativePath = relativePath; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public List<MethodInfo> getMethods() { return methods; }
        public void setMethods(List<MethodInfo> methods) { this.methods = methods; }

        public List<String> getFields() { return fields; }
        public void setFields(List<String> fields) { this.fields = fields; }

        public String getSuperClass() { return superClass; }
        public void setSuperClass(String superClass) { this.superClass = superClass; }

        public List<String> getInterfaces() { return interfaces; }
        public void setInterfaces(List<String> interfaces) { this.interfaces = interfaces; }

        public String getSourceCode() { return sourceCode; }
        public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }

        public String getClassComment() { return classComment; }
        public void setClassComment(String classComment) { this.classComment = classComment; }

        public List<String> getAnnotations() { return annotations; }
        public void setAnnotations(List<String> annotations) { this.annotations = annotations; }
    }

    /**
     * 方法信息
     */
    public static class MethodInfo {
        private String name;
        private String returnType;
        private List<String> parameters;
        private List<String> modifiers;
        private String sourceCode;
        private String comment;              // 方法注释（Javadoc）
        private List<String> annotations;   // 方法注解

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getReturnType() { return returnType; }
        public void setReturnType(String returnType) { this.returnType = returnType; }

        public List<String> getParameters() { return parameters; }
        public void setParameters(List<String> parameters) { this.parameters = parameters; }

        public List<String> getModifiers() { return modifiers; }
        public void setModifiers(List<String> modifiers) { this.modifiers = modifiers; }

        public String getSourceCode() { return sourceCode; }
        public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }

        public String getComment() { return comment; }
        public void setComment(String comment) { this.comment = comment; }

        public List<String> getAnnotations() { return annotations; }
        public void setAnnotations(List<String> annotations) { this.annotations = annotations; }
    }

    /**
     * 项目分析结果
     */
    public static class ProjectAnalysisResult {
        private String projectKey;
        private int totalClasses;
        private int totalMethods;
        private List<String> businessModules;
        private long analysisTime;

        public String getProjectKey() { return projectKey; }
        public void setProjectKey(String projectKey) { this.projectKey = projectKey; }

        public int getTotalClasses() { return totalClasses; }
        public void setTotalClasses(int totalClasses) { this.totalClasses = totalClasses; }

        public int getTotalMethods() { return totalMethods; }
        public void setTotalMethods(int totalMethods) { this.totalMethods = totalMethods; }

        public List<String> getBusinessModules() { return businessModules; }
        public void setBusinessModules(List<String> businessModules) { this.businessModules = businessModules; }

        public long getAnalysisTime() { return analysisTime; }
        public void setAnalysisTime(long analysisTime) { this.analysisTime = analysisTime; }
    }

    /**
     * 类路径映射结果
     */
    public static class ClassPathMappingResult {
        private String projectKey;
        private Map<String, String> classToPathMapping;
        private int totalClasses;
        private List<String> errors;
        private int errorCount;
        private boolean completed;

        public ClassPathMappingResult() {}

        public String getProjectKey() { return projectKey; }
        public void setProjectKey(String projectKey) { this.projectKey = projectKey; }

        public Map<String, String> getClassToPathMapping() { return classToPathMapping; }
        public void setClassToPathMapping(Map<String, String> classToPathMapping) {
            this.classToPathMapping = classToPathMapping;
        }

        public int getTotalClasses() { return totalClasses; }
        public void setTotalClasses(int totalClasses) { this.totalClasses = totalClasses; }

        public List<String> getErrors() { return errors; }
        public void setErrors(List<String> errors) { this.errors = errors; }

        public int getErrorCount() { return errorCount; }
        public void setErrorCount(int errorCount) { this.errorCount = errorCount; }

        public boolean isCompleted() { return completed; }
        public void setCompleted(boolean completed) { this.completed = completed; }
    }

    /**
     * 数据流分析结果
     */
    public static class DataFlowAnalysis {
        private String className;
        private String methodName;
        private List<DataFlowInfo> assignments;
        private List<DataFlowInfo> fieldAccesses;
        private List<DataFlowInfo> parameterFlows;
        private List<DataFlowInfo> returnFlows;

        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }

        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }

        public List<DataFlowInfo> getAssignments() { return assignments; }
        public void setAssignments(List<DataFlowInfo> assignments) { this.assignments = assignments; }

        public List<DataFlowInfo> getFieldAccesses() { return fieldAccesses; }
        public void setFieldAccesses(List<DataFlowInfo> fieldAccesses) { this.fieldAccesses = fieldAccesses; }

        public List<DataFlowInfo> getParameterFlows() { return parameterFlows; }
        public void setParameterFlows(List<DataFlowInfo> parameterFlows) { this.parameterFlows = parameterFlows; }

        public List<DataFlowInfo> getReturnFlows() { return returnFlows; }
        public void setReturnFlows(List<DataFlowInfo> returnFlows) { this.returnFlows = returnFlows; }
    }

    /**
     * 数据流信息
     */
    public static class DataFlowInfo {
        private String variable;
        private String type;
        private String flow;

        public String getVariable() { return variable; }
        public void setVariable(String variable) { this.variable = variable; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getFlow() { return flow; }
        public void setFlow(String flow) { this.flow = flow; }
    }
}
