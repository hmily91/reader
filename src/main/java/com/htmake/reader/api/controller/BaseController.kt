package com.htmake.reader.api.controller

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssArticle
import io.legado.app.model.webBook.WebBook
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.StaticHandler;
import mu.KotlinLogging
import com.htmake.reader.config.AppConfig
import com.htmake.reader.config.BookConfig
import io.legado.app.constant.DeepinkBookSource
import com.htmake.reader.utils.error
import com.htmake.reader.utils.success
import com.htmake.reader.utils.getStorage
import com.htmake.reader.utils.saveStorage
import com.htmake.reader.utils.asJsonArray
import com.htmake.reader.utils.asJsonObject
import com.htmake.reader.utils.toDataClass
import com.htmake.reader.utils.toMap
import com.htmake.reader.utils.fillData
import com.htmake.reader.utils.getWorkDir
import com.htmake.reader.utils.getRandomString
import com.htmake.reader.utils.genEncryptedPassword
import com.htmake.reader.entity.User
import com.htmake.reader.utils.SpringContextUtils
import com.htmake.reader.utils.deleteRecursively
import com.htmake.reader.utils.unzip
import com.htmake.reader.utils.zip
import com.htmake.reader.utils.jsonEncode
import com.htmake.reader.utils.getRelativePath
import com.htmake.reader.utils.getFileExtetion
import com.htmake.reader.verticle.RestVerticle
import com.htmake.reader.SpringEvent
import org.springframework.stereotype.Component
import io.vertx.core.json.JsonObject
import io.vertx.core.json.JsonArray
import io.vertx.core.http.HttpMethod
import com.htmake.reader.api.ReturnData
import io.legado.app.utils.MD5Utils
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.URL;
import java.util.UUID;
import io.vertx.ext.web.client.WebClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import java.io.File
import java.lang.Runtime
import kotlin.collections.mutableMapOf
import kotlin.system.measureTimeMillis
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat;
import io.legado.app.utils.EncoderUtils
import io.legado.app.model.rss.Rss
import org.springframework.scheduling.annotation.Scheduled
import io.legado.app.model.localBook.LocalBook
import java.nio.file.Paths
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import io.legado.app.help.coroutine.Coroutine

private val logger = KotlinLogging.logger {}

open class BaseController(override val coroutineContext: CoroutineContext): CoroutineScope {
    var loginExpireDays = 7

    val appConfig: AppConfig
    val env: Environment

    init {
        appConfig = SpringContextUtils.getBean("appConfig", AppConfig::class.java)
        env = SpringContextUtils.getBean(Environment::class.java)
    }

    suspend fun saveUserSession(context: RoutingContext, userMap: MutableMap<String, Map<String, Any>>, user: User, regenerateToken: Boolean = true): Map<String, Any> {
        user.last_login_at = System.currentTimeMillis()
        if (regenerateToken) {
            user.token = genEncryptedPassword(user.username, System.currentTimeMillis().toString())
            var tokenMap: MutableMap<String, Long>? = null
            var expire = System.currentTimeMillis() + loginExpireDays * 86400 * 1000
            if (user.token_map != null) {
                tokenMap = user.token_map as? MutableMap<String, Long>
            }
            if (tokenMap == null) {
                tokenMap = mutableMapOf(user.token to expire)
            } else {
                tokenMap.put(user.token, expire)
            }
            // 删除已过期token
            tokenMap.values.removeAll { it < user.last_login_at }
            user.token_map = tokenMap
        }
        userMap.put(user.username, user.toMap())
        saveStorage("data", "users", value = userMap)

        val loginData = formatUser(user)

        context.session().put("username", user.username)
        context.put("username", user.username)

        return loginData
    }

