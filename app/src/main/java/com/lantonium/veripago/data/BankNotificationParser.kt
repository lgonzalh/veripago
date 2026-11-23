package com.lantonium.veripago.data

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

data class ParsedTransfer(val amount: Long, val sender: String)

object BankNotificationParser {
    fun parse(text: String, rule: BankRuleEntity): ParsedTransfer? {
        return try {
            val amountMatch = Regex(rule.amountRegex).find(text) ?: return null
            val rawAmount = amountMatch.groupValues.getOrNull(1).orEmpty()
            val amount = parseCopAmount(rawAmount) ?: return null
            val sender = Regex(rule.senderRegex).find(text)?.groupValues?.getOrNull(1)
                ?.trim()?.trimEnd('.', ',', '!', '?', ';').orEmpty()
            ParsedTransfer(amount, sender)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun parseCopAmount(raw: String): Long? {
        var value = raw.filter { it.isDigit() || it == '.' || it == ',' }
        if (value.isBlank() || value.none(Char::isDigit)) return null
        val lastSeparator = max(value.lastIndexOf('.'), value.lastIndexOf(','))
        val hasBoth = value.contains('.') && value.contains(',')
        value = when {
            hasBoth && value.length - lastSeparator - 1 == 2 -> value.substring(0, lastSeparator).filter(Char::isDigit)
            hasBoth -> value.filter(Char::isDigit)
            lastSeparator >= 0 && value.length - lastSeparator - 1 == 3 -> value.filter(Char::isDigit)
            lastSeparator >= 0 && value[lastSeparator] == ',' -> value.removeRange(lastSeparator, lastSeparator + 1).filter(Char::isDigit)
            lastSeparator >= 0 -> value.substring(0, lastSeparator).filter(Char::isDigit)
            else -> value
        }
        return value.toLongOrNull()?.takeIf { it > 0L }
    }

    fun idempotencyHash(sourcePackage: String, amount: Long, receivedAt: Long, sender: String): String {
        // NotificationListenerService may report the same notification more than once with
        // the same postTime. Preserve millisecond precision so separate same-value transfers
        // in a broad time window do not collapse into one idempotency key.
        val stable = "$sourcePackage|$amount|$receivedAt|${normalizeName(sender)}"
        return MessageDigest.getInstance("SHA-256").digest(stable.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun normalizeName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}

object OrderReconciler {
    fun bestMatch(orders: List<OrderEntity>, sender: String): OrderEntity? {
        if (orders.isEmpty()) return null
        val normalized = BankNotificationParser.normalizeName(sender)
        if (normalized.isBlank()) return orders.minByOrNull(OrderEntity::createdAt)
        val ranked = orders.map { it to similarity(normalized, BankNotificationParser.normalizeName(it.customerName)) }
        val plausible = ranked.filter { it.second >= 0.58 }
        return if (plausible.isNotEmpty()) {
            plausible.sortedWith(compareByDescending<Pair<OrderEntity, Double>> { it.second }.thenBy { it.first.createdAt }).first().first
        } else orders.minByOrNull(OrderEntity::createdAt)
    }

    private fun similarity(left: String, right: String): Double {
        if (left == right) return 1.0
        if (left.isBlank() || right.isBlank()) return 0.0
        val distance = levenshtein(left, right)
        return 1.0 - distance.toDouble() / max(left.length, right.length)
    }

    private fun levenshtein(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { i, a ->
            val current = IntArray(right.length + 1)
            current[0] = i + 1
            right.forEachIndexed { j, b ->
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (a == b) 0 else 1)
            }
            previous = current
        }
        return previous[right.length]
    }
}
