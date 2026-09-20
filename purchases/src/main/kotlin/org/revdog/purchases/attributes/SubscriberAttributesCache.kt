package org.revdog.purchases.attributes

import android.content.SharedPreferences
import org.json.JSONObject
import org.revdog.purchases.Logger
import org.revdog.purchases.caching.DeviceCache
import org.revdog.purchases.common.keysSequence

/**
 * 属性本地缓冲：**按 appUserID 分桶** + `is_synced` 标记 + LWW。
 * 结构对照 RC `subscriberattributes/caching/SubscriberAttributesCache.kt`。
 *
 * 落盘形状与 RC 逐字一致（同一个 SharedPreferences，key `<prefix>.subscriberAttributes`）：
 *
 * ```json
 * { "attributes": { "<appUserID>": { "<key>": {"key": "...", "value": "...",
 *                                              "updated_at_ms": 123, "is_synced": false} } } }
 * ```
 *
 * **偏离 RC 一处**：RC 的条目里叫 `set_time`，我方叫 `updated_at_ms` —— 它就是上行的
 * LWW 时间戳，两个名字只会让人以为是两个概念（iOS 侧同名）。
 *
 * **偏离 iOS 一处**：iOS 是「一属性一文件」（坑 #51：`UserDefaults` 字典整体读-改-写非原子）。
 * Android 这边所有写入都在本类的 `@Synchronized` 方法里、且 `SharedPreferences` 自身是进程内
 * 单例 + 线程安全，RMW 的竞态在单进程内被这把锁挡住；多进程宿主（`android:process`）
 * 本来就不该共享 prefs（RC 同款前提）。保留 RC 的单 key 布局换取「一次 commit 写一批」。
 */
