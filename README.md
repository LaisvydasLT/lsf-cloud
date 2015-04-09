# Short description:

A Jenkins plugin that allows submitting batch jobs to LSF batch system.

# Plugin features:

Adds a new type of cloud ("LSF Cloud") which creates a slave for every running job with the specified label and terminates the slave when the job is done. Every cloud is associated with a queue type of LSF batch system, so all slaves created by the cloud will submit batch jobs to the associated queue.

Adds a new type of build step ("Run job on LSF") which allows submitting a batch job to LSF. The build step monitors the job status and periodically (time is configurable) outputs the progress (output of the job) if the job is running. It also outputs the errors and the exit code of the job if the job fails. If the job is terminated in Jenkins, it is also terminated in LSF.

Allows files to be uploaded (or specified by a path) and sent to LSF before the execution of the job and downloaded from LSF after the job is finished (currently only shared file systems are supported), this is configured in the new build step.

Allows to select if the owner of the job should receive an email from LSF when the job is done.

# How to use:

Go to "Manage Jenkins"->"Configure System" and add a new cloud "LSF Cloud".

Fill in the required information to the created cloud configuration. Make sure that the host to which the cloud will connect has access to LSF.

Now when you create a new project, you can add a build step "Run job on LSF" in which you can specify the batch job script which will be submitted to LSF (additional configurations are available but are optional). Make sure that the project is restricted where it can be run and the label specified matches the created LSF Cloud label.
