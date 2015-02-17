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
import hudson.slaves.Cloud;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tasks.Shell;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import jenkins.model.Jenkins;
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

    private final String job;

    @DataBoundConstructor
    public LSFBuilder(String job) {
        this.job = job;
    }

    public String getJob() {
        return job;
    }

    /**
     * This is where the interaction between Jenkins and LSF happens.
     * @param build
     * @param launcher
     * @param listener
     * @return
     * @throws InterruptedException
     * @throws IOException 
     */
    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        String queueType = "8nm";
        // finds the queue type by searching through the clouds with the associated label
        for (Cloud cloud : Jenkins.getInstance().clouds) {
            if (cloud instanceof LSFCloud && cloud.canProvision(build.getProject().getAssignedLabel())) {
                queueType = ((LSFCloud) cloud).getQueueType();
                break;
            }
        }
        // stores the job in a script file
        String jobFileName = "JOB-" + UUID.randomUUID().toString();
        PrintWriter writer = new PrintWriter(Jenkins.getInstance().root.getAbsolutePath() + "/userContent/" + jobFileName, "UTF-8");
        writer.print(job);
        writer.close();
        // sends the file to the slave
        CopyToSlaveBuildWrapper copyToSlave = new CopyToSlaveBuildWrapper(jobFileName, "", false, false, CopyToSlaveBuildWrapper.RELATIVE_TO_HOME, false);
        copyToSlave.setUp(build, launcher, listener);
        // sets the correct permission of the file for execution
        Shell shell = new Shell("chmod 755 " + jobFileName);
        shell.perform(build, launcher, listener);
        // submits the job to LSF
        shell = new Shell("bsub -q " + queueType + " " + jobFileName + " | tee output");
        shell.perform(build, launcher, listener);
        // stores the job id 
        CopyToMasterNotifier copyToMaster = new CopyToMasterNotifier("output", "", true, "output", true);
        copyToMaster.perform(build, launcher, listener);
        BufferedReader br = new BufferedReader(new FileReader("output/output"));
        String jobId = br.readLine();
        jobId = jobId.substring(jobId.indexOf('<', 0) + 1, jobId.indexOf('>', 0));
        try {
            String jobStatus = "";
            // initializes the shell commands for job status and result
            shell = new Shell("bjobs " + jobId + " | tee output");
            Shell result = new Shell("cat LSFJOB_" + jobId + "/STDOUT");
            Shell echo = new Shell("echo -------------------------------------------------"
                    + "-------------------------------------------------------------------"
                    + "--------------");
            // loops for checking the job's status and progress until it reaches an ending state
            while (!ENDING_STATES.contains(jobStatus)) {
                Thread.sleep(60000);
                echo.perform(build, launcher, listener);
                // prints and stores the current state of the job
                shell.perform(build, launcher, listener);
                copyToMaster.perform(build, launcher, listener);
                br = new BufferedReader(new FileReader("output/output"));
                br.readLine();
                jobStatus = br.readLine().trim().split(" ")[2];
                // prints the progress of the job if it is in a running state
                if (jobStatus.equals("RUN")) {
                    echo.perform(build, launcher, listener);
                    result.perform(build, launcher, listener);
                }
                
            }
            // prints the final result
            echo.perform(build, launcher, listener);
            result.perform(build, launcher, listener);
        } catch (InterruptedException e) {
            // kills the job if it was interrupted
            shell = new Shell("bkill " + jobId);
            shell.perform(build, launcher, listener);
            return false;
        } finally {
            // cleans up the files
            shell = new Shell("rm " + jobFileName);
            shell.perform(build, launcher, listener);
            File file = new File(Jenkins.getInstance().root.getAbsolutePath() + "/userContent/" + jobFileName);
            file.delete();
        }
        return true;
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
