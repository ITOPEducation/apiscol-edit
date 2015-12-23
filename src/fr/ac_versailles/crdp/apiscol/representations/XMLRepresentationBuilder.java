package fr.ac_versailles.crdp.apiscol.representations;

import java.net.URI;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.lang.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import fr.ac_versailles.crdp.apiscol.UsedNamespaces;
import fr.ac_versailles.crdp.apiscol.edit.fileHandling.TransferRegistry;
import fr.ac_versailles.crdp.apiscol.edit.fileHandling.TransferRegistry.TransferStates;
import fr.ac_versailles.crdp.apiscol.edit.fileHandling.UrlParsingStates;
import fr.ac_versailles.crdp.apiscol.edit.urlHandling.UrlParsingRegistry;
import fr.ac_versailles.crdp.apiscol.utils.XMLUtils;

public class XMLRepresentationBuilder extends AbstractRepresentationBuilder {

	private static final String ATOM = "http://www.w3.org/2005/Atom";

	@Override
	public Object getFileTransferRepresentation(Integer fileTransferIdentifier,
			URI baseUri, TransferRegistry fileTransferRegistry,
			URI contentWebServiceUri) {
		Document report = createXMLDocument();
		Element rootElement = report.createElement("apiscol:status");
		Element stateElement = report.createElement("apiscol:state");
		TransferStates transferState = fileTransferRegistry
				.getTransferState(fileTransferIdentifier);
		stateElement.setTextContent(transferState.toString());
		Element linkElement = report.createElement("link");
		linkElement.setAttribute(
				"href",
				getUrlForFileTransfer(UriBuilder.fromUri(baseUri),
						fileTransferIdentifier).toString());
		linkElement.setAttribute("rel", "self");
		linkElement.setAttribute("type", "application/atom+xml");
		Element messageElement = report.createElement("apiscol:message");
		messageElement.setTextContent(fileTransferRegistry
				.getMessage(fileTransferIdentifier));
		rootElement.appendChild(stateElement);
		rootElement.appendChild(linkElement);
		rootElement.appendChild(messageElement);
		Element resourceLinkElement = report.createElement("link");
		String resourceId = fileTransferRegistry
				.getResourceIdForTransfer(fileTransferIdentifier);
		resourceLinkElement.setAttribute("href", String.format("%s/%s",
				contentWebServiceUri.toString(), resourceId));
		resourceLinkElement.setAttribute("rel", "item");
		resourceLinkElement.setAttribute("type", "application/atom+xml");
		rootElement.appendChild(resourceLinkElement);
		report.appendChild(rootElement);
		XMLUtils.addNameSpaces(report, UsedNamespaces.ATOM);
		return report;
	}

	private static Document createXMLDocument() {
		DocumentBuilderFactory docFactory = DocumentBuilderFactory
				.newInstance();
		docFactory.setNamespaceAware(true);
		DocumentBuilder docBuilder = null;
		try {
			docBuilder = docFactory.newDocumentBuilder();
		} catch (ParserConfigurationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		Document doc = docBuilder.newDocument();
		return doc;
	}

	@Override
	public MediaType getMediaType() {
		return MediaType.APPLICATION_XML_TYPE;
	}

	@Override
	public Object getUrlParsingRespresentation(Integer urlParsingId,
			URI baseUri, UrlParsingRegistry urlParsingRegistry,
			URI contentWebServiceUri) {
		Document report = createXMLDocument();
		Element rootElement = report.createElement("apiscol:status");
		Element stateElement = report.createElement("apiscol:state");
		UrlParsingStates parsingState = urlParsingRegistry
				.getTransferState(urlParsingId);
		stateElement.setTextContent(parsingState.toString());
		Element linkElement = report.createElement("link");
		linkElement.setAttribute("href",
				getUrlForUrlParsing(UriBuilder.fromUri(baseUri), urlParsingId)
						.toString());
		linkElement.setAttribute("rel", "self");
		linkElement.setAttribute("type", "application/atom+xml");

		Element messageElement = report.createElement("apiscol:message");
		messageElement.setTextContent(urlParsingRegistry
				.getMessage(urlParsingId));
		rootElement.appendChild(stateElement);
		rootElement.appendChild(linkElement);
		rootElement.appendChild(messageElement);
		String resourceId = urlParsingRegistry
				.getResourceIdForParsing(urlParsingId);
		if (!StringUtils.isBlank(resourceId)) {
			Element resourceLinkElement = report.createElement("link");

			resourceLinkElement.setAttribute("href", String.format("%s/%s",
					contentWebServiceUri.toString(), resourceId));
			resourceLinkElement.setAttribute("rel", "item");
			resourceLinkElement.setAttribute("type", "application/atom+xml");
			rootElement.appendChild(resourceLinkElement);
		}
		report.appendChild(rootElement);
		XMLUtils.addNameSpaces(report, UsedNamespaces.ATOM);
		return report;
	}

}
