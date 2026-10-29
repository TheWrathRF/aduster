package app.aduster.filters

import app.aduster.https.HttpsConfig
import app.aduster.settings.DeviceProfile
import app.aduster.settings.Settings
import app.aduster.settings.SettingsBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Defaults that follow the phone (its languages and maker), not one country or brand. */
class GlobalDefaultsTest {
    private val english = Device(listOf("en"), "Google")

    private fun webOn(s: Settings, d: Device) = FilterCatalog.enabledWeb(s, d).map { it.id }.toSet()
    private fun dnsOn(s: Settings, d: Device) = FilterCatalog.enabledDns(s, d).map { it.id }.toSet()

    @Test
    fun regionalListsFollowThePhonesLanguages() {
        val s = Settings()
        assertTrue(FilterCatalog.lists.none { it.group == Group.WEB_REGIONAL && FilterCatalog.isEnabled(it, s, english) })
        assertEquals(setOf("adguard-german"), webOn(s, Device(listOf("de", "en"), "Google")).filter { it in REGIONAL }.toSet())
        assertEquals(setOf("adguard-spanish-portuguese"), webOn(s, Device(listOf("pt"), "Google")).filter { it in REGIONAL }.toSet())
        // A regional list's trusted-rules companion follows it: on with it, off without it.
        val companions = FilterCatalog.trustedCompanions.filter { FilterCatalog.byId(it.adguardTrustedOf!!)?.group == Group.WEB_REGIONAL }
        assertTrue(companions.isNotEmpty())
        for (companion in companions) {
            val base = FilterCatalog.byId(companion.adguardTrustedOf!!)!!
            val on = webOn(s, Device(base.languages.toList(), "Google"))
            assertTrue(companion.id, base.id in on && companion.id in on)
            assertFalse(companion.id, companion.id in webOn(s, english))
        }
    }

    @Test
    fun aSwitchSetByHandIsNeverOverridden() {
        val german = Device(listOf("de"), "Google")
        assertFalse("adguard-german" in webOn(Settings(listOverrides = mapOf("adguard-german" to false)), german))
        assertTrue("adguard-french" in webOn(Settings(listOverrides = mapOf("adguard-french" to true)), german))
    }

    @Test
    fun noCountryListIsBuiltInByDefault() {
        assertTrue(FilterCatalog.lists.filter { it.group == Group.WEB_REGIONAL }.all { it.languages.isNotEmpty() })
    }

    @Test
    fun theDeviceListFollowsTheMaker() {
        val s = Settings()
        for (maker in listOf("Xiaomi", "POCO", "Redmi")) {
            assertEquals(maker, setOf(FilterCatalog.XIAOMI_LIST), dnsOn(s, Device(listOf("en"), maker)).filter { it in DEVICE }.toSet())
            assertEquals(DeviceProfile.SAFE, FilterCatalog.deviceProfile(s, Device(listOf("en"), maker)))
        }
        // Other makers: offered, off until switched on.
        val samsung = Device(listOf("en"), "samsung")
        assertEquals("hagezi-samsung", samsung.brandList)
        assertTrue(dnsOn(s, samsung).none { it in DEVICE })
        assertEquals(setOf("hagezi-samsung"), dnsOn(s.copy(deviceProfile = DeviceProfile.FULL), samsung).filter { it in DEVICE }.toSet())
        assertEquals("hagezi-oppo-realme", Device(listOf("en"), "OnePlus").brandList)
        assertEquals("hagezi-huawei", Device(listOf("en"), "HONOR").brandList)
        // A maker without a list: nothing, whatever the setting.
        assertNull(english.brandList)
        assertTrue(dnsOn(s.copy(deviceProfile = DeviceProfile.FULL), english).none { it in DEVICE })
        // Safe exists only where tested exceptions exist: elsewhere it means off.
        assertEquals(DeviceProfile.OFF, FilterCatalog.deviceProfile(s.copy(deviceProfile = DeviceProfile.SAFE), samsung))
        // No Device list in the first-run snapshot: it comes with the first update.
        assertTrue(FilterCatalog.lists.filter { it.group == Group.DEVICE }.none { it.bundled })
    }

    @Test
    fun theXiaomiProfileKeyIsReadIntoTheDeviceProfile() {
        val old = """{"format":"aduster-settings","version":1,"settings":{"xiaomiProfile":"aggressive","listOverrides":{"a-removed-list":true,"adguard-russian":false}}}"""
        val imported = SettingsBackup.decode(old, Settings())
        assertNotNull(imported)
        val s = imported!!.settings
        assertEquals(DeviceProfile.FULL, s.deviceProfile)
        assertEquals(DeviceProfile.FULL, FilterCatalog.deviceProfile(s, Device(listOf("en"), "Xiaomi")))
        assertFalse("adguard-russian" in webOn(s, Device(listOf("ru"), "Xiaomi")))
        // A bare settings object without the export marker, with the Off profile.
        val bare = SettingsBackup.decode("""{"xiaomiProfile":"off","userRules":"||ads.example^"}""", Settings())
        assertEquals(DeviceProfile.OFF, bare!!.settings.deviceProfile)
    }

    @Test
    fun governmentSitesAreNeverDecryptedByRule() {
        val set = HttpsConfig.GOVERNMENT.toHashSet()
        for (host in listOf("www.gov.uk", "www.gov.br", "impots.gouv.fr", "www.gob.mx", "www.go.jp", "ird.govt.nz", "www.gub.uy",
            "help.gv.at", "www.canada.gc.ca", "www.admin.ch", "www.usa.gov", "army.mil", "who.int", "www.mod.mil.za")) {
            assertTrue(host, HttpsConfig.neverDecrypted(host, set))
        }
        for (host in listOf("go.com", "golang.org", "gov.example.com", "governor.com", "www.example.edu.au", "example.co.uk")) {
            assertFalse(host, HttpsConfig.neverDecrypted(host, set))
        }
    }

    @Test
    fun theBuiltInNeverDecryptListIsGlobal() {
        val hosts = File("src/main/assets/builtin/https-sensitive.txt").readLines().filter { it.isNotBlank() && !it.startsWith("!") }
        assertTrue(hosts.size > 40)
        // No country's own sites: at most one host under any country code (a global service,
        // such as a bot check or a mail provider).
        val byCountry = hosts.map { it.substringAfterLast('.') }.filter { it.length == 2 }.groupingBy { it }.eachCount()
        assertTrue(byCountry.toString(), byCountry.values.all { it <= 1 })
        for (signIn in listOf("accounts.google.com", "appleid.apple.com", "login.microsoftonline.com", "account.samsung.com", "account.xiaomi.com", "id.cloud.huawei.com")) {
            assertTrue(signIn, signIn in hosts)
        }
    }

    private companion object {
        val REGIONAL = FilterCatalog.lists.filter { it.group == Group.WEB_REGIONAL }.map { it.id }.toSet()
        val DEVICE = FilterCatalog.lists.filter { it.group == Group.DEVICE }.map { it.id }.toSet()
    }
}
