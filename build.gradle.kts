import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
    id("java")
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.18.0"
}

group = "dev.ggnotes"
// The +<sha> suffix pins each build to its commit — visible in Settings > Plugins
// and in the zip name, so "which build is installed?" always has an answer.
val gitShortSha = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
}.standardOutput.asText.map { it.trim() }
version = "0.2.9+" + runCatching { gitShortSha.get().ifEmpty { "dev" } }.getOrDefault("dev")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Ultimate base, same artifact as git-focus (already in the local Gradle
        // cache). The distribution embeds no IDE and targets 242+ across the
        // IntelliJ-platform family.
        intellijIdeaUltimate("2024.2.5")
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
    }
    // Store reader: TOML 1.0 (Apache-2.0; pulls antlr4-runtime, BSD-3) and the
    // JetBrains markdown parser (Apache-2.0). The platform supplies the Kotlin
    // stdlib, so it is excluded from the markdown artifact. Gson is bundled by
    // the platform (lib/util-8.jar) and is not declared here.
    implementation("org.tomlj:tomlj:1.1.1")
    implementation("org.jetbrains:markdown:0.7.3") {
        exclude(group = "org.jetbrains.kotlin")
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.ggnotes"
        name = "GgNotes"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "242"
            // open-ended compatibility: no until-build stamp in the distribution
            untilBuild = provider { null }
        }
    }
    // no settings UI — skip the IDE-booting searchable-options step
    buildSearchableOptions = false
    // Marketplace publishing. All secrets come from the environment; the
    // blocks are inert when the variables are unset.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    pluginVerification {
        ides {
            // floor and newest released IDE; keep explicit so verification
            // doesn't download every major release in the open range
            select {
                types = listOf(IntelliJPlatformType.IntellijIdeaCommunity)
                channels = listOf(ProductRelease.Channel.RELEASE)
                sinceBuild = "242"
                untilBuild = "242.*"
            }
            select {
                types = listOf(IntelliJPlatformType.IntellijIdeaCommunity)
                channels = listOf(ProductRelease.Channel.RELEASE)
                sinceBuild = "243"
                untilBuild = "243.*"
            }
            select {
                // 2025.3+ ships as the unified IntelliJ IDEA distribution,
                // not as a separate Community (IC) artifact
                types = listOf(IntelliJPlatformType.IntellijIdea)
                channels = listOf(ProductRelease.Channel.RELEASE)
                sinceBuild = "253"
                untilBuild = "253.*"
            }
            select {
                // current-generation targets: 2026.1 and 2026.2
                types = listOf(IntelliJPlatformType.IntellijIdea)
                channels = listOf(ProductRelease.Channel.RELEASE)
                sinceBuild = "261"
                untilBuild = "262.*"
            }
        }
    }
}

// Acceptance runs (AGENTS.md §3.2): `./gradlew runIde --args=/path/to/checkout`
// opens that project in the sandbox IDE without the trust dialog;
// `-Pggnotes.stateHome=/tmp/x` points gg's store lookup at a throwaway
// XDG_STATE_HOME holding notes/<key>/commits.toml.
// `-Pggnotes.demo` makes the startup activity open the tool window, the
// first overview and its first anchor by itself (screenshots, smoke runs).
fun org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask.acceptanceDefaults() {
    systemProperty("idea.trust.all.projects", "true")
    // a fresh sandbox otherwise stops at the privacy-policy / data-sharing dialogs
    systemProperty("jb.privacy.policy.text", "<!--999.999-->")
    systemProperty("jb.consents.confirmation.enabled", "false")
    providers.gradleProperty("ggnotes.stateHome").orNull?.let { environment("XDG_STATE_HOME", it) }
    providers.gradleProperty("ggnotes.demo").orNull?.let { shot ->
        systemProperty("ggnotes.demo", "true")
        // -Pggnotes.demo=/path/file.png also saves a picture of the IDE frame there
        if (shot.isNotBlank()) systemProperty("ggnotes.demo.shot", shot)
        // -Pggnotes.demoKind=review|notes opens that row kind instead of the first overview
        providers.gradleProperty("ggnotes.demoKind").orNull?.let { systemProperty("ggnotes.demo.kind", it) }
        // -Pggnotes.demoAnchor=N goes to anchor N instead of the first one
        providers.gradleProperty("ggnotes.demoAnchor").orNull?.let { systemProperty("ggnotes.demo.anchor", it) }
        // -Pggnotes.demoDelay=S takes the shot S seconds after the tool window opened (default 9)
        providers.gradleProperty("ggnotes.demoDelay").orNull?.let { systemProperty("ggnotes.demo.delay", it) }
        // -Pggnotes.demoDocumentOnly=true hides the list before the shot (the Document Only toggle)
        providers.gradleProperty("ggnotes.demoDocumentOnly").orNull?.let { systemProperty("ggnotes.demo.documentOnly", it) }
    }
}
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") { acceptanceDefaults() }

// The IU sandbox stops at its first-run license dialog; Community has none,
// so `./gradlew runIdeCommunity --args=...` is the headless-friendly run.
intellijPlatformTesting.runIde {
    register("runIdeCommunity") {
        type = IntelliJPlatformType.IntellijIdeaCommunity
        version = "2024.2.5"
        task { acceptanceDefaults() }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Pinned to the Kotlin bundled with the oldest supported IDE
        // (2024.2 ships stdlib 1.9). Left unset, the 2.3 compiler emits
        // calls into stdlib 2.2+ intrinsics (e.g. SpillingKt in suspend
        // functions), a NoSuchClassError on 242 at runtime that only
        // `gradlew verifyPlugin` catches. Raise this only together with
        // since-build.
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
    }
}
