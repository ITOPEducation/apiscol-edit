package fr.ac_versailles.crdp.apiscol.edit;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import com.sun.jersey.core.header.FormDataContentDisposition;
import com.sun.jersey.multipart.FormDataParam;

@Path("/import")
public class ImportAPI {

	@Context
	UriInfo uriInfo;

	@POST
	@Produces({ MediaType.APPLICATION_ATOM_XML, MediaType.APPLICATION_XML })
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	public Response createPackageImport(@Context HttpServletRequest request,
			@Context ServletContext context,
			@FormDataParam("package") InputStream uploadedInputStream,
			@FormDataParam("package") FormDataContentDisposition fileDetail) {
		File tempFile = null;
		try {
			tempFile = File.createTempFile("archive", "");
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		writeStreamToFile(uploadedInputStream, tempFile);
		return Response.ok().build();
	}

	private static void writeStreamToFile(InputStream uploadedInputStream,
			File file) {
		file.getParentFile().mkdirs();
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

	}

}
