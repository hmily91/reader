package com.htmake.reader

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.*
import io.vertx.core.json.Json
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import mu.KotlinLogging
import com.htmake.reader.api.YueduApi

import com.htmake.reader.verticle.RestVerticle
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.context.annotation.Bean
import javax.annotation.PostConstruct

private val logger = KotlinLogging.logger {}

@SpringBootApplication
@EnableScheduling
class ReaderApplication {

    @Autowired
    private lateinit var yueduApi: YueduApi

    companion object {
        val vertx by lazy { Vertx.vertx() }
        fun vertx() = vertx
    }

    @PostConstruct
    fun deployVerticle() {
        Json.mapper.apply {
            registerKotlinModule()
        }

        Json.prettyMapper.apply {
            registerKotlinModule()
        }

        Json.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        vertx().deployVerticle(yueduApi)
    }

    @Bean
    fun webClient(): WebClient {
        val webClientOptions = WebClientOptions()
        webClientOptions.isTryUseCompression = true
        webClientOptions.logActivity = true
        webClientOptions.isFollowRedirects = true
        webClientOptions.isTrustAll = true

        val httpClient = vertx().createHttpClient(HttpClientOptions().setTrustAll(true))

//        val webClient = WebClient.wrap(HttpClient(delegateHttpClient), webClientOptions)
        val webClient = WebClient.wrap(httpClient, webClientOptions)

        return webClient
    }

    /**
     * 每 30 分钟输出一条运行状态，方便在容器的「运行日志」面板确认服务还活着
     */
    @Scheduled(fixedDelay = 30 * 60 * 1000L, initialDelay = 60 * 1000L)
    fun heartbeatLog() {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
        val maxMb = rt.maxMemory() / 1024 / 1024
        logger.info("reader 运行中 | 内存 {} / {} MB | 线程数 {}", usedMb, maxMb, Thread.activeCount())
    }

}

fun main(args: Array<String>) {
    val context = SpringApplication.run(ReaderApplication::class.java, *args)
    val env = context.environment
    val port = env.getProperty("server.port", "8080")
    val profiles = env.activeProfiles.joinToString(",").ifBlank { "default" }
    val storagePath = env.getProperty("reader.app.storagePath", "./storage")
    val logPath = env.getProperty("logging.path", "./logs")
    logger.info("==========================================================")
    logger.info("reader 启动完成")
    logger.info("  访问端口: {}", port)
    logger.info("  运行环境: {}", profiles)
    logger.info("  数据目录: {}", storagePath)
    logger.info("  日志目录: {}", logPath)
    logger.info("==========================================================")
}




