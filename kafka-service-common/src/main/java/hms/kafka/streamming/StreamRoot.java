package hms.kafka.streamming;

import java.io.IOException;
import java.util.Hashtable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;

public class StreamRoot<TReq,TRes> extends KafkaStreamNodeBase<TRes,Void>{
	private String requestTopic;
	
	public StreamRoot(Class<TRes> manifiestTRes,  Logger logger, String server, String topic) {
		super(logger, manifiestTRes, server, UUID.randomUUID().toString(), topic+".return");
		this.requestTopic = topic;
	}
	
	protected void processRequest(HMSMessage<TRes> response) {
		handleResponse(response);
	}		

	private Map<UUID, StreamReponse> waiters = new Hashtable<UUID, StreamReponse>();
	private synchronized UUID nextId() {
		return UUID.randomUUID();
	} 
	
	public void handleResponse(HMSMessage<TRes> reponse) {
		if(waiters.containsKey(reponse.getRequestId())) {
			StreamReponse waiter = waiters.remove(reponse.getRequestId()) ;
			waiter.setData(reponse);
			waiter.notify();
		}
	}
	
	public void handleRequestError(UUID id, String error) {
		if(waiters.containsKey(id)) {			
			StreamReponse waiter = waiters.remove(id) ;
			waiter.setError(error);
			waiter.notify();			
		}
	}	
	
	public StreamReponse startStream(java.util.function.Function<UUID,HMSMessage<TReq>> createRequest) {
		return this.startStream(createRequest, this.timeout);
	}
	
	public StreamReponse startStream(java.util.function.Function<UUID,HMSMessage<TReq>> createRequest, int timeout) {
		UUID id = this.nextId();
		StreamReponse waiter = new StreamReponse(id);
		this.waiters.put(id, waiter);
		HMSMessage<TReq> request = createRequest.apply(id);
		if(request != null) {
			request.addReponsePoint(this.consumeTopic);
			try {
				ProducerRecord<String, byte[]> record = KafkaMessageUtils.getProcedureRecord(request, this.requestTopic);
				this.producer.send(record).get(timeout, TimeUnit.MILLISECONDS);
			} catch (IOException | InterruptedException | ExecutionException | TimeoutException e) {
				this.handleRequestError(id, "Request error: "+e.getMessage());
				return waiter;
			}
			
			try {
				if(waiter.needWaiting()) {
					waiter.wait(timeout);
				}
			} catch (InterruptedException e) {
				this.handleRequestError(id, "Request error: "+e.getMessage());
				return waiter;
			}					
		}else {
			waiter.setError("Empty request");
		}
		return waiter;
	}	
}
