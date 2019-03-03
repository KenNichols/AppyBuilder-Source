// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2016-2020 AppyBuilder.com, All Rights Reserved - Info@AppyBuilder.com
// https://www.gnu.org/licenses/gpl-3.0.en.html

// Copyright 2009-2011 Google, All Rights reserved
// Copyright 2011-2012 MIT, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0

package com.google.appinventor.server;

import com.google.appinventor.common.utils.StringUtils;
import com.google.appinventor.server.flags.Flag;
import com.google.appinventor.server.project.youngandroid.YoungAndroidProjectService;
import com.google.appinventor.server.storage.StorageIo;
import com.google.appinventor.server.storage.StorageIoInstanceHolder;
import com.google.appinventor.shared.rpc.UploadResponse;
import com.google.appinventor.shared.rpc.project.Project;
import com.google.appinventor.shared.rpc.project.RawFile;
import com.google.appinventor.shared.rpc.project.TextFile;
import com.google.appinventor.shared.rpc.project.UserProject;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidProjectNode;
import com.google.appinventor.shared.storage.StorageUtil;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteStreams;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

import javax.annotation.Nullable;

/**
 * Implementation of {@link FileImporter} based on {@link StorageIo}
 *
 * @author lizlooney@google.com (Liz Looney)
 */
public final class FileImporterImpl implements FileImporter {

  // Maximum size of an uploaded asset, in megabytes.
  private static final Flag<Float> maxAssetSizeMegs = Flag.createFlag("max.asset.size.megs", 9f);

  private static final Logger LOG = Logger.getLogger(FileImporterImpl.class.getName());

  private final StorageIo storageIo = StorageIoInstanceHolder.INSTANCE;

  @Override
  public UserProject importProject(String userId, String projectName,
      InputStream uploadedFileStream) throws FileImporterException, IOException {
    return importProject(userId, projectName, uploadedFileStream, null);
  }

  @Override
  public UserProject importProject(String userId, String projectName,
                                   InputStream uploadedFileStream, @Nullable String projectHistory)
      throws FileImporterException, IOException {
    // The projectName parameter has already been validated, including checking for an
    // existing project with the same name. (See TextValidators.checkNewProjectName).

    // Begin creating the project.
    Project project = new Project(projectName);
    project.setProjectType(YoungAndroidProjectNode.YOUNG_ANDROID_PROJECT_TYPE);

    // As we process the ZipEntry for each file, we'll adjust the directory structure so that it is
    // appropriate for this user.
    // Here we get the information (such as the qualified form name) that we'll need to do that.
    String qualifiedFormName = StringUtils.getQualifiedFormName(
        storageIo.getUser(userId).getUserEmail(), projectName);
    String srcDirectory = YoungAndroidProjectService.getSourceDirectory(qualifiedFormName);

    ZipInputStream zin = new ZipInputStream(uploadedFileStream);
    boolean isProjectArchive = false;  // have we found at least one project properties file?
    try {
      // Extract files
      while (true) {
        ZipEntry entry;
        try {
          entry = zin.getNextEntry();
          if (entry == null) {
            break;
          }
        } catch (ZipException e) {
          // The uploaded file is not a valid zip file
          LOG.log(Level.SEVERE, "Invalid Project Archive Format", e);
          throw new FileImporterException(UploadResponse.Status.NOT_PROJECT_ARCHIVE);
        }

        if (!entry.isDirectory()) {
          String fileName = entry.getName();

          if (fileName.equals(YoungAndroidProjectService.PROJECT_PROPERTIES_FILE_NAME)) {
            // The content for the youngandroidproject/project.properties file must be regenerated
            // so that it contains the correct entries for "main" and "name", which are dependent on
            // the projectName and qualifiedFormName.
            String content = YoungAndroidProjectService.getProjectPropertiesFileContents(
              projectName, qualifiedFormName, null, null, null, null, null, null, null, null, null, null, null, null);
            project.addTextFile(new TextFile(fileName, content));
            isProjectArchive = true;

          } else if (fileName.equals(FileExporter.REMIX_INFORMATION_FILE_PATH) ||
              fileName.equals(StorageUtil.ANDROID_KEYSTORE_FILENAME)) {
            // If the remix information file is present, we ignore it. In the past, a remix
            // information file was saved in the zip when project source was downloaded and
            // retrieved from the zip when it was uploaded. However, we no longer do that because
            // we don't have a way to verify that the contents of the remix information file is
            // accurate during the upload.
            // If a keystore file is present we ignore that too for now, since
            // we don't have per-project keystores. The only way to get such a
            // source zip at the moment is using the admin functionality to
            // download another user's project source.
            continue;

          } else {

            if (fileName.startsWith(YoungAndroidProjectService.SRC_FOLDER)) {
              // For files within the src folder, we need to update the directory that we put files
              // in. Adjust the fileName so that it corresponds to this project's package.
              fileName = srcDirectory + '/' + StorageUtil.basename(fileName);
            }

            // Get the file content from the ZipEntry.
            ByteArrayOutputStream contentStream = new ByteArrayOutputStream();
            ByteStreams.copy(zin, contentStream);

            project.addRawFile(new RawFile(fileName, contentStream.toByteArray()));
          }
        }
      }
    } finally {
      zin.close();
    }

    if (!isProjectArchive) {
      // The uploaded file seems to be a valid zip file, but it doesn't contain the project
      // properties file.
      throw new FileImporterException(UploadResponse.Status.NOT_PROJECT_ARCHIVE);
    }

    // Set project history if provided
    if (projectHistory != null) {
      project.setProjectHistory(projectHistory);
    }
    String settings = YoungAndroidProjectService.getProjectSettings(null, null, null, null, null, null, null, null, null, null, null, null);
    long projectId = storageIo.createProject(userId, project, settings);
    return storageIo.getUserProject(userId, projectId);
  }

