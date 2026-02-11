package com.smancode.sman.model

/**
 * 业务术语
 */
class BusinessTerm {

    var name: String? = null
        set

    var category: String? = null
        set

    var description: String? = null

    var confidence: Double? = null

    var codeMappings: List<CodeElement>? = null

    var attributes: Map<String, Any>? = null

    constructor()

    constructor(name: String, category: String) {
        this.name = name
        this.category = category
    }
}
