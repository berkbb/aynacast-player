package com.babadogan.aynacast.tv.util

import java.io.ByteArrayOutputStream

/** DNS-SD TXT record wire format: each "key=value" entry prefixed with its one-byte length. */
object DnsTxtRecord {

    private const val MAX_ENTRY_BYTES = 255

    fun encode(attributes: Map<String, ByteArray?>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((key, value) in attributes) {
            val entry = key.toByteArray(Charsets.UTF_8) + if (value != null) "=".toByteArray() + value else ByteArray(0)
            if (entry.isEmpty() || entry.size > MAX_ENTRY_BYTES) continue
            out.write(entry.size)
            out.write(entry)
        }
        return out.toByteArray()
    }
}