    suspend fun checkAuth(context: RoutingContext): Boolean {
        if (!appConfig.secure) {
            return true
        }
        var username = context.session().get("username") as String? ?: ""
        var userInfo = getUserInfoClass(username)
        if (userInfo != null) {
            context.put("username", userInfo.username)
            context.put("userInfo", userInfo)
            return true
        }
        // 自动登录
        var accessToken = context.queryParam("accessToken").firstOrNull() ?: ""
        if (accessToken.isNotEmpty()) {
            var userMap = mutableMapOf<String, Map<String, Any>>()
            var userMapJson: JsonObject? = asJsonObject(getStorage("data", "users"))
            if (userMapJson != null) {
                userMap = userMapJson.map as? MutableMap<String, Map<String, Any>> ?: mutableMapOf<String, Map<String, Any>>()
            }
            var tmp = accessToken.split(":", limit=2)
            if (tmp.size >= 2) {
                var _username = tmp[0]
                var token = tmp[1]
                var existedUser: User? = userMap.getOrDefault(_username, null)?.toDataClass()
                if (existedUser != null && token.isNotEmpty()) {
                    var isLogin = false
                    if (existedUser.token.isNotEmpty() && existedUser.token.equals(token)) {
                        isLogin = true
                    }
                    // 查找历史有效会话
                    if (!isLogin && existedUser.token_map != null) {
                        var tokenMap = existedUser.token_map as? MutableMap<String, Long>
                        if (tokenMap != null &&
                            tokenMap.containsKey(token)) {
                            if (tokenMap.getOrDefault(token, 0L) > System.currentTimeMillis()) {
                                isLogin = true
                                // 延长有效期
                                tokenMap.put(token, System.currentTimeMillis() + loginExpireDays * 86400 * 1000)
                            } else {
                                // 删除过期token
                                tokenMap.remove(token)
                            }
                            existedUser.token_map = tokenMap
                        }
                    }
                    if (isLogin) {
                        // 保存用户session
                        saveUserSession(context, userMap, existedUser, false)
                        context.put("username", existedUser.username)
                        context.put("userInfo", existedUser)
                    }
                    return isLogin
                }
            }
        }

        return false
    }

    fun checkManagerAuth(context: RoutingContext): Boolean {
        if (!appConfig.secure) {
            return true
        }
        if (appConfig.secureKey.isEmpty()) {
            return true
        }
        var secureKey = context.queryParam("secureKey").firstOrNull() ?: ""
        if (secureKey.equals(appConfig.secureKey)) {
            // 判断是否需要修改 userNameSpace
            var userNS = context.queryParam("userNS").firstOrNull()
            if (userNS != null && userNS.isNotEmpty()) {
                context.put("userNameSpace", userNS)
            }
            return true
        }
        return false
    }

    fun getUserNameSpace(context: RoutingContext): String {
        if (!appConfig.secure) {
            return "default"
        }
        // 管理权限，可以修改 userNameSpace 来获取任意用户信息
        checkManagerAuth(context)
        var userNS = context.get("userNameSpace") as String?
        if (userNS != null && userNS.isNotEmpty()) {
            return userNS
        }
        var username = context.get("username") as String?
        if (username != null) {
            return username;
        }
        return "default"
    }

    fun getUserStorage(context: Any, vararg path: String): String? {
        var userNameSpace = ""
        when(context) {
            is RoutingContext -> userNameSpace = getUserNameSpace(context)
            is String -> userNameSpace = context
        }
        if (userNameSpace.isEmpty()) {
            return getStorage("data", *path)
        }
        return getStorage("data", userNameSpace, *path)
    }

    fun saveUserStorage(context: Any, path: String, value: Any) {
        var userNameSpace = ""
        when(context) {
            is RoutingContext -> userNameSpace = getUserNameSpace(context)
            is String -> userNameSpace = context
        }
        if (userNameSpace.isEmpty()) {
            return saveStorage("data", path, value = value)
        }
        return saveStorage("data", userNameSpace, path, value = value)
    }

    fun getUserInfoClass(username: String): User? {
        var user: User? = getUserInfoMap(username)?.toDataClass()
        return user
    }

