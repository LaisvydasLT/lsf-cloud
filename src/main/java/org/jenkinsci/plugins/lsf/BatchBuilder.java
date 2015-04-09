/*
 * The MIT License
 *
 * Copyright 2015 Laisvydas Skurevicius.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.lsf;

import com.michelin.cio.hudson.plugins.copytoslave.CopyToMasterNotifier;
import com.michelin.cio.hudson.plugins.copytoslave.CopyToSlaveBuildWrapper;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tasks.Shell;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletException;
import jenkins.model.Jenkins;
import jenkins.util.BuildListenerAdapter;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.FileUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

/**
 *
 * @author Laisvydas Skurevicius
 */
public class BatchBuilder extends Builder {

    // the batch job script
    private String job;
    // the files that need to be downloaded after job completion
    private String filesToDownload = "";
    // the destination path to which the files will be downloaded
    private String downloadDestination;
    // the files that need to be sent before executing the job
    private String filesToSend = "";
    // how often the status of the job should be checked
    private int checkFrequencyMinutes = 1;
    // names of the files that have been uploaded (separated by commas)
    private String uploadedFiles = getUploadedFiles();
    // configuration for checking if email should be sent
    private boolean sendEmail = false;
    // file name for the communication between master and slave
    private static final String COMMUNICATION_FILE = "output";
    // name of the file where the running job output is saved
    private static final String PROGRESS_FILE = "jobProgress";
    private String masterWorkingDirectory;
    private String slaveWorkingDirectory;

    /**
     * @param job
     * @param filesToDownload
     * @param downloadDestination
     * @param filesToSend
     * @param checkFrequencyMinutes
     * @param sendEmail
     */
    @DataBoundConstructor
    public BatchBuilder(String job, String filesToDownload,
            String downloadDestination, String filesToSend,
            int checkFrequencyMinutes, boolean sendEmail) {
        this.job = job;
        this.filesToDownload = filesToDownload;
        this.downloadDestination = downloadDestination;
        this.filesToSend = filesToSend;
        this.checkFrequencyMinutes = checkFrequencyMinutes;
        this.uploadedFiles = getUploadedFiles();
        this.sendEmail = sendEmail;
    }

    public String getJob() {
        return job;
    }

    public String getFilesToDownload() {
        return filesToDownload;
    }

    public int getCheckFrequencyMinutes() {
        return checkFrequencyMinutes;
    }

    public String getFilesToSend() {
        return filesToSend;
    }

    public String getDownloadDestination() {
        return downloadDestination;
    }

    public boolean getSendEmail() {
        return sendEmail;
    }

