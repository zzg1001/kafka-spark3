package com.ike
import org.apache.spark.SparkConf
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.streaming.kafka010._
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.streaming.scheduler.{StreamingListener, StreamingListenerBatchCompleted}
import redis.clients.jedis.Jedis

import java.text.SimpleDateFormat
import java.util.Date

object KafkaSparkStreamingRedis {
  def main(args: Array[String]): Unit = {
    val conf = new SparkConf()
//      .setMaster("local[*]")
      .setAppName("KafkaRedisDailyMetrics")
    val ssc = new StreamingContext(conf, Seconds(10)) // 每10秒一个批次

    // Kafka 参数配置
    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> "172.16.10.132:9092", // Kafka 服务器地址
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> "spark-streaming-group",
      "auto.offset.reset" -> "latest",
      "enable.auto.commit" -> (false: java.lang.Boolean)
    )

    val topics = Array("test") // Kafka 主题
    val stream = KafkaUtils.createDirectStream[String, String](
      ssc,
      LocationStrategies.PreferConsistent,
      ConsumerStrategies.Subscribe[String, String](topics, kafkaParams)
    )

    stream.foreachRDD { rdd =>
      rdd.foreachPartition { partition =>
        val jedis = new Jedis("172.16.10.80", 6379) // 创建 Redis 连接
        val dateFormat = new SimpleDateFormat("yyyyMMdd")
        val today = dateFormat.format(new Date())
        val redisKeyUV = s"users_cnt:$today" // 当天的UV键
        val redisKeyTotal = s"total_visits_cnt:$today" // 当天的总访问数键

        partition.foreach { record =>
          val fields = record.value.split("\\|")
          val userId = fields(0) // 假设用户ID是每条消息的第一个字段
          val browser = fields(2) // 浏览器字段
          val browserKey = s"browser_visits_cnt:$browser:$today" // 每种浏览器的访问计数键

          jedis.sadd(redisKeyUV, userId) // 将用户ID添加到当天的 SET 中
          jedis.incr(redisKeyTotal) // 增加当天的总访问计数
          jedis.incr(browserKey) // 增加对应浏览器的访问计数
        }
        jedis.close() // 关闭 Redis 连接
      }
    }

    // 定时打印每天的UV、总访问统计和各浏览器的访问统计
    ssc.addStreamingListener(new StreamingListener() {
      override def onBatchCompleted(context: StreamingListenerBatchCompleted): Unit = {
        val jedis = new Jedis("172.16.10.80", 6379)
        val dateFormat = new SimpleDateFormat("yyyyMMdd")
        val today = dateFormat.format(new Date())
        val redisKeyUV = s"users_cnt:$today"
        val redisKeyTotal = s"total_visits_cnt:$today"
        val browsers = List("Chrome", "Firefox", "Safari") // 假设这些是所有可能的浏览器
        try {
          val uvCount = jedis.scard(redisKeyUV)
          val totalCount = jedis.get(redisKeyTotal)
          val browserCounts = browsers.map { browser =>
            val browserKey = s"browser_visits_cnt:$browser:$today"
            val count = jedis.get(browserKey)
            s"$browser: ${Option(count).getOrElse("0")}"
          }.mkString(", ")

          println(s"UV count for $today: $uvCount, Total visits: $totalCount, Browser visits: $browserCounts")
        } finally {
          jedis.close()
        }
      }
    })

    ssc.start()
    ssc.awaitTermination()
  }
}