    fun getUserInfoMap(username: String): Map<String, Any>? {
        if (username.isEmpty()) {
            return null
        }
        var userMap = mutableMapOf<String, Map<String, Any>>()
        var userMapJson: JsonObject? = asJsonObject(getStorage("data", "users"))
        if (userMapJson != null) {
            userMap = userMapJson.map as MutableMap<String, Map<String, Any>>
        }
        return userMap.getOrDefault(username, null)
    }

    fun formatUser(userInfo: Any): MutableMap<String, Any> {
        var user: User? = null
        if (userInfo !is User) {
            var userMap = userInfo as? Map<String, Any>
            if (userMap != null) {
                user = userMap.toDataClass()
            }
        } else {
            user = userInfo
        }
        if (user == null) {
            return mutableMapOf()
        }
        return mutableMapOf(
            "username" to user.username,
            "lastLoginAt" to user.last_login_at,
            "accessToken" to user.username + ":" + user.token,
            "enableWebdav" to user.enable_webdav,
            "enableLocalStore" to user.enable_local_store,
            "role" to (if (user.role.isEmpty()) "user" else user.role),
            "createdAt" to user.created_at
        )
    }

    // ==================== 书源权限与用户授权（新增） ====================

    /**
     * 判断当前请求是否具备管理员权限
     * 1. 未开启鉴权（secure=false）时，所有人视为管理员
     * 2. 携带正确管理密码（管理模式）的请求视为管理员
     * 3. 登录用户角色为 admin 时视为管理员
     */
    fun isAdmin(context: RoutingContext): Boolean {
        if (!appConfig.secure) {
            return true
        }
        if (appConfig.secureKey.isNotEmpty()) {
            var secureKey = context.queryParam("secureKey").firstOrNull() ?: ""
            if (secureKey.isNotEmpty() && secureKey.equals(appConfig.secureKey)) {
                return true
            }
        }
        var username = context.get("username") as String?
        if (username.isNullOrEmpty()) {
            username = context.session().get("username") as String?
        }
        if (username.isNullOrEmpty()) {
            return false
        }
        var user = getUserInfoClass(username)
        if (user == null) {
            return false
        }
        return user.role.equals("admin")
    }

    /** 指定命名空间（用户名）是否为管理员：default 与 admin 角色可见全部书源 */
    fun isAdminUserNameSpace(userNameSpace: String): Boolean {
        if (!appConfig.secure) {
            return true
        }
        if (userNameSpace.isEmpty() || userNameSpace.equals("default")) {
            return true
        }
        var user = getUserInfoClass(userNameSpace)
        if (user == null) {
            return false
        }
        return user.role.equals("admin")
    }

    /** 书源授权表：bookSourceUrl -> 授权用户名列表，持久化在 storage/data/bookSourceGrants.json */
    fun getBookSourceGrantMap(): MutableMap<String, MutableList<String>> {
        var grantMap = mutableMapOf<String, MutableList<String>>()
        var grantJson: JsonObject? = asJsonObject(getStorage("data", "bookSourceGrants"))
        if (grantJson != null) {
            grantJson.forEach {
                var url = it.key
                var userList = parseUserList(it.value)
                if (url.isNotEmpty()) {
                    grantMap.put(url, userList)
                }
            }
        }
        return grantMap
    }

    /** 把授权表里的值（可能是数组、{"list":[...]}、字符串）统一解析成用户名列表 */
    fun parseUserList(value: Any?): MutableList<String> {
        var userList = mutableListOf<String>()
        when (value) {
            is JsonArray -> {
                for (i in 0 until value.size()) {
                    var username = value.getString(i)
                    if (!username.isNullOrEmpty()) {
                        userList.add(username)
                    }
                }
            }
            is JsonObject -> {
                var inner = value.getJsonArray("list")
                if (inner != null) {
                    for (i in 0 until inner.size()) {
                        var username = inner.getString(i)
                        if (!username.isNullOrEmpty()) {
                            userList.add(username)
                        }
                    }
                }
            }
            is Iterable<*> -> {
                value.forEach {
                    var username = it?.toString() ?: ""
                    if (username.isNotEmpty()) {
                        userList.add(username)
                    }
                }
            }
            is String -> {
                if (value.isNotEmpty()) {
                    userList.add(value)
                }
            }
        }
        return userList
    }

