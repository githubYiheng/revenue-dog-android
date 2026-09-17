package org.revdog.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.psiUtil.parents

/**
 * 结构对照 RC `detekt-rules/src/main/kotlin/com/revenuecat/purchases/detekt/ForbiddenPublicEnum.kt`。
 *
 * 公开 enum 会破坏二进制与源码兼容：后端加一个枚举值，宿主里穷尽的 `when` 当场编译不过。
 * 可扩展的对外枚举一律用 `@Poko class` + companion 里的 `@JvmField val` 常量（设计 §1）。
 */
class ForbiddenPublicEnum(config: Config) : Rule(config) {

    override val issue = Issue(
        id = "ForbiddenPublicEnum",
        severity = Severity.Maintainability,
        description = "Public enum classes break binary compatibility. " +
            "Adding new entries is a source-breaking change for consumers using exhaustive when expressions. " +
            "Use a @Poko class with companion constants, or annotate with @InternalRevenueDogAPI if intentional.",
        debt = Debt.TWENTY_MINS,
    )

    private val ignoreAnnotated: List<String> = valueOrDefault("ignoreAnnotated", emptyList())

    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        if (klass.isEnum() && isPublicApi(klass) && !isAnnotatedWith(klass, ignoreAnnotated)) {
            report(CodeSmell(issue, Entity.atName(klass), message = "${klass.name}: ${issue.description}"))
        }
    }

    private fun isPublicApi(klass: KtClass): Boolean {
        if (klass.isNonPublic()) return false
        return klass.parents.filterIsInstance<KtClassOrObject>().none { it.isNonPublic() }
    }

    private fun KtModifierListOwner.isNonPublic(): Boolean =
        hasModifier(KtTokens.PRIVATE_KEYWORD) ||
            hasModifier(KtTokens.INTERNAL_KEYWORD) ||
            hasModifier(KtTokens.PROTECTED_KEYWORD)

    private fun isAnnotatedWith(klass: KtClass, annotationNames: List<String>): Boolean {
        if (annotationNames.isEmpty()) return false
        return klass.annotationEntries.any { entry ->
            val shortName = entry.shortName?.asString() ?: return@any false
            annotationNames.any { it == shortName || it.endsWith(".$shortName") }
        }
    }
}
