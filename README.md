# lsf-jenkins

Short description:

A Jenkins plugin that allows submitting batch jobs to LSF batch system.

Installation:

"SSH Slaves plugin" and "Copy To Slave Plugin" need to be installed before installing  this plugin (you can find them in "Manage Jenkins"->"Manage Plugins"->"Available"). To build and install this plugin on your Jenkins: 
Run command "mvn clean install" in the "lsf-jenkins" folder.
Open your Jenkins, go to "Manage Jenkins"->"Manage Plugins"->"Advanced". 
Select the file "lsf-jenkins.hpi" (from the lsf-jenkins/target folder) in the "Upload Plugin" section and press "Upload".
The plugin should be installed on your Jenkins now.
