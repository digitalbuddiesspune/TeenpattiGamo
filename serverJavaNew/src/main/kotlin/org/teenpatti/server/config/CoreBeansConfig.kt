package org.teenpatti.server.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter
import org.teenpatti.server.common.AppClock
import org.teenpatti.server.common.AppIdGenerator
import org.teenpatti.server.common.AppRandomSource
import org.teenpatti.server.common.ClockProvider
import org.teenpatti.server.common.IdGenerator
import org.teenpatti.server.common.RandomSource
import org.teenpatti.server.common.Scheduler
import org.teenpatti.server.common.SchedulerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

@Configuration
internal class CoreBeansConfig {
    @Bean
    fun clockProvider(): ClockProvider = AppClock()

    @Bean(destroyMethod = "close")
    fun mongoClient(env: AppEnvironment): MongoClient = MongoClients.create(env.mongoUri)

    @Bean
    fun idGenerator(): IdGenerator = AppIdGenerator()

    @Bean
    fun randomSource(): RandomSource = AppRandomSource()

    @Bean(destroyMethod = "shutdownNow")
    fun scheduledExecutorService(): ScheduledExecutorService = Executors.newScheduledThreadPool(12)

    @Bean(destroyMethod = "destroy")
    fun rabbitConnectionFactory(env: AppEnvironment): ConnectionFactory {
        val factory = CachingConnectionFactory()
        factory.setUri(env.platformAmqpUrl.ifBlank { "amqp://guest:guest@localhost:5672/" })
        return factory
    }

    @Bean
    fun scheduler(executor: ScheduledExecutorService): Scheduler = SchedulerFactory.fromExecutor(executor)

    @Bean
    fun rabbitMessageConverter(objectMapper: ObjectMapper): Jackson2JsonMessageConverter = Jackson2JsonMessageConverter(objectMapper)

    @Bean
    @Primary
    fun objectMapper(): ObjectMapper =
        ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
}
