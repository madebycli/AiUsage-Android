package com.madebycli.aiusage.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
class Tokens(val access: String, val refresh: String, val accountId: String?, val expiresAt: Long?)

interface TokenStore {
    fun readTokens(): Tokens?
    fun writeTokens(tokens: Tokens)
    fun clear()
}

// All account data and tokens stay in noBackupFilesDir, encrypted by a device-bound key.
class SecureStore(context: Context, private val namespace: String = "account") : TokenStore {
    private val directory = context.noBackupFilesDir
    private val alias = "aiusage.$namespace.v1"
    private fun filename(name: String) = if (namespace == "account") name else "$namespace.$name"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    @Synchronized
    private fun write(name: String, text: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(text.toByteArray())
        val file = AtomicFile(File(directory, filename(name)))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    @Synchronized
    private fun read(name: String): String? {
        val file = AtomicFile(File(directory, filename(name)))
        if (!file.baseFile.exists()) return null
        val bytes = file.readFully()
        val ivLength = bytes.first().toInt()
        require(ivLength in 12..16 && bytes.size > ivLength + 1)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 1 + ivLength)))
            doFinal(bytes.copyOfRange(1 + ivLength, bytes.size)).toString(Charsets.UTF_8)
        }
    }
    override fun readTokens(): Tokens? = read("tokens.enc")?.let { appJson.decodeFromString(it) }
    override fun writeTokens(tokens: Tokens) = write("tokens.enc", appJson.encodeToString(tokens))
    fun readRecord(): PersonalRecord = read("usage.enc")?.let { appJson.decodeFromString(it) } ?: PersonalRecord()
    fun writeRecord(record: PersonalRecord) = write("usage.enc", appJson.encodeToString(record))
    fun readClaude(): ClaudeCredential? = read("tokens.enc")?.let { appJson.decodeFromString(it) }
    fun writeClaude(value: ClaudeCredential) = write("tokens.enc", appJson.encodeToString(value))
    @Synchronized
    override fun clear() {
        AtomicFile(File(directory, filename("tokens.enc"))).delete()
        AtomicFile(File(directory, filename("usage.enc"))).delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
}
