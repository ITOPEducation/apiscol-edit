package fr.ac_versailles.crdp.apiscol.edit.urlHandling;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response.Status;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.core.util.MultivaluedMapImpl;

import fr.ac_versailles.crdp.apiscol.restClient.LanWebResource;

public class UrlParsingWorker implements Runnable {

	private final Integer identifier;
	private final String resourceId;
	private final String url;
	private final LanWebResource contentWebServiceResource;
	private final UrlParsingRegistry urlParsingRegistry;
	private final Boolean updateArchive;
	private final String eTag;

	public UrlParsingWorker(Integer identifier, String resourceId, String url,
			LanWebResource contentWebServiceResource, Boolean updateArchive,
			String eTag, UrlParsingRegistry urlParsingRegistry) {
		this.identifier = identifier;
		this.resourceId = resourceId;
		this.url = url;
		this.contentWebServiceResource = contentWebServiceResource;
		this.updateArchive = updateArchive;
		this.eTag = eTag;
		this.urlParsingRegistry = urlParsingRegistry;
	}

	@Override
	public void run() {
		MultivaluedMap<String, String> params = new MultivaluedMapImpl();
		params.add("url", url);
		params.add("update_archive", updateArchive == null ? "false"
				: updateArchive.toString());
		ClientResponse response = contentWebServiceResource.path("resource")
				.path(resourceId).accept(MediaType.APPLICATION_XML)
				.header(HttpHeaders.IF_MATCH, eTag)
				.put(ClientResponse.class, params);
		if (response.getStatus() == Status.OK.getStatusCode()) {
			urlParsingRegistry.notifyParsingSuccess(identifier);
		} else {
			urlParsingRegistry.notifyParsingFailure(identifier,
					response.getEntity(String.class));
		}

	}

}
