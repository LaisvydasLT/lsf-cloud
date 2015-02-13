/*
 * The MIT License
 *
 * Copyright 2015 seven.
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
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tasks.Shell;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 *
 * @author seven
 */
public class LSFBuilder extends Builder {

    private final String job;

    @DataBoundConstructor
    public LSFBuilder(String job) {
        this.job = job;
    }

    public String getJob() {
        return job;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        Shell sh = new Shell("bsub " + job + " | tee output");
        sh.perform(build, launcher, listener);
        CopyToMasterNotifier copy = new CopyToMasterNotifier("output", "", true, "output", true);
        copy.perform(build, launcher, listener);
        BufferedReader br = new BufferedReader(new FileReader("output/output"));
        String jobId = br.readLine();
        jobId = jobId.substring(jobId.indexOf('<', 0) + 1, jobId.indexOf('>', 0));
        System.out.println(jobId);
        String jobStatus = "";
        sh = new Shell("bjobs " + jobId + " | tee output");
        while (!jobStatus.equals("DONE")) {
            Thread.sleep(60000);
            sh.perform(build, launcher, listener);
            copy.perform(build, launcher, listener);
            br = new BufferedReader(new FileReader("output/output"));
            br.readLine();
            jobStatus = br.readLine().trim().split(" ")[2];
            System.out.println(jobStatus);
        }
        sh = new Shell("cat LSFJOB_" + jobId + "/STDOUT");
        sh.perform(build, launcher, listener);
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
