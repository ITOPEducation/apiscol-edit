package fr.ac_versailles.crdp.apiscol.edit;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import javax.xml.parsers.DocumentBuilder;

import org.apache.commons.lang.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.core.header.FormDataContentDisposition;
import com.sun.jersey.core.util.MultivaluedMapImpl;
import com.sun.jersey.multipart.FormDataMultiPart;
import com.sun.jersey.multipart.FormDataParam;

import fr.ac_versailles.crdp.apiscol.ApiscolApi;
import fr.ac_versailles.crdp.apiscol.ParametersKeys;
import fr.ac_versailles.crdp.apiscol.ResourcesKeySyntax;
import fr.ac_versailles.crdp.apiscol.UsedNamespaces;
import fr.ac_versailles.crdp.apiscol.edit.fileHandling.TransferRegistry;
import fr.ac_versailles.crdp.apiscol.edit.sync.SyncAgent;
import fr.ac_versailles.crdp.apiscol.edit.sync.SyncService;
import fr.ac_versailles.crdp.apiscol.edit.sync.SyncService.SYNC_MODES;
import fr.ac_versailles.crdp.apiscol.edit.urlHandling.UrlParsingRegistry;
import fr.ac_versailles.crdp.apiscol.representations.EntitiesRepresentationBuilderFactory;
import fr.ac_versailles.crdp.apiscol.representations.IEntitiesRepresentationBuilder;
import fr.ac_versailles.crdp.apiscol.utils.FileUtils;
import fr.ac_versailles.crdp.apiscol.utils.XMLUtils;

@Path("/")
public class ResourceEditionAPI extends ApiscolApi {

	@Context
	UriInfo uriInfo;
	@Context
	ServletContext context;

	private static UrlParsingRegistry urlParsingRegistry;

	private static TransferRegistry transferRegistry;

	private static WebResource contentWebServiceResource;
	private static WebResource thumbsWebServiceResource;
	private static WebResource metadataWebServiceResource;

	private static boolean initialized;
	private static boolean syncServiceInitialized;

	private static Client client;
	private static String fileRepoPath;
	private static String temporaryFilesPrefix;

	public ResourceEditionAPI(@Context HttpServletRequest request,
			@Context ServletContext context) {
		super(context);
		if (!initialized)
			initializeStaticParameters(context);
	}

