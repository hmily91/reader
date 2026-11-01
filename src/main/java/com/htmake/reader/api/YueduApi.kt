package com.htmake.reader.api

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssArticle
import io.legado.app.model.webBook.WebBook
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.StaticHandler;
import mu.KotlinLogging
import com.htmake.reader.config.AppConfig
import com.htmake.reader.config.BookConfig
import io.legado.app.constant.DeepinkBookSource
import com.htmake.reader.api.controller.BookController
import com.htmake.reader.api.controller.BookSourceController
import com.htmake.reader.api.controller.RssSourceController
import com.htmake.reader.api.controller.UserController
import com.htmake.reader.api.controller.WebdavController
import com.htmake.reader.api.controller.ReplaceRuleController
import com.htmake.reader.api.controller.BookmarkController
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

@Component
class YueduApi : RestVerticle() {
    @Autowired
    private lateinit var appConfig: AppConfig

    @Autowired
    private lateinit var env: Environment

    override suspend fun initRouter(router: Router) {
        setupPort()

        // 旧版数据迁移
        migration()

        // 书源授权迁移（旧版本每个用户各持一份书源副本，新版改为系统书源 + 授权表）
        migrateBookSourceGrant()

        // web界面
        router.route("/*").handler(StaticHandler.create("web").setDefaultContentEncoding("UTF-8"));

        // assets
        var assetsDir = getWorkDir("storage", "assets");
        var assetsDirFile = File(assetsDir);
        if (!assetsDirFile.exists()) {
            assetsDirFile.mkdirs();
        }
        var assetsCss = getWorkDir("storage", "assets", "reader.css");
        var assetsCssFile = File(assetsCss);
        if (!assetsCssFile.exists()) {
            assetsCssFile.writeText("/* 在此处可以编写CSS样式来自定义页面 */");
        }
        router.route("/assets/*").handler(StaticHandler.create().setAllowRootFileSystemAccess(true).setWebRoot(assetsDir).setDefaultContentEncoding("UTF-8"));

        // epub资源
        var dataDir = getWorkDir("storage", "data");
        router.route("/epub/*").handler {
            var path = it.request().path().replace("/epub/", "/", true)
            path = URLDecoder.decode(path, "UTF-8")
            if (path.endsWith("html", true)) {
                var filePath = File(dataDir + path)
                if (filePath.exists()) {
                    // 处理 js 注入脚本
                    BookConfig.injectJavascriptToEpubChapter(filePath.toString())
                }
            }
            it.next()
        }
        router.route("/epub/*").handler(StaticHandler.create().setAllowRootFileSystemAccess(true).setWebRoot(dataDir).setDefaultContentEncoding("UTF-8"));

        // 获取系统信息
        router.get("/reader3/getSystemInfo").coroutineHandler { getSystemInfo(it) }


        ////////// 接口部分
        val bookController = BookController(coroutineContext)
        val bookSourceController = BookSourceController(coroutineContext)
        val rssSourceController = RssSourceController(coroutineContext)
        val userController = UserController(coroutineContext)
        val webdavController = WebdavController(coroutineContext, router) { ctx, error ->
            onHandlerError(ctx, error)
        }
        val replaceRuleController = ReplaceRuleController(coroutineContext)
        val bookmarkController = BookmarkController(coroutineContext)

        /** 书源模块 */
        router.post("/reader3/saveBookSource").coroutineHandler { bookSourceController.saveBookSource(it) }
        router.post("/reader3/saveBookSources").coroutineHandler { bookSourceController.saveBookSources(it) }

        router.get("/reader3/getBookSource").coroutineHandler { bookSourceController.getBookSource(it) }
        router.post("/reader3/getBookSource").coroutineHandler { bookSourceController.getBookSource(it) }
        router.get("/reader3/getBookSources").coroutineHandler { bookSourceController.getBookSources(it) }
        router.post("/reader3/getBookSources").coroutineHandler { bookSourceController.getBookSources(it) }

        router.post("/reader3/deleteAllBookSources").coroutineHandler { bookSourceController.deleteAllBookSources(it) }
        router.post("/reader3/deleteBookSource").coroutineHandler { bookSourceController.deleteBookSource(it) }
        router.post("/reader3/deleteBookSources").coroutineHandler { bookSourceController.deleteBookSources(it) }

        // 上传书源文件
        router.post("/reader3/readSourceFile").coroutineHandler { bookSourceController.readSourceFile(it) }

        // 读取远程书源文件
        router.post("/reader3/readRemoteSourceFile").coroutineHandlerWithoutRes { bookSourceController.readRemoteSourceFile(it) }

        // 设置默认书源
        router.post("/reader3/setAsDefaultBookSources").coroutineHandler { bookSourceController.setAsDefaultBookSources(it) }
        router.post("/reader3/deleteUserBookSource").coroutineHandler { bookSourceController.deleteUserBookSource(it) }
        router.post("/reader3/deleteBookSourcesFile").coroutineHandler { bookSourceController.deleteBookSourcesFile(it) }

        // 书源授权（仅管理员，指定书源开放给哪些用户）
        router.post("/reader3/saveBookSourceGrants").coroutineHandler { bookSourceController.saveBookSourceGrants(it) }

        /** 书籍模块 */
        // 书架
        router.get("/reader3/getBookshelf").coroutineHandler { bookController.getBookshelf(it) }
        router.get("/reader3/getShelfBook").coroutineHandler { bookController.getShelfBook(it) }
        router.post("/reader3/saveBook").coroutineHandler { bookController.saveBook(it) }
        router.post("/reader3/deleteBook").coroutineHandler { bookController.deleteBook(it) }
        router.post("/reader3/deleteBooks").coroutineHandler { bookController.deleteBooks(it) }

        // 失效书源
        router.post("/reader3/getInvalidBookSources").coroutineHandler { bookController.getInvalidBookSources(it) }

        // 探索
        router.post("/reader3/exploreBook").coroutineHandler { bookController.exploreBook(it) }
        router.get("/reader3/exploreBook").coroutineHandler { bookController.exploreBook(it) }

        // 搜索
        router.get("/reader3/searchBook").coroutineHandler { bookController.searchBook(it) }
        router.post("/reader3/searchBook").coroutineHandler { bookController.searchBook(it) }
        router.get("/reader3/searchBookMulti").coroutineHandler { bookController.searchBookMulti(it) }
        router.post("/reader3/searchBookMulti").coroutineHandler { bookController.searchBookMulti(it) }
        router.get("/reader3/searchBookMultiSSE").coroutineHandlerWithoutRes { bookController.searchBookMultiSSE(it) }

        // 书籍详情
        router.get("/reader3/getBookInfo").coroutineHandler { bookController.getBookInfo(it) }
        router.post("/reader3/getBookInfo").coroutineHandler { bookController.getBookInfo(it) }

        // 章节列表
        router.get("/reader3/getChapterList").coroutineHandler { bookController.getChapterList(it) }
        router.post("/reader3/getChapterList").coroutineHandler { bookController.getChapterList(it) }

        // 内容
        router.get("/reader3/getBookContent").coroutineHandler { bookController.getBookContent(it) }
        router.post("/reader3/getBookContent").coroutineHandler { bookController.getBookContent(it) }

        // 保存阅读进度
        router.post("/reader3/saveBookProgress").coroutineHandler { bookController.saveBookProgress(it) }

        // 封面
        router.get("/reader3/cover").coroutineHandlerWithoutRes { bookController.getBookCover(it) }

        // 搜索其它来源
        router.get("/reader3/searchBookSource").coroutineHandler { bookController.searchBookSource(it) }
        router.post("/reader3/searchBookSource").coroutineHandler { bookController.searchBookSource(it) }
        router.get("/reader3/getAvailableBookSource").coroutineHandler { bookController.getAvailableBookSource(it) }
        router.post("/reader3/getAvailableBookSource").coroutineHandler { bookController.getAvailableBookSource(it) }
        router.get("/reader3/searchBookSourceSSE").coroutineHandlerWithoutRes { bookController.searchBookSourceSSE(it) }

        // 换源
        router.get("/reader3/setBookSource").coroutineHandler { bookController.setBookSource(it) }
        router.post("/reader3/setBookSource").coroutineHandler { bookController.setBookSource(it) }

        // 修改分组
        router.post("/reader3/saveBookGroupId").coroutineHandler { bookController.saveBookGroupId(it) }
        router.post("/reader3/addBookGroupMulti").coroutineHandler { bookController.addBookGroupMulti(it) }
        router.post("/reader3/removeBookGroupMulti").coroutineHandler { bookController.removeBookGroupMulti(it) }

        // 导入本地文件
        router.post("/reader3/importBookPreview").coroutineHandler { bookController.importBookPreview(it) }
        router.post("/reader3/refreshLocalBook").coroutineHandler { bookController.refreshLocalBook(it) }

        // 获取txt章节规则
        router.get("/reader3/getTxtTocRules").coroutineHandler { bookController.getTxtTocRules(it) }
        router.post("/reader3/getChapterListByRule").coroutineHandler { bookController.getChapterListByRule(it) }

        // 书籍分组
        router.get("/reader3/getBookGroups").coroutineHandler { bookController.getBookGroups(it) }
        router.post("/reader3/saveBookGroup").coroutineHandler { bookController.saveBookGroup(it) }
        router.post("/reader3/deleteBookGroup").coroutineHandler { bookController.deleteBookGroup(it) }
        router.post("/reader3/saveBookGroupOrder").coroutineHandler { bookController.saveBookGroupOrder(it) }

        // 书仓功能
        // 获取书仓文件列表
        router.get("/reader3/getLocalStoreFileList").coroutineHandler { bookController.getLocalStoreFileList(it) }
        // 下载书仓文件
        router.get("/reader3/getLocalStoreFile").coroutineHandlerWithoutRes { bookController.getLocalStoreFile(it) }
        // 删除书仓文件
        router.post("/reader3/deleteLocalStoreFile").coroutineHandler { bookController.deleteLocalStoreFile(it) }
        router.post("/reader3/deleteLocalStoreFileList").coroutineHandler { bookController.deleteLocalStoreFileList(it) }
        // 从本地书仓/webdav导入
        router.post("/reader3/importFromLocalPathPreview").coroutineHandler { bookController.importFromLocalPathPreview(it) }
        // 上传文件到书仓
        router.post("/reader3/uploadFileToLocalStore").coroutineHandler { bookController.uploadFileToLocalStore(it) }

        // 调试书源
        router.get("/reader3/bookSourceDebugSSE").coroutineHandlerWithoutRes { bookController.bookSourceDebugSSE(it) }

        // 缓存书籍章节
        router.get("/reader3/cacheBookSSE").coroutineHandlerWithoutRes { bookController.cacheBookSSE(it) }
        // 获取书籍缓存信息
        router.get("/reader3/getShelfBookWithCacheInfo").coroutineHandler { bookController.getShelfBookWithCacheInfo(it) }
        // 删除书籍章节缓存
        router.post("/reader3/deleteBookCache").coroutineHandler { bookController.deleteBookCache(it) }

        // 导出书籍
        router.post("/reader3/exportBook").coroutineHandlerWithoutRes { bookController.exportBook(it) }
        router.get("/reader3/exportBook").coroutineHandlerWithoutRes { bookController.exportBook(it) }

        // 全文搜索
        router.get("/reader3/searchBookContent").coroutineHandler { bookController.searchBookContent(it) }
        router.post("/reader3/searchBookContent").coroutineHandler { bookController.searchBookContent(it) }

        /** 用户模块 */
        // 上传文件
        router.post("/reader3/uploadFile").coroutineHandler { userController.uploadFile(it) }

        // 删除文件
        router.post("/reader3/deleteFile").coroutineHandler { userController.deleteFile(it) }

        // 登录
        router.post("/reader3/login").coroutineHandler { userController.login(it) }
        // 注销登录
        router.post("/reader3/logout").coroutineHandler { userController.logout(it) }

        // 获取用户信息
        router.get("/reader3/getUserInfo").coroutineHandler { userController.getUserInfo(it) }

        // 用户备份本地配置
        router.post("/reader3/saveUserConfig").coroutineHandler { userController.saveUserConfig(it) }

        // 用户恢复本地配置
        router.get("/reader3/getUserConfig").coroutineHandler { userController.getUserConfig(it) }

        // 获取用户列表
        router.get("/reader3/getUserList").coroutineHandler { userController.getUserList(it) }

        // 删除用户
        router.post("/reader3/deleteUsers").coroutineHandler { userController.deleteUsers(it) }

        // 添加用户
        router.post("/reader3/addUser").coroutineHandler { userController.addUser(it) }

        // 重置用户密码
        router.post("/reader3/resetPassword").coroutineHandler { userController.resetPassword(it) }

        // 更新用户
        router.post("/reader3/updateUser").coroutineHandler { userController.updateUser(it) }


        /** webdav模块 */
        // 获取webdav备份列表
        router.get("/reader3/getWebdavFileList").coroutineHandler { webdavController.getWebdavFileList(it) }

        // 下载webdav文件
        router.get("/reader3/getWebdavFile").coroutineHandlerWithoutRes { webdavController.getWebdavFile(it) }

        // 上传webdav文件
        router.post("/reader3/uploadFileToWebdav").coroutineHandler { webdavController.uploadFileToWebdav(it) }

        // 删除webdav文件
        router.get("/reader3/deleteWebdavFile").coroutineHandler { webdavController.deleteWebdavFile(it) }
        router.post("/reader3/deleteWebdavFile").coroutineHandler { webdavController.deleteWebdavFile(it) }
        router.post("/reader3/deleteWebdavFileList").coroutineHandler { webdavController.deleteWebdavFileList(it) }

        // 从webdav备份恢复
        router.post("/reader3/restoreFromWebdav").coroutineHandler { webdavController.restoreFromWebdav(it) }

        // 备份到webdav
        router.post("/reader3/backupToWebdav").coroutineHandler { webdavController.backupToWebdav(it) }


        /** rss模块 */
        // rss
        router.get("/reader3/getRssSources").coroutineHandler { rssSourceController.getRssSources(it) }
        router.post("/reader3/saveRssSource").coroutineHandler { rssSourceController.saveRssSource(it) }
        router.post("/reader3/saveRssSources").coroutineHandler { rssSourceController.saveRssSources(it) }
        router.post("/reader3/deleteRssSource").coroutineHandler { rssSourceController.deleteRssSource(it) }
        // rss 列表
        router.get("/reader3/getRssArticles").coroutineHandler { rssSourceController.getRssArticles(it) }
        router.post("/reader3/getRssArticles").coroutineHandler { rssSourceController.getRssArticles(it) }
        // rss 内容
        router.get("/reader3/getRssContent").coroutineHandler { rssSourceController.getRssContent(it) }
        router.post("/reader3/getRssContent").coroutineHandler { rssSourceController.getRssContent(it) }

        /** 替换规则模块 */
        router.get("/reader3/getReplaceRules").coroutineHandler { replaceRuleController.getReplaceRules(it) }
        router.post("/reader3/saveReplaceRule").coroutineHandler { replaceRuleController.saveReplaceRule(it) }
        router.post("/reader3/saveReplaceRules").coroutineHandler { replaceRuleController.saveReplaceRules(it) }
        router.post("/reader3/deleteReplaceRule").coroutineHandler { replaceRuleController.deleteReplaceRule(it) }
        router.post("/reader3/deleteReplaceRules").coroutineHandler { replaceRuleController.deleteReplaceRules(it) }

        /** 书签模块 */
        router.get("/reader3/getBookmarks").coroutineHandler { bookmarkController.getBookmarks(it) }
        router.post("/reader3/saveBookmark").coroutineHandler { bookmarkController.saveBookmark(it) }
        router.post("/reader3/saveBookmarks").coroutineHandler { bookmarkController.saveBookmarks(it) }
        router.post("/reader3/deleteBookmark").coroutineHandler { bookmarkController.deleteBookmark(it) }
        router.post("/reader3/deleteBookmarks").coroutineHandler { bookmarkController.deleteBookmarks(it) }
    }

