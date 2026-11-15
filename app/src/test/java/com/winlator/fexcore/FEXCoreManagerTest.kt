package com.winlator.fexcore

import com.winlator.core.envvars.EnvVars
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FEXCoreManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val builtInPresets = listOf(
        FEXCorePreset.STABILITY,
        FEXCorePreset.COMPATIBILITY,
        FEXCorePreset.INTERMEDIATE,
        FEXCorePreset.PERFORMANCE,
        FEXCorePreset.EXTREME,
        FEXCorePreset.DENUVO,
    )

    private fun configOf(content: String): Map<String, String> {
        val config = JSONObject(content).getJSONObject("Config")
        return config.keys().asSequence().associateWith { config.getString(it) }
    }

    private fun envOf(envVars: EnvVars): Map<String, String> = envVars.associateWith { envVars.get(it) }

    private fun envName(option: String) = "FEX_" + option.uppercase()

    @Test
    fun `app config turns on vector and memcpy TSO and keeps the Steam client off code caches`() {
        val config = configOf(FEXCoreManager.APP_CONFIG_CONTENT)
        assertEquals(
            mapOf(
                "Multiblock" to "0",
                "X87ReducedPrecision" to "0",
                "TSOEnabled" to "1",
                "VectorTSOEnabled" to "1",
                "MemcpySetTSOEnabled" to "1",
                "HalfBarrierTSOEnabled" to "1",
                "MonoHacks" to "0",
                "EnableCodeCachingWIP" to "0",
            ),
            config,
        )
    }

    @Test
    fun `every option the app config sets is taken out of the environment`() {
        val appOptions = configOf(FEXCoreManager.APP_CONFIG_CONTENT).keys
        val env = EnvVars()
        appOptions.forEach { env.put(envName(it), "7") }

        val taken = FEXCoreManager.takeAppConfigOptions(env)

        assertTrue(env.isEmpty)
        assertEquals(appOptions.map(::envName).toSet(), taken.toSet())
    }

    @Test
    fun `intermediate keeps its values, split between Config json and the environment`() {
        val env = FEXCorePresetManager.getEnvVars(null, FEXCorePreset.INTERMEDIATE)

        val taken = FEXCoreManager.takeAppConfigOptions(env)

        assertTrue(env.isEmpty)
        assertEquals(
            mapOf(
                "TSOEnabled" to "1",
                "VectorTSOEnabled" to "0",
                "MemcpySetTSOEnabled" to "0",
                "HalfBarrierTSOEnabled" to "1",
                "X87ReducedPrecision" to "1",
                "Multiblock" to "1",
            ),
            configOf(FEXCoreManager.containerConfigContent(taken)),
        )
    }

    @Test
    fun `options the app config does not set stay in the environment`() {
        val env = FEXCorePresetManager.getEnvVars(null, FEXCorePreset.DENUVO)

        FEXCoreManager.takeAppConfigOptions(env)

        assertEquals(
            mapOf(
                "FEX_SMALLTSCSCALE" to "1",
                "FEX_VOLATILEMETADATA" to "1",
                "FEX_SMCCHECKS" to "full",
                "FEX_HIDEHYPERVISORBIT" to "1",
            ),
            envOf(env),
        )
    }

    @Test
    fun `no preset loses or changes an option`() {
        for (preset in builtInPresets) {
            val original = envOf(FEXCorePresetManager.getEnvVars(null, preset))
            val env = FEXCorePresetManager.getEnvVars(null, preset)

            val taken = FEXCoreManager.takeAppConfigOptions(env)
            val fromFile = configOf(FEXCoreManager.containerConfigContent(taken)).mapKeys { envName(it.key) }

            assertTrue(preset, fromFile.keys.none { env.has(it) })
            assertEquals(preset, original, envOf(env) + fromFile)
        }
    }

    @Test
    fun `values are written as JSON strings`() {
        val options = EnvVars()
        options.put("FEX_MULTIBLOCK", "a\"b\\c")

        assertEquals(mapOf("Multiblock" to "a\"b\\c"), configOf(FEXCoreManager.containerConfigContent(options)))
    }

    @Test
    fun `an empty option set writes an empty Config block`() {
        assertTrue(configOf(FEXCoreManager.containerConfigContent(EnvVars())).isEmpty())
    }

    private fun tsoOptions() = EnvVars().apply { put("FEX_TSOENABLED", "1") }

    private fun configFile(root: File) = File(root, ".fex-emu/Config.json")

    @Test
    fun `a config location or portable mode in the environment is detected`() {
        assertFalse(FEXCoreManager.configLocationOverridden(EnvVars("FEX_TSOENABLED=1")))
        assertTrue(FEXCoreManager.configLocationOverridden(EnvVars("FEX_APP_CONFIG_LOCATION=/x/")))
        assertTrue(FEXCoreManager.configLocationOverridden(EnvVars("FEX_PORTABLE=1")))
    }

    @Test
    fun `a config is ours only when it matches the record`() {
        assertTrue(FEXCoreManager.isOwnConfig("a", "a"))
        assertFalse(FEXCoreManager.isOwnConfig("a", "b"))
        assertFalse(FEXCoreManager.isOwnConfig("a", null))
        assertFalse(FEXCoreManager.isOwnConfig(null, null))
    }

    @Test
    fun `the config this launcher wrote is rewritten and deleted`() {
        val root = temp.newFolder()

        assertTrue(FEXCoreManager.writeContainerConfig(root, tsoOptions()))
        assertEquals(mapOf("TSOEnabled" to "1"), configOf(configFile(root).readText()))
        assertTrue(FEXCoreManager.writeContainerConfig(root, EnvVars()))

        FEXCoreManager.deleteOwnContainerConfig(root)

        assertFalse(configFile(root).exists())
        assertTrue(FEXCoreManager.canWriteContainerConfig(root))
    }

    @Test
    fun `a user's config is neither overwritten nor deleted`() {
        val root = temp.newFolder()
        val user = "{\"Config\": {\"Multiblock\": \"1\"}}"
        configFile(root).parentFile!!.mkdirs()
        configFile(root).writeText(user)

        assertFalse(FEXCoreManager.canWriteContainerConfig(root))
        assertFalse(FEXCoreManager.writeContainerConfig(root, tsoOptions()))
        FEXCoreManager.deleteOwnContainerConfig(root)

        assertEquals(user, configFile(root).readText())
    }

    @Test
    fun `a config the user changed after it was written is kept`() {
        val root = temp.newFolder()
        assertTrue(FEXCoreManager.writeContainerConfig(root, tsoOptions()))
        configFile(root).writeText("{}")

        FEXCoreManager.deleteOwnContainerConfig(root)

        assertEquals("{}", configFile(root).readText())
        assertFalse(FEXCoreManager.canWriteContainerConfig(root))
    }
}
