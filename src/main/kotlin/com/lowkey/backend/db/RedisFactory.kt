package com.lowkey.backend.db

import io.ktor.server.config.ApplicationConfig
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import org.slf4j.LoggerFactory

object RedisFactory {
    private val log = LoggerFactory.getLogger(RedisFactory::class.java)

    private var client: RedisClient? = null
    private var connection: StatefulRedisConnection<String, String>? = null

    fun init(config: ApplicationConfig) {
        val url = config.property("redis.url").getString()
        try {
            client = RedisClient.create(url)
            connection = client!!.connect()
            log.info("Connected to Redis at {}", url)
        } catch (e: Exception) {
            log.warn(
                "Redis unavailable at {} — start docker-compose before using Redis. ({})",
                url,
                e.message
            )
            client?.shutdown()
            client = null
            connection = null
        }
    }

    fun connection(): StatefulRedisConnection<String, String> =
        connection ?: error("Redis not initialized. Call RedisFactory.init() first.")

    fun sync(): RedisCommands<String, String> = connection().sync()

    fun isReady(): Boolean = connection != null

    fun close() {
        connection?.close()
        connection = null
        client?.shutdown()
        client = null
        log.info("Redis connection closed")
    }
}
