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
public class LSFBuilder extends Builder {

    private static final Set ENDING_STATES = new HashSet();

    static {
        ENDING_STATES.add("DONE");
        ENDING_STATES.add("EXIT");
        /*
         ENDING_STATES.add("PSUS");
         ENDING_STATES.add("USUS");
         ENDING_STATES.add("SSUS");
         */
    }

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

    /**
     *
     * @param job
     * @param filesToDownload
     * @param downloadDestination
     * @param filesToSend
     * @param checkFrequencyMinutes
     */
    @DataBoundConstructor
    public LSFBuilder(String job, String filesToDownload, String downloadDestination, String filesToSend, int checkFrequencyMinutes, boolean sendEmail) {
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
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {

        String jobStatus = "";
        // the default queue type is 8nm
        String queueType = "8nm";
        // randomly generated job script name
        String jobFileName = "JOB-" + UUID.randomUUID().toString();
        // a fake listener for hiding output of some commands to make the console easier to read
        BuildListenerAdapter fakeListener = new BuildListenerAdapter(TaskListener.NULL);

        // finds the queue type by searching through the clouds with the associated label
        for (Cloud cloud : Jenkins.getInstance().clouds) {
            if (cloud instanceof LSFCloud && cloud.canProvision(build.getProject().getAssignedLabel())) {
                queueType = ((LSFCloud) cloud).getQueueType();
                break;
            }
        }

        // stores the current working directory
        Shell shell = new Shell("pwd > output");
        shell.perform(build, launcher, fakeListener);
        CopyToMasterNotifier copyOutputToMaster = new CopyToMasterNotifier("output", "", true, build.getRootDir().getAbsolutePath(), true);
        copyOutputToMaster.perform(build, launcher, fakeListener);
        BufferedReader br = new BufferedReader(new FileReader(build.getRootDir().getAbsolutePath() + "/output"));
        String currentWorkingDirectory = br.readLine();

        // sends the selected files to the slave and prepares the commands to send files to LSF
        String sendFilesShellCommands = "";
        String filesWithoutPaths = "";
        for (String file : filesToSend.split(",")) {
            File fileToSend = new File(file.trim());
            sendFilesShellCommands = sendFilesShellCommands + "cp \"" + currentWorkingDirectory + "/" + fileToSend.getName() + "\" .\n";
            Files.copy(fileToSend.toPath(), new File(build.getProject().getRootDir().getAbsolutePath() + "/workspace/" + fileToSend.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
            filesWithoutPaths = fileToSend.getName() + "," + filesWithoutPaths;
        }
        for (String file : uploadedFiles.split(",")) {
            sendFilesShellCommands = sendFilesShellCommands + "cp \"" + currentWorkingDirectory + "/" + file + "\" .\n";
        }
        CopyToSlaveBuildWrapper copyToSlave = new CopyToSlaveBuildWrapper(filesWithoutPaths + uploadedFiles, "", false, false, CopyToSlaveBuildWrapper.RELATIVE_TO_WORKSPACE, false);
        copyToSlave.setUp(build, launcher, listener);

        // stores the job in a script file
        PrintWriter writer = new PrintWriter(Jenkins.getInstance().root.getAbsolutePath() + "/userContent/" + jobFileName, "UTF-8");
        writer.print(sendFilesShellCommands + job + "\n");

        // inputs the files to download commands to the job
        if (!filesToDownload.isEmpty()) {
            for (String file : filesToDownload.split(",")) {
                writer.print("cp \"" + file.trim() + "\" \"" + currentWorkingDirectory + "/\" > /dev/null\n");
            }
        }
        writer.close();

        // sends the job file to the slave
        copyToSlave = new CopyToSlaveBuildWrapper(jobFileName, "", false, false, CopyToSlaveBuildWrapper.RELATIVE_TO_HOME, false);
        copyToSlave.setUp(build, launcher, fakeListener);

        // sets the correct permission of the file for execution
        shell = new Shell("#!/bin/bash +x\n chmod 755 " + jobFileName + " > /dev/null");
        shell.perform(build, launcher, fakeListener);
        
        // checks if email notifications should be sent and configures the command
        String emailConfiguration = "";
        if (!getSendEmail()) {
            emailConfiguration = "LSB_JOB_REPORT_MAIL=N ";
        }
        // submits the job to LSF
        shell = new Shell("#!/bin/bash +x\n" + emailConfiguration + "bsub -q " + queueType + " -e \"errorLog\" " + jobFileName + " | tee output");
        shell.perform(build, launcher, listener);

        // stores the job id
        copyOutputToMaster.perform(build, launcher, fakeListener);
        br = new BufferedReader(new FileReader(build.getRootDir().getAbsolutePath() + "/output"));
        String jobId = br.readLine();
        jobId = jobId.substring(jobId.indexOf('<', 0) + 1, jobId.indexOf('>', 0));

        try {
            shell = new Shell("#!/bin/bash +x\n bjobs " + jobId);
            shell.perform(build, launcher, listener);

            // initializes the shell commands for job status and result
            // command for checking the status of the job
            shell = new Shell("#!/bin/bash +x\n bjobs " + jobId + " > output");
            // command for getting the ouput of a running job
            Shell bpeek = new Shell("#!/bin/bash +x\n bpeek " + jobId + " > result");
            // command for counting lines in the result file (for tracking of job progress)
            Shell countNumberOfLines = new Shell("#!/bin/bash +x\n wc -l result > output");
            // used for output progress tracking (specifies how many lines to skip when printing job output file)
            int offset = 2;
            // a flag which tracks if job has new output (if it needs to be printed)
            boolean new_output = true;
            Shell result;

            // loops for checking the job's status and progress until it reaches an ending state
            while (!ENDING_STATES.contains(jobStatus)) {
                Thread.sleep(checkFrequencyMinutes * 60000);

                // prints and stores the current state of the job
                shell.perform(build, launcher, fakeListener);
                copyOutputToMaster.perform(build, launcher, fakeListener);
                br = new BufferedReader(new FileReader(build.getRootDir().getAbsolutePath() + "/output"));
                br.readLine();
                jobStatus = br.readLine().trim().split(" ")[2];
                listener.getLogger().println("JOB STATUS: " + jobStatus);
                if (jobStatus.equals("PEND")) {
                    listener.getLogger().println("Waiting in a queue for scheduling and dispatch.");
                } // prints the progress of the job if it is in a running state
                else if (jobStatus.equals("RUN")) {
                    listener.getLogger().println("Dispatched to a host and running.");
                    bpeek.perform(build, launcher, fakeListener);
                    countNumberOfLines.perform(build, launcher, fakeListener);
                    copyOutputToMaster.perform(build, launcher, fakeListener);
                    br = new BufferedReader(new FileReader(build.getRootDir().getAbsolutePath() + "/output"));
                    String first_word = br.readLine();
                    // checks if command didn't fail and the result file exists
                    if (first_word != null) {
                        first_word = first_word.split(" ")[0];
                        if (!first_word.equals("wc:")) {
                            result = new Shell("#!/bin/bash +x\n tail -n+" + offset + " result > output");
                            result.perform(build, launcher, fakeListener);
                            copyOutputToMaster.perform(build, launcher, fakeListener);
                            String output = FileUtils.readFileToString(new File(build.getRootDir().getAbsolutePath() + "/output"));
                            if (new_output && output.lastIndexOf("\n<< output from stderr >>") != -1) {
                                listener.getLogger().println("---------------------------------------------------JOB OUTPUT START---------------------------------------------------");
                                listener.getLogger().println();
                                listener.getLogger().println(output.substring(0, output.lastIndexOf("\n<< output from stderr >>")));
                                listener.getLogger().println("---------------------------------------------------JOB OUTPUT END-----------------------------------------------------");
                                new_output = false;
                            }
                            if (offset < Integer.parseInt(first_word) - 1) {
                                offset = Integer.parseInt(first_word) - 1;
                                new_output = true;
                            }
                        }
                    }
                } else if (jobStatus.equals("DONE")) {
                    listener.getLogger().println("Finished normally with zero exit value.");
                } else if (jobStatus.equals("EXIT")) {
                    listener.getLogger().println("Finished with non-zero exit value.");
                } else if (jobStatus.equals("PSUS")) {
                    listener.getLogger().println("Suspended while pending.");
                } else if (jobStatus.equals("USUS")) {
                    listener.getLogger().println("Suspended by user.");
                } else if (jobStatus.equals("SSUS")) {
                    listener.getLogger().println("Suspended by the LSF system.");
                } else if (jobStatus.equals("WAIT")) {
                    listener.getLogger().println("Members of a chunk job that are waiting to run.");
                }

            }

            // prints the remaining job output
            result = new Shell("#!/bin/bash +x\n tail -n+" + (offset - 1) + " LSFJOB_" + jobId + "/STDOUT");
            listener.getLogger().println("---------------------------------------------------JOB OUTPUT START---------------------------------------------------");
            listener.getLogger().println();
            result.perform(build, launcher, listener);
            listener.getLogger().println();
            listener.getLogger().println("---------------------------------------------------JOB OUTPUT END-----------------------------------------------------");
            // downloads the selected files after job completion (if there are any)
            if (!filesToDownload.isEmpty()) {
                listener.getLogger().println();
                listener.getLogger().println("Downloading the selected files:");
                boolean is_default = false;
                // default destination is the build directory
                if (downloadDestination.isEmpty()) {
                    downloadDestination = build.getRootDir().getAbsolutePath();
                    is_default = true;
                }
                CopyToMasterNotifier copyFilesToMaster = new CopyToMasterNotifier(filesToDownload, "", true, downloadDestination, true);
                copyFilesToMaster.perform(build, launcher, listener);
                // resets the download destination
                if (is_default) {
                    downloadDestination = "";
                }
            }
        } catch (InterruptedException e) {
            // kills the job if it was interrupted
            shell = new Shell("#!/bin/bash +x\n bkill " + jobId);
            shell.perform(build, launcher, listener);
            jobStatus = "ABORTED";
        } finally {

            // Outputs the error log if the job exited
            if (jobStatus.equals("EXIT")) {
                listener.getLogger().println();
                listener.getLogger().println("Job exited with following errors:");
                shell = new Shell("#!/bin/bash +x\n cat errorLog");
                shell.perform(build, launcher, listener);

                // prints the exit code if there is one
                shell = new Shell("#!/bin/bash +x\n bjobs -l " + jobId + " > output");
                shell.perform(build, launcher, fakeListener);
                copyOutputToMaster.perform(build, launcher, fakeListener);
                String exitCode = FileUtils.readFileToString(new File(build.getRootDir().getAbsolutePath() + "/output"));
                if (exitCode.contains("Exited with exit code ")) {
                    listener.getLogger().println();
                    exitCode = exitCode.substring(exitCode.indexOf("Exited with exit code "), exitCode.length());
                    exitCode = exitCode.substring(0, exitCode.indexOf(".") + 1);
                    listener.getLogger().println(exitCode);
                }
            }

            // cleans up the files
            shell = new Shell("rm -rf " + currentWorkingDirectory + "/*");
            shell.perform(build, launcher, fakeListener);
            File file = new File(Jenkins.getInstance().root.getAbsolutePath() + "/userContent/" + jobFileName);
            file.delete();
            for (String fileWithouPath : filesWithoutPaths.split(",")) {
                File f = new File(Jenkins.getInstance().root.getAbsolutePath() + "/userContent/" + fileWithouPath);
                f.delete();
            }
        }
        return jobStatus.equals("DONE");
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

        public void doStartUpload(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
            rsp.setContentType("text/html");
            req.getView(LSFBuilder.class, "startUpload.jelly").forward(req, rsp);
        }

        public void doUploadFile(StaplerRequest req, StaplerResponse rsp, @QueryParameter String job) throws IOException, ServletException {
            try {
                AbstractProject prj = (AbstractProject) Jenkins.getInstance().getItemByFullName(job);
                ServletFileUpload upload = new ServletFileUpload(new DiskFileItemFactory());
                FileItem fileItem = req.getFileItem("uploadedFile");
                String fileName = Util.getFileName(fileItem.getName());
                File f = new File(prj.getRootDir().getAbsolutePath() + "/workspace/" + fileName);
                fileItem.write(f);
                fileItem.delete();
                uploadedFiles.add(f);
                save();
                rsp.setContentType("text/html");
                String redirect = req.getRequestURL().toString().substring(0, req.getRequestURL().toString().lastIndexOf("/") + 1) + "startUpload" + "?job=" + job + "&files=" + getUploadedFileNames();
                rsp.sendRedirect(redirect);
            } catch (Exception ex) {
                Logger.getLogger(LSFBuilder.class.getName()).log(Level.SEVERE, null, ex);
            }
        }

        public void doDeleteFile(StaplerRequest req, StaplerResponse rsp, @QueryParameter String job, @QueryParameter String file) throws IOException, ServletException {
            for (File f : uploadedFiles) {
                if (f.getName().equals(file)) {
                    f.delete();
                    uploadedFiles.remove(f);
                    break;
                }
            }
            save();
            rsp.setContentType("text/html");
            String redirect = req.getRequestURL().toString().substring(0, req.getRequestURL().toString().lastIndexOf("/") + 1) + "startUpload" + "?job=" + job + "&files=" + getUploadedFileNames();
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
