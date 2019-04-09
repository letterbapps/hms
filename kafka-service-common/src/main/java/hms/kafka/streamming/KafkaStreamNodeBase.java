package hms.kafka.streamming;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;

import hms.provider.KafkaProviderMeta;

public abstract class KafkaStreamNodeBase<TCon, TRep> {
	protected KafkaConsumer<UUID, byte[]> consumer;
	protected KafkaProducer<UUID, byte[]> producer;


	protected int timeout = 5000;
	protected int numberOfExecutors = 5;
	private boolean shutdownNode = false; 

	protected abstract Logger getLogger();	
	protected abstract Class<TCon> getReqManifest();
	protected abstract String getConsumeTopic();
	protected abstract String getForwardTopic();
	
	protected String getAfterForwardTopic() {
		return null;
	}
	protected abstract String getGroupid();
	protected abstract String getServer();
	
	protected KafkaStreamNodeBase() {
		this(5);
	}
	protected KafkaStreamNodeBase(int numberOfExecutors) {
		this.numberOfExecutors = numberOfExecutors;
		this.ensureTopics();
		this.createProducer();
		this.createConsummer();
	}

	public void setTimeout(int timeoutInMillisecons) {
		this.timeout = timeoutInMillisecons;
	}

	protected void ensureTopic(String topic) {
		Properties props = new Properties();
		props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, this.getServer());
		AdminClient adminClient = AdminClient.create(props);

		NewTopic cTopic = new NewTopic(topic, 2, (short) 1);
		CreateTopicsResult createTopicsResult = adminClient.createTopics(Arrays.asList(cTopic));
		try {
			createTopicsResult.all().get();
		} catch (InterruptedException | ExecutionException e) {
			this.getLogger().error("Create topic error {}", e.getMessage());
		}
	}

	protected void ensureTopics() {
		this.ensureTopic(this.getConsumeTopic());
		if(this.getForwardTopic()!=null) {
			this.ensureTopic(this.getForwardTopic());
		}		
		
		if(this.getAfterForwardTopic()!=null) {
			this.ensureTopic(this.getAfterForwardTopic());
		}
	}

	protected void createProducer() {
		Properties props = new Properties();
		props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, this.getServer());
		props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
				"org.apache.kafka.common.serialization.UUIDSerializer");
		props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
				"org.apache.kafka.common.serialization.ByteArraySerializer");
		this.producer = new KafkaProducer<>(props);
	}
	
	

	protected void createConsummer() {
		Properties consumerProps = new Properties();
		consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, this.getServer());
		consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, this.getGroupid());
		consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
				"org.apache.kafka.common.serialization.UUIDDeserializer");		
		consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
				"org.apache.kafka.common.serialization.ByteArrayDeserializer");
		this.consumer = new KafkaConsumer<>(consumerProps);
		this.consumer.subscribe(Collections.singletonList(this.getConsumeTopic()));

		
		{// process record
			java.util.function.Consumer<ConsumerRecord<UUID, byte[]>> processRecord = (record)->{
				try {
					 HMSMessage<TCon> request = KafkaMessageUtils.getHMSMessage(this.getReqManifest(), record);
					this.getLogger().info("Consuming {} {}",this.getConsumeTopic(), request.getRequestId());						 
					TRep res = this.processRequest(request);
					if(this.getForwardTopic()!=null) {
						if(this.getAfterForwardTopic() == null) {
							this.reply(request, res);
						}else {
							this.forward(request, res);
						}
					}
				} catch (IOException e) {
					this.getLogger().error("Consumer error {} {}", this.getConsumeTopic(), e.getMessage());
				}
			};
	
			final ExecutorService ex = this.numberOfExecutors > 1 ? Executors.newFixedThreadPool(numberOfExecutors):null;
			final Semaphore executorSemaphore = this.numberOfExecutors > 1 ? new Semaphore(numberOfExecutors) : null;		
			java.util.function.Consumer<ConsumerRecord<UUID, byte[]>> processRecordByPool = 
			this.numberOfExecutors > 1 ? (record) -> {
				try {
					executorSemaphore.acquire();
				} catch (InterruptedException e) {
					this.getLogger().error("Consumer error {} {}", this.getConsumeTopic(), e.getMessage());
				}
				ex.execute(()->{
					processRecord.accept(record);
					executorSemaphore.release();
				});			
			} : processRecord;
			
			Runnable cleanupPool = this.numberOfExecutors > 1 ? () -> {
				ex.shutdown();
				try {
					ex.awaitTermination(10, TimeUnit.MINUTES);
				} catch (InterruptedException e) {
					this.getLogger().error("Shutdown consumer error {} {}", this.getConsumeTopic(), e.getMessage());
				}
			}:()->{};
			
			new Thread(() -> {		
				while (!shutdownNode) {
					ConsumerRecords<UUID, byte[]> records = this.consumer.poll(Duration.ofMillis(500));
					for (ConsumerRecord<UUID, byte[]> record : records) {
						processRecordByPool.accept(record);
					}
				}
				cleanupPool.run();
			}).start();
		}
		
		this.getLogger().info("Consumer {} ready {}",this.getConsumeTopic(), this.numberOfExecutors);						 
	}

	protected void reply(HMSMessage<TCon> request, TRep value) {
		HMSMessage<TRep> replymsg = request.forwardRequest();
		replymsg.setData(value);
		String replytop = request.getCurrentResponsePoint(this.getForwardTopic());
		try {
			ProducerRecord<UUID, byte[]> record = KafkaMessageUtils.getProcedureRecord(replymsg, replytop);
			this.producer.send(record).get();
		} catch (IOException | InterruptedException | ExecutionException e) {
			this.getLogger().error("Reply message error {}", e.getMessage());
		}
	}
	
	protected void forward(HMSMessage<TCon> request, TRep value) {
		HMSMessage<TRep> forwardReq = request.forwardRequest();
		forwardReq.setData(value);
		try {//forward to find hub-id, then back to TrackingWithHubMessage
			forwardReq.addReponsePoint(this.getAfterForwardTopic(), request.getData());					
			ProducerRecord<UUID, byte[]> record = KafkaMessageUtils.getProcedureRecord(forwardReq, this.getForwardTopic());					
			this.producer.send(record);
		} catch (IOException e) {
			this.getLogger().error("Forward request error {}", e.getMessage());
		}		
	}

	protected abstract TRep processRequest(HMSMessage<TCon> record);
}
