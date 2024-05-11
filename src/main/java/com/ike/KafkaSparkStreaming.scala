import org.apache.spark.SparkConf
import org.apache.spark.streaming.{Seconds, StreamingContext, Minutes}
import org.apache.spark.streaming.kafka010._
import org.apache.kafka.common.serialization.StringDeserializer

object KafkaSparkStreaming {
  def main(args: Array[String]): Unit = {
    val conf = new SparkConf().setMaster("local[*]").setAppName("KafkaSparkStreamingPVUV")
    val ssc = new StreamingContext(conf, Seconds(10)) // 设置时间间隔为10秒
//    ssc.checkpoint("D:\\work\project\kafka-spark3\checkpoint-directory") // 设置检查点目录，用于状态恢复

    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> "172.16.10.132:9092", // Kafka 服务器地址
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> "use_a_separate_group_id_for_each_stream",
      "auto.offset.reset" -> "latest",
      "enable.auto.commit" -> (false: java.lang.Boolean)
    )

    val topics = Array("test") // Kafka 主题
    val stream = KafkaUtils.createDirectStream[String, String](
      ssc,
      LocationStrategies.PreferConsistent,
      ConsumerStrategies.Subscribe[String, String](topics, kafkaParams)
    )

    // 解析数据
    val parsedStream = stream.map(record => {
      val fields = record.value.split("\\|")
      (fields(0), fields(4)) // 提取 user_id 和 page_ui
    })

    // 计算总的平台访问量 (PV)
    val totalPageViews = parsedStream.map(_ => (1, 1)).reduceByKey(_ + _)
    val formattedTotalPageViews = totalPageViews.map { case (_, count) => ("total_page_cnt", count) }
    formattedTotalPageViews.print() // 打印整个平台的总访问量

    // 计算总的独立访客数 (UV)
    val userVisits = parsedStream.map { case (userId, _) => (userId, 1) }
    val uniqueUserVisits = userVisits.reduceByKeyAndWindow((a: Int, b: Int) => 1, Minutes(10), Seconds(10))
    val totalUniqueUsers = uniqueUserVisits.map(_ => (1, 1)).reduceByKey(_ + _)
    val formattedTotalUniqueUsers = totalUniqueUsers.map { case (_, count) => ("total_unique_users", count) }
    formattedTotalUniqueUsers.print() // 打印整个平台的独立访客数 (UV)

    ssc.start()
    ssc.awaitTermination()
  }
}
