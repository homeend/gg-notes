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
version = "0.1.0+" + runCatching { gitShortSha.get().ifEmpty { "dev" } }.getOrDefault("dev")

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
