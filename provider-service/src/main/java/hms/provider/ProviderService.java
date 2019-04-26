package hms.provider;

import java.security.InvalidKeyException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import hms.common.ExceptionWrapper;
import hms.common.IHMSExecutorContext;
import hms.dto.ProviderTracking;
import hms.hub.IHubService;
import hms.provider.models.ProviderModel;
import hms.provider.models.ProviderModel.ProviderTrackingModel;
import hms.provider.repositories.IProviderRepository;

public class ProviderService implements IProviderService{    
	private static final Logger logger = LoggerFactory.getLogger(ProviderService.class);

	private IProviderRepository repo;
	private IHubService hubservice;
	protected IHMSExecutorContext execContext;
	
	@Inject
	public ProviderService(IHMSExecutorContext ec,IHubService hubservice, IProviderRepository repo){
		this.repo = repo;
		this.hubservice = hubservice;
		this.execContext = ec;
	}
	
	@Override
	public CompletableFuture<Boolean> clear() {
		return CompletableFuture.supplyAsync(() -> {
			this.repo.clear();
			return true;
		},this.execContext.getExecutor());		
	}

	@Override
	public CompletableFuture<Boolean> initprovider(hms.dto.Provider providerdto) {
		return CompletableFuture.supplyAsync(()->{
			ProviderModel provider = this.repo.LoadById(providerdto.getProviderid());
			if(provider == null) {
				provider = new ProviderModel();			
			}
			provider.load(providerdto);
			this.repo.Save(provider);
			return true;
		}, this.execContext.getExecutor());
	}	

	protected Boolean internalTrackingProviderHub(ProviderTracking trackingdto, UUID hubid) {		
		hms.provider.models.ProviderModel provider = this.repo.LoadById(trackingdto.getProviderid());
		if(provider == null) {
			throw ExceptionWrapper.wrap(new InvalidKeyException(String.format("Provider not found {0}", trackingdto.getProviderid())));
		}
		ProviderTrackingModel tracking = new ProviderTrackingModel(hubid, trackingdto.getLatitude(),trackingdto.getLongitude());
		provider.setCurrentTracking(tracking);
		this.repo.Save(provider);
		return true;
	}	
	
	@Override
	public CompletableFuture<Boolean> tracking(ProviderTracking trackingdto) {
		return this.hubservice.getHostingHubId(trackingdto.getLatitude(), trackingdto.getLongitude())
					.thenApplyAsync((hubid) -> {
						return this.internalTrackingProviderHub(trackingdto, hubid);			
					}, this.execContext.getExecutor());
	}
	
	
	protected List<hms.dto.Provider> internalQueryProviders(List<UUID> hubids, hms.dto.Coordinate position, double distance){
		return this.repo.queryProviders(hubids, position.getLatitude(), position.getLongitude(), distance)
		.stream().map(p -> new hms.dto.Provider(p.getProviderid(), p.getName()))
		.collect(Collectors.toList());
	}
	
	@Override 
	public CompletableFuture<List<hms.dto.Provider>> queryProviders(hms.dto.Coordinate position, double distance){
		return this.hubservice.getConverHubIds(position.getLatitude(), position.getLongitude(), distance)
			.thenApplyAsync((hubids) -> {
				return this.internalQueryProviders(hubids, position, distance);	
			},this.execContext.getExecutor());	
	}
}
