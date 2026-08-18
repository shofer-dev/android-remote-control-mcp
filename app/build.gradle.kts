import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    jacoco
}

/**
 * Derives the version name from `git describe` output.
 *
 * Output formats handled:
 * - "v1.2.3"              → exact tag    → "1.2.3"
 * - "v1.2.3-7-gabc1234"   → after tag    → "1.2.3-dev.7+abc1234"
 * - "v1.2.3-beta-7-g..."  → pre-release  → "1.2.3-beta-dev.7+abc1234"
 * - "abc1234"              → no tags      → "0.0.0-dev+abc1234"
 *
 * Returns null when git is unavailable or the command fails.
 */
fun getGitDescribeVersion(): String? {
    return try {
        val process =
            ProcessBuilder("git", "describe", "--tags", "--match", "v*", "--always")
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
        val output =
            process
                .inputStream
                .bufferedReader()
                .readText()
                .trim()
        val exitCode = process.waitFor()
        if (exitCode != 0 || output.isEmpty()) return null

        // Describe pattern checked first: the -N-gHASH suffix is unambiguous.
        // Using (.+) for the version part lets the regex engine backtrack correctly
        // even when pre-release segments contain hyphens (e.g. v1.0.0-rc1-3-g1234567).
        val describePattern = Regex("""^v(.+)-(\d+)-g([0-9a-f]+)$""")
        val tagPattern = Regex("""^v(\d+\.\d+\.\d+(?:-.+)?)$""")

        when {
            describePattern.matches(output) -> {
                val match = describePattern.find(output)!!
                val baseVersion = match.groupValues[1]
                val commitCount = match.groupValues[2]
                val hash = match.groupValues[3]
                "$baseVersion-dev.$commitCount+$hash"
            }

            tagPattern.matches(output) -> {
                tagPattern.find(output)!!.groupValues[1]
            }

            else -> {
                "0.0.0-dev+$output"
            }
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Runs `git <args>` in the repo root, capturing stdout only. stderr is discarded so a
 * git warning/hint can never be merged into and corrupt the parsed output. Returns
 * (exitCode, trimmed stdout), or null if the process could not be started.
 */
fun runGit(vararg args: String): Pair<Int, String>? =
    try {
        val process =
            ProcessBuilder(listOf("git", *args))
                .directory(rootDir)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        val output =
            process
                .inputStream
                .bufferedReader()
                .readText()
                .trim()
        Pair(process.waitFor(), output)
    } catch (_: Exception) {
        null
    }

/**
 * True when the working tree is a shallow clone, detected via the `shallow` marker file
 * in the COMMON git dir (shared across linked worktrees, so `--git-common-dir` is
 * required — `--git-dir` points at a worktree's private dir and would miss it).
 * `--git-common-dir` exists since git 2.5; on older/absent git this returns false.
 */
fun isShallowClone(): Boolean {
    val gitCommonDir = runGit("rev-parse", "--git-common-dir") ?: return false
    if (gitCommonDir.first != 0) return false
    return rootDir.resolve(gitCommonDir.second).resolve("shallow").exists()
}

/**
 * Derives the numeric version code from git history.
 *
 * `versionCode = (2_000_000 + commitCount) * 10 + (dirty ? 1 : 0)` where:
 * - `commitCount` is `git rev-list --count HEAD` — strictly monotonic on `main`, so
 *   every commit/merge auto-increments the code with no manual bumping.
 * - The `2_000_000` base clears the ceiling of the previous scheme (max 1_090_099 for
 *   v1.9.0), so codes under the new scheme never regress below already-shipped builds.
 * - The trailing digit marks a dirty working tree (uncommitted changes to tracked
 *   files): a local dirty build sorts one above the clean build at the same commit
 *   without ever colliding with the next commit's code.
 *
 * Returns null only when git is unavailable (no repository / git absent), so the caller
 * fails the build rather than falling back to a hardcoded code. A shallow clone still
 * derives a (truncated) code here so config-only work — `help`, dependency resolution,
 * IDE sync — is not broken; shipping a shallow (wrong) code is prevented separately by
 * the release-artifact guard (see gradle.taskGraph.whenReady below).
 */
fun getGitVersionCode(): Int? {
    val (countExit, countOutput) = runGit("rev-list", "--count", "HEAD") ?: return null
    if (countExit != 0) return null
    val commitCount = countOutput.toIntOrNull() ?: return null

    // `git diff --quiet HEAD` exits 1 when tracked files differ from HEAD (staged or
    // unstaged); untracked files are intentionally ignored. Any other non-zero exit is
    // a git error, not a dirty tree, so bail rather than mislabel it.
    val (dirtyExit, _) = runGit("diff", "--quiet", "HEAD") ?: return null
    val dirty =
        when (dirtyExit) {
            0 -> false
            1 -> true
            else -> return null
        }

    return (2_000_000 + commitCount) * 10 + if (dirty) 1 else 0
}

val isExplicitVersion =
    project.gradle.startParameter
        .projectProperties
        .containsKey("VERSION_NAME")
val fallbackVersion = project.findProperty("VERSION_NAME") as String? ?: "1.0.0"
val versionNameProp =
    if (isExplicitVersion) fallbackVersion else (getGitDescribeVersion() ?: fallbackVersion)
val isExplicitVersionCode =
    project.gradle.startParameter
        .projectProperties
        .containsKey("VERSION_CODE")
val versionCodeProp =
    if (isExplicitVersionCode) {
        // An explicit -PVERSION_CODE override MUST be a valid integer; fail loudly
        // rather than silently degrading to a fallback and shipping a wrong code.
        val raw = project.findProperty("VERSION_CODE") as String
        raw.toIntOrNull() ?: error("VERSION_CODE must be an integer, got: \"$raw\"")
    } else {
        // The version code is ALWAYS derived from git — there is no hardcoded fallback.
        // Fail only when git is entirely unavailable (no repository); a shallow clone
        // still derives a code so config-only tasks (help, dependency resolution, IDE
        // sync) are not broken. A shallow RELEASE build is rejected by the guard below.
        getGitVersionCode()
            ?: error(
                "Cannot derive versionCode: no git repository found. Build from a git " +
                    "checkout, or pass an explicit -PVERSION_CODE=<integer>.",
            )
    }

// A release/bundle artifact must carry a full-history git-derived code. A shallow
// checkout yields a truncated (wrong, too-low) count, so refuse to build one from a
// shallow tree — but only for actual release-artifact tasks, so config-only work
// (help, dependency resolution, IDE sync) on a shallow checkout is unaffected.
gradle.taskGraph.whenReady {
    val buildingReleaseArtifact =
        !isExplicitVersionCode &&
            gradle.taskGraph.allTasks.any { task ->
                task.name.contains("Release") &&
                    (
                        task.name.startsWith("assemble") ||
                            task.name.startsWith("bundle") ||
                            task.name.startsWith("package")
                    )
            }
    if (buildingReleaseArtifact && (getGitVersionCode() == null || isShallowClone())) {
        error(
            "Refusing to build a release artifact without a full-history git versionCode " +
                "(missing repository or shallow clone). Build from a full git checkout, or " +
                "pass an explicit -PVERSION_CODE=<integer>.",
        )
    }
}

ktlint {
    version.set("1.8.0")
}

android {
    namespace = "com.danielealbano.androidremotecontrolmcp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.danielealbano.androidremotecontrolmcp"
        minSdk = 33
        targetSdk = 34
        versionCode = versionCodeProp
        versionName = versionNameProp
    }

    // Distribution flavors: `gms` (full app, GitHub/Play) and `foss` (F-Droid, no Google Play Services).
    // Release applicationId is identical for both flavors; debug builds get a per-flavor suffix (set via the
    // variant API in androidComponents below) so gms/foss debug builds can be installed side-by-side.
    flavorDimensions += "distribution"
    productFlavors {
        create("gms") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
    }

    // Release signing configuration (optional, uses keystore.properties if present)
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    if (keystorePropertiesFile.exists()) {
        val keystoreProperties = Properties()
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))

        signingConfigs {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            // Debug applicationId is set per-flavor via the variant API (androidComponents below) so it becomes
            // `…mcp.<flavor>.debug`, keeping the release applicationId identical across flavors.
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // QUERY_ALL_PACKAGES is required for app management tools (list/launch/force-stop)
        disable += "QueryAllPackagesPermission"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Material Components (XML themes)
    implementation(libs.material)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.navigation)
    debugImplementation(libs.compose.ui.tooling)

    // Lifecycle
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // DataStore
    implementation(libs.datastore.preferences)

    // DocumentFile (SAF)
    implementation(libs.androidx.documentfile)

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.video)

    // Google Play Services (gms flavor only — excluded from the foss/F-Droid build)
    "gmsImplementation"(libs.play.services.location)

    // OpenStreetMap
    implementation(libs.osmdroid)

    // Ktor Client (Event Channel dispatcher — no Logging plugin, it would expose auth token)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // OkHttp WebSocket — the platform connector's outbound /ws/device client
    implementation(libs.okhttp)

    // MCP SDK
    implementation(libs.mcp.kotlin.sdk.server)
    runtimeOnly(libs.slf4j.android)

    // Kotlinx
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // Accompanist
    implementation(libs.accompanist.permissions)

    // Unit Testing
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    // Test-only: EventDispatcherImplTest stands up a local Ktor/Netty server as the receiver for the
    // event-channel client. Server-side Ktor is not on the app (shipped) classpath — only the test one.
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.netty)
}

