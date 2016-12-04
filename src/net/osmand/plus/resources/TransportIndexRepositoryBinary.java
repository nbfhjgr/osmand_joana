package net.osmand.plus.resources;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import net.osmand.PlatformUtil;
import net.osmand.ResultMatcher;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.data.TransportRoute;
import net.osmand.data.TransportStop;
import net.osmand.plus.resources.ResourceManager.BinaryMapReaderResource;
import net.osmand.plus.resources.ResourceManager.BinaryMapReaderResourceType;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;

public class TransportIndexRepositoryBinary implements TransportIndexRepository {
	private static final Log log = PlatformUtil.getLog(TransportIndexRepositoryBinary.class);
	private BinaryMapReaderResource resource;

	public TransportIndexRepositoryBinary(BinaryMapReaderResource resource) {
		this.resource = resource;
	}
	
	public BinaryMapIndexReader getOpenFile() {
		return resource.getReader(BinaryMapReaderResourceType.TRANSPORT);
	}

	@Override
	public boolean checkContains(double latitude, double longitude) {
		return resource.getShallowReader().containTransportData(latitude, longitude);
	}
	@Override
	public boolean checkContains(double topLatitude, double leftLongitude, double bottomLatitude, double rightLongitude) {
		return resource.getShallowReader().containTransportData(topLatitude, leftLongitude, bottomLatitude, rightLongitude);
	}
	
	@Override
	public synchronized void searchTransportStops(double topLatitude, double leftLongitude, double bottomLatitude, double rightLongitude,
			int limit, List<TransportStop> stops, ResultMatcher<TransportStop> matcher) {
		long now = System.currentTimeMillis();
		try {
			getOpenFile().searchTransportIndex(BinaryMapIndexReader.buildSearchTransportRequest(MapUtils.get31TileNumberX(leftLongitude),
					MapUtils.get31TileNumberX(rightLongitude), MapUtils.get31TileNumberY(topLatitude), 
					MapUtils.get31TileNumberY(bottomLatitude), limit, stops));
			if (log.isDebugEnabled()) {
				log.debug(String.format("Search for %s done in %s ms found %s.", //$NON-NLS-1$
						topLatitude + " " + leftLongitude, System.currentTimeMillis() - now, stops.size())); //$NON-NLS-1$
			}
		} catch (IOException e) {
			log.error("Disk error ", e); //$NON-NLS-1$
		}
	}

	@Override
	public synchronized Collection<TransportRoute> getRouteForStop(TransportStop stop){
		try {
			Collection<TransportRoute> res = getOpenFile().getTransportRoutes(stop.getReferencesToRoutes()).valueCollection();
			if(res != null){
				return res;
			}
		} catch (IOException e) {
			log.error("Disk error ", e); //$NON-NLS-1$
		}
		return Collections.emptyList();
	}
	
	@Override
	public boolean acceptTransportStop(TransportStop stop) {
		return resource.getShallowReader().transportStopBelongsTo(stop);
	}


}
