package com.rusty.flick

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Pretends to be an NFC Forum Type 4 Tag holding one NDEF message: the active preset.
 * A phone held to the watch sees an ordinary NFC sticker with a link on it.
 *
 * Flow a reader runs: SELECT NDEF app (AID D2760000850101) -> SELECT CC file E103 -> READ it
 * -> SELECT NDEF file E104 -> READ the 2-byte length -> READ the message.
 * With nothing active the app SELECT fails, so the watch looks like no tag at all.
 */
class BeamService : HostApduService() {
    private var appSelected = false
    private var ndefFile: ByteArray? = null
    private var current: ByteArray? = null
    private var buzzed = false

    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        if (apdu.size < 4) return SW_WRONG_LENGTH
        val ins = apdu[1].toInt() and 0xFF
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF

        return when {
            ins == 0xA4 && p1 == 0x04 -> {
                val aid = data(apdu) ?: return SW_WRONG_LENGTH
                if (!aid.contentEquals(NDEF_AID)) return SW_NOT_FOUND
                Store.load(this)
                val message = Store.state.value.active?.ndef()?.toByteArray() ?: return SW_NOT_FOUND
                // Snapshot now so switching presets mid-read can't hand the phone a torn message.
                ndefFile = byteArrayOf((message.size shr 8).toByte(), message.size.toByte()) + message
                appSelected = true
                current = null
                buzzed = false
                SW_OK
            }
            ins == 0xA4 && p1 == 0x00 -> {
                if (!appSelected) return SW_NOT_FOUND
                val id = data(apdu) ?: return SW_WRONG_LENGTH
                current = when {
                    id.contentEquals(CC_ID) -> capabilityContainer(ndefFile!!.size)
                    id.contentEquals(NDEF_ID) -> ndefFile
                    else -> null
                }
                if (current == null) SW_NOT_FOUND else SW_OK
            }
            ins == 0xB0 -> {
                val file = current ?: return SW_NOT_SELECTED
                val offset = (p1 shl 8) or p2
                if (offset > file.size) return SW_WRONG_P1P2
                var le = if (apdu.size > 4) apdu[4].toInt() and 0xFF else 0
                if (le == 0) le = 256
                val end = minOf(file.size, offset + le)
                // The phone has read to the end of the link: one buzz so you know it landed.
                if (file === ndefFile && end == file.size && !buzzed) {
                    buzzed = true
                    beamed()
                }
                file.copyOfRange(offset, end) + SW_OK
            }
            else -> SW_INS_NOT_SUPPORTED
        }
    }

    override fun onDeactivated(reason: Int) {
        appSelected = false
        current = null
    }

    private fun beamed() {
        Store.countBeam(this)
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
    }

    private fun data(apdu: ByteArray): ByteArray? {
        if (apdu.size < 5) return null
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) return null
        return apdu.copyOfRange(5, 5 + lc)
    }

    private fun capabilityContainer(ndefSize: Int): ByteArray {
        val max = maxOf(ndefSize, 0x0400)
        return byteArrayOf(
            0x00, 0x0F,             // CC length
            0x20,                   // mapping version 2.0
            0x00, 0xFF.toByte(),    // max R-APDU data (MLe)
            0x00, 0xFF.toByte(),    // max C-APDU data (MLc)
            0x04, 0x06,             // NDEF File Control TLV
            0xE1.toByte(), 0x04,    // NDEF file id
            (max shr 8).toByte(), max.toByte(),
            0x00,                   // read: open
            0xFF.toByte(),          // write: never
        )
    }

    companion object {
        private val NDEF_AID = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
        private val CC_ID = byteArrayOf(0xE1.toByte(), 0x03)
        private val NDEF_ID = byteArrayOf(0xE1.toByte(), 0x04)
        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        private val SW_NOT_SELECTED = byteArrayOf(0x69, 0x86.toByte())
        private val SW_WRONG_P1P2 = byteArrayOf(0x6B, 0x00)
        private val SW_WRONG_LENGTH = byteArrayOf(0x67, 0x00)
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
    }
}
