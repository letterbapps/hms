package hms.hub;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface IHubService {
	public CompletableFuture<UUID> asynGetHostingHubId(double latitude, double longitude);
	public CompletableFuture<List<UUID>> asynGetConveringHubs(hms.dto.GeoQuery query);

	public UUID getHostingHubId(double latitude, double longitude);
	public List<UUID> getConveringHubs(hms.dto.GeoQuery query);	
	public String getZone(UUID hubid);

	void split(UUID id, int parts) ;	
}