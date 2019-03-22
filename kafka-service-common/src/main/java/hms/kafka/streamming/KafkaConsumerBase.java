package hms.kafka.streamming;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.slf4j.Logger;

public abstract class KafkaConsumerBase<TReq> {	
	protected KafkaConsumer<String, byte[]> consumer;
	protected String consumeTopic;
	protected String groupid;
	protected String server;
	protected int timeout = 5000;
	private Logger logger;
	private Class<TReq> reqManifest;
	protected KafkaConsumerBase(Logger logger, Class<TReq> reqManifest, String server, String groupid, String topic) {
		this.logger = logger;
		this.server = server;
		this.groupid = groupid;
		this.consumeTopic = topic;
		this.reqManifest = reqManifest;
		this.ensureTopic();
		this.createConsummer();
	}
	
	public void setTimeout(int timeoutInMillisecons) {
		this.timeout = timeoutInMillisecons;
	}
	
	protected void ensureTopic() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, this.server);
        AdminClient adminClient = AdminClient.create(props);
        
        NewTopic returnTopic = new NewTopic(this.consumeTopic, 2, (short)1);
        CreateTopicsResult createTopicsResult = adminClient.createTopics(Arrays.asList(returnTopic));
        try {
			createTopicsResult.all().get();
		} catch (InterruptedException | ExecutionException e) {
			logger.error("Create topic error",e);
		}
	}	
	
	protected void createConsummer() {
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, this.server);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, this.groupid);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteArrayDeserializer");        
        this.consumer = new KafkaConsumer<>(consumerProps);    
        this.consumer.subscribe(Pattern.compile(String.format("^%s.*", this.consumeTopic)));
        
        CompletableFuture.runAsync(()->{
            while(true) {
            	ConsumerRecords<String, byte[]> records = this.consumer.poll(Duration.ofMillis(100));
				for (ConsumerRecord<String, byte[]> record : records) {					
					try {
						this.processRequest(KafkaMessageUtils.getHMSMessage(this.reqManifest, record));
					} catch (IOException e) {
						// TODO Auto-generated catch block
						logger.error("Consumer error "+consumeTopic, e.getMessage());
					}
				}
            }
        });
	}		
	
	protected abstract void processRequest(HMSMessage<TReq> record) ;
}
