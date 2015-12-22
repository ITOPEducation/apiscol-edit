package fr.ac_versailles.crdp.apiscol.edit.sync;

import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.log4j.Logger;
import org.w3c.dom.Document;

import com.sun.jersey.api.client.WebResource;

import fr.ac_versailles.crdp.apiscol.restClient.LanWebResource;
import fr.ac_versailles.crdp.apiscol.utils.LogUtility;

public class SyncService {

	private static LanWebResource contentWebServiceResource;
	private static LanWebResource metadataWebServiceResource;
	private static LanWebResource thumbsWebServiceResource;
	private static ExecutorService syncExecutor;
	private static URI baseUri;

	public static enum SYNC_MODES {
		FROM_RESOURCE_ID, FROM_METADATA_ID, FROM_MANIFEST
	}

	public static void initialize(LanWebResource contentWebServiceResource,
			LanWebResource metadataWebServiceResource,
			LanWebResource thumbsWebServiceResource) {
		SyncService.contentWebServiceResource = contentWebServiceResource;
		SyncService.metadataWebServiceResource = metadataWebServiceResource;
		SyncService.thumbsWebServiceResource = thumbsWebServiceResource;
		syncExecutor = Executors.newSingleThreadExecutor();

	}

	public static void forwardContentInformationToMetadata(String resourceId) {
		SyncAgent syncAgent = new SyncAgent(SYNC_MODES.FROM_RESOURCE_ID,
				contentWebServiceResource, metadataWebServiceResource,
				thumbsWebServiceResource, resourceId, baseUri);
		syncExecutor.execute(syncAgent);

	}

	public static void updateMetadataWithContentInformation(String metadataId) {
		SyncAgent syncAgent = new SyncAgent(SYNC_MODES.FROM_METADATA_ID,
				contentWebServiceResource, metadataWebServiceResource,
				thumbsWebServiceResource, metadataId, baseUri);
		syncExecutor.execute(syncAgent);

	}

	public static void notifyUriInfo(URI baseUri) {
		SyncService.baseUri = baseUri;
	}

	public static void stopExecutors() {
		createLogger();
		logger.info("Thread executors are going to be stopped for Apiscol Edit Synchronisation Service.");
		if (syncExecutor != null)
			syncExecutor.shutdown();

	}

	private static Logger logger;

	private static void createLogger() {
		if (logger == null)
			logger = LogUtility.createLogger(SyncService.class
					.getCanonicalName());
	}

}
