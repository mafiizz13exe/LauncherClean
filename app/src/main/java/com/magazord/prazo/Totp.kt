package com.magazord.prazo

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** TOTP (RFC 6238) próprio: aceita chaves curtas (80 bits), como a do Magazord. */
object Totp {
    private const val ALFABETO = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private fun base32(s: String): ByteArray {
        val limpo = s.uppercase().filter { it in ALFABETO }
        var buffer = 0
        var bits = 0
        val out = ArrayList<Byte>()
        for (ch in limpo) {
            buffer = (buffer shl 5) or ALFABETO.indexOf(ch)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xFF).toByte())
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return out.toByteArray()
    }

    fun codigo(secret: String, agoraSeg: Long = System.currentTimeMillis() / 1000): String {
        val chave = base32(secret)
        val msg = ByteBuffer.allocate(8).putLong(agoraSeg / 30).array()
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(chave, "HmacSHA1"))
        val h = mac.doFinal(msg)
        val o = h[h.size - 1].toInt() and 0x0F
        val bin = ((h[o].toInt() and 0x7F) shl 24) or
                ((h[o + 1].toInt() and 0xFF) shl 16) or
                ((h[o + 2].toInt() and 0xFF) shl 8) or
                (h[o + 3].toInt() and 0xFF)
        return (bin % 1_000_000).toString().padStart(6, '0')
    }

    fun segundosRestantes(): Long = 30 - (System.currentTimeMillis() / 1000 % 30)
}