    /**
     * This is where the interaction between Jenkins and LSF happens.
     *
     * @param build
     * @param launcher
     * @param listener
     * @return
     * @throws InterruptedException
     * @throws IOException
     */
    @Override
    public boolean perform(AbstractBuild<?, ?> build,
            Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {
        masterWorkingDirectory = Jenkins.getInstance().root.getAbsolutePath()
                + "/userContent/" + build.getProject().getName() + "/";
        BatchSystem batchSystem = new LSF(build, launcher,
                listener, COMMUNICATION_FILE, masterWorkingDirectory);
        CopyToMasterNotifier copyFileToMaster
                = new CopyToMasterNotifier(COMMUNICATION_FILE, "",
                        true, masterWorkingDirectory, true);
        String jobStatus = "";
        // randomly generated job script name
        String jobFileName = "JOB-" + UUID.randomUUID().toString();
        // a fake listener for hiding output of some 
        // commands to make the console easier to read
        BuildListenerAdapter fakeListener
                = new BuildListenerAdapter(TaskListener.NULL);
        // gets the queue type from the cloud
        String queueType = getQueueType(build);
        // stores the current working directory of the slave
        slaveWorkingDirectory
                = getSlaveWorkingDirectory(build, launcher, fakeListener);
        // sends the selected files to the slave 
        // and prepares the commands to send files to LSF
        String sendFilesShellCommands = sendFiles(build, launcher, listener);
        sendJobToSlave(build, launcher, listener, sendFilesShellCommands,
                jobFileName);
        // sets the correct permission of the file for execution
        setPermissionOnJobFile(build, launcher, listener, jobFileName);
        String jobId = batchSystem.submitJob(jobFileName, sendEmail, queueType);
        try {
            // command for counting lines in the result file 
            //(for tracking of job progress)
            Shell countNumberOfLines
                    = new Shell("#!/bin/bash +x\n wc -l "
                            + PROGRESS_FILE + " > " + COMMUNICATION_FILE);
            // used for output progress tracking 
            // (specifies how many lines to skip when printing job output file)
            int offset = 0;
            // loops for checking the job's status and progress until 
            // it reaches an ending state
            while (!batchSystem.isEndStatus(jobStatus)) {
                Thread.sleep(checkFrequencyMinutes * 60000);
                jobStatus = batchSystem.getJobStatus(jobId);
                listener.getLogger().println("JOB STATUS: " + jobStatus);
                batchSystem.processStatus(jobStatus);
                if (!batchSystem.isRunningStatus(jobStatus)) {
                    continue;
                }
                batchSystem.createJobProgressFile(jobId, PROGRESS_FILE);
                countNumberOfLines.perform(build, launcher, fakeListener);
                copyFileToMaster.perform(build, launcher, fakeListener);
                BufferedReader fileReader = new BufferedReader(
                        new FileReader(masterWorkingDirectory
                                + COMMUNICATION_FILE));
                String first_word = fileReader.readLine();
                // checks if command didn't fail and the result file exists
                if (first_word == null) {
                    continue;
                }
                first_word = first_word.split(" ")[0];
                if (first_word.equals("wc:")) {
                    continue;
                }
                int numberOfLines = Integer.parseInt(first_word);
                batchSystem.createFormattedRunningJobOutputFile(
                        PROGRESS_FILE, offset, numberOfLines);
                copyFileToMaster.perform(build, launcher, fakeListener);
                String output = FileUtils.readFileToString(
                        new File(masterWorkingDirectory + COMMUNICATION_FILE));
                if (!output.isEmpty()) {
                    printJobOutput(listener, output);
                }
                if (offset < numberOfLines) {
                    offset = numberOfLines;
                }
            }
            batchSystem.createFinishedJobOutputFile(jobId, offset);
            copyFileToMaster.perform(build, launcher, fakeListener);
            String output = FileUtils.readFileToString(
                    new File(masterWorkingDirectory + COMMUNICATION_FILE));
            printJobOutput(listener, output);
            downloadFiles(build, launcher, listener);
        } catch (InterruptedException e) {
            batchSystem.killJob(jobId);
            jobStatus = "ABORTED";
        } finally {
            if (batchSystem.jobExitedWithErrors(jobStatus)) {
                listener.getLogger().println();
                batchSystem.printErrorLog();
                batchSystem.printExitCode(jobId);
            }
            batchSystem.cleanUpFiles(jobId);
            cleanUpFiles(build, launcher, fakeListener, jobFileName, jobId);
        }
        return batchSystem.jobCompletedSuccessfully(jobStatus);
    }

    /**
     * prints the given output to console
     *
     * @param listener
     * @param output
     */
    protected void printJobOutput(BuildListener listener, String output) {
        listener.getLogger().println("------------------------------------"
                + "---------------JOB OUTPUT START------------------------"
                + "---------------------------");
        listener.getLogger().println();
        listener.getLogger().println(output);
        listener.getLogger().println("------------------------------------"
                + "---------------JOB OUTPUT END--------------------------"
                + "---------------------------");
    }

    /**
     * @param build
     * @return queue type from the cloud
     */
    protected String getQueueType(AbstractBuild<?, ?> build) {
        // finds the queue type by searching through the clouds 
        // with the associated label
        for (Cloud cloud : Jenkins.getInstance().clouds) {
            if (cloud instanceof BatchCloud && cloud.canProvision(
                    build.getProject().getAssignedLabel())) {
                return ((BatchCloud) cloud).getQueueType();
            }
        }
        return null;
    }

    /**
     * @param build
     * @param launcher
     * @param listener
     * @return current working directory in the slave machine
     * @throws InterruptedException
     * @throws IOException
     */
    protected String getSlaveWorkingDirectory(AbstractBuild<?, ?> build,
            Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {
        Shell shell = new Shell("pwd > " + COMMUNICATION_FILE);
        shell.perform(build, launcher, listener);
        CopyToMasterNotifier copyFileToMaster
                = new CopyToMasterNotifier(COMMUNICATION_FILE, "", true,
                        masterWorkingDirectory, true);
        copyFileToMaster.perform(build, launcher, listener);
        BufferedReader br = new BufferedReader(
                new FileReader(masterWorkingDirectory + COMMUNICATION_FILE));
        return br.readLine();
    }

    /**
     * sends the selected files to the slave machine
     *
     * @param build
     * @param launcher
     * @param listener
     * @return shell commands for sending files to batch system
     * @throws IOException
     * @throws InterruptedException
     */
    protected String sendFiles(AbstractBuild<?, ?> build, Launcher launcher,
            BuildListener listener)
            throws IOException, InterruptedException {
        String sendFilesShellCommands = "";
        String filesWithoutPaths = "";
        if (!filesToSend.isEmpty()) {
            for (String file : filesToSend.split(",")) {
                File fileToSend = new File(file.trim());
                sendFilesShellCommands = sendFilesShellCommands + "cp \""
                        + slaveWorkingDirectory + "/"
                        + fileToSend.getName() + "\" .\n";
                Files.copy(fileToSend.toPath(),
                        new File(masterWorkingDirectory
                                + fileToSend.getName()).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                filesWithoutPaths = build.getProject().getName() + "/"
                        + fileToSend.getName() + ","
                        + filesWithoutPaths;
            }
        }
        if (!uploadedFiles.isEmpty()) {
            for (String file : uploadedFiles.split(",")) {
                sendFilesShellCommands = sendFilesShellCommands + "cp \""
                        + slaveWorkingDirectory + "/" + file + "\" .\n";
                filesWithoutPaths = build.getProject().getName() + "/" + file
                        + "," + filesWithoutPaths;
            }
        }
        if (!filesToSend.isEmpty() || !uploadedFiles.isEmpty()) {
            CopyToSlaveBuildWrapper copyToSlave
                    = new CopyToSlaveBuildWrapper(filesWithoutPaths,
                            "", true, false,
                            CopyToSlaveBuildWrapper.RELATIVE_TO_HOME,
                            false);
            copyToSlave.setUp(build, launcher, listener);
        }
        return sendFilesShellCommands;
    }

    /**
     * downloads the selected files from slave to master
     *
     * @param build
     * @param launcher
     * @param listener
     * @throws InterruptedException
     * @throws IOException
     */
    protected void downloadFiles(AbstractBuild<?, ?> build, Launcher launcher,
            BuildListener listener) throws InterruptedException, IOException {
        if (!filesToDownload.isEmpty()) {
            listener.getLogger().println();
            listener.getLogger().println("Downloading the selected files:");
            boolean is_default = false;
            // default destination is the build directory
            if (downloadDestination.isEmpty()) {
                downloadDestination = build.getRootDir().getAbsolutePath();
                is_default = true;
            }
            CopyToMasterNotifier copyFilesToMaster
                    = new CopyToMasterNotifier(filesToDownload, "",
                            true, downloadDestination, true);
            copyFilesToMaster.perform(build, launcher, listener);
            // resets the download destination
            if (is_default) {
                downloadDestination = "";
            }
        }
    }

    /**
     * sends the job script file to slave
     *
     * @param build
     * @param launcher
     * @param listener
     * @param sendFilesShellCommands
     * @param jobFileName
     * @throws IOException
     * @throws InterruptedException
     */
    protected void sendJobToSlave(AbstractBuild<?, ?> build, Launcher launcher,
            BuildListener listener, String sendFilesShellCommands,
            String jobFileName) throws IOException, InterruptedException {
        // stores the job in a script file
        PrintWriter writer
                = new PrintWriter(masterWorkingDirectory
                        + jobFileName, "UTF-8");
        writer.print(sendFilesShellCommands + job + "\n");

        // inputs the files to download commands to the job
        if (!filesToDownload.isEmpty()) {
            for (String file : filesToDownload.split(",")) {
                writer.print("cp \"" + file.trim() + "\" \""
                        + slaveWorkingDirectory + "/\" > /dev/null\n");
            }
        }
        writer.close();
        // sends the job file to the slave
        CopyToSlaveBuildWrapper copyToSlave = new CopyToSlaveBuildWrapper(
                build.getProject().getName() + "/" + jobFileName,
                "", true, false, CopyToSlaveBuildWrapper.RELATIVE_TO_HOME,
                false);
        copyToSlave.setUp(build, launcher, listener);
    }

    /**
     * sets the correct permission on the job file
     *
     * @param build
     * @param launcher
     * @param listener
     * @param jobFileName
     * @throws InterruptedException
     */
    protected void setPermissionOnJobFile(AbstractBuild<?, ?> build,
            Launcher launcher, BuildListener listener, String jobFileName)
            throws InterruptedException {
        Shell shell = new Shell("#!/bin/bash +x\n chmod 755 "
                + jobFileName + " > /dev/null");
        shell.perform(build, launcher, listener);
    }

    /**
     * cleans up the temporary files in the master and the slave
     *
     * @param build
     * @param launcher
     * @param listener
     * @param jobFileName
     * @param jobId
     * @throws InterruptedException
     */
    protected void cleanUpFiles(AbstractBuild<?, ?> build, Launcher launcher,
            BuildListener listener, String jobFileName, String jobId)
            throws InterruptedException {
        String filesToDelete = jobFileName + " "
                + PROGRESS_FILE + " " + COMMUNICATION_FILE;
        for (String uploadedFile : uploadedFiles.split(",")) {
            filesToDelete = filesToDelete + " " + uploadedFile.trim();
        }
        for (String fileToDownload : filesToDownload.split(",")) {
            filesToDelete = filesToDelete + " " + fileToDownload.trim();
        }
        File file = new File(masterWorkingDirectory + jobFileName);
        file.delete();
        for (String fileToSend : filesToSend.split(",")) {
            String fileName = new File(fileToSend.trim()).getName();
            file = new File(masterWorkingDirectory + fileName);
            file.delete();
            filesToDelete = filesToDelete + " " + fileName;
        }
        file = new File(masterWorkingDirectory + COMMUNICATION_FILE);
        file.delete();
        Shell shell = new Shell("rm " + filesToDelete);
        shell.perform(build, launcher, listener);
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    public String getUploadedFiles() {
        return getDescriptor().getUploadedFileNames();
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public Set<File> uploadedFiles = new HashSet<File>();

        public Set<File> getUploadedFiles() {
            return uploadedFiles;
        }

        public DescriptorImpl() {
            load();
        }

        public void doStartUpload(StaplerRequest req, StaplerResponse rsp)
                throws IOException, ServletException {
            rsp.setContentType("text/html");
            req.getView(BatchBuilder.class,
                    "startUpload.jelly").forward(req, rsp);
        }

        public void doUploadFile(StaplerRequest req, StaplerResponse rsp,
                @QueryParameter String job)
                throws IOException, ServletException {
            try {
                AbstractProject prj = (AbstractProject) 
                        Jenkins.getInstance().getItemByFullName(job);
                ServletFileUpload upload
                        = new ServletFileUpload(new DiskFileItemFactory());
                FileItem fileItem = req.getFileItem("uploadedFile");
                String fileName = Util.getFileName(fileItem.getName());
                File f = new File(Jenkins.getInstance().root.getAbsolutePath()
                        + "/userContent/" + job + "/" + fileName);
                System.out.println(f.getPath());
                fileItem.write(f);
                fileItem.delete();
                uploadedFiles.add(f);
                save();
            } catch (FileNotFoundException ex) {
            } catch (Exception ex) {
                Logger.getLogger(BatchBuilder.class.getName())
                        .log(Level.SEVERE, null, ex);
            } finally {
                rsp.setContentType("text/html");
                String redirect = req.getRequestURL().toString().substring(0,
                        req.getRequestURL().toString().lastIndexOf("/") + 1)
                        + "startUpload" + "?job=" + job + "&files="
                        + getUploadedFileNames();
                rsp.sendRedirect(redirect);
            }
        }

        public void doDeleteFile(StaplerRequest req, StaplerResponse rsp,
                @QueryParameter String job, @QueryParameter String file)
                throws IOException, ServletException {
            for (File f : uploadedFiles) {
                if (f.getName().equals(file)) {
                    f.delete();
                    uploadedFiles.remove(f);
                    break;
                }
            }
            save();
            rsp.setContentType("text/html");
            String redirect = req.getRequestURL().toString().substring(0,
                    req.getRequestURL().toString().lastIndexOf("/") + 1)
                    + "startUpload" + "?job=" + job + "&files="
                    + getUploadedFileNames();
            rsp.sendRedirect(redirect);
        }

        public String getUploadedFileNames() {
            String files = "";
            for (File f : uploadedFiles) {
                files = files + f.getName() + ",";
            }
            return files;
        }

        @Override
        public String getDisplayName() {
            return "Run job on LSF";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> type) {
            return true;
        }
    }

}
