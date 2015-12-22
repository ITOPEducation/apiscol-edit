package fr.ac_versailles.crdp.apiscol.edit.sync;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response.Status;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.google.gson.Gson;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.core.util.MultivaluedMapImpl;

import fr.ac_versailles.crdp.apiscol.UsedNamespaces;
import fr.ac_versailles.crdp.apiscol.edit.sync.SyncService.SYNC_MODES;
import fr.ac_versailles.crdp.apiscol.restClient.LanWebResource;
import fr.ac_versailles.crdp.apiscol.utils.LogUtility;
import fr.ac_versailles.crdp.apiscol.utils.XMLUtils;

public class SyncAgent implements Runnable {

	private SYNC_MODES mode;
	private LanWebResource contentWebServiceResource;
	private LanWebResource metadataWebServiceResource;
	private LanWebResource thumbsWebServiceResource;
	private Logger logger;
	private String entityUrnOrId;
	private URI editUri;
	private Document contentTechInfos;

	public SyncAgent(SyncService.SYNC_MODES mode,
			LanWebResource contentWebServiceResource,
			LanWebResource metadataWebServiceResource,
			LanWebResource thumbsWebServiceResource, String entityUrnOrId,
			URI baseUri) {
		this.mode = mode;
		this.contentWebServiceResource = contentWebServiceResource;
		this.metadataWebServiceResource = metadataWebServiceResource;
		this.thumbsWebServiceResource = thumbsWebServiceResource;
		this.entityUrnOrId = entityUrnOrId;
		this.editUri = baseUri;
		createLogger();
	}

	public SyncAgent(SYNC_MODES mode, LanWebResource contentWebServiceResource,
			LanWebResource metadataWebServiceResource, URI baseUri) {
		this.mode = mode;
		this.contentWebServiceResource = contentWebServiceResource;
		this.metadataWebServiceResource = metadataWebServiceResource;
		createLogger();
	}

	private void createLogger() {
		if (logger == null)
			logger = LogUtility
					.createLogger(this.getClass().getCanonicalName());

	}

	@Override
	public void run() {
		if (mode == SYNC_MODES.FROM_RESOURCE_ID)
			forwardContentInformationToMetadata();
		else if (mode == SYNC_MODES.FROM_METADATA_ID)
			updateMetadataWithContentInformation();

	}

	private void updateMetadataWithContentInformation() {
		MultivaluedMap<String, String> contentQueryParams = new MultivaluedMapImpl();
		String metadataUri = getMetadataUriFromId(entityUrnOrId);
		contentQueryParams.add("mdid", metadataUri);
		ClientResponse contentWebServiceResponse = contentWebServiceResource
				.path("resource").queryParams(contentQueryParams)
				.accept(MediaType.APPLICATION_XML_TYPE)
				.get(ClientResponse.class);
		if (contentWebServiceResponse.getStatus() == Status.OK.getStatusCode()) {
			Document contentResponse = contentWebServiceResponse
					.getEntity(Document.class);
			SyncService
					.forwardContentInformationToMetadata(extractResourceId(contentResponse));
		}
	}

	private String getMetadataUriFromId(String metadataId) {
		return new StringBuilder()
				.append(metadataWebServiceResource.getURI().toString())
				.append("/").append(metadataId).toString();
	}

	private String extractResourceId(Document contentResponse) {
		if (contentResponse == null
				|| contentResponse.getDocumentElement() == null
				|| contentResponse
						.getDocumentElement()
						.getElementsByTagNameNS(UsedNamespaces.ATOM.getUri(),
								"id").getLength() == 0)
			return "";

		return ((Element) contentResponse.getDocumentElement()
				.getElementsByTagNameNS(UsedNamespaces.ATOM.getUri(), "id")
				.item(0)).getTextContent();
	}