	private void initializeStaticParameters(ServletContext context) {
		client = Client.create();

		fileRepoPath = getProperty(ParametersKeys.fileRepoPath, context);
		temporaryFilesPrefix = getProperty(ParametersKeys.temporaryFilesPrefix,
				context);
		URI contentWebserviceUrl = null;
		URI metadataWebserviceUrl = null;
		URI thumbsWebserviceUrl = null;
		try {
			contentWebserviceUrl = new URI(getProperty(
					ParametersKeys.contentWebserviceUrl, context));
		} catch (URISyntaxException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		try {
			metadataWebserviceUrl = new URI(getProperty(
					ParametersKeys.metadataWebserviceUrl, context));
		} catch (URISyntaxException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		try {
			thumbsWebserviceUrl = new URI(getProperty(
					ParametersKeys.thumbsWebserviceUrl, context));
		} catch (URISyntaxException e) {
			e.printStackTrace();
		}
		contentWebServiceResource = client.resource(UriBuilder.fromUri(
				contentWebserviceUrl).build());

		metadataWebServiceResource = client.resource(UriBuilder.fromUri(
				metadataWebserviceUrl).build());
		thumbsWebServiceResource = client.resource(UriBuilder.fromUri(
				thumbsWebserviceUrl).build());

		transferRegistry = new TransferRegistry(fileRepoPath,
				temporaryFilesPrefix, contentWebServiceResource);
		urlParsingRegistry = new UrlParsingRegistry(contentWebServiceResource);

		SyncService.initialize(contentWebServiceResource,
				metadataWebServiceResource, thumbsWebServiceResource);
		initialized = true;
	}

	@POST
	@Path("/resource")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces({ MediaType.APPLICATION_ATOM_XML, MediaType.APPLICATION_XML })
	public Response createResource(
			@FormParam(value = "mdid") final String metadataId,
			@FormParam(value = "type") final String scormType)
			throws ContentServiceFailureException {
		if (!syncServiceInitialized)
			SyncService.notifyUriInfo(uriInfo.getBaseUri());
		ClientResponse cr = createEmptyResource(metadataId, scormType);
		Document response = null;
		if (Response.Status.CREATED.getStatusCode() == cr.getStatus()) {
			response = cr.getEntity(Document.class);
		} else {
			String error = cr.getEntity(String.class);
			throw new ContentServiceFailureException(error);
		}
		String resourceUrn = ((Element) response.getElementsByTagName("entry")
				.item(0)).getElementsByTagName("id").item(0).getTextContent();
		String resourceId = ResourcesKeySyntax
				.extractResourceIdFromUrn(resourceUrn);
		File resourceDirectory = getResourceDirectory(fileRepoPath, resourceId);
		if (!resourceDirectory.exists()) {
			logger.error(String
					.format("Error while creating resource %s, directory %s was not created",
							resourceUrn, resourceDirectory.getAbsolutePath()));
		} else {
			if (StringUtils.isNotEmpty(metadataId))
				SyncService.forwardContentInformationToMetadata(resourceId);
		}
		return Response
				.status(cr.getStatus())
				.header("Access-Control-Allow-Origin", "*")
				.entity(response)
				.type(MediaType.APPLICATION_XML)
				.header(HttpHeaders.ETAG,
						cr.getHeaders().getFirst(HttpHeaders.ETAG)).build();
	}

	private ClientResponse createEmptyResource(String metadataId,
			String scormType) {
		MultivaluedMap<String, String> postParams = new MultivaluedMapImpl();
		postParams.add("mdid", metadataId);
		postParams.add("type", scormType);
		postParams.add("edit_uri", uriInfo.getBaseUri().toString());
		ClientResponse cr = contentWebServiceResource.path("resource")
				.accept(MediaType.APPLICATION_XML_TYPE)
				// send what you want as if match
				.header(HttpHeaders.IF_MATCH, UUID.randomUUID().toString())
				.post(ClientResponse.class, postParams);
		return cr;
	}

	@POST
	@Path("/resource/{resid}/refresh")
	@Produces({ MediaType.APPLICATION_ATOM_XML, MediaType.APPLICATION_XML })
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response updateResource(
			@Context HttpServletRequest request,
			@PathParam(value = "resid") final String resourceId,
			@DefaultValue("false") @FormParam(value = "preview") final boolean updatePreview,
			@DefaultValue("false") @FormParam(value = "index") final boolean updateIndex,
			@DefaultValue("false") @FormParam(value = "archive") final boolean updateArchive,
			@DefaultValue("false") @FormParam(value = "sync-tech-infos") final boolean updateTechnicalInfos) {
		if (!syncServiceInitialized)
			SyncService.notifyUriInfo(uriInfo.getBaseUri());
		if (StringUtils.isBlank(resourceId))
			return Response.status(Response.Status.BAD_REQUEST)
					.header("Access-Control-Allow-Origin", "*")
					.entity("The resource id is not correct").build();
		if (updateTechnicalInfos) {
			SyncAgent syncAgent = new SyncAgent(SYNC_MODES.FROM_RESOURCE_ID,
					contentWebServiceResource, metadataWebServiceResource,
					thumbsWebServiceResource, resourceId, uriInfo.getBaseUri());
			Thread thread = new Thread(syncAgent);
			thread.start();
			try {
				thread.join();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			return Response.status(Status.OK)
					.header("Access-Control-Allow-Origin", "*")
					.entity(syncAgent.getContentTechInfos())
					.type(MediaType.APPLICATION_XML).build();
		}
		String ifMatch = request.getHeader(HttpHeaders.IF_MATCH);
		MultivaluedMap<String, String> params = new MultivaluedMapImpl();
		params.add("edit_uri", uriInfo.getBaseUri().toString());
		params.add("preview", updatePreview ? "true" : "false");
		params.add("index", updateIndex ? "true" : "false");
		params.add("archive", updateArchive ? "true" : "false");
		ClientResponse response = contentWebServiceResource.path("resource")
				.path(resourceId).path("refresh")
				.accept(MediaType.APPLICATION_XML)
				.header(HttpHeaders.IF_MATCH, ifMatch)
				.post(ClientResponse.class, params);
		String etag = response.getHeaders().getFirst(HttpHeaders.ETAG);
		SyncService.forwardContentInformationToMetadata(resourceId);
		String entity = response.getEntity(String.class);
		return Response.status(response.getStatus()).entity(entity)

		.type(response.getType()).header(HttpHeaders.ETAG, etag)
				.header("Access-Control-Allow-Origin", "*").build();
	}

	@POST
	@Path("/meta/{mdid}/refresh")
	@Produces({ MediaType.APPLICATION_ATOM_XML, MediaType.APPLICATION_XML })
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response updateMetadata(
			@Context HttpServletRequest request,
			@PathParam(value = "mdid") final String metadataId,
			@DefaultValue("false") @FormParam(value = "index") final boolean updateIndex) {
		if (!syncServiceInitialized)
			SyncService.notifyUriInfo(uriInfo.getBaseUri());
		String entity;
		if (StringUtils.isBlank(metadataId))
			return Response.status(Response.Status.BAD_REQUEST)
					.header("Access-Control-Allow-Origin", "*")
					.entity("The metadata id is not correct").build();
		if (updateIndex) {
			String ifMatch = request.getHeader(HttpHeaders.IF_MATCH);
			MultivaluedMap<String, String> params = new MultivaluedMapImpl();
			params.add("edit_uri", uriInfo.getBaseUri().toString());
			params.add("index", updateIndex ? "true" : "false");
			ClientResponse response = metadataWebServiceResource
					.path(metadataId).path("refresh")
					.accept(MediaType.APPLICATION_XML)
					.header(HttpHeaders.IF_MATCH, ifMatch)
					.post(ClientResponse.class, params);
			String etag = response.getHeaders().getFirst(HttpHeaders.ETAG);
			entity = response.getEntity(String.class);
			return Response.status(response.getStatus()).entity(entity)
					.type(response.getType()).header(HttpHeaders.ETAG, etag)
					.header("Access-Control-Allow-Origin", "*").build();
		}

		return Response.status(Status.BAD_REQUEST).entity("No parameter")
				.header("Access-Control-Allow-Origin", "*").build();

	}

	@PUT
	@Path("/resource/{resid}")
	@Produces({ MediaType.APPLICATION_ATOM_XML, MediaType.APPLICATION_XML })
	public Response setPropertiesForResource(
			@Context HttpServletRequest request,
			@PathParam(value = "resid") final String resourceId,
			@FormParam(value = "mdid") final String metadataId,
			@FormParam(value = "url") final String url,
			@FormParam(value = "main_filename") final String mainFileName,
			@FormParam(value = "type") final String scormType,
			@DefaultValue("true") @FormParam(value = "update") final boolean updateArchive) {
		if (!syncServiceInitialized)
			SyncService.notifyUriInfo(uriInfo.getBaseUri());
		if (StringUtils.isBlank(resourceId))
			return Response.status(Response.Status.BAD_REQUEST)
					.header("Access-Control-Allow-Origin", "*")
					.entity("The resource id is not correct").build();
		String ifMatch = request.getHeader(HttpHeaders.IF_MATCH);
		MultivaluedMap<String, String> params = new MultivaluedMapImpl();
		if (StringUtils.isNotBlank(metadataId))
			params.add("mdid", metadataId);
		if (StringUtils.isNotBlank(scormType))
			params.add("type", scormType);
		if (StringUtils.isNotBlank(url))
			params.add("url", url);
		if (StringUtils.isNotBlank(mainFileName))
			params.add("main_filename", mainFileName);
		params.add("edit_uri", uriInfo.getBaseUri().toString());
		params.add("update_preview", updateArchive ? "true" : "false");
		ClientResponse response = contentWebServiceResource.path("resource")
				.path(resourceId).accept(MediaType.APPLICATION_XML)
				.header(HttpHeaders.IF_MATCH, ifMatch)
				.put(ClientResponse.class, params);
		String etag = response.getHeaders().getFirst(HttpHeaders.ETAG);
		SyncService.forwardContentInformationToMetadata(resourceId);
		String entity = response.getEntity(String.class);
		return Response.status(response.getStatus()).entity(entity)
				.type(response.getType()).header(HttpHeaders.ETAG, etag)
				.header("Access-Control-Allow-Origin", "*").build();
	}

	private String removeWebServiceUri(String metadataId) {
		return metadataId
				.replace(metadataWebServiceResource.getURI() + "/", "");
	}

	private String extractThumbUriFromThumbRepresentation(Document thumbs) {
		if (thumbs == null
				|| thumbs.getDocumentElement() == null
				|| thumbs
						.getDocumentElement()
						.getElementsByTagNameNS(
								UsedNamespaces.APISCOL.getUri(), "thumb")
						.getLength() == 0)
			return "";

		return ((Element) thumbs
				.getDocumentElement()
				.getElementsByTagNameNS(UsedNamespaces.APISCOL.getUri(),
						"thumb").item(0)).getTextContent();
	}

	private String extractUriFromReport(Document doc, String format) {
		Element root = doc.getDocumentElement();
		NodeList links = root.getElementsByTagName("link");
		Element link;
		for (int i = 0; i < links.getLength(); i++) {
			link = (Element) links.item(i);
			if (link.getAttribute("rel").equals("self")
					&& link.getAttribute("type").equals(format))
				return link.getAttribute("href");
		}
		return StringUtils.EMPTY;
	}

	@POST
	@Path("/transfer")
	@Produces({ MediaType.APPLICATION_ATOM_XML, MediaType.APPLICATION_XML })
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	public Response createFileTransfer(
			@Context HttpServletRequest request,
			@FormDataParam(value = "resid") String resourceId,
			@FormDataParam(value = "main_filename") final String mainFileName,
			@DefaultValue("true") @FormDataParam(value = "update_archive") final boolean updateArchive,
			@DefaultValue("false") @FormDataParam(value = "is_archive") final boolean isArchive,
			@DefaultValue("false") @FormDataParam(value = "main") final boolean mainFile,
			@FormDataParam("file") InputStream uploadedInputStream,
			@FormDataParam("file") FormDataContentDisposition fileDetail) {
		if (!syncServiceInitialized)
			SyncService.notifyUriInfo(uriInfo.getBaseUri());
		if (ResourcesKeySyntax.isUrn(resourceId))
			resourceId = ResourcesKeySyntax
					.extractResourceIdFromUrn(resourceId);
		Integer fileTransferId;
		String fileName = fileDetail.getFileName();
		if (fileName.contains("/"))
			fileName = fileName.substring(fileName.lastIndexOf("/") + 1);

		String eTag = request.getHeader(HttpHeaders.IF_MATCH);
		byte[] data = null;
		try {
			data = copyStreamToBuffer(uploadedInputStream);
		} catch (IOException e) {
			e.printStackTrace();
			return Response
					.serverError()
					.entity("The server was not able to handle your file with this error message :"
							+ e.getMessage()).type(MediaType.TEXT_PLAIN)
					.header("Access-Control-Allow-Origin", "*").build();
		}
		try {
			uploadedInputStream.close();
		} catch (IOException e) {
			logger.warn(String
					.format("A probleme was encountered while closing the input stream for file %s : %s",
							fileDetail.getFileName(), e.getMessage()));
		}
		synchronized (transferRegistry) {
			fileTransferId = transferRegistry.newTransfer(resourceId, data,
					fileName, mainFileName, updateArchive, mainFile, eTag,
					isArchive ? TransferRegistry.TransferTypes.ARCHIVE
							: TransferRegistry.TransferTypes.FILE, this);
		}
		String requestedFormat = request.getHeader(HttpHeaders.ACCEPT);
		IEntitiesRepresentationBuilder rb = EntitiesRepresentationBuilderFactory
				.getRepresentationBuilder(requestedFormat, context);
		return Response
				.ok()
				.entity(rb.getFileTransferRepresentation(fileTransferId,
						uriInfo, transferRegistry, contentWebServiceResource
								.path("resource").getURI()))
				.header("Access-Control-Allow-Origin", "*")
				.type(rb.getMediaType()).build();
	}

	private byte[] copyStreamToBuffer(InputStream uploadedInputStream)
			throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		int c;
		while ((c = uploadedInputStream.read()) != -1) {
			bos.write(c);
		}
		uploadedInputStream.close();
		bos.close();
		return bos.toByteArray();
	}

	@POST
	@Path("/url_parsing")
	@Produces({ MediaType.APPLICATION_ATOM_XML, MediaType.APPLICATION_XML })
	public Response createUrlParsing(
			@Context HttpServletRequest request,
			@FormParam(value = "resid") String resourceId,
			@DefaultValue("true") @QueryParam(value = "update_archive") final Boolean updateArchive,
			@FormParam(value = "url") final String url) {
		if (!syncServiceInitialized)
			SyncService.notifyUriInfo(uriInfo.getBaseUri());
		if (StringUtils.isBlank(resourceId)) {
			String message = "If you want to parse the url "
					+ url
					+ " you should specify the resource id as 'resid' query parameter";
			logger.warn(message);
			return Response.status(Status.BAD_REQUEST).entity(message)
					.header("Access-Control-Allow-Origin", "*").build();
		}
		if (StringUtils.isBlank(url)) {
			String message = "If you want to parse an url for resource"
					+ resourceId
					+ " you should specify the url string as 'url' query parameter.";
			logger.warn(message);
			return Response.status(Status.BAD_REQUEST).entity(message)
					.header("Access-Control-Allow-Origin", "*").build();
		}
		if (ResourcesKeySyntax.isUrn(resourceId))
			resourceId = ResourcesKeySyntax
					.extractResourceIdFromUrn(resourceId);
		Integer urlParsingId;
		String eTag = request.getHeader(HttpHeaders.IF_MATCH);
		synchronized (urlParsingRegistry) {
			urlParsingId = urlParsingRegistry.newUrlParsing(resourceId, url,
					updateArchive, eTag);
		}
		String requestedFormat = request.getContentType();
		IEntitiesRepresentationBuilder rb = EntitiesRepresentationBuilderFactory
				.getRepresentationBuilder(requestedFormat, context);
		return Response
				.ok()
				.entity(rb.getUrlParsingRespresentation(urlParsingId, uriInfo,
						urlParsingRegistry,
						contentWebServiceResource.path("resource").getURI()))
				.header("Access-Control-Allow-Origin", "*")
				.type(rb.getMediaType()).build();
	}

	@GET
	@Path("/transfer/{transferid}")
	@Produces({ MediaType.APPLICATION_ATOM_XML, MediaType.APPLICATION_XML,
			"application/javascript" })
	public Response getFileTransferState(@Context HttpServletRequest request,
			@PathParam(value = "transferid") final Integer transferId,
			@QueryParam(value = "format") final String format)
			throws IOException {
		if (!syncServiceInitialized)
			SyncService.notifyUriInfo(uriInfo.getBaseUri());
		String requestedFormat = guessRequestedFormat(request, format);
		IEntitiesRepresentationBuilder rb = EntitiesRepresentationBuilderFactory
				.getRepresentationBuilder(requestedFormat, context);
		return Response
				.ok()
				.entity(rb.getFileTransferRepresentation(transferId, uriInfo,
						transferRegistry,
						contentWebServiceResource.path("resource").getURI()))
				.header("Access-Control-Allow-Origin", "*")
				.type(rb.getMediaType()).build();
	}

	@GET
	@Path("/url_parsing/{urlparsingid}")
	@Produces({ MediaType.APPLICATION_ATOM_XML, MediaType.APPLICATION_XML })
	public Response getUrlParsingState(@Context HttpServletRequest request,
			@PathParam(value = "urlparsingid") final Integer urlParsingId)
			throws IOException {
		if (!syncServiceInitialized)
			SyncService.notifyUriInfo(uriInfo.getBaseUri());
		String requestedFormat = request.getContentType();
		IEntitiesRepresentationBuilder rb = EntitiesRepresentationBuilderFactory
				.getRepresentationBuilder(requestedFormat, context);
		return Response
				.ok()
				.entity(rb.getUrlParsingRespresentation(urlParsingId, uriInfo,
						urlParsingRegistry,
						contentWebServiceResource.path("resource").getURI()))
				.header("Access-Control-Allow-Origin", "*")
				.type(rb.getMediaType()).build();
	}

	@DELETE
	@Path("/resource/{resid}")
	@Produces({ MediaType.APPLICATION_ATOM_XML, MediaType.APPLICATION_XML })
	public Response deleteContent(
			@Context HttpServletRequest request,
			@PathParam(value = "resid") final String resourceId,
			@QueryParam(value = "fname") final String fileName,
			@DefaultValue("true") @QueryParam(value = "update_archive") final boolean updateArchive) {
		if (!syncServiceInitialized)
			SyncService.notifyUriInfo(uriInfo.getBaseUri());
		if (StringUtils.isBlank(fileName))
			return deleteResource(request, resourceId);
		else
			return deleteFile(request, resourceId, fileName, updateArchive);
	}

	public Response deleteFile(HttpServletRequest request,
			final String resourceId, final String fileName,
			final boolean updateArchive) {
		String ifMatch = request.getHeader(HttpHeaders.IF_MATCH);
		ClientResponse response = null;
		MultivaluedMap<String, String> queryParams = new MultivaluedMapImpl();
		queryParams.add("update_archive", updateArchive ? "true" : "false");
		queryParams.add("fname", fileName);
		queryParams.add("edit_uri", uriInfo.getBaseUri().toString());
		try {
			response = contentWebServiceResource.path("resource")
					.path(resourceId).queryParams(queryParams)
					.accept(MediaType.APPLICATION_ATOM_XML)
					.header(HttpHeaders.IF_MATCH, ifMatch)
					.delete(ClientResponse.class);
		} catch (UniformInterfaceException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ClientHandlerException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		String etag = response.getHeaders().getFirst(HttpHeaders.ETAG);
		int status = response.getStatus();
		if (Response.Status.OK.getStatusCode() == status) {
			SyncService.forwardContentInformationToMetadata(resourceId);
			return Response.status(response.getStatus())
					.entity(response.getEntity(Document.class))
					.header(HttpHeaders.ETAG, etag).type(response.getType())
					.header("Access-Control-Allow-Origin", "*").build();
		} else {
			String entity = response.getEntity(String.class);
			return Response
					.status(status)
					.entity(entity)
					.header(HttpHeaders.ETAG,
							response.getHeaders().getFirst(HttpHeaders.ETAG))
					.header("Access-Control-Allow-Origin", "*")
					.type(response.getType()).build();
		}

	}

	public Response deleteResource(@Context HttpServletRequest request,
			final String resourceId) {

		String ifMatch = request.getHeader(HttpHeaders.IF_MATCH);
		ClientResponse response = contentWebServiceResource.path("resource")
				.path(resourceId).accept(MediaType.APPLICATION_ATOM_XML)
				.header(HttpHeaders.IF_MATCH, ifMatch)
				.delete(ClientResponse.class);

		int status = response.getStatus();
		if (Response.Status.OK.getStatusCode() == status) {
			Document entity = response.getEntity(Document.class);
			return Response.ok().entity(entity).type(response.getType())
					.header("Access-Control-Allow-Origin", "*")
					.header("Access-Control-Allow-Methods", "DELETE").build();
		} else {
			String entity = response.getEntity(String.class);
			return Response.status(status).entity(entity)
					.header("Access-Control-Allow-Origin", "*")
					.type(response.getType()).build();
		}

	}

	public static File getResourceDirectory(String fileRepoPath,
			String resourceId) {
		// TODO file repopath est connu dans toute la classe
		File file = new File(FileUtils.getFilePathHierarchy(fileRepoPath,
				resourceId.toString()));
		return file;
	}

	@POST
	@Path("/meta")
	@Produces({ MediaType.APPLICATION_ATOM_XML, MediaType.APPLICATION_XML })
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	public Response createMetadata(
			@Context HttpServletRequest request,
			@FormDataParam("file") InputStream uploadedInputStream,
			@FormDataParam("file") FormDataContentDisposition fileDetail,
			@DefaultValue("false") @FormDataParam(value = "url_autodetect") final boolean urlAutoDetect)
			throws ContentServiceFailureException,
			UnknownMetadataRepositoryException, UnknownMetadataException {
		if (!syncServiceInitialized)
			SyncService.notifyUriInfo(uriInfo.getBaseUri());
		File file = writeToTempFile(uploadedInputStream);
		try {

			uploadedInputStream.close();
		} catch (IOException e) {
			logger.warn(String
					.format("A probleme was encountered while closing the input streamm for file %s : %s",
							fileDetail.getFileName(), e.getMessage()));
		}
		FormDataMultiPart form = null;
		ClientResponse cr = null;
		try {
			form = new FormDataMultiPart().field("file", file,
					MediaType.MULTIPART_FORM_DATA_TYPE).field("edit_uri",
					uriInfo.getBaseUri().toString());

			cr = metadataWebServiceResource.accept(MediaType.APPLICATION_XML)
					.type(MediaType.MULTIPART_FORM_DATA)
					// send what you want as etag
					.header(HttpHeaders.IF_MATCH, UUID.randomUUID().toString())
					.entity(form).post(ClientResponse.class);
		} finally {
			try {
				form.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		int status = cr.getStatus();

		if (Response.Status.OK.getStatusCode() == status) {
			Document response = cr.getEntity(Document.class);
			if (urlAutoDetect) {
				String contentUrl = this
						.extractContentUrlFromMetadataResponse(response);
				String metadataId = extractUriFromReport(response, "text/html");

				if (StringUtils.isEmpty(contentUrl)) {
					logger.warn("Autodetection of url impossible : no url provided");
				} else {
					logger.info("Autodetection de l'url " + contentUrl);
					ClientResponse contentClientResponse = createEmptyResource(
							metadataId, "url");
					Document contentXmlResponse = null;

					if (Response.Status.OK.getStatusCode() == cr.getStatus()) {
						contentXmlResponse = contentClientResponse
								.getEntity(Document.class);
					} else {
						String error = contentClientResponse
								.getEntity(String.class);
						throw new ContentServiceFailureException(error);
					}
					String resourceUrn = ((Element) contentXmlResponse
							.getElementsByTagName("entry").item(0))
							.getElementsByTagName("id").item(0)
							.getTextContent();
					String resourceId = ResourcesKeySyntax
							.extractResourceIdFromUrn(resourceUrn);
					String contentEtag = extractEtagFromContentDocument(contentXmlResponse);
					boolean updateArchive = true;
					if (StringUtils.isNotEmpty(metadataId)) {
						SyncService
								.forwardContentInformationToMetadata(resourceId);
						Integer urlParsingId;

						synchronized (urlParsingRegistry) {
							urlParsingId = urlParsingRegistry.newUrlParsing(
									resourceId, contentUrl, updateArchive,
									contentEtag);
						}
						String requestedFormat = request.getContentType();
						IEntitiesRepresentationBuilder rb = EntitiesRepresentationBuilderFactory
								.getRepresentationBuilder(requestedFormat,
										context);
						Document notReturnedEntity = (Document) rb
								.getUrlParsingRespresentation(
										urlParsingId,
										uriInfo,
										urlParsingRegistry,
										contentWebServiceResource.path(
												"resource").getURI());
						Node notReturnedEntityRoot = notReturnedEntity
								.getFirstChild();
						Node movedNode = response.importNode(
								notReturnedEntityRoot, true);
						// replace content url in metadata document
						response.getDocumentElement().appendChild(movedNode);
						String contentXMLRepresentationUrl = extractUriFromReport(
								contentXmlResponse, "application/atom+xml");
						String contentHTMLRepresentationUrl = extractUriFromReport(
								contentXmlResponse, "text/html");
						Element root = response.getDocumentElement();
						NodeList links = root.getElementsByTagName("link");
						Element link;
						for (int i = 0; i < links.getLength(); i++) {
							link = (Element) links.item(i);
							if (link.getAttribute("rel").equals("describes")) {
								if (link.getAttribute("type").equals(
										"application/atom+xml"))
									link.setAttribute("href",
											contentXMLRepresentationUrl);
								if (link.getAttribute("type").equals(
										"text/html"))
									link.setAttribute("href",
											contentHTMLRepresentationUrl);
							}
						}
						MultivaluedMap<String, String> thumbsQueryParams = new MultivaluedMapImpl();
						thumbsQueryParams.add("mdids", metadataId);
						try {
							// first we ask to get the last thumb etag
							ClientResponse thumbsWebServiceResponse1 = thumbsWebServiceResource
									.queryParams(thumbsQueryParams)
									.accept(MediaType.APPLICATION_XML_TYPE)
									.get(ClientResponse.class);

							Document thumbXMLResponse = thumbsWebServiceResponse1
									.getEntity(Document.class);
							String thumbEtag = extractEtagFromThumbDocument(thumbXMLResponse);
							MultivaluedMap<String, String> iconsQueryParams = new MultivaluedMapImpl();
							iconsQueryParams.add("mdid", metadataId);
							iconsQueryParams.add("auto", "true");
							ClientResponse thumbsWebServiceResponse = thumbsWebServiceResource
									.queryParams(iconsQueryParams)
									.accept(MediaType.APPLICATION_XML_TYPE)
									.header(HttpHeaders.IF_MATCH, thumbEtag)
									.put(ClientResponse.class);
							String thumbUri = "";
							Document thumbsDocument;
							if (thumbsWebServiceResponse.getStatus() != Status.OK
									.getStatusCode()) {
								String message = String
										.format("The thumbs ws service response for  mdid %s was not ok with message %s ",
												metadataId,
												thumbsWebServiceResponse
														.getEntity(String.class));
								logger.error(message);

							} else {
								thumbsDocument = thumbsWebServiceResponse
										.getEntity(Document.class);
								thumbUri = extractThumbUriFromThumbRepresentation(thumbsDocument);
								logger.info(String
										.format("The thumbs ws service response for mdid %s was ok with uri %s ",
												metadataId, thumbUri));
							}
							updateThumbUriInMetadatas(metadataId, thumbUri);
							// nothing to do with the response
						} catch (Exception e) {
							logger.error("It seems impossible to delete the thumb attached to this resource "
									+ metadataId);
							e.printStackTrace();
						}

					}

				}
			}

			return Response.status(cr.getStatus()).type(cr.getType())
					.header("Access-Control-Allow-Origin", "*")
					.entity(response).build();
		} else {
			return Response.status(cr.getStatus()).type(cr.getType())
					.header("Access-Control-Allow-Origin", "*")
					.entity(cr.getEntity(String.class)).build();
		}

	}

	private String extractContentUrlFromMetadataResponse(Document response) {
		String contentUrl = "";
		Boolean goOn = true;
		NodeList entryElements = response.getElementsByTagName("entry");
		Element entryElement = null;
		if (goOn && entryElements.getLength() > 0) {
			entryElement = (Element) entryElements.item(0);
		} else {
			goOn = false;
		}
		NodeList linkElements = entryElement.getElementsByTagName("link");
		Element linkElement = null;
		int nbLinks = linkElements.getLength();
		if (goOn && nbLinks > 0) {
			goOn = false;
			for (int i = 0; i < nbLinks; i++) {
				linkElement = (Element) linkElements.item(i);
				if (linkElement.getAttribute("rel").equals("describes")
						&& linkElement.getAttribute("type").equals("text/html")) {
					goOn = true;

					break;
				}

			}

		} else {
			goOn = false;
		}
		if (goOn && linkElement.hasAttribute("href")) {
			contentUrl = linkElement.getAttribute("href");
		} else {
			goOn = false;
		}
		return contentUrl;
	}

	@PUT
	@Path("/meta/{mdid}")
	@Produces({ MediaType.APPLICATION_ATOM_XML, MediaType.APPLICATION_XML })
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	public Response changeMetadata(@Context HttpServletRequest request,
			@FormDataParam("file") InputStream uploadedInputStream,
			@FormDataParam("file") FormDataContentDisposition fileDetail,
			@PathParam(value = "mdid") final String metadataId) {
		if (!syncServiceInitialized)
			SyncService.notifyUriInfo(uriInfo.getBaseUri());
		String ifMatch = request.getHeader(HttpHeaders.IF_MATCH);
		File file = writeToTempFile(uploadedInputStream);
		try {
			uploadedInputStream.close();
		} catch (IOException e) {
			logger.warn(String
					.format("A probleme was encountered while closing the input streamm for file %s : %s",
							fileDetail.getFileName(), e.getMessage()));
		}
		FormDataMultiPart form = null;
		ClientResponse cr = null;
		try {
			form = new FormDataMultiPart().field("file", file,
					MediaType.MULTIPART_FORM_DATA_TYPE).field("edit_uri",
					uriInfo.getBaseUri().toString());
			cr = metadataWebServiceResource.path(metadataId)
					.accept(MediaType.APPLICATION_XML)
					.type(MediaType.MULTIPART_FORM_DATA)
					.header(HttpHeaders.IF_MATCH, ifMatch).entity(form)
					.put(ClientResponse.class);

		} finally {
			try {
				form.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		int status = cr.getStatus();
		SyncService.updateMetadataWithContentInformation(metadataId);
		if (Response.Status.OK.getStatusCode() == status) {
			Document entity = cr.getEntity(Document.class);
			return Response.status(cr.getStatus()).type(cr.getType())
					.header("Access-Control-Allow-Origin", "*").entity(entity)
					.build();
		} else {
			String entity = cr.getEntity(String.class);
			return Response.status(cr.getStatus()).type(cr.getType())
					.header("Access-Control-Allow-Origin", "*").entity(entity)
					.build();
		}

	}

	@DELETE
	@Path("/meta/{mdid}")
	@Produces({ MediaType.APPLICATION_ATOM_XML, MediaType.APPLICATION_XML })
	public Response deleteMetadata(@Context HttpServletRequest request,
			@PathParam(value = "mdid") final String metadataId) {
		if (!syncServiceInitialized)
			SyncService.notifyUriInfo(uriInfo.getBaseUri());
		String ifMatch = request.getHeader(HttpHeaders.IF_MATCH);
		ClientResponse response = metadataWebServiceResource.path(metadataId)
				.accept(MediaType.APPLICATION_ATOM_XML)
				.header(HttpHeaders.IF_MATCH, ifMatch)
				.delete(ClientResponse.class);
		MultivaluedMap<String, String> thumbsQueryParams = new MultivaluedMapImpl();
		Document metaXmlResponse = response.getEntity(Document.class);

		thumbsQueryParams.add("mdids",
				extractUriFromReport(metaXmlResponse, "text/html"));

		// first we ask to get the last thumb etag
		ClientResponse thumbsWebServiceResponse1 = thumbsWebServiceResource
				.queryParams(thumbsQueryParams)
				.accept(MediaType.APPLICATION_XML_TYPE)
				.get(ClientResponse.class);

		try {
			Document thumbXMLResponse = thumbsWebServiceResponse1
					.getEntity(Document.class);
			String thumbEtag = extractEtagFromThumbDocument(thumbXMLResponse);
			MultivaluedMap<String, String> thumbsQueryParams2 = new MultivaluedMapImpl();
			thumbsQueryParams2.add("mdid",
					extractUriFromReport(metaXmlResponse, "text/html"));
			thumbsWebServiceResource.queryParams(thumbsQueryParams2)
					.accept(MediaType.APPLICATION_XML_TYPE)
					.header(HttpHeaders.IF_MATCH, thumbEtag)
					.delete(ClientResponse.class);
			// nothing to do with the response
		} catch (Exception e) {
			logger.error("It seems impossible to delete the thumb attached to this resource "
					+ metadataId);
			e.printStackTrace();
		}
		int status = response.getStatus();
		if (Response.Status.OK.getStatusCode() == status) {
			return Response.status(response.getStatus())
					.entity(metaXmlResponse).type(response.getType())
					.header("Access-Control-Allow-Origin", "*").build();
		} else {
			return Response.status(response.getStatus())
					.entity(metaXmlResponse).type(response.getType())
					.header("Access-Control-Allow-Origin", "*").build();
		}

	}

	private String extractEtagFromThumbDocument(Document doc) {
		// TODO better catch excetions
		return ((Element) doc.getDocumentElement()
				.getElementsByTagName("thumb").item(0)).getAttribute("version");
	}

	private String extractEtagFromContentDocument(Document doc) {
		NodeList entryElements = doc.getElementsByTagName("entry");
		if (entryElements.getLength() > 0) {
			NodeList updatedElements = doc.getElementsByTagName("updated");
			Element updatedElement = null;
			if (updatedElements.getLength() > 0) {
				updatedElement = (Element) updatedElements.item(0);
				return updatedElement.getTextContent();
			}
		}
		return "";
	}

	@DELETE
	@Path("/meta")
	@Produces({ MediaType.APPLICATION_ATOM_XML, MediaType.APPLICATION_XML })
	public Response deleteMetadataWithQueryParameter(
			@Context HttpServletRequest request,
			@QueryParam(value = "mdid") final String metadataUri)
			throws UnknownMetadataException {
		if (!syncServiceInitialized)
			SyncService.notifyUriInfo(uriInfo.getBaseUri());
		String ifMatch = request.getHeader(HttpHeaders.IF_MATCH);
		if (!metadataUri.startsWith(metadataWebServiceResource.getURI()
				.toString()))
			throw new UnknownMetadataException(
					String.format(
							"The metadata uri %s does not belong to this apiscol instance",
							metadataUri));
		// TODO mapper UnknownMetadataException
		String metadataId = metadataUri.replace(metadataWebServiceResource
				.getURI().toString() + "/", "");
		// TODO accepter aussi des uuid
		ClientResponse response = metadataWebServiceResource.path(metadataId)
				.accept(MediaType.APPLICATION_ATOM_XML)
				.header(HttpHeaders.IF_MATCH, ifMatch)
				.delete(ClientResponse.class);
		int status = response.getStatus();
		if (Response.Status.OK.getStatusCode() == status) {
			return Response.status(response.getStatus())
					.entity(response.getEntity(Document.class))
					.type(response.getType())
					.header("Access-Control-Allow-Origin", "*").build();
		} else {
			return Response.status(response.getStatus())
					.entity(response.getEntity(String.class))
					.type(response.getType())
					.header("Access-Control-Allow-Origin", "*").build();
		}

	}

	private File writeToTempFile(InputStream uploadedInputStream) {
		String file = String.format("%s/%s", System
				.getProperty("java.io.tmpdir"), UUID.randomUUID().toString());
		try {

			OutputStream out = new FileOutputStream(file);
			int read = 0;
			byte[] bytes = new byte[1024];
			while ((read = uploadedInputStream.read(bytes)) != -1) {
				out.write(bytes, 0, read);
			}
			out.flush();
			out.close();
		} catch (IOException e) {
			// TODO mapper l'exception
			e.printStackTrace();
		}
		return new File(file);
	}

	@POST
	@Path("/maintenance/{service}/{operation}")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces({ MediaType.APPLICATION_ATOM_XML, MediaType.APPLICATION_XML })
	public Response handleMaintenanceOperation(
			@Context HttpServletRequest request,
			@PathParam(value = "service") final String serviceName,
			@PathParam(value = "operation") final String requestedOperation) {
		if (!syncServiceInitialized)
			SyncService.notifyUriInfo(uriInfo.getBaseUri());
		WebResource webServiceResource = null;
		Boolean requestedOperationExists = false;
		if (serviceName.equals("content")) {
			webServiceResource = contentWebServiceResource;
			if (requestedOperation.equals("optimization")
					|| requestedOperation.equals("link_update_process")
					|| requestedOperation.equals("deletion")
					|| requestedOperation.equals("recovery"))
				requestedOperationExists = true;
		} else if (serviceName.equals("meta")) {
			webServiceResource = metadataWebServiceResource;
			if (requestedOperation.equals("optimization")
					|| requestedOperation.equals("deletion")
					|| requestedOperation.equals("recovery"))
				requestedOperationExists = true;
		} else {
			return Response
					.status(Status.BAD_REQUEST)
					.type(MediaType.TEXT_PLAIN)
					.header("Access-Control-Allow-Origin", "*")
					.entity(String.format("The web service %s does not exist",
							serviceName)).build();
		}
		if (!requestedOperationExists)
			return Response
					.status(Status.BAD_REQUEST)
					.type(MediaType.TEXT_PLAIN)
					.header("Access-Control-Allow-Origin", "*")
					.entity(String
							.format("The operation %s does not apply to the web service %s",
									requestedOperation, serviceName)).build();
		ClientResponse response = webServiceResource.path("maintenance")
				.path(requestedOperation)
				.accept(MediaType.APPLICATION_XML_TYPE)
				// send what you want as if match
				.header(HttpHeaders.IF_MATCH, UUID.randomUUID().toString())

				.post(ClientResponse.class);

		int status = response.getStatus();

		if (Response.Status.OK.getStatusCode() == status) {
			return Response.ok().entity(response.getEntity(Document.class))
					.type(response.getType())
					.header("Access-Control-Allow-Origin", "*").build();
		} else {
			return Response.status(response.getStatus())
					.entity(response.getEntity(String.class))
					.type(response.getType())
					.header("Access-Control-Allow-Origin", "*").build();
		}
	}

	@POST
	@Path("/thumb")
	@Produces({ MediaType.APPLICATION_ATOM_XML, MediaType.APPLICATION_XML })
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	public Response sendCustomThumb(@Context HttpServletRequest request,
			@FormDataParam("image") InputStream uploadedInputStream,
			@FormDataParam("image") FormDataContentDisposition fileDetail,
			@FormDataParam("mdid") String metadataId)
			throws UnknownMetadataException {
		if (!syncServiceInitialized)
			SyncService.notifyUriInfo(uriInfo.getBaseUri());
		File image = writeToTempFile(uploadedInputStream);
		try {
			uploadedInputStream.close();
		} catch (IOException e) {
			logger.warn(String
					.format("A probleme was encountered while closing the input streamm for image (custom thumb) %s : %s",
							fileDetail.getFileName(), e.getMessage()));
		}
		FormDataMultiPart form = new FormDataMultiPart()
				.field("image", image, MediaType.MULTIPART_FORM_DATA_TYPE)
				.field("mdid", ResourcesKeySyntax.removeSSL(metadataId))
				.field("fname", fileDetail.getFileName());
		String ifMatch = request.getHeader(HttpHeaders.IF_MATCH);
		ClientResponse thumbsWebServiceResponse = thumbsWebServiceResource
				.accept(MediaType.APPLICATION_XML)
				.type(MediaType.MULTIPART_FORM_DATA)
				.header(HttpHeaders.IF_MATCH, ifMatch).entity(form)
				.post(ClientResponse.class);
		Document thumbsDocument = thumbsWebServiceResponse
				.getEntity(Document.class);
		String thumbUri = extractThumbUriFromThumbRepresentation(thumbsDocument);
		logger.info(String
				.format("The thumbs ws service response for mdid %s was ok with uri %s ",
						metadataId, thumbUri));
		int status = thumbsWebServiceResponse.getStatus();
		if (Response.Status.OK.getStatusCode() == status) {
			// TODO
			updateThumbUriInMetadatas(ResourcesKeySyntax.removeSSL(metadataId),
					thumbUri);
			return Response.status(thumbsWebServiceResponse.getStatus())
					.type(thumbsWebServiceResponse.getType())
					.header("Access-Control-Allow-Origin", "*")
					.entity(thumbsDocument).build();
		} else {
			return Response.status(status)
					.type(thumbsWebServiceResponse.getType())
					.header("Access-Control-Allow-Origin", "*")
					.entity(thumbsDocument).build();
		}
	}

	@PUT
	@Path("/thumb")
	@Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_ATOM_XML,
			MediaType.TEXT_HTML, MediaType.APPLICATION_XHTML_XML })
	public Response setThumbForMetadata(
			@Context HttpServletRequest request,
			@QueryParam(value = "mdid") final String metadataId,
			@QueryParam(value = "format") final String format,
			@DefaultValue("false") @QueryParam(value = "auto") final String auto,
			@DefaultValue("") @QueryParam(value = "src") final String imageUrl,
			@DefaultValue("default") @QueryParam(value = "status") final String status)
			throws UnknownMetadataRepositoryException, UnknownMetadataException {
		return sendThumbPutRequest(request, metadataId, format, imageUrl,
				status, context, auto);

	}

	@GET
	@Path("/thumb")
	@Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_ATOM_XML,
			MediaType.TEXT_HTML, MediaType.APPLICATION_XHTML_XML })
	public Response setThumbForMetadata_HTMLUNIT_WORKAROUND(
			@Context HttpServletRequest request,
			@QueryParam(value = "mdid") final String metadataId,
			@QueryParam(value = "format") final String format,
			@DefaultValue("false") @QueryParam(value = "auto") final String auto,
			@DefaultValue("") @QueryParam(value = "src") final String imageUrl,
			@DefaultValue("default") @QueryParam(value = "status") final String status)
			throws UnknownMetadataRepositoryException, UnknownMetadataException {
		return sendThumbPutRequest(request, metadataId, format, imageUrl,
				status, context, auto);

	}

	private Response sendThumbPutRequest(HttpServletRequest request,
			String metadataId, String format, String imageUrl, String status,
			ServletContext context, String auto)
			throws UnknownMetadataRepositoryException, UnknownMetadataException {
		Boolean autoParam = StringUtils.equalsIgnoreCase(auto, "true");
		metadataId = ResourcesKeySyntax.removeSSL(metadataId);
		if (autoParam)
			if (StringUtils.isNotBlank(imageUrl))
				return Response
						.status(Status.BAD_REQUEST)
						.entity("If auto parameter is set to true, you cannot specify the image url, but you did : "
								+ imageUrl)
						.header("Access-Control-Allow-Origin", "*").build();
		if (!syncServiceInitialized)
			SyncService.notifyUriInfo(uriInfo.getBaseUri());
		if (!status.equals("default"))
			return Response
					.status(Status.BAD_REQUEST)
					.entity("This thumb status is not accepted at this time "
							+ status)
					.header("Access-Control-Allow-Origin", "*").build();
		if (!metadataId.startsWith(metadataWebServiceResource.getURI()
				.toString()))
			throw new UnknownMetadataRepositoryException(
					"This apiscol instance does handle iconification only for this metadata repository "
							+ metadataWebServiceResource.getURI().toString()
							+ "not for " + metadataId);

		String ifMatch = request.getHeader(HttpHeaders.IF_MATCH);
		MultivaluedMap<String, String> iconsQueryParams = new MultivaluedMapImpl();
		iconsQueryParams.add("mdid", metadataId);
		iconsQueryParams.add("src", imageUrl);
		iconsQueryParams.add("auto", autoParam ? "true" : "false");
		ClientResponse thumbsWebServiceResponse = thumbsWebServiceResource
				.queryParams(iconsQueryParams)
				.accept(MediaType.APPLICATION_XML_TYPE)
				.header(HttpHeaders.IF_MATCH, ifMatch)
				.put(ClientResponse.class);
		String thumbUri = "";
		Document thumbsDocument;
		if (thumbsWebServiceResponse.getStatus() != Status.OK.getStatusCode()) {
			String message = String
					.format("The thumbs ws service response for  mdid %s was not ok with message %s ",
							metadataId,
							thumbsWebServiceResponse.getEntity(String.class));
			logger.error(message);
			return Response.status(thumbsWebServiceResponse.getStatus())
					.entity(message).type(MediaType.TEXT_PLAIN)
					.header("Access-Control-Allow-Origin", "*").build();

		} else {
			thumbsDocument = thumbsWebServiceResponse.getEntity(Document.class);
			thumbUri = extractThumbUriFromThumbRepresentation(thumbsDocument);
			logger.info(String
					.format("The thumbs ws service response for mdid %s was ok with uri %s ",
							metadataId, thumbUri));
		}
		updateThumbUriInMetadatas(metadataId, thumbUri);
		return Response.status(thumbsWebServiceResponse.getStatus())
				.entity(thumbsDocument)
				.type(thumbsWebServiceResponse.getType())
				.header("Access-Control-Allow-Origin", "*").build();
	}

	private void updateThumbUriInMetadatas(String metadataId, String thumbUri)
			throws UnknownMetadataException {
		// TODO move to sync agent
		metadataId = removeWebServiceUri(metadataId);
		ClientResponse metaGetResponse = metadataWebServiceResource
				.path(metadataId).accept(MediaType.APPLICATION_XML)
				.get(ClientResponse.class);

		if (metaGetResponse.getStatus() != Status.OK.getStatusCode()) {
			String metaRepresentation = metaGetResponse.getEntity(String.class);
			String message = String
					.format("Error while trying to retrieve metadata %s (updating thumb uri) with message %s : abort",
							metadataId, metaRepresentation);
			logger.error(message);
			throw new UnknownMetadataException(message);

		}

		String metaEtag = metaGetResponse.getHeaders().getFirst(
				HttpHeaders.ETAG);
		MultivaluedMap<String, String> params = new MultivaluedMapImpl();
		if (StringUtils.isNotBlank(thumbUri))
			params.add("thumb", thumbUri);
		ClientResponse metaPutResponse = metadataWebServiceResource
				.path(metadataId).path("technical_infos")
				.accept(MediaType.APPLICATION_XML)
				.header(HttpHeaders.IF_MATCH, metaEtag)
				.put(ClientResponse.class, params);

		if (metaPutResponse.getStatus() != Status.OK.getStatusCode()) {
			String entity = metaPutResponse.getEntity(String.class);
			logger.warn(String
					.format("Failed to update technical informations for metadata %s with message %s : abort",
							metadataId, entity));
		}

	}

	@PUT
	@Path("/meta/{mdid}/hierarchy")
	@Produces({ MediaType.APPLICATION_ATOM_XML, MediaType.APPLICATION_XML })
	public Response setMetadataHierarchy(@Context HttpServletRequest request,
			@PathParam(value = "mdid") final String metadataId,
			@FormParam(value = "hierarchy") final String hierarchy) {

		if (StringUtils.isBlank(hierarchy))
			return Response.status(Response.Status.BAD_REQUEST)
					.header("Access-Control-Allow-Origin", "*")
					.entity("The hierarchy is empty").build();
		String ifMatch = request.getHeader(HttpHeaders.IF_MATCH);
		MultivaluedMap<String, String> params = new MultivaluedMapImpl();
		if (StringUtils.isNotBlank(metadataId))
			params.add("mdid", metadataId);
		if (StringUtils.isNotBlank(hierarchy))
			params.add("hierarchy", hierarchy);
		params.add("edit_uri", uriInfo.getBaseUri().toString());

		ClientResponse response = metadataWebServiceResource.path(metadataId)
				.path("hierarchy").accept(MediaType.APPLICATION_XML)
				.header(HttpHeaders.IF_MATCH, ifMatch)
				.put(ClientResponse.class, params);
		String etag = response.getHeaders().getFirst(HttpHeaders.ETAG);

		String entity = response.getEntity(String.class);
		return Response.status(response.getStatus()).entity(entity)
				.type(response.getType()).header(HttpHeaders.ETAG, etag)
				.header("Access-Control-Allow-Origin", "*").build();
	}
}
