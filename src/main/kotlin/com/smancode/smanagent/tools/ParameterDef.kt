package com.smancode.smanagent.tools

/**
 * 参数定义
 */
class ParameterDef {

    /**
     * 参数名
     */
    var name: String? = null

    /**
     * 参数类型
     */
    var type: Class<*>? = null

    /**
     * 是否必需
     */
    var isRequired: Boolean = false

    /**
     * 参数描述
     */
    var description: String? = null

    /**
     * 默认值
     */
    var defaultValue: Any? = null

    constructor()

    constructor(name: String, type: Class<*>, required: Boolean, description: String?) {
        this.name = name
        this.type = type
        this.isRequired = required
        this.description = description
    }

    constructor(name: String, type: Class<*>, required: Boolean, description: String?, defaultValue: Any?) :
        this(name, type, required, description) {
        this.defaultValue = defaultValue
    }
}
