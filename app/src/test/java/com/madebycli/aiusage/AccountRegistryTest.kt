package com.madebycli.aiusage

import androidx.test.core.app.ApplicationProvider
import com.madebycli.aiusage.data.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountRegistryTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun clean() {
        context.getSharedPreferences("accounts", 0).edit().clear().commit()
        context.noBackupFilesDir.listFiles()?.forEach { it.delete() }
    }

    @Test fun `accounts keep the order they are given and can be reordered`() {
        val registry = AccountRegistry(context)
        val a = AccountRegistry.newAccount("Codex"); val b = AccountRegistry.newAccount("Claude Code"); val c = AccountRegistry.newAccount("Codex")
        listOf(a, b, c).forEach(registry::add)
        assertEquals(listOf(a.id, b.id, c.id), registry.list().map { it.id })
        registry.reorder(listOf(c.id, a.id, b.id))
        assertEquals(listOf(c.id, a.id, b.id), AccountRegistry(context).list().map { it.id })
        registry.reorder(listOf(b.id))
        assertEquals("accounts missing from the new order keep their place behind it", listOf(b.id, c.id, a.id), registry.list().map { it.id })
        registry.reorder(listOf("unknown", a.id))
        assertEquals(3, registry.list().size)
    }

    @Test fun `every account gets its own storage namespace and nothing secret is in the list`() {
        val a = AccountRegistry.newAccount("Codex"); val b = AccountRegistry.newAccount("Codex")
        assertNotEquals(a.id, b.id)
        assertNotEquals(a.namespace, b.namespace)
        AccountRegistry(context).add(a)
        val raw = context.getSharedPreferences("accounts", 0).getString("list", "")!!
        assertFalse(raw.contains("token", ignoreCase = true))
    }

    @Test fun `rename removal and identity lookup`() {
        val registry = AccountRegistry(context)
        val a = AccountRegistry.newAccount("Codex", identity = "codex:acct-1", detail = "me@example.com")
        registry.add(a)
        assertEquals(a.id, registry.byIdentity("Codex", "codex:acct-1")!!.id)
        assertNull("the same identity of another provider is another account", registry.byIdentity("Claude Code", "codex:acct-1"))
        assertNull(registry.byIdentity("Codex", null))
        registry.update(a.id) { it.copy(label = "Arbeit") }
        assertEquals("Arbeit", registry.find(a.id)!!.label)
        registry.remove(a.id)
        assertTrue(registry.list().isEmpty())
    }

    @Test fun `unnamed accounts are numbered per provider and named ones keep their name`() {
        val a = AccountRegistry.newAccount("Codex"); val b = AccountRegistry.newAccount("Codex"); val c = AccountRegistry.newAccount("Claude Code")
        val all = listOf(a, b, c)
        assertEquals("Codex 1", displayName(a, all))
        assertEquals("Codex 2", displayName(b, all))
        assertEquals("Claude", displayName(c, all))
        assertEquals("Privat", displayName(b.copy(label = "Privat"), all))
        assertEquals("me@example.com", displayName(a.copy(detail = "me@example.com"), all))
        assertEquals("Codex", displayName(a, listOf(a)))
    }

    @Test fun `the single accounts of older versions become the first accounts`() {
        context.noBackupFilesDir.resolve("tokens.enc").writeBytes(byteArrayOf(1))
        context.noBackupFilesDir.resolve("claude.tokens.enc").writeBytes(byteArrayOf(1))
        val accounts = AccountRegistry(context).list()
        assertEquals(listOf("Codex", "Claude Code"), accounts.map { it.provider })
        assertEquals(listOf("account", "claude"), accounts.map { it.namespace })
        assertEquals("migration runs once", accounts, AccountRegistry(context).list())
        context.noBackupFilesDir.resolve("tokens.enc").delete()
        assertEquals("a later missing file does not remove the account silently", 2, AccountRegistry(context).list().size)
    }

    @Test fun `a fresh install starts without accounts`() {
        assertTrue(AccountRegistry(context).list().isEmpty())
    }
}
