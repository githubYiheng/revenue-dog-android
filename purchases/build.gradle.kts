import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.poko)
    alias(libs.plugins.detekt)
    alias(libs.plugins.metalava)
    `maven-publish`
}

// Kotlin 支持来自 AGP 9 的内置集成（`android.builtInKotlin`，默认开）：
// 不再 apply `org.jetbrains.kotlin.android`，它与 AGP 9 的 new DSL 不兼容。

android {
    namespace = "org.revdog.purchases"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        // consumer ProGuard 规则随包（设计 §7）。
        consumerProguardFiles("consumer-rules.pro")
    }

    // 不开 BuildConfig：版本号硬编码在 `common/Config.kt`（对照 RC `Config.frameworkVersion`），
    // 由 `ConfigVersionTest` 与 gradle.properties / 版本目录对账。
    // 顺带把生成的 `BuildConfig` 挡在 metalava 公开面之外。
    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    // 单一变体（考古 §9.2：不要 productFlavors）。
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// ---- Maven 发布（M4 **占位**：配置齐、凭据待用户定）----
//
// 坐标 `org.revdog:purchases:<VERSION_NAME>`，POM 元数据在 `gradle.properties` 的 POM_* 段。
// `afterEvaluate` 是必须的：`components["release"]` 由 AGP 在自己的 afterEvaluate 里才注册
// （`singleVariant("release")` 决定了只有这一个）。
//
// **TODO(user)**：发布渠道（GitHub Packages / Maven Central Portal）、账号、凭据、
// 以及 Central 需要的 GPG 签名密钥，全部由用户定。三个属性
// （`revdogMavenUrl` / `revdogMavenUser` / `revdogMavenPassword`）走
// `~/.gradle/gradle.properties` 或 `-P` 传入，**绝不入库**；缺任一时只注册 mavenLocal，
// `./gradlew :purchases:publishToMavenLocal` 可以在本地把 aar + sources jar + pom 验一遍。
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.property("GROUP") as String
                artifactId = project.property("POM_ARTIFACT_ID") as String
                version = project.property("VERSION_NAME") as String
                pom {
                    name.set(project.property("POM_NAME") as String)
                    description.set(project.property("POM_DESCRIPTION") as String)
                    url.set(project.property("POM_URL") as String)
                    licenses {
                        license {
                            name.set(project.property("POM_LICENCE_NAME") as String)
                            url.set(project.property("POM_LICENCE_URL") as String)
                            distribution.set(project.property("POM_LICENCE_DIST") as String)
                        }
                    }
                    developers {
                        developer {
                            id.set(project.property("POM_DEVELOPER_ID") as String)
                            name.set(project.property("POM_DEVELOPER_NAME") as String)
                        }
                    }
                    scm {
                        url.set(project.property("POM_SCM_URL") as String)
                        connection.set(project.property("POM_SCM_CONNECTION") as String)
                        developerConnection.set(project.property("POM_SCM_DEV_CONNECTION") as String)
                    }
                }
            }
        }
        val repoUrl = project.findProperty("revdogMavenUrl") as String?
        val repoUser = project.findProperty("revdogMavenUser") as String?
        val repoPassword = project.findProperty("revdogMavenPassword") as String?
        if (repoUrl != null && repoUser != null && repoPassword != null) {
            repositories {
                maven {
                    name = "revdog"
                    url = uri(repoUrl)
                    credentials {
                        username = repoUser
                        password = repoPassword
                    }
                }
            }
        } else {
            // `info` 级而不是 `lifecycle`：这行会在每次 configure 时求值，
            // 用 lifecycle 会污染 api-check / r8-check 的输出。TODO(user) 的正式提示在
            // `scripts/sdk-android-release.sh` 的 dry-run 末尾。
            logger.info(
                "TODO(user)：未配 revdogMavenUrl / revdogMavenUser / revdogMavenPassword，" +
                    "只有 publishToMavenLocal 可用（凭据与发布渠道由用户定）",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// 公开面只进不出：显式 API 模式强制 public 声明写出可见性与返回类型。
// **只加在生产 source set 上** —— 测试里写 `class FooTest` 不该被逼着加 `public`。
tasks.withType<KotlinCompile>().configureEach {
    if (!name.contains("UnitTest") && !name.contains("AndroidTest")) {
        compilerOptions.freeCompilerArgs.add("-Xexplicit-api=strict")
    }
}

// 版本号对账用（ConfigVersionTest）：把构建期的真相源喂给单测。
tasks.withType<Test>().configureEach {
    systemProperty("revdog.versionName", project.property("VERSION_NAME") as String)
    systemProperty("revdog.billingClientVersion", libs.versions.billingClient.get())
    // 错误码表的两端唯一真相源（sdk/error-codes.json）。ErrorCodesContractTest 读它对账。
    systemProperty("revdog.errorCodesJson", rootProject.file("../error-codes.json").absolutePath)
}

dependencies {
    // `api` 而不是 `implementation`：宿主要能直接引用 BillingClient 类型（对照 RC `api(libs.billing)`）。
    api(libs.billing)

    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.common)
    api(libs.coroutines.core)
    implementation(libs.coroutines.android)

    detektPlugins(project(":detekt-rules"))

    testImplementation(libs.junit)
    testImplementation(libs.assertJ)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    // Android 在单测里把 org.json 打成 stub；塞一个真实实现，让解析路径真的被跑到
    // （对照 RC `purchases/build.gradle.kts:222`）。
    testImplementation(libs.json)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    // **故意不配 baseline**：从第一天就零遗留（RC 的 baseline 有 241 条，
    // 其中 24 条就是它自己的 ForbiddenPublicEnum）。要么改代码，要么最小范围 @Suppress。
    source.setFrom(files("src/main/kotlin"))
}

// metalava：公开 API 基线（考古 §8.4 三件套之一）。
// 生成的 BuildConfig 不进基线 —— 它随构建类型变化，不是我们承诺的公开面。
metalava {
    filename.set("api/purchases.api")
    hiddenAnnotations.add("org.revdog.purchases.InternalRevenueDogAPI")
    arguments.addAll(listOf("--hide", "ReferencesHidden"))
}
