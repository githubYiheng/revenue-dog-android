package org.revdog.purchases.offerings

import org.json.JSONObject
import org.revdog.purchases.Logger
import org.revdog.purchases.ProductType
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.caching.DeviceCache
import org.revdog.purchases.common.CacheDurations
import org.revdog.purchases.common.DateProvider
import org.revdog.purchases.common.DefaultDateProvider
import org.revdog.purchases.common.DeliveryOrigin
import org.revdog.purchases.common.MainDispatcher
import org.revdog.purchases.google.BillingWrapper
import org.revdog.purchases.models.StoreProduct
import org.revdog.purchases.networking.Backend

/**
 * offerings 的取数、缓存与 **ProductDetails 批量填充**。
 * 结构对照 RC `common/offerings/OfferingsManager.kt` + `OfferingsFactory`。
 *
 * 缓存分两层（与 iOS 同策略）：
 * - **磁盘**只存后端下发的原始响应（不冻价格，坑 31）；
 * - **内存**存填好商品详情的成品。进程重启后从磁盘响应重新填一次。
 */
internal class OfferingsManager(
    private val deviceCache: DeviceCache,
    private val backend: Backend,
    private val billing: BillingWrapper,
    private val mainDispatcher: MainDispatcher,
    private val dateProvider: DateProvider = DefaultDateProvider(),
) {

    val cachedOfferings: Offerings?
        get() = deviceCache.getOfferingsMemoryCache()

    /**
     * @param fetchCurrent 忽略缓存强制联网（logIn / 购买后调用）。
     * @param onSuccess 第二个参数是这份 offerings 的**来源**（M4 新增，
     * `offerings_fetch.cache_hit` 由它推导；[DeliveryOrigin.STALE_FALLBACK] 还会让编排层
     * 多记一条 `sdk_warning{offerings_cache_fallback}`）。
     */
    fun getOfferings(
        appUserID: String,
        appInBackground: Boolean,
        fetchCurrent: Boolean = false,
        onError: (PurchasesError) -> Unit,
        onSuccess: (Offerings, DeliveryOrigin) -> Unit,
    ) {
        val cached = cachedOfferings
        when {
            fetchCurrent || cached == null -> fetchFromNetwork(appUserID, appInBackground, onError, onSuccess)
            else -> {
                // 缓存命中先交付；过期了顺手在后台刷一次（RC 同款 `vendCachedOfferingsAndMaybeRefresh`）。
                mainDispatcher.dispatch { onSuccess(cached, DeliveryOrigin.CACHE) }
                if (CacheDurations.isStale(
                        deviceCache.getOfferingsCachesLastUpdated(),
                        appInBackground,
                        dateProvider.now(),
                    )
                ) {
                    Logger.debug { "offerings 缓存已过期，后台刷新" }
                    fetchFromNetwork(appUserID, appInBackground, onError = {}, onSuccess = { _, _ -> })
                }
            }
        }
    }

    private fun fetchFromNetwork(
        appUserID: String,
        appInBackground: Boolean,
        onError: (PurchasesError) -> Unit,
        onSuccess: (Offerings, DeliveryOrigin) -> Unit,
    ) {
        backend.getOfferings(
            appUserID = appUserID,
            appInBackground = appInBackground,
            onSuccess = { response ->
                deviceCache.cacheOfferingsResponse(response)
                createOfferings(response, DeliveryOrigin.NETWORK, onError, onSuccess)
            },
            onError = { error, _ ->
                // 后端失败时用磁盘上的原始响应兜底（stale 缓存优于空白付费墙）。
                val cachedResponse = deviceCache.getCachedOfferingsResponse()
                if (cachedResponse != null) {
                    Logger.warn { "offerings 拉取失败，退回磁盘缓存：$error" }
                    createOfferings(cachedResponse, DeliveryOrigin.STALE_FALLBACK, onError, onSuccess)
                } else {
                    mainDispatcher.dispatch { onError(error) }
                }
            },
        )
    }

    /**
     * 解析 + **按 type 分两次查 Play** + 填充。
     *
     * 分两次是必须的（考古 §3.8 / 坑 11）：先用全部 productIds 查 SUBS，
     * 再用**同一批** productIds 查 INAPP。Play 不接受混合 type 的查询。
     */
    private fun createOfferings(
        response: JSONObject,
        origin: DeliveryOrigin,
        onError: (PurchasesError) -> Unit,
        onSuccess: (Offerings, DeliveryOrigin) -> Unit,
    ) {
        val productIds = OfferingParser.productIdsToQuery(response)
        if (productIds.isEmpty()) {
            deliver(OfferingParser.createOfferings(response, emptyMap(), emptyList()), origin, onSuccess)
            return
        }

        billing.queryProductDetailsAsync(
            productType = ProductType.SUBS,
            productIds = productIds,
            onReceive = { subsResult ->
                billing.queryProductDetailsAsync(
                    productType = ProductType.INAPP,
                    productIds = productIds,
                    onReceive = { inAppResult ->
                        val allProducts = subsResult.storeProducts + inAppResult.storeProducts
                        // 一个 id 在 SUBS 查不到、在 INAPP 查到了（或反之）不算 not found：
                        // 取两次查询的**交集**才是真的一个都没查到。
                        val found = allProducts.map { it.productId }.toSet()
                        val notFound = (subsResult.notFoundProductIds + inAppResult.notFoundProductIds)
                            .distinct()
                            .filterNot { it in found }
                        deliver(
                            OfferingParser.createOfferings(response, allProducts.groupById(), notFound),
                            origin,
                            onSuccess,
                        )
                    },
                    onError = { error -> mainDispatcher.dispatch { onError(error) } },
                )
            },
            onError = { error -> mainDispatcher.dispatch { onError(error) } },
        )
    }

    private fun deliver(
        offerings: Offerings,
        origin: DeliveryOrigin,
        onSuccess: (Offerings, DeliveryOrigin) -> Unit,
    ) {
        if (offerings.notFoundProductIds.isNotEmpty()) {
            Logger.warn {
                "offerings 中有 ${offerings.notFoundProductIds.size} 个商品在 Play 上找不到：" +
                    offerings.notFoundProductIds.joinToString()
            }
        }
        deviceCache.cacheOfferingsInMemory(offerings)
        mainDispatcher.dispatch { onSuccess(offerings, origin) }
    }

    private fun List<StoreProduct>.groupById(): Map<String, List<StoreProduct>> = groupBy { it.productId }
}
