package fr.ac_versailles.crdp.apiscol.edit.fileHandling;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import fr.ac_versailles.crdp.apiscol.edit.ResourceEditionAPI;

public class TransferWorker implements Runnable {

	private final String resourceId;
	private final String fileRepoPath;
	private final String temporaryFilesPrefix;
	private final byte[] fileData;
	private final String fileName;
	private final TransferRegistry caller;
	private final Integer identifier;

	public TransferWorker(Integer counter, String fileRepoPath,
			String temporaryFilesPrefix, String resourceId, byte[] bs,
			String fileName, TransferRegistry fileAdditionRegistry) {
		this.identifier = counter;
		this.fileRepoPath = fileRepoPath;
		this.temporaryFilesPrefix = temporaryFilesPrefix;
		this.resourceId = resourceId;
		this.fileData = bs;
		this.fileName = fileName;
		this.caller = fileAdditionRegistry;
	}

	@Override
	public void run() {
		boolean success = transferFile(resourceId);
		if (success)
			caller.notifyThreadSuccessfullTermination(identifier, resourceId);
	}

	private boolean transferFile(String resourceId) {
		File resourceDirectory = ResourceEditionAPI.getResourceDirectory(
				fileRepoPath, resourceId.toString());
		File out = new File(String.format("%s/%s%s",
				resourceDirectory.getAbsolutePath(), temporaryFilesPrefix,
				fileName));
		if (out.exists()) {
			caller.notifyFileEverTransmitted(
					identifier,
					resourceId,
					String.format(
							"A file of the same name(%s) for the same resource is currently waiting on the file server",
							fileName));
			return false;
		}
		FileOutputStream streamOut = null;
		try {
			streamOut = new FileOutputStream(out);
			streamOut.write(fileData);
			streamOut.close();
			out.setReadable(true, false);
			out.setWritable(true, false);
		} catch (FileNotFoundException e) {
			caller.notifyProblemOnFileSystem(identifier, resourceId,
					"We had a problem creating the file to the file server : file not found :"
							+ out.getAbsolutePath());
			return false;
		} catch (IOException e) {
			caller.notifyProblemOnFileSystem(identifier, resourceId,
					"We had a problem writing the file to the file server");
			return false;
		}
		return true;
	}
}
