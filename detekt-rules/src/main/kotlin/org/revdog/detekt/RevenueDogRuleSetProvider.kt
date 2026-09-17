package org.revdog.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

/** 结构对照 RC `detekt-rules/.../RevenueCatRuleSetProvider.kt`。 */
class RevenueDogRuleSetProvider : RuleSetProvider {

    override val ruleSetId: String = "revenuedog"

    override fun instance(config: Config): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            ForbiddenPublicEnum(config),
        ),
    )
}