    /** 保存书源授权表
     * 注意：这里必须存「纯 Kotlin 集合」而不是 JsonArray —— saveStorage 用 gson 序列化，
     * JsonArray 会被 gson 当成普通 bean 写成 {"list":[...]}，再读回来就不是数组了。
     */
    fun saveBookSourceGrantMap(grantMap: Map<String, List<String>>) {
        var plainMap = mutableMapOf<String, List<String>>()
        grantMap.forEach {
            if (it.key.isNotEmpty()) {
                plainMap.put(it.key, it.value.filter { name -> name.isNotEmpty() })
            }
        }
        saveStorage("data", "bookSourceGrants", value = plainMap)
    }

    /** 系统书源（管理员维护的唯一数据源），位于 storage/data/default/bookSource.json */
    fun getSystemBookSourceJson(): JsonArray {
        var bookSourceList: JsonArray? = asJsonArray(getStorage("data", "default", "bookSource"))
        if (bookSourceList == null) {
            bookSourceList = JsonArray()
        }
        return bookSourceList
    }

    /** 把用户名列表转成 JsonArray */
    fun toUserJsonArray(userList: List<String>?): JsonArray {
        var userArray = JsonArray()
        if (userList != null) {
            for (name in userList) {
                if (name.isNotEmpty()) {
                    userArray.add(name)
                }
            }
        }
        return userArray
    }

    /** 把 Vert.x 的 JsonObject/JsonArray 递归转成普通 Map/List。
     * Jackson 直接序列化 JsonObject 会输出 {"map":...}、JsonArray 会输出 {"list":...}，
     * 返回给前端/客户端前必须先转成纯集合类型。
     */
    fun toPlainValue(value: Any?): Any? {
        return when (value) {
            is JsonObject -> {
                var map = linkedMapOf<String, Any?>()
                value.forEach {
                    map.put(it.key, toPlainValue(it.value))
                }
                map
            }
            is JsonArray -> {
                var list = arrayListOf<Any?>()
                for (i in 0 until value.size()) {
                    list.add(toPlainValue(value.getValue(i)))
                }
                list
            }
            else -> value
        }
    }

    /** 按授权关系过滤出指定用户可见的书源 */
    fun filterVisibleBookSource(bookSourceList: JsonArray, userNameSpace: String): JsonArray {
        if (!appConfig.secure) {
            return bookSourceList
        }
        if (isAdminUserNameSpace(userNameSpace)) {
            return bookSourceList
        }
        var grantMap = getBookSourceGrantMap()
        var result = JsonArray()
        for (i in 0 until bookSourceList.size()) {
            var bookSourceObject = bookSourceList.getJsonObject(i)
            if (bookSourceObject == null) {
                continue
            }
            var bookSourceUrl = bookSourceObject.getString("bookSourceUrl") ?: ""
            if (bookSourceUrl.isEmpty()) {
                continue
            }
            var userList = grantMap.get(bookSourceUrl)
            if (userList != null && userList.contains(userNameSpace)) {
                result.add(bookSourceObject)
            }
        }
        return result
    }

    fun getUserWebdavHome(context: Any): String {
        var prefix = getWorkDir("storage", "data")
        var userNameSpace = ""
        when(context) {
            is RoutingContext -> userNameSpace = getUserNameSpace(context)
            is String -> userNameSpace = context
        }
        if (userNameSpace.isNotEmpty()) {
            prefix = prefix + File.separator + userNameSpace
        }
        prefix = prefix + File.separator + "webdav"
        var file = File(prefix)
        if (!file.exists()) {
            file.mkdirs()
        }
        return prefix
    }

