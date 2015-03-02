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
import jenkins.model.Jenkins;
import jenkins.util.BuildListenerAdapter;
import org.apache.commons.io.FileUtils;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 *
 * @author Laisvydas Skurevicius
 */
public class LSFBuilder extends Builder {

    private static final Set ENDING_STATES = new HashSet();

    static {
        ENDING_STATES.add("DONE");
        ENDING_STATES.add("EXIT");
        ENDING_STATES.add("PSUS");
        ENDING_STATES.add("USUS");
        ENDING_STATES.add("SSUS");
    }

    private String job;
    private String filesToDownload = "";
    private String downloadDestination;
    private String filesToSend = "";
    private int checkFrequencyMinutes = 1;

    /**
     *
     * @param job
     * @param filesToDownload
     * @param filesToSend
     * @param checkFrequencyMinutes
     */
    @DataBoundConstructor
    public LSFBuilder(String job, String filesToDownload, String downloadDestination, String filesToSend, int checkFrequencyMinutes) {
        this.job = job;
        this.filesToDownload = filesToDownload;
        this.downloadDestination = downloadDestination;
        this.filesToSend = filesToSend;
        this.checkFrequencyMinutes = checkFrequencyMinutes;
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
            File fileToSend = new File(file);
            sendFilesShellCommands = sendFilesShellCommands + "cp \"" + currentWorkingDirectory + "/" + fileToSend.getName() + "\" .\n";
            Files.copy(fileToSend.toPath(), new File(Jenkins.getInstance().root.getAbsolutePath() + "/userContent/" + fileToSend.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
            filesWithoutPaths = fileToSend.getName() + "," + filesWithoutPaths;
        }
        CopyToSlaveBuildWrapper copyToSlave = new CopyToSlaveBuildWrapper(filesWithoutPaths, "", false, false, CopyToSlaveBuildWrapper.RELATIVE_TO_SOMEWHERE_ELSE, false);
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

        // submits the job to LSF
        shell = new Shell("#!/bin/bash +x\n bsub -q " + queueType + " -e \"errorLog\" " + jobFileName + " | tee output");
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
            shell = new Shell("#!/bin/bash +x\n bjobs " + jobId + " > output");
            Shell bpeek = new Shell("#!/bin/bash +x\n bpeek " + jobId + " > result");
            Shell result;
            Shell countNumberOfLines = new Shell("#!/bin/bash +x\n wc -l result > output");
            int offset = 2;
            boolean new_output = true;

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
                    listener.getLogger().println("Job is still waiting in a queue for scheduling and dispatch...");
                } // prints the progress of the job if it is in a running state
                else if (jobStatus.equals("RUN")) {
                    bpeek.perform(build, launcher, fakeListener);
                    countNumberOfLines.perform(build, launcher, fakeListener);
                    copyOutputToMaster.perform(build, launcher, fakeListener);
                    br = new BufferedReader(new FileReader(build.getRootDir().getAbsolutePath() + "/output"));
                    String first_word = br.readLine();
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
                }
            }

            // prints the remaining job output
            result = new Shell("#!/bin/bash +x\n tail -n+" + (offset - 1) + " LSFJOB_" + jobId + "/STDOUT");
            listener.getLogger().println("---------------------------------------------------JOB OUTPUT START---------------------------------------------------");
            listener.getLogger().println();
            result.perform(build, launcher, listener);
            listener.getLogger().println();
            listener.getLogger().println("---------------------------------------------------JOB OUTPUT END-----------------------------------------------------");
            if (!filesToDownload.isEmpty()) {
                listener.getLogger().println();
                listener.getLogger().println("Copying the selected files:");
                if (downloadDestination.isEmpty()) {
                    downloadDestination = build.getRootDir().getAbsolutePath();
                }
                CopyToMasterNotifier copyFilesToMaster = new CopyToMasterNotifier(filesToDownload, "", true, downloadDestination, true);
                copyFilesToMaster.perform(build, launcher, listener);
            }

        } catch (InterruptedException e) {
            // kills the job if it was interrupted
            shell = new Shell("#!/bin/bash +x\n bkill " + jobId);
            shell.perform(build, launcher, listener);
            jobStatus = "ABORT";
        } finally {

            // Outputs the error log if the job exited
            if (jobStatus.equals("EXIT")) {
                listener.getLogger().println();
                listener.getLogger().println("Job exited with following errors:");
                shell = new Shell("#!/bin/bash +x\n cat errorLog");
                shell.perform(build, launcher, listener);

                // prints the exit code
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

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {

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
