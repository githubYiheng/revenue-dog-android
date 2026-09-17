package org.revdog.purchases.support

/**
 * 夹具。
 *
 * [SUBSCRIBER_RESPONSE] **逐字取自 `docs/plan/api-contract-v1.md` §2.2 的完整响应示例**
 * （RC 官方原文），只额外加了 Google 专属的三个字段：`product_plan_identifier`（entitlement 与
 * subscription 两处）与 `auto_resume_date`（考古 §2.10 标「我方契约必须新增」的项）。
 *
 * 用真实契约示例做夹具、而不是自己编一个最小 JSON：编的那种会在字段可空性上骗过自己。
 */
internal object Fixtures {

    /**
     * `POST /v1/receipts` 的成功响应 = 完整 Subscriber + **`purchased_products`**（考古 §2.8）。
     *
     * @param shouldConsumeByProductId `null` = 整个 `purchased_products` 字段缺失（契约违规）；
     * map 里 value 为 `null` = 有条目但没有 `should_consume`（同样是契约违规）。
     */
    fun receiptResponse(shouldConsumeByProductId: Map<String, Boolean?>?): String {
        val json = org.json.JSONObject(SUBSCRIBER_RESPONSE)
        if (shouldConsumeByProductId != null) {
            val purchased = org.json.JSONObject()
            shouldConsumeByProductId.forEach { (productId, shouldConsume) ->
                purchased.put(
                    productId,
                    org.json.JSONObject().apply { shouldConsume?.let { put("should_consume", it) } },
                )
            }
            json.put("purchased_products", purchased)
        }
        return json.toString()
    }


    const val SUBSCRIBER_RESPONSE: String = """
{
  "request_date": "2019-07-26T17:40:10Z",
  "request_date_ms": 1564162810884,
  "subscriber": {
    "entitlements": {
      "pro_cat": {
        "expires_date": null,
        "grace_period_expires_date": null,
        "product_identifier": "onetime",
        "purchase_date": "2019-04-05T21:52:45Z"
      },
      "premium": {
        "expires_date": "2019-08-14T21:07:40Z",
        "grace_period_expires_date": null,
        "product_identifier": "annual",
        "product_plan_identifier": "annual-base",
        "purchase_date": "2019-07-14T20:07:40Z"
      }
    },
    "first_seen": "2019-02-21T00:08:41Z",
    "last_seen": "2019-07-26T17:40:10Z",
    "management_url": "https://play.google.com/store/account/subscriptions",
    "account_token": "0123456789abcdef0123456789abcdef",
    "non_subscriptions": {
      "onetime": [
        {
          "id": "cadba0c81b",
          "is_sandbox": true,
          "purchase_date": "2019-04-05T21:52:45Z",
          "store": "play_store"
        }
      ]
    },
    "original_app_user_id": "XXX-XXXXX-XXXXX-XX",
    "original_application_version": "1.0",
    "original_purchase_date": "2019-01-30T23:54:10Z",
    "other_purchases": {},
    "subscriptions": {
      "annual": {
        "auto_resume_date": null,
        "billing_issues_detected_at": null,
        "expires_date": "2019-08-14T21:07:40Z",
        "grace_period_expires_date": null,
        "is_sandbox": true,
        "original_purchase_date": "2019-02-21T00:42:05Z",
        "ownership_type": "PURCHASED",
        "period_type": "normal",
        "product_plan_identifier": "annual-base",
        "purchase_date": "2019-07-14T20:07:40Z",
        "refunded_at": null,
        "store": "play_store",
        "store_transaction_id": "GPA.6801-7988-0152-76034..5",
        "unsubscribe_detected_at": "2019-07-17T22:48:38Z"
      },
      "rc_promo_pro_cat_monthly": {
        "auto_resume_date": null,
        "billing_issues_detected_at": null,
        "expires_date": "2019-08-26T01:02:16Z",
        "grace_period_expires_date": null,
        "is_sandbox": false,
        "original_purchase_date": "2019-07-26T01:02:16Z",
        "ownership_type": "FAMILY_SHARED",
        "period_type": "normal",
        "purchase_date": "2019-07-26T01:02:16Z",
        "refunded_at": null,
        "store": "promotional",
        "store_transaction_id": "a42db3af39530cb82b17eaf9c6576393",
        "unsubscribe_detected_at": null
      }
    }
  }
}
"""

    /** 后端加了我们不认识的枚举值与字段（坑 42：解析必须容忍，绝不能抛）。 */
    const val SUBSCRIBER_RESPONSE_WITH_UNKNOWN_ENUMS: String = """
{
  "request_date": "2026-09-18T00:00:00Z",
  "request_date_ms": 1789689600000,
  "brand_new_top_level_field": {"whatever": true},
  "subscriber": {
    "entitlements": {
      "pro": {
        "expires_date": "2099-01-01T00:00:00Z",
        "grace_period_expires_date": null,
        "product_identifier": "sub_a",
        "purchase_date": "2026-09-01T00:00:00Z"
      }
    },
    "first_seen": "2026-01-01T00:00:00Z",
    "last_seen": "2026-09-18T00:00:00Z",
    "original_app_user_id": "user-1",
    "subscriptions": {
      "sub_a": {
        "expires_date": "2099-01-01T00:00:00Z",
        "purchase_date": "2026-09-01T00:00:00Z",
        "original_purchase_date": "2026-01-01T00:00:00Z",
        "store": "some_new_store_we_dont_know",
        "period_type": "some_new_period_type",
        "ownership_type": "SOMETHING_ELSE",
        "is_sandbox": false,
        "some_future_field": 42
      }
    },
    "non_subscriptions": {}
  }
}
"""

    /**
     * offerings 响应。含契约 §2.3 的 `platform_product_plan_identifier`
     * （考古 §2.15 D 项：Android 侧必须 P0 下发）。
     *
     * `$rd_missing` 这个 package 故意指向一个 Play 上不存在的商品，
     * 用来验证「不丢 package、`product` 为 null、进 notFoundProductIds」。
     */
    const val OFFERINGS_RESPONSE: String = """
{
  "current_offering_id": "default",
  "offerings": [
    {
      "identifier": "default",
      "description": "The default offering",
      "packages": [
        {
          "identifier": "${'$'}rc_monthly",
          "platform_product_identifier": "sub_premium",
          "platform_product_plan_identifier": "monthly-base"
        },
        {
          "identifier": "${'$'}rc_annual",
          "platform_product_identifier": "sub_premium",
          "platform_product_plan_identifier": "annual-base"
        },
        {
          "identifier": "consumable",
          "platform_product_identifier": "coins_100"
        },
        {
          "identifier": "${'$'}rd_missing",
          "platform_product_identifier": "not_on_play",
          "platform_product_plan_identifier": "nope"
        }
      ]
    }
  ]
}
"""
}