	private void forwardContentInformationToMetadata() {
		ClientResponse contentResponse = contentWebServiceResource
				.path("resource").path(entityUrnOrId).path("technical_infos")
				.accept(MediaType.APPLICATION_XML).get(ClientResponse.class);

		if (contentResponse.getStatus() != Status.OK.getStatusCode()) {
			String infos = contentResponse.getEntity(String.class);
			logger.warn(String
					.format("Error while trying to retrieve technical information for resource %s with message %s : abort",
							entityUrnOrId, infos));
			return;
		}
		contentTechInfos = contentResponse.getEntity(Document.class);
		String metadataId = extractTechnicalField("metadata", contentTechInfos);
		if (StringUtils.isBlank(metadataId)) {
			logger.warn(String
					.format("No metadata detected while trying to retrieve technical information for resource %s. Service response : %s : abort",
							entityUrnOrId,
							XMLUtils.XMLToString(contentTechInfos)));
			return;
		}

		String size = extractTechnicalField("size", contentTechInfos);
		String technicalLocation = extractTechnicalField("technical-location",
				contentTechInfos);
		String apiscolInstance = extractTechnicalField("apiscol_instance",
				contentTechInfos);
		String location = extractTechnicalField("location", contentTechInfos);
		String language = extractTechnicalField("language", contentTechInfos);
		String format = extractTechnicalField("format", contentTechInfos);
		String previewUri = extractTechnicalField("preview", contentTechInfos);

		if (!metadataId.startsWith(metadataWebServiceResource.getWanUrl()
				.toString())) {
			logger.warn(String
					.format("The metadata id %s received for resource %s does not belong to this apiscol instance : abort",
							metadataId, entityUrnOrId));
			return;
		}
		List<String> metadataList = new ArrayList<String>();
		metadataList.add(metadataId);
		String jsonMetadataList = new Gson().toJson(metadataList);
		MultivaluedMap<String, String> iconsQueryParams = new MultivaluedMapImpl();
		iconsQueryParams.add("mdids", jsonMetadataList);
		ClientResponse thumbsWebServiceResponse = thumbsWebServiceResource
				.queryParams(iconsQueryParams)
				.accept(MediaType.APPLICATION_XML_TYPE)
				.get(ClientResponse.class);
		String thumbUri = "";
		try {
			if (thumbsWebServiceResponse.getStatus() != Status.OK
					.getStatusCode()) {
				logger.error(String
						.format("The thumbs ws service response for ressource %s with mdid %s was not ok with message %s ",
								entityUrnOrId, metadataId,
								thumbsWebServiceResponse
										.getEntity(String.class)));

			} else {
				Document thumbsDocument = thumbsWebServiceResponse
						.getEntity(Document.class);
				thumbUri = extractThumbUri(thumbsDocument);
				logger.info(String
						.format("The thumbs ws service response for ressource %s with mdid %s was ok with uri %s ",
								entityUrnOrId, metadataId, thumbUri));
			}
		} finally {

			metadataId = removeWebServiceUri(metadataId);
			ClientResponse metaGetResponse = metadataWebServiceResource
					.path(metadataId).accept(MediaType.APPLICATION_XML)
					.get(ClientResponse.class);

			if (metaGetResponse.getStatus() != Status.OK.getStatusCode()) {
				String metaRepresentation = metaGetResponse
						.getEntity(String.class);
				logger.warn(String
						.format("Error while trying to retrieve metadata %s for resource %s with message %s : abort",
								metadataId, entityUrnOrId, metaRepresentation));
				return;
			}
			String metaEtag = metaGetResponse.getHeaders().getFirst(
					HttpHeaders.ETAG);
			MultivaluedMap<String, String> params = new MultivaluedMapImpl();
			if (StringUtils.isNotBlank(size))
				params.add("size", size);
			if (StringUtils.isNotBlank(technicalLocation))
				params.add("technical-location", technicalLocation);
			if (StringUtils.isNotBlank(apiscolInstance))
				params.add("apiscol_instance", apiscolInstance);
			if (StringUtils.isNotBlank(location))
				params.add("location", location);
			if (StringUtils.isNotBlank(format))
				params.add("format", format);
			if (StringUtils.isNotBlank(language))
				params.add("language", language);
			if (StringUtils.isNotBlank(thumbUri))
				params.add("thumb", thumbUri);
			if (StringUtils.isNotBlank(previewUri))
				params.add("preview", previewUri);
			if (editUri != null && StringUtils.isNotBlank(editUri.toString()))
				params.add("edit_uri", editUri.toString());
			ClientResponse metaPutResponse = metadataWebServiceResource
					.path(metadataId).path("technical_infos")
					.accept(MediaType.APPLICATION_XML)
					.header(HttpHeaders.IF_MATCH, metaEtag)
					.put(ClientResponse.class, params);
			if (metaGetResponse.getStatus() != Status.OK.getStatusCode()) {
				logger.warn(String
						.format("Failed to update technical informations for metadata %s for resource %s with message %s : abort",
								metadataId, entityUrnOrId,
								metaPutResponse.getEntity(String.class)));
				return;
			}
		}
	}

	private String extractTechnicalField(String tagName, Document infos) {
		if (infos == null
				|| infos.getDocumentElement() == null
				|| infos.getDocumentElement().getElementsByTagName(tagName)
						.getLength() == 0)
			return "";

		return infos.getDocumentElement().getElementsByTagName(tagName).item(0)
				.getTextContent();
	}

	private String extractThumbUri(Document thumbs) {
		if (thumbs == null
				|| thumbs.getDocumentElement() == null
				|| thumbs
						.getDocumentElement()
						.getElementsByTagNameNS(
								UsedNamespaces.APISCOL.getUri(), "link")
						.getLength() == 0
				|| !((Element) thumbs
						.getDocumentElement()
						.getElementsByTagNameNS(
								UsedNamespaces.APISCOL.getUri(), "link")
						.item(0)).hasAttribute("href"))
			return "";

		return ((Element) thumbs
				.getDocumentElement()
				.getElementsByTagNameNS(UsedNamespaces.APISCOL.getUri(), "link")
				.item(0)).getAttribute("href");
	}

	private String removeWebServiceUri(String metadataId) {
		return metadataId.replace(metadataWebServiceResource.getWanUrl() + "/",
				"");
	}

	public Document getContentTechInfos() {
		return contentTechInfos;
	}
}
