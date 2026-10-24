package com.madebycli.aiusage.data

import kotlinx.serialization.Serializable

@Serializable
class Tokens(val access: String, val refresh: String, val accountId: String?, val expiresAt: Long?, val email: String? = null)

interface TokenStore {
    fun readTokens(): Tokens?
    fun writeTokens(tokens: Tokens)
    fun clear()
}

interface DeviceLoginStore {
    fun readDeviceLogin(): PendingDeviceLogin?
    fun writeDeviceLogin(value: PendingDeviceLogin)
    fun clearDeviceLogin()
}
