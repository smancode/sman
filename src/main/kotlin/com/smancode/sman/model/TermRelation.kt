package com.smancode.sman.model

/**
 * 术语关系
 */
class TermRelation {

    var fromTerm: String? = null

    var relation: String? = null

    var toTerm: String? = null

    var confidence: Double? = null

    var evidence: CodeElement? = null

    constructor()

    constructor(fromTerm: String, relation: String, toTerm: String) {
        this.fromTerm = fromTerm
        this.relation = relation
        this.toTerm = toTerm
    }
}
