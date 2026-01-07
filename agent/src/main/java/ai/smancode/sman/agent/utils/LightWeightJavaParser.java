package ai.smancode.sman.agent.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量级 Java 文件解析器（使用正则表达式）
 *
 * 用途：替代 Spoon AST，快速提取 Java 类的结构信息
 * 适用场景：向量索引生成、代码搜索
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
public class LightWeightJavaParser {

    /**
     * 解析 Java 类信息
     *
     * @param content Java 文件内容
     * @return 类信息
     */
    public static ClassInfo parse(String content) {
        ClassInfo info = new ClassInfo();

        // 提取类名
        info.className = extractClassName(content);

        // 提取父类
        info.superClass = extractSuperClass(content);

        // 提取接口
        info.interfaces = extractInterfaces(content);

        // 提取方法签名
        info.methodSignatures = extractMethodSignatures(content);

        // 提取字段
        info.fields = extractFields(content);

        return info;
    }

    /**
     * 提取类名、接口名、枚举名或注解名
     */
    private static String extractClassName(String content) {
        // 匹配 class Xxx、interface Xxx、enum Xxx、@interface Xxx
        Pattern pattern = Pattern.compile(
            "(?:public\\s+)?(?:abstract\\s+)?(?:final\\s+)?" +
            "(class|interface|enum|@interface)\\s+(\\w+)"
        );
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(2);  // 返回名称（不是类型关键字）
        }
        return null;
    }

    /**
     * 提取父类或父接口
     */
    private static String extractSuperClass(String content) {
        // 匹配 class Xxx extends Yyy 或 interface Xxx extends Yyy
        Pattern pattern = Pattern.compile("(class|interface|enum)\\s+\\w+\\s+extends\\s+(\\w+)");
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(2);  // 返回父类/父接口名称
        }
        return null;
    }

    /**
     * 提取实现的接口
     */
    private static List<String> extractInterfaces(String content) {
        List<String> interfaces = new ArrayList<>();
        // 匹配 implements Xxx, Yyy
        Pattern pattern = Pattern.compile("implements\\s+([\\w,\\s<>.]+)");
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            String implementsStr = matcher.group(1);
            // 处理泛型接口，如 List<String>, Map<K, V>
            String[] parts = implementsStr.split(",");
            for (String part : parts) {
                String iface = part.trim();
                // 简化处理：只取接口名，忽略泛型参数
                if (iface.contains("<")) {
                    iface = iface.substring(0, iface.indexOf("<"));
                }
                if (!iface.isEmpty()) {
                    interfaces.add(iface);
                }
            }
        }
        return interfaces;
    }

    /**
     * 提取方法签名
     */
    private static List<String> extractMethodSignatures(String content) {
        List<String> methods = new ArrayList<>();
        // 匹配方法定义：修饰符 返回类型 方法名(参数)
        // 例如: public void test(String name, int age)
        Pattern pattern = Pattern.compile(
            "(?:public|private|protected|static|final|synchronized|native|abstract)\\s+" +
            "(?:<[^>]+>\\s+)?" +  // 泛型返回类型
            "(\\w+(?:<[^>]+>)?)\\s+" +  // 返回类型
            "(\\w+)\\s*" +  // 方法名
            "\\(([^)]*)\\)"  // 参数列表
        );
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String returnType = matcher.group(1);
            String methodName = matcher.group(2);
            String parameters = matcher.group(3).trim();

            StringBuilder signature = new StringBuilder();
            signature.append(returnType).append(" ");
            signature.append(methodName).append("(");
            if (!parameters.isEmpty()) {
                signature.append(parameters);
            }
            signature.append(")");

            methods.add(signature.toString());
        }
        return methods;
    }

    /**
     * 提取字段
     */
    private static List<String> extractFields(String content) {
        List<String> fields = new ArrayList<>();
        // 匹配字段定义：修饰符 类型 字段名
        Pattern pattern = Pattern.compile(
            "(?:public|private|protected|static|final|transient|volatile)\\s+" +
            "(\\w+(?:<[^>]+>)?)\\s+" +  // 类型
            "(\\w+)" +  // 字段名
            "\\s*;"  // 分号
        );
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String type = matcher.group(1);
            String fieldName = matcher.group(2);
            fields.add(type + " " + fieldName);
        }
        return fields;
    }

    /**
     * 类信息结构
     */
    public static class ClassInfo {
        public String className;
        public String superClass;
        public List<String> interfaces = new ArrayList<>();
        public List<String> methodSignatures = new ArrayList<>();
        public List<String> fields = new ArrayList<>();

        public String getClassName() {
            return className;
        }

        public String getSuperClass() {
            return superClass;
        }

        public List<String> getInterfaces() {
            return interfaces;
        }

        public List<String> getMethodSignatures() {
            return methodSignatures;
        }

        public List<String> getFields() {
            return fields;
        }
    }
}