  @Override
  public UserProject importProjectScreen(String userId, long projectId, String projectName,
                                         InputStream uploadedFileStream) throws FileImporterException, IOException {
    // As we process the ZipEntry for each file, we'll adjust the directory structure so that it is
    // appropriate for this user.
    // Here we get the information (such as the qualified form name) that we'll need to do that.
    String qualifiedFormName = StringUtils.getQualifiedFormName(
            storageIo.getUser(userId).getUserEmail(), projectName);
    String srcDirectory = YoungAndroidProjectService.getSourceDirectory(qualifiedFormName);
    // LOG.info("0. srcDirectory before is: " + srcDirectory);

    // Get rid of sub-package
    String foo[] = srcDirectory.split("/");
    srcDirectory="";
    for (int i=0; i<foo.length - 1; i++) {
      srcDirectory = srcDirectory + foo[i] + "/";
    }
    srcDirectory = srcDirectory.substring(0, srcDirectory.length() - 1);

    // LOG.info("0. srcDirectory is: " + srcDirectory+"\r\n"+"0. qualifiedFormName is: " + qualifiedFormName);

    ZipInputStream zin = new ZipInputStream(uploadedFileStream);
    String randomString = generateRandomLetterOrNum();
    try {
      // Extract files
      while (true) {
        ZipEntry entry;
        try {
          entry = zin.getNextEntry();
          if (entry == null) {
            break;
          }
        } catch (ZipException e) {
          // The uploaded file is not a valid zip file
          LOG.log(Level.SEVERE, "Invalid Project Archive Format", e);
          throw new FileImporterException(UploadResponse.Status.NOT_PROJECT_ARCHIVE);
        }
        if (!entry.isDirectory()) {
          String fileName = entry.getName();
          if (fileName.startsWith(YoungAndroidProjectService.SRC_FOLDER)) {
            // For files within the src folder, we need to update the directory that we put files
            // in. Adjust the fileName so that it corresponds to this project's package.

            // LOG.info("1. fileName is: " + fileName);

            fileName = StorageUtil.basename(fileName);
            String fileNameOnly = fileName.split("\\.")[0];
            String newFileNameOnly = "Screen" + randomString;
            if (fileName.endsWith(".scm")) {
              fileName = newFileNameOnly + ".scm";
            } else if (fileName.endsWith(".bky")) {
              fileName = newFileNameOnly + ".bky";
            } else if (fileName.endsWith(".yail")) {
              fileName = newFileNameOnly + ".yail";
            }
            // LOG.info("2. fileName is: " + fileName);
String myFileName = fileName;
            // Get the file content from the ZipEntry.
            ByteArrayOutputStream contentStream = new ByteArrayOutputStream();
             fileName = srcDirectory + '/' + StorageUtil.basename(fileName); // from punya
//            fileName = srcDirectory + '/' + fileName;                       // my version
            // LOG.info("3. fileName is: " + fileName);

            ByteStreams.copy(zin, contentStream);
            // LOG.info("4. copied zin to contentStream ");

            String content = contentStream.toString().replace(fileNameOnly, newFileNameOnly);
            // use these ONLY for debug or there will be huge cost if uploaded to prod
//            LOG.info("The file name only is " + fileNameOnly);
//            LOG.info("The form name is " + fileName);
//            LOG.info("The content string is " + content);
            storageIo.addSourceFilesToProject(userId, projectId, false, fileName);
            storageIo.uploadFileForce(projectId, fileName, userId, content, StorageUtil.DEFAULT_CHARSET);
          }
        }
      }
    } finally {
      zin.close();
    }
    return storageIo.getUserProject(userId, projectId);
  }


