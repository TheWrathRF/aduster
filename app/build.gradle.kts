import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing, only where a key is set up: a properties file (storeFile, storePassword,
// keyAlias, keyPassword) named by -PkeystoreProperties=<path>, else keystore.properties at the
// root. Neither is ever committed. Without one, the release APK is built unsigned and debug
// builds use the standard debug key.
val keystoreProps = Properties().apply {
    val f = (findProperty("keystoreProperties") as String?)?.let(::File) ?: rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.aduster"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "app.aduster"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += "arm64-v8a" }
        // heapprofd from adb needs a profileable app; only the profiling build is one.
        manifestPlaceholders["profileableShell"] = project.hasProperty("keepNativeSymbols").toString()
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // v3 as well as v2: a v3 signature can carry a key rotation, so a later key
                // change can still update this install.
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    // JVM tests call code that names a temp file after the process id (the filter download
    // test): Android's stubs answer 0 there instead of throwing.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        // Keep the .so uncompressed and page-aligned so it is mmapped straight from the APK.
        jniLibs {
            useLegacyPackaging = false
            // Heap profiling (heapprofd) needs function names: build with
            // CARGO_PROFILE_RELEASE_STRIP=debuginfo and -PkeepNativeSymbols. Never for daily use.
            if (project.hasProperty("keepNativeSymbols")) keepDebugSymbols += "**/libaduster.so"
        }
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin", "kotlin-tooling-metadata.json") }
    }

    lint {
        checkReleaseBuilds = false
    }
}

/** Builds the Rust core (libaduster.so) with cargo-ndk. */
abstract class CargoNdkBuild @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val coreDir: DirectoryProperty

    @get:Internal
    abstract val ndkDir: DirectoryProperty

    @get:Input
    abstract val cargo: Property<String>

    @get:Internal
    abstract val cargoHomeDir: Property<String>

    @get:Internal
    abstract val rustupHome: Property<String>

    /**
     * The strip setting and the symbols flag change the .so: a profiling build must never be
     * reused for the daily APK as "up to date".
     */
    @get:Input
    abstract val strip: Property<String>

    @get:Input
    abstract val keepSymbols: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val core = coreDir.get().asFile
        exec.exec {
            workingDir = core
            environment("ANDROID_NDK_HOME", ndkDir.get().asFile.absolutePath)
            if (strip.get().isNotEmpty()) environment("CARGO_PROFILE_RELEASE_STRIP", strip.get())
            commandLine(
                cargo.get(), "ndk", "-t", "arm64-v8a", "-P", "33",
                "-o", outputDir.get().asFile.absolutePath,
                "build", "--release", "-p", "aduster-ffi", "--config", remapPaths(core),
            )
        }
    }

    /**
     * No build-machine or source paths in the library: panic locations record their source file.
     * The Cargo registry, the toolchain and this checkout become neutral names, and each of our
     * own source files a short opaque one (`s0.rs`, `s1.rs`, ... in sorted order), so the code's
     * file layout cannot be read from the library either. Compiler messages keep the real paths
     * (the scope is macros and debug info). Given as `--config`, which adds to the target's
     * rustflags in `.cargo/config.toml`; a RUSTFLAGS variable would replace them.
     */
    private fun remapPaths(core: File): String {
        // Every Rust file of the checkout but build output, whatever folders a tree has.
        val own = core.walkTopDown().onEnter { it.name != "target" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "rs" }.map { it.relativeTo(core).path }.toList().sorted()
        // rustc applies the last matching prefix, so single files come after their folders.
        val pairs = listOf(File(cargoHomeDir.get(), "registry/src").path to "registry", rustupHome.get() to "rustup", core.path to "core") +
            own.withIndex().flatMap { (i, rel) -> listOf(rel to "s$i.rs", File(core, rel).path to "s$i.rs") }
        val flags = listOf("--remap-path-scope=macro,debuginfo") + pairs.map { (from, to) -> "--remap-path-prefix=$from=$to" }
        return "target.aarch64-linux-android.rustflags=[" + flags.joinToString(",") { "'$it'" } + "]"
    }
}

