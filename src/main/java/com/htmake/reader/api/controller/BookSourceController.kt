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

private val logger = KotlinLogging.logger {}

class BookSourceController(coroutineContext: CoroutineContext): BaseController(coroutineContext) {
    private var webClient: WebClient

    init {
        webClient = SpringContextUtils.getBean("webClient", WebClient::class.java)
    }

    /**
     * 获取当前用户可见的书源列表
     * 书源统一由管理员维护在系统空间（default），普通用户按授权关系过滤，
     * 不再为每个用户拷贝一份副本（否则授权形同虚设）。
     */
    suspend fun getUserBookSourceJson(userNameSpace: String): JsonArray? {
        var systemBookSourceList = getSystemBookSourceJson()
        if (systemBookSourceList.size() <= 0) {
            return null
        }
        return filterVisibleBookSource(systemBookSourceList, userNameSpace)
    }

    /** 在书源数组中查找指定书源的下标 */
    fun findBookSourceIndex(bookSourceList: JsonArray, bookSourceUrl: String): Int {
        for (i in 0 until bookSourceList.size()) {
            var _bookSource = bookSourceList.getJsonObject(i)
            var url = _bookSource.getString("bookSourceUrl") ?: ""
            if (url.equals(bookSourceUrl)) {
                return i
            }
        }
        return -1
    }

    /** 判断书源是否带有有效规则（用于防止空规则覆盖已有规则） */
    fun hasBookSourceRule(bookSource: BookSource): Boolean {
        var searchRule = bookSource.ruleSearch
        if (searchRule != null && !searchRule.bookList.isNullOrEmpty()) {
            return true
        }
        var tocRule = bookSource.ruleToc
        if (tocRule != null && !tocRule.chapterList.isNullOrEmpty()) {
            return true
        }
        var contentRule = bookSource.ruleContent
        if (contentRule != null && !contentRule.content.isNullOrEmpty()) {
            return true
        }
        var infoRule = bookSource.ruleBookInfo
        if (infoRule != null && !infoRule.name.isNullOrEmpty()) {
            return true
        }
        return false
    }

    /** 从请求体中读取管理员提交的授权用户列表（shareUsers），不存在时返回 null 表示不变更授权 */
    fun getShareUsersFromBody(context: RoutingContext): MutableList<String>? {
        var body: JsonObject? = null
        try {
            body = context.bodyAsJson
        } catch (e: Exception) {
            return null
        }
        if (body == null) {
            return null
        }
        var shareUsers = body.getJsonArray("shareUsers")
        if (shareUsers == null) {
            return null
        }
        var list = mutableListOf<String>()
        for (i in 0 until shareUsers.size()) {
            var username = shareUsers.getString(i)
            if (!username.isNullOrEmpty() && !list.contains(username)) {
                list.add(username)
            }
        }
        return list
    }

    /** 保存单个书源的授权用户 */
    fun saveBookSourceGrant(bookSourceUrl: String, shareUsers: List<String>?) {
        if (bookSourceUrl.isEmpty() || shareUsers == null) {
            return
        }
        var grantMap = getBookSourceGrantMap()
        grantMap.put(bookSourceUrl, shareUsers.toMutableList())
        saveBookSourceGrantMap(grantMap)
    }

    /** 删除书源时同步清理授权记录 */
    fun removeBookSourceGrant(bookSourceUrl: String) {
        if (bookSourceUrl.isEmpty()) {
            return
        }
        var grantMap = getBookSourceGrantMap()
        if (grantMap.containsKey(bookSourceUrl)) {
            grantMap.remove(bookSourceUrl)
            saveBookSourceGrantMap(grantMap)
        }
    }

    /** 管理员查看书源时附加授权用户列表，便于前端展示与回显 */
    fun appendShareUsers(bookSourceList: JsonArray): JsonArray {
        var grantMap = getBookSourceGrantMap()
        var result = JsonArray()
        for (i in 0 until bookSourceList.size()) {
            var bookSourceObject = bookSourceList.getJsonObject(i).copy()
            var bookSourceUrl = bookSourceObject.getString("bookSourceUrl") ?: ""
            bookSourceObject.put("shareUsers", toUserJsonArray(grantMap.get(bookSourceUrl)))
            result.add(bookSourceObject)
        }
        return result
    }

