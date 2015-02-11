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

import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.Project;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import static java.util.concurrent.TimeUnit.MINUTES;
import java.util.logging.Logger;

/**
 *
 * @author Laisvydas Skurevicius
 */
public class LSFRetentionStrategy extends RetentionStrategy<SlaveComputer> {

    // The amount of minutes until a slave is terminated when idle
    public final int idleTerminationMinutes;

    private static final Logger LOGGER = Logger
            .getLogger(LSFRetentionStrategy.class.getName());

    public LSFRetentionStrategy(int idleTerminationMinutes) {
        this.idleTerminationMinutes = idleTerminationMinutes;
    }

    
    // Checks if the slave computer needs to be terminated and terminates if needed
    @Override
    public long check(SlaveComputer c) {

        if (c.getNode() == null) {
            return 1;
        }

        for (Project p : Hudson.getInstance().getProjects()) {
            if (p.isBuilding() && p.getAssignedLabelString().equals(c.getNode().getLabelString())) {
                System.out.println("SUCCESS " + c.getNode().getLabelString());
                return 1;
            }
        }
        System.out.println("FAIL " + c.getNode().getLabelString());
        
        if ((System.currentTimeMillis() - c.getConnectTime())
                < MINUTES.toMillis(idleTerminationMinutes)) {
            return 1;
        }

        if (c.isOffline()) {
            LOGGER.info("Disconnecting offline computer " + c.getName());
            ((LSFSlave) (c.getNode())).terminate();
            return 1;
        }

        if (c.isIdle()) {
            final long idleMilliseconds
                    = System.currentTimeMillis() - c.getIdleStartMilliseconds();

            if (idleMilliseconds > MINUTES.toMillis(idleTerminationMinutes)) {
                LOGGER.info("Disconnecting idle computer " + c.getName());
                ((LSFSlave) (c.getNode())).terminate();
            }
        }
        return 1;
    }

    @Override
    public void start(SlaveComputer c) {
        c.connect(false);
    }

    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {

        @Override
        public String getDisplayName() {
            return "LSF";
        }
    }

}
