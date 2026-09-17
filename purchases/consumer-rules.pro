# RevenueDog SDK consumer ProGuard 规则（随 aar 分发，设计 §7）。
#
# 门禁：`:example:assembleRelease`（`minifyEnabled true`）+ `scripts/r8-check.sh`。
# 宿主**不该**为了用 SDK 抄任何规则 —— 手测 app 的 `proguard-rules.pro` 里一条 keep 都没有，
# 缺什么会在那条门禁上当场暴露。
#
# 只保留公开面与反射/序列化会用到的部分；宿主**不要**把自己的代码放进 org.revdog.**
# 包名下 —— 下面的 keep 规则会让那些类在 release 里无法被裁剪（对照 RC codegen README 的提醒）。
#
# ⚠️ **偏离 RC，待主代理复核**：RC 的 consumer 规则**不**整包 keep `com.revenuecat.**`，
# 它只 keep 反射真正需要的那几处（枚举常量名、Parcelable CREATOR、org.json、若干 dontwarn），
# 其余靠「宿主代码引用到的才留」由 R8 自己算。下面第一条规则比 RC 宽：它让整个 SDK 的公开面
# 在任何宿主里都不可裁剪、不可改名（可预测、排障时堆栈是原名），代价是宿主哪怕只用
# `configure` + `purchase` 也要背上全部公开类。首个 tag 前是否收窄到 RC 口径，由主代理裁。
-keep class org.revdog.purchases.** { public protected *; }
-keepclassmembers class org.revdog.purchases.** {
    public <init>(...);
}

# Play Billing 的回调接口通过 IPC 反射调用。
-keep class com.android.billingclient.api.** { *; }

# 保留 SDK 公开注解，宿主编译期要看得到。
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions

# org.json —— **SDK 的整个解析层都建在它上面**（`HTTPResult.body` / `CustomerInfoFactory` /
# `OfferingParser` / `DiagnosticsEvent`；考古 §8.1：我方不带 Gson/Moshi）。
# 规则逐字对照 RC `purchases/consumer-rules.pro` 末段，理由照抄：
#   org.json 是 Android framework 的一部分，类**通常**总是可用；但有些宿主把它显式或
#   传递地加进了自己的 classpath，一旦那样它就变成可被裁剪的 program class。
# 没有这条规则时，那些宿主的 release 构建会在第一次解析响应时炸 NoSuchMethodError。
# `:example` 模块**故意**把 `org.json:json` 加成真实依赖来复现这个场景，
# `scripts/r8-check.sh` 断言它出现在 seeds.txt 里。
-keep class org.json.* { *; }

# `PurchasesOrchestrator` 用 `androidx.lifecycle.DefaultLifecycleObserver` 观察进程前后台
# （前台补报与属性同步的触发点）。规则对照 RC consumer-rules 同名一条：
# androidx.lifecycle 自己的 consumer 规则覆盖了绝大多数情况，这条是显式兜底。
-keep class androidx.lifecycle.DefaultLifecycleObserver

# **故意不抄 RC 的另外三类规则**，逐条写明理由（抄了反而是噪音）：
# 1. `-keepclassmembers enum com.revenuecat.** { <fields>; values(); valueOf(); }`
#    —— RC 的 `EnumDeserializerWithDefault` 用 `Class.enumConstants` + `name.lowercase()`
#    按**枚举常量名**反射匹配 JSON。我方公开面一个 enum 都没有（`ForbiddenPublicEnum`
#    detekt 规则强制），内部 enum（`Delay` / `DeliveryOrigin` / `HTTPResult.Origin` /
#    `DiagnosticsUploader.Skip`）只在代码里按引用使用，从不按名字反射 → 不需要。
# 2. `-keepclassmembers class ... implements android.os.Parcelable { CREATOR; }`
#    —— 我方没有任何 Parcelable（公开模型是 `@Poko class`，跨进程传递不在契约里）。
# 3. `-dontwarn com.google.errorprone.** / com.amazon.** / java.lang.ClassValue`
#    —— 那三条对应 RC 的 errorprone 注解、Amazon Appstore feature 模块与 kotlinx.serialization；
#    我方依赖里没有任何一项，R8 在 `:example:assembleRelease` 上零 missing-class 警告。
