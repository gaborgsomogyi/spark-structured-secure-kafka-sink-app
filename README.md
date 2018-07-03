spark-structured-secure-kafka-sink-app
============

### Introduction
This small app shows how to store data in a secure (Kerberized) Kafka cluster from Spark Structured Streaming using [the direct connector](http://spark.apache.org/docs/latest/structured-streaming-kafka-integration.html) which uses the [Kafka Producer API](https://kafka.apache.org/documentation/#producerconfigs). In order to use this app, you need to use Cloudera Distribution of Apache Kafka version 2.1.0 or later. And, you need to use Cloudera Distribution of Apache Spark 2 release 1 or later.

Currently this example focuses on accessing Kafka securely via Kerberos. It assumes SSL (i.e. encryption over the wire) is configured for Kafka. It assumes that Kafka authorization (via Sentry, for example) is not being used. That can be setup separately.

### Build the app
To build, you need Scala 2.11, git and maven on the box.
Do a git clone of this repo and then run:
```
cd spark-structured-secure-kafka-sink-app
mvn clean package
```
Then, take the generated uber jar from `target/spark-structured-secure-kafka-sink-app-1.0-SNAPSHOT-jar-with-dependencies.jar` to the spark client node (where you are going to launch the query from). Let's assume you place this file in the home directory of this client machine.

### Running the app
#### Creating socket source
As the source of the application is socket `netcat` listening has to be started on the client node (where you are going to launch the query from).
```
nc -lk 9999
```
The spark application will connect to this port where words can be entered manually.

#### Creating kafka sink topic
The kafka topic has to be created where the result can be stored:
```
kafka-topics --create --zookeeper <zk-node>:2181 --topic <topic> --partitions 4 --replication-factor 3 --config cleanup.policy=compact

cd ~
# Generate the consumer.properties file which will be used by the console consumer
# to select the appropriate security protocol and mechanism.
# If not using SSL, do not set `ssl.truststore.location` and `ssl.truststore.password` 
# If not using SSL, change security.protocol's value to be SASL_PLAINTEXT (instead of SASL_SSL).
echo "security.protocol=SASL_SSL" >> consumer.properties
echo "sasl.kerberos.service.name=kafka" >> consumer.properties
echo "ssl.truststore.location=/full/path/to/truststore.jks" >> consumer.properties
echo "ssl.truststore.password=password" >> consumer.properties
```
Populate the following contents in a different JAAS conf, say `consumer_jaas.conf`:
```
# Change the /full/path/to/kafka_client.keytab below
# to the full path to the keytab.
# Change the principal name accordingly
cat << 'EOF' > consumer_jaas.conf
KafkaClient {
    com.sun.security.auth.module.Krb5LoginModule required
    useKeyTab=true
    storeKey=true
    keyTab="/full/path/to/kafka_client.keytab"
    useTicketCache=false
    serviceName="kafka"
    principal="user@MY.DOMAIN.COM";
}; 
EOF
```

```
# Run the console consumer
# If not using SSL, change the port below to 9092
export KAFKA_OPTS="-Djava.security.auth.login.config=consumer_jaas.conf"
kafka-console-consumer --bootstrap-server <bootstrap-server>:9093 --consumer.config consumer.properties --topic <topic> --property print.key=true
```

#### Creating configuration
Before you run this app, you need to set up some JAAS configuration for Kerberos access. This particular configuration is inspired by that described in the [Apache Kafka documentation](https://kafka.apache.org/documentation/#security_kerberos_sasl_clientconfig). You also need to have access to the keytab needed for secure Kafka access.

We assume the client user's keytab is called `kafka_client.keytab` and is placed in the home directory on the client box. Let's create a file called `kafka_client_jaas.conf` and place it in the home directory of the user as well with the JAAS conf:
```
# Change kafka_client.keytab to the keytab file name.
# Keep the beginning `./` infront of the keytab name. 
# Change principal to be the real principal below
cat << 'EOF' > kafka_client_jaas.conf
KafkaClient {
    com.sun.security.auth.module.Krb5LoginModule required
    useKeyTab=true
    storeKey=true
    keyTab="./kafka_client.keytab"
    useTicketCache=false
    serviceName="kafka"
    principal="user@MY.DOMAIN.COM";
};
EOF
```

### spark-submit
Now run the following command:
```
# set num-executors, num-cores, etc. according to your needs.
# If simply testing, ok to leave the defaults as below
# Change references to kafka_client.keytab to the actual name of the keytab.
# If the keytab is not present in the current working directory,
# Change kafka_client.keytab to /full/path/to/kafka_client.keytab.
# If not using SSL, change the port 9093 below to the 9092.
# Change last parameter to true if ForeachWriter would be used for kafka sink.
spark2-submit \
  --num-executors 2 \
  --master yarn \
  --deploy-mode cluster \
  --files kafka_client_jaas.conf,kafka_client.keytab \
  --driver-java-options "-Djava.security.auth.login.config=./kafka_client_jaas.conf" \
  --class com.cloudera.spark.examples.StructuredKafkaSinkWordCount \
  --conf "spark.executor.extraJavaOptions=-Djava.security.auth.login.config=./kafka_client_jaas.conf" \
  spark-structured-secure-kafka-sink-app-1.0-SNAPSHOT-jar-with-dependencies.jar \
  <kafka broker>:9093 \
  SASL_SSL \
  <topic> \
  false \
```

### Generating some test data
While you run this app, you may want to generate some data in the socket Spark Streaming is reading from, and may want to view the word counts as the data is being generated. To generate data in the socket, you can use the already opened `netcat` console:
```
word1
word2 word1
```

### What's happening under the hood?
For producing data via SASL/Kerberos, we pass on the JAAS configuration (`kafka_client_jaas.conf`) to all executors. Along with this config, the keytab is also passed on to all executors.

These executors via the JAAS configuration know where the keytab is (in their working directory, since it was passed using `--files`). And, the driver (in the YARN cluster mode) and the executors then use the configured credentails to access Kafka via Kerberos tickets.

### What you should see
```
word1	2
word2	1
```