    suspend fun saveBookSource(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        if (!isAdmin(context)) {
            return returnData.setData("NEED_ADMIN").setErrorMsg("无权限操作：仅管理员可新增或修改书源")
        }
        val bookSource = BookSource.fromJson(context.bodyAsString).getOrNull()
        if (bookSource == null) {
            return returnData.setErrorMsg("参数错误")
        }
        if (bookSource.bookSourceUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("书源链接不能为空")
        }
        if (bookSource.bookSourceName.isNullOrEmpty()) {
            return returnData.setErrorMsg("书源名称不能为空")
        }
        // val bookSource = context.bodyAsJson.mapTo(BookSource::class.java)

        // 书源统一保存在系统空间，由管理员维护
        var bookSourceList = getSystemBookSourceJson()
        var existIndex: Int = findBookSourceIndex(bookSourceList, bookSource.bookSourceUrl)
        if (existIndex >= 0) {
            // 防止前端拿到残缺书源（规则被包了一层 map 等情况）后保存，把已有规则覆盖成空
            var oldSource = bookSourceList.getJsonObject(existIndex).mapTo(BookSource::class.java)
            if (hasBookSourceRule(oldSource) && !hasBookSourceRule(bookSource)) {
                logger.error("拒绝保存空规则书源: {}", bookSource.bookSourceUrl)
                return returnData.setErrorMsg("书源规则为空，已阻止保存（避免覆盖已有规则）。请刷新页面重新加载书源后再编辑")
            }
            var sourceList = bookSourceList.getList()
            sourceList.set(existIndex, JsonObject.mapFrom(bookSource))
            bookSourceList = JsonArray(sourceList)
        } else {
            bookSourceList.add(JsonObject.mapFrom(bookSource))
        }

        // logger.info("bookSourceList: {}", bookSourceList)
        saveUserStorage("default", "bookSource", bookSourceList)

        // 保存管理员设置的授权用户
        var shareUsers = getShareUsersFromBody(context)
        if (shareUsers != null) {
            saveBookSourceGrant(bookSource.bookSourceUrl, shareUsers)
        }
        return returnData.setData("")
    }

    suspend fun saveBookSources(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        if (!isAdmin(context)) {
            return returnData.setData("NEED_ADMIN").setErrorMsg("无权限操作：仅管理员可导入书源")
        }
        var body = context.bodyAsJson
        var bookSourceJsonArray: JsonArray? = context.bodyAsJsonArray
        if (bookSourceJsonArray == null && body != null) {
            // 兼容 { sources: [...], shareUsers: [...] } 形式
            bookSourceJsonArray = body.getJsonArray("sources")
        }
        if (bookSourceJsonArray == null) {
            return returnData.setErrorMsg("参数错误")
        }
        var shareUsers = getShareUsersFromBody(context)

        // 书源统一保存在系统空间，由管理员维护
        var bookSourceList = getSystemBookSourceJson()
        for (k in 0 until bookSourceJsonArray.size()) {
            var bookSourceObject = bookSourceJsonArray.getJsonObject(k)
            if (bookSourceObject == null) {
                continue
            }
            val bookSource = BookSource.fromJson(bookSourceObject.toString()).getOrNull()
            if (bookSource == null) {
                continue
            }
            if (bookSource.bookSourceUrl.isNullOrEmpty()) {
                continue
            }
            // 遍历判断书源是否存在
            var existIndex: Int = findBookSourceIndex(bookSourceList, bookSource.bookSourceUrl)
            if (existIndex >= 0) {
                var sourceList = bookSourceList.getList()
                sourceList.set(existIndex, JsonObject.mapFrom(bookSource))
                bookSourceList = JsonArray(sourceList)
            } else {
                bookSourceList.add(JsonObject.mapFrom(bookSource))
            }
            // 导入时若指定了授权用户，则一并授权
            if (shareUsers != null) {
                saveBookSourceGrant(bookSource.bookSourceUrl, shareUsers)
            }
        }

        // logger.info("bookSourceList: {}", bookSourceList)
        saveUserStorage("default", "bookSource", bookSourceList)
        return returnData.setData("")
    }