/**
 * Downloads the DNS lists bundled as a first-run snapshot: when missing or older than 7 days,
 * so the snapshot does not go stale; a failed download keeps the old copy.
 */
abstract class FetchFilterSnapshot : DefaultTask() {
    @get:Input
    abstract val lists: MapProperty<String, String>

    /** The current week: the task runs again (and checks the age) once a week. */
    @get:Input
    abstract val week: Property<Long>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val maxAge = 7L * 24 * 3600 * 1000
        // Downloads go to the task's own temporary directory, never next to the assets: a `.part`
        // left there would be packaged into the APK. Old ones are removed.
        outputDir.dir("filters").get().asFile.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        // A list taken out of the snapshot must not stay in the APK from an earlier build.
        outputDir.dir("filters").get().asFile.listFiles { f -> f.name.removeSuffix(".txt") !in lists.get().keys }?.forEach { it.delete() }
        lists.get().forEach { (id, url) ->
            val f = outputDir.file("filters/$id.txt").get().asFile
            if (!f.exists() || f.length() == 0L || System.currentTimeMillis() - f.lastModified() > maxAge) {
                f.parentFile.mkdirs()
                logger.lifecycle("Fetching $id")
                val tmp = File(temporaryDir, "$id.txt.part")
                try {
                    // Timeouts: Java's defaults are infinite, so a stalled host would hang the build.
                    val conn = URI(url).toURL().openConnection().apply { connectTimeout = 15_000; readTimeout = 60_000 }
                    conn.getInputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    // `File.renameTo` cannot replace an existing file on Windows and fails silently,
                    // which would leave the snapshot unrefreshed there.
                    if (tmp.length() > 0) Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING) else tmp.delete()
                } catch (e: Exception) {
                    tmp.delete()
                    if (!f.exists()) throw e
                    logger.warn("Could not refresh $id ($e); keeping the copy (${(System.currentTimeMillis() - f.lastModified()) / 86_400_000} days old)")
                }
            }
        }
    }
}

val rustCoreDir = rootProject.layout.projectDirectory.dir("core")
val cargoHome = System.getenv("CARGO_HOME") ?: "${System.getProperty("user.home")}/.cargo"
val isWindows = System.getProperty("os.name").lowercase().contains("windows")

val cargoBuild = tasks.register<CargoNdkBuild>("cargoBuild") {
    coreDir.set(rustCoreDir)
    ndkDir.set(androidComponents.sdkComponents.ndkDirectory)
    cargo.set("$cargoHome/bin/cargo" + if (isWindows) ".exe" else "")
    cargoHomeDir.set(cargoHome)
    rustupHome.set(System.getenv("RUSTUP_HOME") ?: "${System.getProperty("user.home")}/.rustup")
    strip.set(System.getenv("CARGO_PROFILE_RELEASE_STRIP") ?: "")
    keepSymbols.set(project.hasProperty("keepNativeSymbols"))
    sources.from(
        fileTree(rustCoreDir) {
            exclude("target/**")
        },
    )
    outputDir.set(layout.buildDirectory.dir("rust/jniLibs"))
}

val fetchFilterSnapshot = tasks.register<FetchFilterSnapshot>("fetchFilterSnapshot") {
    lists.set(
        mapOf(
            "adguard-dns" to "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
            "hagezi-pro" to "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/pro.txt",
        ),
    )
    week.set(System.currentTimeMillis() / (7L * 24 * 3600 * 1000))
    outputDir.set(layout.buildDirectory.dir("snapshot/assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoBuild, CargoNdkBuild::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(fetchFilterSnapshot, FetchFilterSnapshot::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.core)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // Plain JVM tests of the UI and settings logic: ./gradlew :app:testReleaseUnitTest
    testImplementation("junit:junit:4.13.2")
}
