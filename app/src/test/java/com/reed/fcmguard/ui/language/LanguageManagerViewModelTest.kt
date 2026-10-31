package com.reed.fcmguard.ui.language

import android.graphics.drawable.Drawable
import com.reed.fcmguard.core.locale.LocaleCapabilities
import com.reed.fcmguard.core.locale.LocaleCapabilityPolicy
import com.reed.fcmguard.core.locale.SystemLocaleSnapshot
import com.reed.fcmguard.core.locale.SystemLocaleSnapshots
import com.reed.fcmguard.data.AppDiscovery
import com.reed.fcmguard.data.AppLocaleState
import com.reed.fcmguard.data.InstalledAppLocale
import com.reed.fcmguard.data.LocaleRepository
import com.reed.fcmguard.data.LocaleWriteResult
import com.reed.fcmguard.data.SystemLocaleSource
import com.reed.fcmguard.data.SystemLocaleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanguageManagerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeLocaleRepository : LocaleRepository {
        var capabilities: LocaleCapabilities =
            LocaleCapabilityPolicy.evaluate(sdkInt = 35, holdsChangeConfigurationPermission = false)
        var system = SystemLocaleState(
            primary = "en-US",
            all = listOf("en-US", "vi-VN"),
            source = SystemLocaleSource.LOCALE_MANAGER
        )
        var own: String? = null
        var discovery = AppDiscovery(
            apps = listOf(
                InstalledAppLocale("com.reed.fcmguard", "Mamori", isSelf = true, state = AppLocaleState.SystemDefault),
                InstalledAppLocale("com.example.chat", "Chat", isSelf = false, state = AppLocaleState.NotReadable)
            ),
            restricted = false,
            otherAppLocalesReadable = false
        )
        var setResult: LocaleWriteResult = LocaleWriteResult.Applied("vi")
        var clearResult: LocaleWriteResult = LocaleWriteResult.Applied(null)
        var snapshot: SystemLocaleSnapshot? = null
        var systemSetResult: LocaleWriteResult = LocaleWriteResult.Applied("vi-VN")
        var systemRestoreResult: LocaleWriteResult = LocaleWriteResult.Applied("vi-VN")

        val setCalls = mutableListOf<String>()
        val clearCalls = mutableListOf<Unit>()
        val systemSetCalls = mutableListOf<String>()
        val systemRestoreCalls = mutableListOf<Unit>()

        override suspend fun systemLocaleSnapshot(): SystemLocaleSnapshot? = snapshot

        override suspend fun setSystemLocale(tag: String): LocaleWriteResult {
            systemSetCalls.add(tag)
            if (systemSetResult is LocaleWriteResult.Applied) {
                snapshot = snapshot
                    ?: SystemLocaleSnapshots.create(system.all, 1_000L)
                system = SystemLocaleState(
                    primary = tag,
                    all = listOf(tag) + system.all.filterNot { it == tag },
                    source = SystemLocaleSource.LOCALE_MANAGER
                )
            }
            return systemSetResult
        }

        override suspend fun restoreSystemLocale(): LocaleWriteResult {
            systemRestoreCalls.add(Unit)
            val saved = snapshot
            if (systemRestoreResult is LocaleWriteResult.Applied && saved != null) {
                system = SystemLocaleState(
                    primary = saved.primary,
                    all = saved.tags,
                    source = SystemLocaleSource.LOCALE_MANAGER
                )
            }
            return systemRestoreResult
        }

        override suspend fun capabilities(): LocaleCapabilities = capabilities
        override suspend fun readSystemLocales(): SystemLocaleState = system
        override suspend fun readOwnLocaleOverride(): String? = own

        override suspend fun setOwnLocale(tag: String): LocaleWriteResult {
            setCalls.add(tag)
            if (setResult is LocaleWriteResult.Applied) own = tag
            return setResult
        }

        override suspend fun clearOwnLocale(): LocaleWriteResult {
            clearCalls.add(Unit)
            if (clearResult is LocaleWriteResult.Applied) own = null
            return clearResult
        }

        override suspend fun discoverApps(): AppDiscovery = discovery
        override suspend fun icon(packageName: String): Drawable? = null
    }

    private fun vm(repository: FakeLocaleRepository) = LanguageManagerViewModel(repository)

    @Test
    fun `initial load exposes system locales, apps and capabilities`() = runTest {
        val repository = FakeLocaleRepository()
        val viewModel = vm(repository)

        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertEquals("en-US", state.system?.primary)
        assertEquals(listOf("en-US", "vi-VN"), state.system?.all)
        assertEquals(2, state.apps.size)
        assertNull(state.ownOverride)
        assertTrue(state.capabilities?.can(com.reed.fcmguard.core.locale.LocaleCapability.READ_SYSTEM_LOCALES) == true)
    }

    @Test
    fun `search matches label and package name`() = runTest {
        val repository = FakeLocaleRepository()
        val viewModel = vm(repository)
        advanceUntilIdle()

        viewModel.setQuery("chAt")
        assertEquals(listOf("com.example.chat"), viewModel.state.value.visibleApps.map { it.packageName })

        viewModel.setQuery("fcmguard")
        assertEquals(listOf("com.reed.fcmguard"), viewModel.state.value.visibleApps.map { it.packageName })

        viewModel.setQuery("nothing-here")
        assertTrue(viewModel.state.value.visibleApps.isEmpty())
    }

    @Test
    fun `applying a locale updates the verified state and asks for a recreate`() = runTest {
        val repository = FakeLocaleRepository()
        val viewModel = vm(repository)
        advanceUntilIdle()

        viewModel.applyOwnLocale("vi")
        advanceUntilIdle()

        assertEquals(listOf("vi"), repository.setCalls)
        assertEquals("vi", viewModel.state.value.ownOverride)
        assertEquals(
            AppLocaleState.Override("vi"),
            viewModel.state.value.apps.first { it.isSelf }.state
        )
        assertEquals(LanguageMessage.Applied("vi"), viewModel.state.value.message)
        assertEquals(LanguageEvent.LocaleChanged, withTimeout(1_000) { viewModel.events.first() })
    }

    @Test
    fun `a rejected write is reported and does not change the override`() = runTest {
        val repository = FakeLocaleRepository().apply {
            setResult = LocaleWriteResult.Rejected("permission denied")
        }
        val viewModel = vm(repository)
        advanceUntilIdle()

        viewModel.applyOwnLocale("vi")
        advanceUntilIdle()

        assertNull(viewModel.state.value.ownOverride)
        assertEquals(
            LanguageMessage.Failed(LocaleFailure.Rejected("permission denied")),
            viewModel.state.value.message
        )
        assertFalse(viewModel.state.value.applying)
    }

    @Test
    fun `an unverified write is never reported as success`() = runTest {
        val repository = FakeLocaleRepository().apply {
            setResult = LocaleWriteResult.VerificationFailed("vi", "en")
        }
        val viewModel = vm(repository)
        advanceUntilIdle()

        viewModel.applyOwnLocale("vi")
        advanceUntilIdle()

        assertNull(viewModel.state.value.ownOverride)
        assertEquals(
            LanguageMessage.Failed(LocaleFailure.VerificationFailed("vi", "en")),
            viewModel.state.value.message
        )
    }

    @Test
    fun `changing the system language updates the verified state and records a snapshot`() = runTest {
        val repository = FakeLocaleRepository()
        val viewModel = vm(repository)
        advanceUntilIdle()

        viewModel.changeSystemLocale("vi-VN")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(listOf("vi-VN"), repository.systemSetCalls)
        assertEquals("vi-VN", state.system?.primary)
        assertEquals(listOf("vi-VN", "en-US"), state.system?.all)
        assertEquals(listOf("en-US", "vi-VN"), state.snapshot?.tags)
        assertEquals(LanguageMessage.SystemApplied("vi-VN"), state.message)
        assertFalse(state.applying)
    }

    @Test
    fun `a system change that needs a restart is not reported as success`() = runTest {
        val repository = FakeLocaleRepository().apply {
            systemSetResult = LocaleWriteResult.PendingRestart("ja-JP")
        }
        val viewModel = vm(repository)
        advanceUntilIdle()

        viewModel.changeSystemLocale("ja-JP")
        advanceUntilIdle()

        val state = viewModel.state.value
        // The fake repository left the running configuration untouched, which is exactly
        // what a pending-restart build looks like.
        assertEquals("en-US", state.system?.primary)
        assertEquals(LanguageMessage.PendingRestart("ja-JP"), state.message)
    }

    @Test
    fun `restoring the original system language writes the saved list`() = runTest {
        val repository = FakeLocaleRepository().apply {
            snapshot = SystemLocaleSnapshots.create(listOf("vi-VN", "en-US"), 1_000L)
        }
        val viewModel = vm(repository)
        advanceUntilIdle()

        viewModel.restoreSystemLocale()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(1, repository.systemRestoreCalls.size)
        assertEquals("vi-VN", state.system?.primary)
        assertEquals(listOf("vi-VN", "en-US"), state.system?.all)
        // The snapshot is kept: a restore that already ran is still the original record.
        assertEquals(listOf("vi-VN", "en-US"), state.snapshot?.tags)
        assertEquals(LanguageMessage.SystemRestored("vi-VN"), state.message)
    }

    @Test
    fun `repeated system change taps only write once`() = runTest {
        val repository = FakeLocaleRepository()
        val viewModel = vm(repository)
        advanceUntilIdle()

        viewModel.changeSystemLocale("vi-VN")
        viewModel.changeSystemLocale("ja-JP")
        advanceUntilIdle()

        assertEquals(listOf("vi-VN"), repository.systemSetCalls)
    }

    @Test
    fun `a rejected system change reports the reason and keeps the old locale`() = runTest {
        val repository = FakeLocaleRepository().apply {
            systemSetResult = LocaleWriteResult.Rejected("WRITE_SETTINGS not granted")
        }
        val viewModel = vm(repository)
        advanceUntilIdle()

        viewModel.changeSystemLocale("ja-JP")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("en-US", state.system?.primary)
        assertEquals(
            LanguageMessage.Failed(LocaleFailure.Rejected("WRITE_SETTINGS not granted")),
            state.message
        )
        assertNull(state.snapshot)
    }

    @Test
    fun `the filter narrows the list and still respects the search`() = runTest {
        val repository = FakeLocaleRepository().apply {
            discovery = discovery.copy(
                apps = listOf(
                    InstalledAppLocale(
                        "com.reed.fcmguard", "Mamori", isSelf = true,
                        state = AppLocaleState.Override("vi")
                    ),
                    InstalledAppLocale(
                        "com.example.chat", "Chat", isSelf = false,
                        state = AppLocaleState.SystemDefault
                    )
                )
            )
        }
        val viewModel = vm(repository)
        advanceUntilIdle()

        viewModel.setFilter(AppLocaleFilter.OVERRIDDEN)
        assertEquals(listOf("com.reed.fcmguard"), viewModel.state.value.visibleApps.map { it.packageName })

        viewModel.setFilter(AppLocaleFilter.SYSTEM_DEFAULT)
        assertEquals(listOf("com.example.chat"), viewModel.state.value.visibleApps.map { it.packageName })

        viewModel.setFilter(AppLocaleFilter.ALL)
        viewModel.setQuery("chat")
        assertEquals(listOf("com.example.chat"), viewModel.state.value.visibleApps.map { it.packageName })
    }

    @Test
    fun `clearing the override returns to system default`() = runTest {
        val repository = FakeLocaleRepository().apply { own = "vi" }
        val viewModel = vm(repository)
        advanceUntilIdle()

        viewModel.clearOwnLocale()
        advanceUntilIdle()

        assertEquals(1, repository.clearCalls.size)
        assertNull(viewModel.state.value.ownOverride)
        assertEquals(LanguageMessage.Cleared, viewModel.state.value.message)
        assertEquals(
            AppLocaleState.SystemDefault,
            viewModel.state.value.apps.first { it.isSelf }.state
        )
    }

    @Test
    fun `repeated taps cannot start two writes`() = runTest {
        val repository = FakeLocaleRepository()
        val viewModel = vm(repository)
        advanceUntilIdle()

        // Both calls happen before the queued coroutine runs.
        viewModel.applyOwnLocale("en")
        viewModel.applyOwnLocale("vi")
        advanceUntilIdle()

        assertEquals(listOf("en"), repository.setCalls)
    }

    @Test
    fun `resume re-reads only the externally changeable state`() = runTest {
        val repository = FakeLocaleRepository()
        val viewModel = vm(repository)
        advanceUntilIdle()

        repository.system = repository.system.copy(primary = "vi-VN", all = listOf("vi-VN"))
        viewModel.onResume()
        advanceUntilIdle()

        assertEquals("vi-VN", viewModel.state.value.system?.primary)
        assertEquals(2, viewModel.state.value.apps.size)
    }
}
