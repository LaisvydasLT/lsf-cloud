# Short description:

A Jenkins plugin that allows submitting batch jobs to LSF batch system.

# Installation:

"SSH Slaves plugin" and "Copy To Slave Plugin" need to be installed before installing  this plugin (you can find them in "Manage Jenkins"->"Manage Plugins"->"Available"). 

To build and install this plugin on your Jenkins:

Run command `mvn clean install` in the `lsf-cloud` folder.

Open your Jenkins, go to "Manage Jenkins"->"Manage Plugins"->"Advanced".

Select the file `lsf-cloud.hpi` (from the `lsf-cloud/target` folder) in the "Upload Plugin" section and press "Upload".
The plugin should be installed on your Jenkins now.

# How to use:

Go to "Manage Jenkins"->"Configure System" and add a new cloud "LSF Cloud".

Fill in the required information to the created cloud configuration. Make sure that the host to which the cloud will connect has access to LSF.

Now when you create a new project, you can add a build step "Run job on LSF" in which you can specify the batch job script which will be submitted to LSF (additional configurations are available but are optional). Make sure that the project is restricted where it can be run and the label specified matches the created LSF Cloud label.
