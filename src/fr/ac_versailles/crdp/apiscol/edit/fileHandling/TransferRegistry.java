package fr.ac_versailles.crdp.apiscol.edit.fileHandling;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.Status;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.lang.StringUtils;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.multipart.FormDataMultiPart;

import fr.ac_versailles.crdp.apiscol.edit.ResourceEditionAPI;
import fr.ac_versailles.crdp.apiscol.edit.sync.SyncService;
import fr.ac_versailles.crdp.apiscol.restClient.LanWebResource;
import fr.ac_versailles.crdp.apiscol.utils.XMLUtils;

public class TransferRegistry {

	public enum TransferTypes {
		FILE, ARCHIVE;
	}

	public enum TransferStates {
		initiated("initiated"), aborted("aborted"), pending("pending"), unknown(
				"unknown"), done("done");
		private String value;

		private TransferStates(String value) {
			this.value = value;
		}

		@Override
		public String toString() {
			return value;
		}

	}

	private static Integer counter;
	private static Map<Integer, Thread> transfers = new ConcurrentHashMap<Integer, Thread>();
	private static Map<Integer, String> etags = new ConcurrentHashMap<Integer, String>();
	private static Map<Integer, String> fileNames = new ConcurrentHashMap<Integer, String>();
	private static Map<Integer, String> mainFileNames = new ConcurrentHashMap<Integer, String>();
	private static Map<Integer, Boolean> mainFiles = new ConcurrentHashMap<Integer, Boolean>();
	private static Map<Integer, Boolean> updateArchives = new ConcurrentHashMap<Integer, Boolean>();
	private static Map<Integer, TransferStates> transferStates = new ConcurrentHashMap<Integer, TransferStates>();
	private static Map<Integer, String> messages = new ConcurrentHashMap<Integer, String>();
	private static Map<Integer, String> resourcesIds = new ConcurrentHashMap<Integer, String>();
	private static Map<Integer, TransferTypes> transferTypes = new ConcurrentHashMap<Integer, TransferTypes>();
	private final String fileRepoPath;
	private final String temporaryFilesPrefix;
	private final LanWebResource contentWebServiceResource;

	public TransferRegistry(String fileRepoPath, String temporaryFilesPrefix,
			LanWebResource contentWebServiceResource) {
		this.fileRepoPath = fileRepoPath;
		this.temporaryFilesPrefix = temporaryFilesPrefix;
		this.contentWebServiceResource = contentWebServiceResource;
		counter = 0;
	}

	public int newTransfer(String resourceId, byte[] bs, String fileName,
			String mainFileName, boolean updateArchive, boolean mainFile,
			String eTag, TransferTypes payload,
			ResourceEditionAPI resourceEditionAPI) {
		// first the transfer worker will put the data on the file system
		synchronized (counter) {
			counter++;
			TransferWorker worker = new TransferWorker(counter, fileRepoPath,
					temporaryFilesPrefix, resourceId, bs, fileName, this);
			Thread thread = new Thread(worker);
			// concurrenthashmap does not allow null value
			transfers.put(counter, thread);
			updateArchives.put(counter, updateArchive);
			etags.put(counter, eTag == null ? StringUtils.EMPTY : eTag);
			fileNames.put(counter, fileName == null ? StringUtils.EMPTY
					: fileName);
			mainFileNames.put(counter, mainFileName == null ? StringUtils.EMPTY
					: mainFileName);
			mainFiles.put(counter, mainFile);
			transferStates.put(counter, TransferStates.initiated);
			messages.put(counter, String.format(
					"File %s is being copied on the file server", fileName));
			resourcesIds.put(counter, resourceId);
			transferTypes.put(counter, payload);
			thread.start();
			return counter;
		}
	}

	public void notifyThreadSuccessfullTermination(Integer identifier,
			String resourceId) {
		// no we post to the content web service
		transfers.remove(identifier);
		String updateArchive = updateArchives.get(identifier) == null ? "false"
				: updateArchives.get(identifier).toString();
		messages.put(identifier, String.format(
				"File %s is being registred and indexed",
				fileNames.get(identifier)));
		transferStates.put(identifier, TransferStates.pending);
		ClientResponse response = null;
		FormDataMultiPart form = null;
		try {
			form = new FormDataMultiPart().field("update_archive",
					updateArchive)
					.field("file_name", fileNames.get(identifier));

			switch (transferTypes.get(identifier)) {
			case FILE:
				form = form.field("is_archive", "false").field("main",
						mainFiles.get(identifier).toString());
				response = contentWebServiceResource.path("resource")
						.path(resourceId).accept(MediaType.APPLICATION_XML)
						.header(HttpHeaders.IF_MATCH, etags.get(identifier))
						.type(MediaType.MULTIPART_FORM_DATA).entity(form)
						.put(ClientResponse.class);
				break;
			case ARCHIVE:
				form = form.field("is_archive", "true");
				if (!StringUtils.isBlank(mainFileNames.get(identifier)))
					form = form.field("main_filename",
							mainFileNames.get(identifier));
				response = contentWebServiceResource.path("resource")
						.path(resourceId).accept(MediaType.APPLICATION_XML)
						.header(HttpHeaders.IF_MATCH, etags.get(identifier))
						.type(MediaType.MULTIPART_FORM_DATA).entity(form)
						.put(ClientResponse.class);
				break;
			}

		} finally {
			try {
				form.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		if (response.getStatus() == Status.OK.getStatusCode()) {
			messages.put(identifier, String.format(
					"File %s has been succesfully registred and indexed",
					fileNames.get(identifier)));
			transferStates.put(identifier, TransferStates.done);
			SyncService.forwardContentInformationToMetadata(resourceId);
		} else if (response.getStatus() == Status.CONFLICT.getStatusCode()) {
			messages.put(identifier, String.format(
					"A file named %s is already present for this resource",
					fileNames.get(identifier)));
			transferStates.put(identifier, TransferStates.aborted);
		} else {
			String message = response.getEntity(String.class);
			DocumentBuilder parser;
			try {
				parser = DocumentBuilderFactory.newInstance()
						.newDocumentBuilder();
				Document document = parser.parse(new InputSource(
						new StringReader(message)));
				message = document.getElementsByTagName("message").item(0)
						.getTextContent();
			} catch (ParserConfigurationException e) {

				e.printStackTrace();
			} catch (SAXException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
			// TODO détailler plus par code d'erreur.
			messages.put(identifier, message);
			transferStates.put(identifier, TransferStates.aborted);

		}

	}

	public void notifyFileEverTransmitted(Integer identifier,
			String resourceId, String message) {
		transfers.remove(identifier);
		transferStates.put(identifier, TransferStates.aborted);
		messages.put(identifier, message);
	}

	public void notifyProblemOnFileSystem(Integer identifier,
			String resourceId, String message) {
		transfers.remove(identifier);
		transferStates.put(identifier, TransferStates.aborted);
		messages.put(identifier, message);
	}

	public TransferStates getTransferState(Integer fileTransferIdentifier) {
		if (!transferStates.containsKey(fileTransferIdentifier))
			return TransferStates.unknown;
		return transferStates.get(fileTransferIdentifier);
	}

	public String getMessage(Integer transferIdentifier) {
		if (!messages.containsKey(transferIdentifier))
			return "This transfer operation is missing from system memory.";
		return messages.get(transferIdentifier);
	}

	public String getResourceIdForTransfer(Integer fileTransferIdentifier) {
		if (!resourcesIds.containsKey(fileTransferIdentifier))
			return null;
		return resourcesIds.get(fileTransferIdentifier);
	}

}