    suspend fun setupPort() {
        logger.info("port: {}", port)
        var serverPort = env.getProperty("reader.server.port", Int::class.java)
        logger.info("serverPort: {}", serverPort)
        if (serverPort != null && serverPort > 0) {
            port = serverPort;
        }
    }

    suspend fun migration() {
        try {
            var storageDir = File(getWorkDir("storage"))
            var dataDir = File(getWorkDir("storage", "data", "default"))
            if (!storageDir.exists()) {
                // 直接使用新版本，则创建 default 目录，防止重启之后被迁移
                dataDir.mkdirs()
            } else if (!dataDir.exists()) {
                // 旧版本不管了
                dataDir.mkdirs()
                // 可能存在旧版本，尝试迁移
                // var backupDir = File(getWorkDir("storage-backup"))
                // storageDir.renameTo(backupDir)
                // dataDir.parentFile.mkdirs()
                // backupDir.copyRecursively(dataDir)
            }
        } catch(e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 书源授权迁移（仅执行一次）
     *
     * 旧版本：每个用户各自持有一份书源副本（storage/data/<user>/bookSource.json），
     *         新用户会从系统书源拷贝一份，因此无法做权限隔离。
     * 新版本：书源统一维护在系统空间（storage/data/default/bookSource.json），
     *         普通用户通过授权表（storage/data/bookSourceGrants.json）控制可见性。
     *
     * 首次升级时：
     *   1. 把各用户已有的书源合并进系统书源（按 bookSourceUrl 去重）
     *   2. 按"原来谁有这本书源，就继续授权给谁"初始化授权表，避免升级后书源丢失
     *   3. 用户原有书源副本重命名备份为 bookSource.json.migrated-<时间戳>，不删除
     *   4. 保证升级后至少存在一个管理员，否则无人能管理书源
     */
    suspend fun migrateBookSourceGrant() {
        try {
            if (!appConfig.secure) {
                return
            }
            var grantFile = File(getWorkDir("storage", "data", "bookSourceGrants.json"))
            if (grantFile.exists()) {
                return
            }
            // 用户列表
            var userMap = mutableMapOf<String, MutableMap<String, Any>>()
            var userMapJson: JsonObject? = asJsonObject(getStorage("data", "users"))
            if (userMapJson != null) {
                userMap = userMapJson.map as MutableMap<String, MutableMap<String, Any>>
            }
            var merged = JsonArray()
            var urlSet = mutableSetOf<String>()
            var grantMap = mutableMapOf<String, MutableList<String>>()

            // 系统书源先入库
            var systemList: JsonArray? = asJsonArray(getStorage("data", "default", "bookSource"))
            if (systemList != null) {
                for (i in 0 until systemList.size()) {
                    addMergedBookSource(merged, urlSet, systemList.getJsonObject(i))
                }
            }

            userMap.forEach {
                var username = it.value.getOrDefault("username", "") as? String ?: ""
                if (username.isEmpty()) {
                    return@forEach
                }
                var userBookSourceList: JsonArray? = asJsonArray(getStorage("data", username, "bookSource"))
                if (userBookSourceList != null) {
                    for (i in 0 until userBookSourceList.size()) {
                        var bookSourceObject = userBookSourceList.getJsonObject(i)
                        var bookSourceUrl = bookSourceObject.getString("bookSourceUrl") ?: ""
                        if (bookSourceUrl.isEmpty()) {
                            continue
                        }
                        addMergedBookSource(merged, urlSet, bookSourceObject)
                        var userList = grantMap.get(bookSourceUrl) ?: mutableListOf<String>()
                        if (!userList.contains(username)) {
                            userList.add(username)
                        }
                        grantMap.put(bookSourceUrl, userList)
                    }
                }
                // 备份用户书源副本，避免误删数据
                var userBookSourceFile = File(getWorkDir("storage", "data", username, "bookSource.json"))
                if (userBookSourceFile.exists()) {
                    userBookSourceFile.renameTo(File(getWorkDir("storage", "data", username, "bookSource.json.migrated-" + System.currentTimeMillis())))
                }
            }

            if (merged.size() > 0) {
                saveStorage("data", "default", "bookSource", value = merged)
            }

            // 保证升级后至少存在一个管理员
            var hasAdmin = false
            userMap.forEach {
                var role = it.value.getOrDefault("role", "") as? String ?: ""
                if (role.equals("admin")) {
                    hasAdmin = true
                }
            }
            if (!hasAdmin && userMap.size > 0) {
                var oldestUsername = ""
                var oldestTime = Long.MAX_VALUE
                userMap.forEach {
                    var createdAt = it.value.getOrDefault("created_at", 0L)
                    var createdTime = if (createdAt is Long) createdAt else 0L
                    if (createdTime <= oldestTime) {
                        oldestTime = createdTime
                        oldestUsername = it.value.getOrDefault("username", "") as? String ?: ""
                    }
                }
                if (oldestUsername.isNotEmpty()) {
                    var adminUser = userMap.get(oldestUsername)
                    if (adminUser != null) {
                        adminUser.put("role", "admin")
                        userMap.put(oldestUsername, adminUser)
                    }
                }
                saveStorage("data", "users", value = userMap)
            }

            saveStorage("data", "bookSourceGrants", value = grantMapToJson(grantMap).map)
            logger.info("书源授权迁移完成: 系统书源 {} 条, 授权记录 {} 条", merged.size(), grantMap.size)
        } catch(e: Exception) {
            logger.error("书源授权迁移失败", e)
        }
    }

    fun addMergedBookSource(merged: JsonArray, urlSet: MutableSet<String>, bookSourceObject: JsonObject?) {
        if (bookSourceObject == null) {
            return
        }
        var bookSourceUrl = bookSourceObject.getString("bookSourceUrl") ?: ""
        if (bookSourceUrl.isEmpty() || urlSet.contains(bookSourceUrl)) {
            return
        }
        urlSet.add(bookSourceUrl)
        merged.add(bookSourceObject)
    }

    fun grantMapToJson(grantMap: Map<String, List<String>>): JsonObject {
        var grantJson = JsonObject()
        grantMap.forEach {
            if (it.key.isNotEmpty()) {
                var userArray = JsonArray()
                for (name in it.value) {
                    if (name.isNotEmpty()) {
                        userArray.add(name)
                    }
                }
                grantJson.put(it.key, userArray)
            }
        }
        return grantJson
    }

    override fun started() {
        SpringContextUtils.getApplicationContext().publishEvent(SpringEvent(this as java.lang.Object, "READY", ""));
    }

    override fun onStartError() {
        SpringContextUtils.getApplicationContext().publishEvent(SpringEvent(this as java.lang.Object, "START_ERROR", "应用启动失败，请检查" + port + "端口是否被占用"));
    }

    override fun onHandlerError(ctx: RoutingContext, error: Exception) {
        val returnData = ReturnData()
        logger.error("onHandlerError: ", error)
        if (!ctx.response().headWritten()) {
            ctx.success(returnData.setErrorMsg(error.toString()))
        } else {
            ctx.response().end(error.toString())
        }
    }

    private suspend fun getSystemInfo(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        var systemFont = System.getProperty("reader.system.fonts")
        var freeMemory = "" + (Runtime.getRuntime().freeMemory() / 1024 / 1024) + "M"
        var totalMemory = "" + (Runtime.getRuntime().totalMemory() / 1024 / 1024) + "M"
        var maxMemory = "" + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + "M"
        return returnData.setData(mapOf(
            "fonts" to systemFont,
            "freeMemory" to freeMemory,
            "totalMemory" to totalMemory,
            "maxMemory" to maxMemory
        ))
    }

    /**
     * 定时任务
     */

    /**
     * 每十分钟检查一次书架书籍更新
     */
    @Scheduled(cron = "0 0/10 * * * ?")
    fun shelfUpdateJob()
    {
        launch(Dispatchers.IO) {
            try {
                val bookController = BookController(coroutineContext)

                logger.info("开始检查书架书籍更新")
                // 刷新系统默认书架
                bookController.getBookShelfBooks(true, "default")

                // 刷新用户书架
                if (appConfig.secure) {
                    var userMap = mutableMapOf<String, Map<String, Any>>()
                    var userMapJson: JsonObject? = asJsonObject(getStorage("data", "users"))
                    if (userMapJson != null) {
                        userMap = userMapJson.map as MutableMap<String, Map<String, Any>>
                    }
                    userMap.forEach{
                        try {
                            var ns = it.value.getOrDefault("username", "") as String? ?: ""
                            if (ns.isNotEmpty()) {
                                bookController.getBookShelfBooks(true, ns)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                logger.info("书架书籍更新检查结束")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 每天清理不活跃用户
     */
    @Scheduled(cron = "0 59 23 * * ?")
    fun clearUser()
    {
        if (appConfig.autoClearInactiveUser <= 0 || !appConfig.secure) {
            return
        }
        launch(Dispatchers.IO) {
            try {
                logger.info("开始清理 {} 天未登录用户", appConfig.autoClearInactiveUser)

                var userMap = mutableMapOf<String, Map<String, Any>>()
                var userMapJson: JsonObject? = asJsonObject(getStorage("data", "users"))
                if (userMapJson != null) {
                    userMap = userMapJson.map as MutableMap<String, Map<String, Any>>
                }
                val expireTime = System.currentTimeMillis() - appConfig.autoClearInactiveUser * 86400L * 1000L
                userMap.keys.forEach{
                    try {
                        var user = userMap.get(it)
                        if (user != null) {
                            var username = user.getOrDefault("username", "") as String? ?: ""
                            var last_login_at = user.getOrDefault("last_login_at", 0) as Long? ?: 0L
                            if (username.isNotEmpty() && last_login_at < expireTime) {
                                logger.info("delete user: {}", user)
                                // 删除用户信息
                                userMap.remove(username)
                                // 移除用户目录
                                var userHome = File(getWorkDir("storage", "data", username))
                                logger.info("delete userHome: {}", userHome)
                                if (userHome.exists()) {
                                    userHome.deleteRecursively()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                logger.info("不活跃用户自动清理结束")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}