  public UserProject myVersion_importProjectScreen(String userId, long projectId, String projectName,
                                         InputStream uploadedFileStream) throws FileImporterException, IOException {
    // As we process the ZipEntry for each file, we'll adjust the directory structure so that it is
    // appropriate for this user.
    // Here we get the information (such as the qualified form name) that we'll need to do that.
    String qualifiedFormName = StringUtils.getQualifiedFormName(
            storageIo.getUser(userId).getUserEmail(), projectName);
    String srcDirectory = YoungAndroidProjectService.getSourceDirectory(qualifiedFormName);
                LOG.info("xxxxxxxxxxxx the source dir is " + srcDirectory);

    ZipInputStream zin = new ZipInputStream(uploadedFileStream);
    String randomString = generateRandomLetterOrNum();
    try {
      // Extract files
      while (true) {
        ZipEntry entry;
        try {
          entry = zin.getNextEntry();
          if (entry == null) {
            break;
          }
        } catch (ZipException e) {
          // The uploaded file is not a valid zip file
          LOG.log(Level.SEVERE, "Invalid Project Archive Format", e);
          throw new FileImporterException(UploadResponse.Status.NOT_PROJECT_ARCHIVE);
        }

        if (entry.isDirectory())
          continue;

        String entryName = entry.getName();
        if (! (entryName.endsWith(".scm") || entryName.endsWith(".bky") || entryName.endsWith(".yai")) )
          continue;

        // now, we are dealing with actual source files
//        String onlyFileName = entryName.split("\\.")[0];
        String extension = entryName.split("\\.")[1];
        String newFileName = randomString + "." + extension;

//        if (!entry.isDirectory()) {
          String fileName = entry.getName();
          if (fileName.startsWith(YoungAndroidProjectService.SRC_FOLDER)) {
            // For files within the src folder, we need to update the directory that we put files
            // in. Adjust the fileName so that it corresponds to this project's package.
            fileName = StorageUtil.basename(fileName);
            String fileNameOnly = fileName.split("\\.")[0];
            LOG.info("xxxx fileNameOnly is " + fileNameOnly);

            String newFileNameOnly = "Screen" + randomString;
            if (fileName.endsWith(".scm")) {
              fileName = newFileNameOnly + ".scm";
            } else if (fileName.endsWith(".bky")) {
              fileName = newFileNameOnly + ".bky";
            } else if (fileName.endsWith(".yail")) {
              fileName = newFileNameOnly + ".yail";
            }
            // Get the file content from the ZipEntry.
            ByteArrayOutputStream contentStream = new ByteArrayOutputStream();
            fileName = srcDirectory + '/' + StorageUtil.basename(fileName);

//            fileName = StorageUtil.basename(fileName);   not working
            ByteStreams.copy(zin, contentStream); // copy all bytes from inputstream to output stream
            String content = contentStream.toString().replace(fileNameOnly, newFileNameOnly);
            // use these ONLY for debug or there will be huge cost if uploaded to prod
            LOG.info("The file name only is " + fileNameOnly);
            LOG.info("The form name is " + fileName);
//            LOG.info("The content string is " + content);
            storageIo.addSourceFilesToProject(userId, projectId, false, fileName);
            storageIo.uploadFileForce(projectId, fileName, userId, content, StorageUtil.DEFAULT_CHARSET);
          }
//        }
      }
    } finally {
      zin.close();
    }
    return storageIo.getUserProject(userId, projectId);
  }
  // 6 random letters &/ numbers
  public String generateRandomLetterOrNum() {
    String val = "";
    // char or numbers (5), random 0-9 A-Z
    for(int i = 0; i<6;){
      int ranAny = 48 + (new Random()).nextInt(90-65);
      if(!(57 < ranAny && ranAny<= 65)){
        char c = (char)ranAny;
        val += c;
        i++;
      }
    }
    return val;
  }
  @VisibleForTesting
  public long importFile(String userId, long projectId, String fileName,
      InputStream uploadedFileStream) throws FileImporterException, IOException {
    int maxAssetSizeBytes = (int) (maxAssetSizeMegs.get() * 1024 * 1024);
    int maxSizeBytes = Math.min(maxAssetSizeBytes, storageIo.getMaxJobSizeBytes());

    BufferedInputStream bis = new BufferedInputStream(uploadedFileStream);
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    BufferedOutputStream bos = new BufferedOutputStream(os);

    // Alledgedly since it is buffered, reading in units of one byte
    // should be as fast as many bytes, but we can always adjust this.
    int bytes = 0;
    long fileLength = 0;
    byte[] buffer = new byte[1];
    while ((bytes = bis.read(buffer, 0, buffer.length)) != -1) {
      bos.write(buffer, 0, bytes);
      fileLength += bytes;
    }
    bos.flush();

    // First check the file length, to avoid loading the file into memory if it is too large anyhow.
    if (fileLength > maxSizeBytes) {
      throw new FileImporterException(UploadResponse.Status.FILE_TOO_LARGE);
    }

    byte[] content = os.toByteArray();

    // If the file already exists, we will overwrite the content.
    List<String> sourceFiles = storageIo.getProjectSourceFiles(userId, projectId);
    if (!sourceFiles.contains(fileName)) {
      storageIo.addSourceFilesToProject(userId, projectId, false, fileName);
    }
    return storageIo.uploadRawFileForce(projectId, fileName, userId, content);
  }

  @Override
  public void importUserFile(String userId, String fileName, InputStream uploadedFileStream)
      throws IOException {
    byte[] content = ByteStreams.toByteArray(uploadedFileStream);

    // If the file already exists, we will overwrite the content.
    List<String> userFiles = storageIo.getUserFiles(userId);
    if (!userFiles.contains(fileName)) {
      storageIo.addFilesToUser(userId, fileName);
    }
    storageIo.uploadRawUserFile(userId, fileName, content);
  }

  @Override
  public String importTempFile(InputStream inStream) throws IOException {
    return storageIo.uploadTempFile(ByteStreams.toByteArray(inStream));
  }

  @Override
  public Set<String> getProjectNames(final String userId) {
    List<Long> projectIds = storageIo.getProjects(userId);
    Iterable<String> names = Iterables.transform(projectIds, new Function<Long, String>() {
      @Override
      public String apply(Long projectId) {
        return storageIo.getProjectName(userId, projectId);
      }
    });
    return ImmutableSet.copyOf(names);
  }
}