    suspend fun getBookSource(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        checkAuth(context)
        var bookSourceUrl: String
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            bookSourceUrl = context.bodyAsJson.getString("bookSourceUrl")
        } else {
            // get 请求
            bookSourceUrl = context.queryParam("bookSourceUrl").firstOrNull() ?: ""
        }
        if (bookSourceUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("书源链接不能为空")
        }

        var userNameSpace = getUserNameSpace(context)
        var bookSourceList = getUserBookSourceJson(userNameSpace)
        if (bookSourceList == null) {
            bookSourceList = JsonArray()
        }
        // 遍历判断书本是否存在
        var existIndex: Int = findBookSourceIndex(bookSourceList, bookSourceUrl)
        if (existIndex < 0) {
            return returnData.setErrorMsg("书源信息不存在，或未被授权访问")
        }

        var bookSourceObject = bookSourceList.getJsonObject(existIndex).copy()
        if (isAdmin(context)) {
            // 管理员额外返回已授权的用户列表，便于前端回显
            var grantMap = getBookSourceGrantMap()
            bookSourceObject.put("shareUsers", toUserJsonArray(grantMap.get(bookSourceUrl)))
        }
        // 深拷贝成普通 Map，避免 Jackson 把 JsonObject 序列化成 {"map":...}
        return returnData.setData(toPlainData(bookSourceObject))
    }

    suspend fun getBookSources(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        checkAuth(context)
        var simple: Int = 0
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            simple = context.bodyAsJson.getInteger("simple", 0)
        } else {
            // get 请求
            simple = context.queryParam("simple").firstOrNull()?.toInt() ?: 0
        }
        var userNameSpace = getUserNameSpace(context)
        var bookSourceList = getUserBookSourceJson(userNameSpace)
        if (bookSourceList != null) {
            // 管理员返回全量并附加授权用户，普通用户只返回已授权的书源
            var isAdminUser = isAdmin(context)
            var list = if (isAdminUser) appendShareUsers(bookSourceList) else bookSourceList
            if (simple > 0) {
                var simpleList = arrayListOf<Map<String, Any?>>()
                for (i in 0 until list.size()) {
                    var bookSourceObject = list.getJsonObject(i)
                    simpleList.add(mapOf<String, Any?>(
                        "bookSourceGroup" to bookSourceObject.getString("bookSourceGroup"),
                        "bookSourceName" to bookSourceObject.getString("bookSourceName"),
                        "bookSourceUrl" to bookSourceObject.getString("bookSourceUrl"),
                        "exploreUrl" to bookSourceObject.getString("exploreUrl"),
                        // 必须是普通字符串列表：直接放 JsonArray 会被 Jackson 序列化成 {"list":[...]}
                        "shareUsers" to parseUserList(bookSourceObject.getValue("shareUsers"))
                    ))
                }
                return returnData.setData(simpleList)
            }
            // 深拷贝成普通 List<Map>，避免 Jackson 把 JsonObject 序列化成 {"map":...}
            return returnData.setData(toPlainData(list))
        }
        return returnData.setData(arrayListOf<Int>())
    }

    suspend fun deleteBookSource(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        if (!isAdmin(context)) {
            return returnData.setData("NEED_ADMIN").setErrorMsg("无权限操作：仅管理员可删除书源")
        }
        var body = context.bodyAsJson
        if (body == null) {
            return returnData.setErrorMsg("参数错误")
        }
        var bookSourceUrl = body.getString("bookSourceUrl") ?: ""

        var bookSourceList = getSystemBookSourceJson()
        // 遍历判断书源是否存在
        var existIndex: Int = findBookSourceIndex(bookSourceList, bookSourceUrl)
        if (existIndex >= 0) {
            var removed = bookSourceList.getJsonObject(existIndex)
            bookSourceList.remove(existIndex)
            var removedUrl = removed.getString("bookSourceUrl") ?: ""
            removeBookSourceGrant(removedUrl)
        }

        // logger.info("bookSourceList: {}", bookSourceList)
        saveUserStorage("default", "bookSource", bookSourceList)
        return returnData.setData("")
    }

    suspend fun deleteBookSources(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        if (!isAdmin(context)) {
            return returnData.setData("NEED_ADMIN").setErrorMsg("无权限操作：仅管理员可删除书源")
        }
        val bookSourceJsonArray = context.bodyAsJsonArray
        if (bookSourceJsonArray == null) {
            return returnData.setErrorMsg("参数错误")
        }

        var bookSourceList = getSystemBookSourceJson()
        for (k in 0 until bookSourceJsonArray.size()) {
            var bookSourceObject = bookSourceJsonArray.getJsonObject(k)
            if (bookSourceObject == null) {
                continue
            }
            var bookSourceUrl = bookSourceObject.getString("bookSourceUrl") ?: ""
            if (bookSourceUrl.isEmpty()) {
                continue
            }
            // 遍历判断书源是否存在
            var existIndex: Int = findBookSourceIndex(bookSourceList, bookSourceUrl)
            if (existIndex >= 0) {
                bookSourceList.remove(existIndex)
                removeBookSourceGrant(bookSourceUrl)
            }
        }

        // logger.info("bookSourceList: {}", bookSourceList)
        saveUserStorage("default", "bookSource", bookSourceList)
        return returnData.setData("")
    }

    suspend fun deleteAllBookSources(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        if (!isAdmin(context)) {
            return returnData.setData("NEED_ADMIN").setErrorMsg("无权限操作：仅管理员可清空书源")
        }
        saveUserStorage("default", "bookSource", JsonArray())
        // 同步清空授权关系
        saveBookSourceGrantMap(mapOf<String, List<String>>())
        return returnData.setData("")
    }

    suspend fun setAsDefaultBookSources(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        if (!isAdmin(context)) {
            return returnData.setData("NEED_ADMIN").setErrorMsg("无权限操作：仅管理员可设置书源授权")
        }
        var username = context.bodyAsJson.getString("username") ?: ""
        if (username.isEmpty()) {
            return returnData.setErrorMsg("参数错误")
        }

        // 书源统一维护在系统空间，这里把全部书源授权给指定用户
        var bookSourceList = getSystemBookSourceJson()
        var grantMap = getBookSourceGrantMap()
        for (i in 0 until bookSourceList.size()) {
            var bookSourceUrl = bookSourceList.getJsonObject(i).getString("bookSourceUrl") ?: ""
            if (bookSourceUrl.isEmpty()) {
                continue
            }
            var userList = grantMap.get(bookSourceUrl) ?: mutableListOf<String>()
            if (!userList.contains(username)) {
                userList.add(username)
            }
            grantMap.put(bookSourceUrl, userList)
        }
        saveBookSourceGrantMap(grantMap)
        return returnData.setData("已将全部书源授权给用户 " + username)
    }

    suspend fun readSourceFile(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (context.fileUploads() == null || context.fileUploads().isEmpty()) {
            return returnData.setErrorMsg("请上传文件")
        }
        var sourceList = JsonArray()
        context.fileUploads().forEach {
            // logger.info("readSourceFile: {}", it.uploadedFileName())
            var file = File(it.uploadedFileName())
            if (file.exists()) {
                sourceList.add(file.readText())
                file.delete()
            }
        }
        return returnData.setData(sourceList.getList())
    }

    suspend fun readRemoteSourceFile(context: RoutingContext) {
        val returnData = ReturnData()
        var url: String
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            url = context.bodyAsJson.getString("url") ?: ""
        } else {
            // get 请求
            url = context.queryParam("url").firstOrNull() ?: ""
        }
        if (url.isNullOrEmpty()) {
            context.success(returnData.setErrorMsg("请输入远程书源链接"))
            return
        }

        launch(Dispatchers.IO) {
            webClient.getAbs(url).timeout(3000).send {
                var body = it.result()?.bodyAsString()
                if (body != null) {
                    context.success(returnData.setData(arrayListOf(body)))
                } else {
                    context.success(returnData.setErrorMsg("远程书源链接错误"))
                }
            }
        }
    }

    suspend fun deleteUserBookSource(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        if (!isAdmin(context)) {
            return returnData.setData("NEED_ADMIN").setErrorMsg("无权限操作：仅管理员可删除用户书源")
        }
        val userJsonArray = context.bodyAsJsonArray
        if (userJsonArray == null) {
            return returnData.setErrorMsg("参数错误")
        }
        var userSet = mutableSetOf<String>()
        for (i in 0 until userJsonArray.size()) {
            var username = userJsonArray.getString(i)
            if (!username.isNullOrEmpty()) {
                userSet.add(username)
            }
        }
        if (userSet.isEmpty()) {
            return returnData.setErrorMsg("请选择需要删除书源的用户")
        }
        // 清除这些用户的书源授权
        var grantMap = getBookSourceGrantMap()
        grantMap.forEach {
            var userList = it.value
            userList.removeAll { name -> userSet.contains(name) }
        }
        saveBookSourceGrantMap(grantMap)
        return returnData.setData("删除书源成功")
    }

    suspend fun deleteBookSourcesFile(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        if (!isAdmin(context)) {
            return returnData.setData("NEED_ADMIN").setErrorMsg("无权限操作：仅管理员可恢复默认书源")
        }
        // 清除当前用户（或管理模式指定用户）的书源授权，恢复为"仅管理员可见"
        var userNameSpace = getUserNameSpace(context)
        var grantMap = getBookSourceGrantMap()
        grantMap.forEach {
            var userList = it.value
            userList.removeAll { name -> name.equals(userNameSpace) }
        }
        saveBookSourceGrantMap(grantMap)
        return returnData.setData("")
    }

    /**
     * 批量设置书源授权用户（仅管理员）
     * 入参：{ "bookSourceUrls": ["..."], "usernames": ["..."], "replace": true }
     * replace=true 表示覆盖原有授权，false 表示追加
     */
    suspend fun saveBookSourceGrants(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        if (!isAdmin(context)) {
            return returnData.setData("NEED_ADMIN").setErrorMsg("无权限操作：仅管理员可设置书源授权")
        }
        var body = context.bodyAsJson
        if (body == null) {
            return returnData.setErrorMsg("参数错误")
        }
        var bookSourceUrls = body.getJsonArray("bookSourceUrls")
        var usernames = body.getJsonArray("usernames")
        if (bookSourceUrls == null || usernames == null) {
            return returnData.setErrorMsg("参数错误")
        }
        var replace = body.getValue("replace") as? Boolean ?: false

        var userList = mutableListOf<String>()
        for (i in 0 until usernames.size()) {
            var username = usernames.getString(i)
            if (!username.isNullOrEmpty() && !userList.contains(username)) {
                userList.add(username)
            }
        }

        var grantMap = getBookSourceGrantMap()
        for (i in 0 until bookSourceUrls.size()) {
            var bookSourceUrl = bookSourceUrls.getString(i) ?: ""
            if (bookSourceUrl.isEmpty()) {
                continue
            }
            if (replace) {
                grantMap.put(bookSourceUrl, userList.toMutableList())
            } else {
                var existedUsers = grantMap.get(bookSourceUrl) ?: mutableListOf<String>()
                for (name in userList) {
                    if (!existedUsers.contains(name)) {
                        existedUsers.add(name)
                    }
                }
                grantMap.put(bookSourceUrl, existedUsers)
            }
        }
        saveBookSourceGrantMap(grantMap)
        return returnData.setData("书源授权已保存")
    }
}