androidComponents {
    // Per-flavor debug applicationId (`…mcp.gms.debug` / `…mcp.foss.debug`) so both debug builds coexist, while
    // the release applicationId stays identical across flavors (`com.danielealbano.androidremotecontrolmcp`).
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.applicationId.set(
            "com.danielealbano.androidremotecontrolmcp.${variant.flavorName}.debug",
        )
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "4g"
    // Distribute tests across all available CPU cores for faster execution.
    maxParallelForks = (Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
    // MockK uses byte-buddy/reflection internally; JDK 17 strong encapsulation
    // blocks access to these packages from unnamed modules, causing test failures.
    jvmArgs(
        "--add-opens",
        "java.base/java.lang=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.util=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.time=ALL-UNNAMED",
    )
}

jacoco {
    toolVersion = "0.8.14"
}

val jacocoExcludes =
    listOf(
        // Android generated
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        // Hilt / Dagger generated
        "**/*_HiltModules*",
        "**/*_Factory*",
        "**/*_MembersInjector*",
        "**/Hilt_*",
        "**/dagger/**",
        "**/*Module_*",
        "**/*_Impl*",
        // Compose generated
        "**/*ComposableSingletons*",
        // Android framework classes (require device/emulator, not unit-testable)
        "**/McpApplication*",
        "**/services/mcp/McpServerService*",
        "**/services/mcp/BootCompletedReceiver*",
        "**/services/screencapture/ScreenCaptureService*",
        "**/services/accessibility/McpAccessibilityService*",
        // Platform connector: Android-runtime classes (WebSocket/service/keystore) are not
        // unit-testable on the JVM; the pure logic (crypto, framing, dedupe) IS covered by tests.
        "**/services/connector/PlatformConnector*",
        "**/services/connector/crypto/KeystoreDeviceIdentity*",
        // UI layer (requires instrumented/Compose tests)
        "**/ui/**",
        // Dependency injection configuration
        "**/di/**",
    )

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testGmsDebugUnitTest")

    reports {
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/jacocoTestReport/html"))
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jacocoTestReport/jacocoTestReport.xml"))
        csv.required.set(false)
    }

    val debugTree =
        fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/gmsDebug") {
            exclude(jacocoExcludes)
        }

    classDirectories.setFrom(debugTree)
    sourceDirectories.setFrom(files("src/main/kotlin", "src/gms/kotlin"))
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include("jacoco/testGmsDebugUnitTest.exec")
        },
    )
}

tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn("jacocoTestReport")

    val debugTree =
        fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/gmsDebug") {
            exclude(jacocoExcludes)
        }

    classDirectories.setFrom(debugTree)
    sourceDirectories.setFrom(files("src/main/kotlin", "src/gms/kotlin"))
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include("jacoco/testGmsDebugUnitTest.exec")
        },
    )

    violationRules {
        rule {
            limit {
                minimum = "0.50".toBigDecimal()
            }
        }
    }
}
