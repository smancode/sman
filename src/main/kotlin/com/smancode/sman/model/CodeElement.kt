package com.smancode.sman.model

/**
 * 代码元素
 */
class CodeElement {

    var type: String? = null

    var name: String? = null

    var signature: String? = null

    var filePath: String? = null

    var startLine: Int? = null

    var endLine: Int? = null

    var code: String? = null

    constructor()

    constructor(type: String, name: String, signature: String, filePath: String?, startLine: Int?) {
        this.type = type
        this.name = name
        this.signature = signature
        this.filePath = filePath
        this.startLine = startLine
    }
}
