package com.smancode.smanagent.tools;

/**
 * 参数定义
 */
public class ParameterDef {

    /**
     * 参数名
     */
    private String name;

    /**
     * 参数类型
     */
    private Class<?> type;

    /**
     * 是否必需
     */
    private boolean required;

    /**
     * 参数描述
     */
    private String description;

    /**
     * 默认值
     */
    private Object defaultValue;

    public ParameterDef() {
    }

    public ParameterDef(String name, Class<?> type, boolean required, String description) {
        this.name = name;
        this.type = type;
        this.required = required;
        this.description = description;
    }

    public ParameterDef(String name, Class<?> type, boolean required, String description, Object defaultValue) {
        this(name, type, required, description);
        this.defaultValue = defaultValue;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Class<?> getType() {
        return type;
    }

    public void setType(Class<?> type) {
        this.type = type;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
    }
}
