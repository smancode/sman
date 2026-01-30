package com.smancode.smanagent.analysis.database

import com.smancode.smanagent.analysis.database.model.DbEntity
import org.slf4j.LoggerFactory

/**
 * 业务概念推断服务
 *
 * 基于主键推断业务含义（而非表名）
 */
class BusinessConceptInferenceService {

    private val logger = LoggerFactory.getLogger(BusinessConceptInferenceService::class.java)

    companion object {
        /**
         * 主键到业务概念的映射
         */
        private val PRIMARY_KEY_CONCEPTS = mapOf(
            "loan_id" to "借据",
            "customer_id" to "客户",
            "contract_id" to "合同",
            "repayment_plan_id" to "还款计划",
            "account_id" to "账户",
            "transaction_id" to "交易",
            "order_id" to "订单",
            "product_id" to "产品",
            "user_id" to "用户",
            "bill_id" to "账单",
            "payment_id" to "支付",
            "invoice_id" to "发票",
            "receipt_id" to "收据",
            "voucher_id" to "凭证",
            "journal_id" to "流水",
            "ledger_id" to "账本"
        )

        /**
         * 表名前缀到业务概念的映射
         */
        private val TABLE_PREFIX_CONCEPTS = mapOf(
            "t_loan" to "借据",
            "t_customer" to "客户",
            "t_contract" to "合同",
            "t_repayment" to "还款",
            "t_account" to "账户",
            "t_transaction" to "交易",
            "t_order" to "订单",
            "t_product" to "产品",
            "t_user" to "用户",
            "t_bill" to "账单",
            "t_payment" to "支付",
            "t_invoice" to "发票",
            "t_receipt" to "收据",
            "t_voucher" to "凭证",
            "t_journal" to "流水",
            "t_ledger" to "账本",
            "acct_" to "账户",
            "sys_" to "系统",
            "cfg_" to "配置",
            "dict_" to "字典"
        )
    }

    /**
     * 推断业务概念
     *
     * @param dbEntity 数据库实体
     * @return 业务概念
     */
    fun inferBusinessConcept(dbEntity: DbEntity): String {
        // 1. 主键优先
        dbEntity.primaryKey?.let { primaryKey ->
            PRIMARY_KEY_CONCEPTS[primaryKey]?.let { return it }
        }

        // 2. 主键模式匹配
        dbEntity.primaryKey?.let { primaryKey ->
            if (primaryKey.endsWith("_id")) {
                val baseName = primaryKey.removeSuffix("_id")
                // loan_id -> loan -> 借据
                val concept = inferFromKeyName(baseName)
                if (concept != "未知") {
                    return concept
                }
            }
        }

        // 3. 表名推断
        return inferFromTableName(dbEntity.tableName)
    }

    /**
     * 从键名推断业务概念
     *
     * @param keyName 键名（loan_id -> loan）
     * @return 业务概念
     */
    private fun inferFromKeyName(keyName: String): String {
        return when (keyName) {
            "loan" -> "借据"
            "customer" -> "客户"
            "contract" -> "合同"
            "repayment" -> "还款"
            "account" -> "账户"
            "transaction" -> "交易"
            "order" -> "订单"
            "product" -> "产品"
            "user" -> "用户"
            "bill" -> "账单"
            "payment" -> "支付"
            "invoice" -> "发票"
            "receipt" -> "收据"
            "voucher" -> "凭证"
            "journal" -> "流水"
            "ledger" -> "账本"
            else -> "未知"
        }
    }

    /**
     * 从表名推断业务概念
     *
     * @param tableName 表名
     * @return 业务概念
     */
    private fun inferFromTableName(tableName: String): String {
        // 1. 完全匹配
        TABLE_PREFIX_CONCEPTS[tableName]?.let { return it }

        // 2. 前缀匹配
        for ((prefix, concept) in TABLE_PREFIX_CONCEPTS) {
            if (tableName.startsWith(prefix)) {
                return concept
            }
        }

        // 2.1. 表名前缀模式匹配
        for ((prefix, concept) in TABLE_PREFIX_CONCEPTS) {
            if (tableName.startsWith(prefix)) {
                return concept
            }
        }

        // 3. 表名模式推断
        // t_loan -> 借据
        // acct_loan -> 账户
        // sys_config -> 系统
        if (tableName.startsWith("t_")) {
            val baseName = tableName.removePrefix("t_")
            return inferFromKeyName(baseName)
        }

        if (tableName.contains("_")) {
            val parts = tableName.split("_")
            val prefix = parts[0]
            val keyWithUnderscore = "$prefix" + "_"
            return TABLE_PREFIX_CONCEPTS[keyWithUnderscore] ?: inferFromKeyName(parts.getOrElse(1) { "" })
        }

        return "未知"
    }
}
