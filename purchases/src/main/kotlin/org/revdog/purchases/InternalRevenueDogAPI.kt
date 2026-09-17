package org.revdog.purchases

/**
 * 结构对照 RC `InternalRevenueCatAPI.kt`。
 *
 * 标记「语言上 public、但不是给宿主开发者用」的符号（跨模块使用、混合框架 SDK）。
 * metalava 会把它标注的一切**从 api.txt 里隐藏**，所以它不算我方承诺的公开面。
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This API is internal to RevenueDog and may change or be removed without a major version bump.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
)
public annotation class InternalRevenueDogAPI
