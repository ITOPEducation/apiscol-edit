package fr.ac_versailles.crdp.apiscol.edit.urlHandling;

import java.util.HashMap;
import java.util.Map;

import com.sun.jersey.api.client.WebResource;

import fr.ac_versailles.crdp.apiscol.edit.fileHandling.UrlParsingStates;
import fr.ac_versailles.crdp.apiscol.edit.sync.SyncService;

public class UrlParsingRegistry {

	private static Integer counter;
	private static Map<Integer, Thread> urlParsings = new HashMap<Integer, Thread>();
	private static Map<Integer, String> etags = new HashMap<Integer, String>();
	private static Map<Integer, String> urls = new HashMap<Integer, String>();
	private static Map<Integer, Boolean> updateArchives = new HashMap<Integer, Boolean>();
	private static Map<Integer, UrlParsingStates> parsingStates = new HashMap<Integer, UrlParsingStates>();
	private static Map<Integer, String> messages = new HashMap<Integer, String>();
	private static Map<Integer, String> resourcesIds = new HashMap<Integer, String>();
	private final WebResource contentWebServiceResource;

	public UrlParsingRegistry(WebResource contentWebServiceResource) {
		this.contentWebServiceResource = contentWebServiceResource;
		counter = 0;

	}

	public Integer newUrlParsing(String resourceId, String url,
			Boolean updateArchive, String eTag) {
		synchronized (counter) {
			counter++;
			UrlParsingWorker worker = new UrlParsingWorker(counter, resourceId,
					url, contentWebServiceResource, updateArchive, eTag, this);
			Thread thread = new Thread(worker);
			thread.start();
			urlParsings.put(counter, thread);
			updateArchives.put(counter, updateArchive);
			etags.put(counter, eTag);
			urls.put(counter, url);
			parsingStates.put(counter, UrlParsingStates.initiated);
			messages.put(counter, String.format(
					"Url %s has been send to content server for parsing", url));
			resourcesIds.put(counter, resourceId);
			return counter;
		}
	}

	public void notifyParsingSuccess(Integer identifier) {
		urlParsings.put(identifier, null);
		parsingStates.put(identifier, UrlParsingStates.done);
		messages.put(
				identifier,
				"This url has been successfully registred and indexed on the content  web service.");
		SyncService.forwardContentInformationToMetadata(resourcesIds
				.get(identifier));
	}

	public void notifyParsingFailure(Integer identifier, String message) {
		urlParsings.put(identifier, null);
		parsingStates.put(identifier, UrlParsingStates.aborted);
		messages.put(
				identifier,
				"This url has been rejected from the content web service with the following message :"
						+ message);
	}

	public UrlParsingStates getTransferState(Integer urlParsingId) {
		if (!parsingStates.containsKey(urlParsingId))
			return UrlParsingStates.unknown;
		return parsingStates.get(urlParsingId);
	}

	public String getMessage(Integer urlParsingId) {
		if (!messages.containsKey(urlParsingId))
			return "This url parsing operation is unknown from the system";
		return messages.get(urlParsingId);
	}

	public String getResourceIdForParsing(Integer urlParsingId) {
		if (!resourcesIds.containsKey(urlParsingId))
			return "";
		return resourcesIds.get(urlParsingId);
	}

	public Thread getThread(Integer urlParsingId) {
		if (urlParsings.containsKey(urlParsingId))
			return urlParsings.get(urlParsingId);
		return null;
	}

}
