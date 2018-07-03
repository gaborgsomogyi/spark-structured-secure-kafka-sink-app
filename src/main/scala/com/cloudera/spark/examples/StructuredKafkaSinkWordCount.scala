/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cloudera.spark.examples

import java.util.UUID
import scala.collection.JavaConverters._

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}

import org.apache.spark.sql.{ForeachWriter, SparkSession}
import org.apache.spark.sql.streaming.OutputMode


class KafkaForeachWriter(kafkaParams: Map[String, Object], topic: String) extends ForeachWriter[(String, String)] {
  var producer: KafkaProducer[String, String] = _

  def open(partitionId: Long,version: Long): Boolean = {
    producer = new KafkaProducer[String, String](kafkaParams.asJava)
    true
  }

  def process(value: (String, String)): Unit = {
    producer.send(new ProducerRecord(topic, value._1, value._2))
  }

  def close(errorOrNull: Throwable): Unit = {
    producer.close()
  }
}


object StructuredKafkaSinkWordCount {
  def main(args: Array[String]) {
    if (args.length < 4) {
      System.err.println(s"""
                            |Usage: StructuredKafkaSinkWordCount <bootstrap-servers> <protocol> <topic> <use-foreach-writer> [<checkpoint-location>]
                            |  <bootstrap-servers> The Kafka "bootstrap.servers" configuration.
                            |  A comma-separated list of host:port.
                            |  <protocol> Protocol used to communicate with brokers.
                            |  Valid values are: 'PLAINTEXT', 'SSL', 'SASL_PLAINTEXT', 'SASL_SSL'.
                            |  <topic> Single topic where output will be written.
                            |  <use-foreach-writer> Whether ForeachWriter should be used instead of kafka DataStreamWriter.
                            |  <checkpoint-location> Directory in which to create checkpoints.
                            |  If not provided, defaults to a randomized directory in /tmp.
                            |
      """.stripMargin)
      System.exit(1)
    }

    val Array(bootstrapServers, protocol, topic, useForeachWriter) = args
    val checkpointLocation =
      if (args.length > 4) args(4) else "/tmp/temporary-" + UUID.randomUUID.toString

    val isUsingSsl = protocol.endsWith("SSL")
    val isUsingForeachWriter = useForeachWriter.toBoolean

    val spark = SparkSession
      .builder
      .appName("StructuredKafkaSinkWordCount")
      .getOrCreate()

    import spark.implicits._

    // Create DataSet representing the stream of input lines from socket
    // nc -lk localhost 9999
    val lines = spark
      .readStream
      .format("socket")
      .option("host", "localhost")
      .option("port", 9999)
      .load()
      .as[String]

    // Generate running word count
    val wordCounts = lines
      .flatMap(_.split(" "))
      .groupBy("value")
      .count()
      .coalesce(4)
      .toDF("key", "value")
      .selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)")
      .as[(String, String)]

    // Start running the query that writes the running counts to kafka topic
    val writer = wordCounts
      .writeStream
      .outputMode(OutputMode.Update)
      .option("checkpointLocation", checkpointLocation)

    val kafkaBaseParams = Map[String, String](
      "kafka.bootstrap.servers" -> bootstrapServers,
      "kafka.security.protocol" -> protocol,
      "kafka.sasl.kerberos.service.name" -> "kafka"
    )

    val additionalSslParams = if (isUsingSsl) {
      Map(
        "kafka.ssl.truststore.location" -> "/etc/cdep-ssl-conf/CA_STANDARD/truststore.jks",
        "kafka.ssl.truststore.password" -> "cloudera"
      )
    } else {
      Map.empty
    }

    if (!isUsingForeachWriter) {
      val kafkaParams = kafkaBaseParams ++ Map[String, String](
        "topic" -> topic
      ) ++ additionalSslParams

      writer
        .options(kafkaParams)
        .format("kafka")
    } else {
      val kafkaParams = kafkaBaseParams ++ Map[String, Object](
        "key.serializer" -> "org.apache.kafka.common.serialization.StringSerializer",
        "value.serializer" -> "org.apache.kafka.common.serialization.StringSerializer"
      ) ++ additionalSslParams

      val kafkaForeachWriter = new KafkaForeachWriter(kafkaParams, topic)
      writer.foreach(kafkaForeachWriter)
    }

    val query = writer.start()
    query.awaitTermination()
  }
}