    fun getFileExt(url: String, defaultExt: String=""): String {
        return getFileExtetion(url, defaultExt)
    }

    suspend fun limitConcurrent(concurrentCount: Int, startIndex: Int, endIndex: Int, handler: suspend CoroutineScope.(Int) -> Any) {
        limitConcurrent(concurrentCount, startIndex, endIndex, handler) {_, _ ->
            true
        }
    }

    suspend fun limitConcurrent(concurrentCount: Int, startIndex: Int, endIndex: Int, handler: suspend CoroutineScope.(Int) -> Any, needContinue: (ArrayList<Any>, Int) -> Boolean) {
        var lastIndex = startIndex
        var loopCount = 0
        var resultCount = 0
        var loopStart = System.currentTimeMillis()
        var costTime = 0L
        var deferredList = arrayListOf<Deferred<Any>>()
        while(true) {
            var croutineCount = deferredList.size;
            if (croutineCount < concurrentCount) {
                for(i in lastIndex until endIndex) {
                    croutineCount += 1;
                    deferredList.add(async {
                        handler(i)
                    })

                    lastIndex = i
                    if (croutineCount >= concurrentCount) {
                        break;
                    }
                }
            }
            var resultList = arrayListOf<Any>()

            // 等待任何一个完成
            while (resultList.size <= 0) {
                delay(10)
                var stillDeferredList = arrayListOf<Deferred<Any>>()
                for (i in 0 until deferredList.size) {
                    try {
                        var deferred = deferredList.get(i)
                        if (deferred.isCompleted) {
                            resultCount++
                            resultList.add(deferred.getCompleted())
                        } else if (!deferred.isCancelled) {
                            stillDeferredList.add(deferred)
                        } else {
                            resultCount++
                        }
                    } catch(e: Exception) {

                    }
                }
                deferredList.clear()
                deferredList.addAll(stillDeferredList)
            }

            if (resultCount / concurrentCount > loopCount) {
                loopCount = resultCount / concurrentCount
                costTime = System.currentTimeMillis() - loopStart
                logger.info("Loop: {} concurrentCount: {} lastIndex: {} endIndex: {} costTime: {} ms deferredList size: {}", loopCount, croutineCount, lastIndex, endIndex, costTime, deferredList.size)
            }

            if (lastIndex >= endIndex - 1) {
                // 搞完了，等待所有结束
                for (i in 0 until deferredList.size) {
                    try {
                        resultList.add(deferredList.get(i).await())
                    } catch(e: Exception) {

                    }
                }
                deferredList.clear()
                needContinue(resultList, loopCount)
                break;
            }
            if (resultList.size > 0) {
                if (!needContinue(resultList, loopCount)) {
                    break;
                }
            }
            lastIndex = lastIndex + 1
        }

        // for (i in 0 until concurrentCount) {
        //     runBlocking(concurrentCount, startIndex + i , endIndex, handler, needContinue)
        // }
    }

    suspend fun runBlocking(concurrentCount: Int, startIndex: Int, endIndex: Int, handler: suspend CoroutineScope.(Int) -> Any, needContinue: (ArrayList<Any>, Int) -> Boolean) {
        var lastIndex = startIndex

        Coroutine.async(this, coroutineContext) {
            handler(lastIndex)
        }.timeout(30000L)
        .onSuccess(Dispatchers.IO) {
            if (lastIndex < endIndex - concurrentCount && needContinue(arrayListOf(it), 0)) {
                lastIndex += concurrentCount
                runBlocking(concurrentCount, lastIndex, endIndex, handler, needContinue)
            }
        }
        .onError(Dispatchers.IO) {
            if (lastIndex < endIndex - concurrentCount) {
                lastIndex += concurrentCount
                runBlocking(concurrentCount, lastIndex, endIndex, handler, needContinue)
            } else {
                needContinue(arrayListOf(), 0)
            }
        }
    }
}