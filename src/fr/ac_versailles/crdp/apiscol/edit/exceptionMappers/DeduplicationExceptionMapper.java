package fr.ac_versailles.crdp.apiscol.edit.exceptionMappers;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import fr.ac_versailles.crdp.apiscol.edit.DeduplicationException;

@Provider
public class DeduplicationExceptionMapper implements
		ExceptionMapper<DeduplicationException> {
	@Override
	public Response toResponse(DeduplicationException e) {
		return Response.status(Status.PRECONDITION_FAILED)
				.type(MediaType.APPLICATION_XML).entity(e.getXMLMessage())
				.build();
	}
}