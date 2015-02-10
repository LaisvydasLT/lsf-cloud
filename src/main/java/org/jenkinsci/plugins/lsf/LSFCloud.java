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
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.logging.Logger;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

/**
 *
 * @author Laisvydas Skurevicius
 */
public class LSFCloud extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(LSFCloud.class.getName());

    /*@Initializer(after = InitMilestone.JOB_LOADED)
     public static void init() {
     Hudson h = Hudson.getInstance();
     List<Node> slaves = h.getNodes();
     LOGGER.info("-----------------------------------INITIALIZATION------------------------------------------");
     // Turning the AUTOMATIC_SLAVE_LAUNCH flag off because the below slave removals
     // causes computer launch in other slaves that have not been removed yet.
     // To study how a slave removal updates the entire list, one can refer to
     // Hudson NodeProvisioner class and follow this method chain removeNode() ->
     // setNodes() -> updateComputerList() -> updateComputer().
     h.AUTOMATIC_SLAVE_LAUNCH = false;
     for (Node n : slaves) {
     //Remove all slaves that were persisted when Jenkins shutdown.
     if (n instanceof LSFSlave) {
     ((LSFSlave) n).terminate();
     }
     }

     // Turn it back on for future real slaves.
     h.AUTOMATIC_SLAVE_LAUNCH = true;
     }*/
    @DataBoundConstructor
    public LSFCloud() {
        super("LSFCloud");
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Label label, final int excessWorkload) {
        Hudson h = Hudson.getInstance();
        List<Node> slaves = h.getNodes();
        List<PlannedNode> list = new ArrayList<PlannedNode>();
        if (slaves.isEmpty()) {
            list.add(new PlannedNode(this.getDisplayName(), Computer.threadPoolForRemoting
                    .submit(new Callable<Node>() {
                        public Node call() throws Exception {
                            LSFSlave s = doProvision(excessWorkload);
                            return s;
                        }
                    }), excessWorkload));
        }
        return list;
    }

    private LSFSlave doProvision(int numExecutors) throws Descriptor.FormException, IOException {
        String name = "LSF-jenkins-" + UUID.randomUUID().toString();
        return new LSFSlave(name, numExecutors);
    }

    @Override
    public boolean canProvision(Label label) {
        if (label.matches(Label.parse("LSF"))) {
            return true;
        }
        return false;
    }

    public String getCloudName() {
        DescriptorImpl descriptor = (DescriptorImpl) super.getDescriptor();
        return descriptor.getCloudName();

    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {

        private String cloudName = "";

        public String getCloudName() {
            return cloudName;
        }

        public DescriptorImpl() {
            load();
        }

        @Override
        public String getDisplayName() {
            return "LSF Cloud";
        }

        @Override
        public boolean configure(StaplerRequest sr, JSONObject jsono) throws FormException {
            cloudName = jsono.getString("cloudName");
            save();
            return super.configure(sr, jsono);
        }

    }
}
