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

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.Secret;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.logging.Logger;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 *
 * @author Laisvydas Skurevicius
 */
public class BatchCloud extends Cloud {

    // The name of the cloud
    private String cloudName;
    // LSF queue type
    private String queueType;
    // The label that the cloud is associated with
    private String label;
    // Host name of the slave computer
    private String hostname;
    private int port = 22;
    // credentials for connecting to the slave computer through ssh 
    private String username;
    private Secret password;

    private static final Logger LOGGER = Logger
            .getLogger(BatchCloud.class.getName());

    @DataBoundConstructor
    public BatchCloud(String cloudName, String queueType, String label,
            String hostname, int port, String username, String password) {
        super(cloudName);
        this.cloudName = cloudName;
        this.queueType = queueType;
        this.label = label;
        this.hostname = hostname;
        this.port = port;
        this.username = username;
        this.password = Secret.fromString(password);
    }

    /**
     * Creates a slave when there is a running job with an appropriate label
     *
     * @param label
     * @param excessWorkload
     * @return
     */
    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Label label,
            final int excessWorkload) {
        List<PlannedNode> list = new ArrayList<PlannedNode>();
        list.add(new PlannedNode(this.getDisplayName(),
                Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                    @Override
                    public Node call() throws Exception {
                        BatchSlave s = doProvision(excessWorkload);
                        return s;
                    }
                }), excessWorkload));
        return list;
    }

    private BatchSlave doProvision(int numExecutors) 
            throws Descriptor.FormException, IOException {
        String name = "BatchSystem-" + UUID.randomUUID().toString();
        return new BatchSlave(name, this.label, numExecutors, hostname, port, 
                username, password);
    }

    /**
     * Checks if a jobs label matches the clouds label and determines if a slave
     * should be created
     *
     * @param label
     * @return
     */
    @Override
    public boolean canProvision(Label label) {
        if (label.matches(Label.parse(this.label))) {
            return true;
        }
        return false;
    }

    public void setCloudName(String cloudName) {
        this.cloudName = cloudName;
    }

    public String getCloudName() {
        return cloudName;
    }

    public String getQueueType() {
        return queueType;
    }

    public void setQueueType(String queueType) {
        this.queueType = queueType;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return Secret.toString(password);
    }

    public void setPassword(String password) {
        this.password = Secret.fromString(password);
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {

        @Override
        public String getDisplayName() {
            return "LSF Cloud";
        }
    }
}