@Suppress("TooManyFunctions")
internal class SubscriberAttributesCache(
    private val preferences: SharedPreferences,
    apiKey: String,
) {

    private val cacheKey = "${DeviceCache.SHARED_PREFERENCES_PREFIX}$apiKey.subscriberAttributes"

    // region 读

    @Synchronized
    fun allStored(appUserID: String): Map<String, SubscriberAttribute> = readAll()[appUserID] ?: emptyMap()

    @Synchronized
    fun unsynced(appUserID: String): Map<String, SubscriberAttribute> =
        allStored(appUserID).filterValues { !it.isSynced }

    @Synchronized
    fun attribute(appUserID: String, key: String): SubscriberAttribute? = allStored(appUserID)[key]

    /** 全部身份的待同步属性（RC `getUnsyncedSubscriberAttributes()`：空白 appUserID 一律跳过）。 */
    @Synchronized
    fun unsyncedForAllUsers(): Map<String, Map<String, SubscriberAttribute>> = readAll()
        .filterKeys { it.isNotBlank() }
        .mapValues { (_, attributes) -> attributes.filterValues { !it.isSynced } }
        .filterValues { it.isNotEmpty() }

    // endregion

    // region 写

    @Synchronized
    fun setAttributes(appUserID: String, attributes: Map<String, SubscriberAttribute>) {
        if (attributes.isEmpty()) return
        val all = readAll().toMutableMap()
        all[appUserID] = (all[appUserID] ?: emptyMap()) + attributes
        writeAll(all)
    }

    /**
     * 标记已同步。结构对照 RC `SubscriberAttributesManager.markAsSynced`：
     * **只对「值与上行时一致」的条目生效** —— 上行途中被新 setter 覆盖过的属性必须留在待发队列，
     * 否则那次更新会永远发不出去。
     *
     * 已同步的**墓碑直接删条目**：服务端已经存了 NULL，本地不必再留
     * （也让 50 个自定义属性的计数干净，与 iOS 同口径）。
     */
    @Synchronized
    fun markSynced(appUserID: String, sent: Collection<SubscriberAttribute>) {
        if (sent.isEmpty()) return
        val all = readAll().toMutableMap()
        val current = (all[appUserID] ?: emptyMap()).toMutableMap()
        var changed = false
        sent.forEach { attribute ->
            val stored = current[attribute.key] ?: return@forEach
            if (stored.isSynced) return@forEach
            if (stored.value != attribute.value || stored.updatedAtMs != attribute.updatedAtMs) return@forEach
            if (stored.isTombstone) {
                current.remove(attribute.key)
            } else {
                current[attribute.key] = stored.copy(isSynced = true)
            }
            changed = true
        }
        if (!changed) return
        if (current.isEmpty()) all.remove(appUserID) else all[appUserID] = current
        writeAll(all)
    }

    /**
     * logIn 时把**待同步**属性搬到新身份（结构对照 RC `copyUnsyncedSubscriberAttributes`）。
     *
     * 只搬未同步的那些：已同步的属于旧 customer，服务端那边已经落库了。
     * 新身份下已有同键且不更旧 → 不覆盖（LWW，与 iOS `migrateIfOldIsAnonymous` 同规则）。
     *
     * **三端一致：只在「旧身份是匿名」时才会走到这里**（坑 #52，避免两个真实用户之间串属性）。
     * 这道门在调用方：RC 在 `IdentityManager.copySubscriberAttributesToNewUserIfOldIsAnonymous`，
     * iOS 在 `migrateIfOldIsAnonymous`，我方在 `PurchasesOrchestrator.logIn` 的成功回调里。
     * 本方法自己不判身份形态 —— 它只负责「搬」这个动作。
     */
    @Synchronized
    fun copyUnsynced(from: String, to: String) {
        if (from == to || from.isBlank() || to.isBlank()) return
        val source = unsynced(from)
        if (source.isEmpty()) return
        val all = readAll().toMutableMap()
        val target = (all[to] ?: emptyMap()).toMutableMap()
        source.forEach { (key, attribute) ->
            val existing = target[key]
            if (existing != null && existing.updatedAtMs >= attribute.updatedAtMs) return@forEach
            target[key] = attribute.copy(isSynced = false)
        }
        all[to] = target
        // 旧身份的整桶清掉（RC `clearAllSubscriberAttributesFromUser`）：已同步项归旧 customer，
        // 未同步项已经搬走了。
        all.remove(from)
        writeAll(all)
        Logger.debug { "已把 ${source.size} 条待同步属性从 $from 搬到 $to" }
    }

    @Synchronized
    fun clear(appUserID: String) {
        val all = readAll().toMutableMap()
        if (all.remove(appUserID) == null) return
        writeAll(all)
    }

    /** 已全部同步的其它身份的桶可以删（RC `clearSubscriberAttributesIfSyncedForSubscriber`）。 */
    @Synchronized
    fun clearIfAllSynced(appUserID: String) {
        if (unsynced(appUserID).isEmpty()) clear(appUserID)
    }

    // endregion

    // region 落盘

    @Suppress("ReturnCount")
    private fun readAll(): Map<String, Map<String, SubscriberAttribute>> {
        val raw = preferences.getString(cacheKey, null) ?: return emptyMap()
        return runCatching {
            val attributes = JSONObject(raw).optJSONObject(ROOT_KEY) ?: return emptyMap()
            attributes.keysSequence().mapNotNull { appUserID ->
                val perUser = attributes.optJSONObject(appUserID) ?: return@mapNotNull null
                val parsed = perUser.keysSequence().mapNotNull { key ->
                    perUser.optJSONObject(key)?.let { SubscriberAttribute.fromJson(it) }?.let { key to it }
                }.toMap()
                appUserID to parsed
            }.toMap()
        }.getOrElse {
            Logger.warn { "属性缓存解析失败，按空处理" }
            emptyMap()
        }
    }

    private fun writeAll(value: Map<String, Map<String, SubscriberAttribute>>) {
        val attributes = JSONObject()
        value.forEach { (appUserID, perUser) ->
            val userJson = JSONObject()
            perUser.forEach { (key, attribute) -> userJson.put(key, attribute.toJson()) }
            attributes.put(appUserID, userJson)
        }
        preferences.edit().putString(cacheKey, JSONObject().put(ROOT_KEY, attributes).toString()).apply()
    }

    // endregion

    private companion object {
        const val ROOT_KEY = "attributes"
    }
}
