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

// ---- Maven 发布（ADR 0072：自托管静态仓库 `https://maven.revdog.org/releases`）----
//
// 坐标 `org.revdog:purchases:<VERSION_NAME>`，POM 元数据在 `gradle.properties` 的 POM_* 段。
// `afterEvaluate` 是必须的：`components["release"]` 由 AGP 在自己的 afterEvaluate 里才注册
// （`singleVariant("release")` 决定了只有这一个）。
//
// Gradle 这一侧**只发到本地 staging 目录**（`build/maven-staging`，标准 Maven 布局 + 校验和），
// 不持有任何凭据、不碰网络；上传 R2 由 `scripts/sdk-android-maven-publish.sh` 做，
// 门禁（版本不可变、与公开仓库 tag 同源、先拉回远端 maven-metadata.xml 再合并）也都在那里。
// 不引入第三方发布插件：手写 `maven-publish` 足够，少一个要跟 AGP 版本对齐的依赖。
//   ./gradlew :purchases:publishReleasePublicationToStagingRepository
//   ./gradlew :purchases:publishToMavenLocal        # 本地宿主联调用
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
        repositories {
            maven {
                name = "staging"
                url = uri(layout.buildDirectory.dir("maven-staging"))
            }
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
