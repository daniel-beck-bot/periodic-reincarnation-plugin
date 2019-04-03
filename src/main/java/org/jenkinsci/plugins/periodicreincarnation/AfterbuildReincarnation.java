package org.jenkinsci.plugins.periodicreincarnation;

import static hudson.model.Result.SUCCESS;

import hudson.AbortException;
import hudson.Extension;
import hudson.maven.MavenBuild;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;

/**
 * This class triggers a restart automatically after a build has failed.
 * 
 * @author yboev
 * 
 */
@Extension
public class AfterbuildReincarnation extends RunListener<AbstractBuild<?, ?>> {

	/**
	 * Maximal times a project can be automatically restarted from this class in
	 * a row.
	 * 
	 */
	private int maxRestartDepth;

	/**
	 * Tells if this type of restart is enabled(either globally or locally).
	 */
	private boolean isEnabled;
	/**
	 * Tells if the afterbuild restart was configured locally.
	 */
	private boolean isLocallyEnabled;

	@Override
	public void onCompleted(AbstractBuild<?, ?> build, TaskListener listener) {

		// stop if no build or project can be retrieved
		if (build == null || build.getProject() == null || isMavenBuild(build)) {
			return;
		}

		// stop if build was a success
		if (build.getResult() == SUCCESS) {
			return;
		}

		final JobLocalConfiguration localConfig = build.getProject()
				.getProperty(JobLocalConfiguration.class);
		final PeriodicReincarnationGlobalConfiguration globalConfig = PeriodicReincarnationGlobalConfiguration
				.get();
		// stop if there is no global configuration
		if (globalConfig == null) {
			return;
		}
		setConfigVariables(localConfig, globalConfig);

		// stop if not enabled
		if (!this.isEnabled) {
			return;
		}

		if (!this.isLocallyEnabled) {
			// try to restart the project by finding a matching regEx or
			// FailureCause or restart
			// it because of an unchanged configuration
			periodicTriggerRestart(build);
			noChangeRestart(build, globalConfig);
		} else {
			// restart project for which afterbuild restart has been enabled
			// locally
			localRestart(build);
		}
	}

	/**
	 * Method to restart a given project if it was configured locally. Here we
	 * should check for no regular expressions that match our project. If the
	 * maximal depth is not reached we restart.
	 * 
	 * @param build
	 *            The current build.
	 */
	private void localRestart(AbstractBuild<?, ?> build) {
		if (checkRestartDepth(build)) {
			Utils.restart((AbstractProject<?, ?>) build.getProject(),
					"(Afterbuild restart) Locally configured project.", null,
					Constants.AFTERBUILDQUIETPERIOD);
		}
	}

	/**
	 * Checks if we can restart the project for unchanged project configuration.
	 * 
	 * @param build
	 *            the build
	 * @param config
	 *            the periodic reincarnation configuration
	 */
	private void noChangeRestart(AbstractBuild<?, ?> build,
			PeriodicReincarnationGlobalConfiguration config) {
		if (config.isRestartUnchangedJobsEnabled()
				&& Utils.qualifyForUnchangedRestart(
						(AbstractProject<?, ?>) build.getProject())
				&& checkRestartDepth(build)) {
			Utils.restart((AbstractProject<?, ?>) build.getProject(),
					"(Afterbuild restart) No difference between last two builds",
					null, Constants.AFTERBUILDQUIETPERIOD);
		}
	}

	/**
	 * Checks if we can restart the project for a FailureCause or RegEx hit.
	 * 
	 * @param build
	 *            the build
	 */
	private void periodicTriggerRestart(AbstractBuild<?, ?> build) {
		if (Utils.isBfaAvailable()) {
			final BuildFailureObject bfa = Utils
					.checkBuildForBuildFailure(build);
			if (bfa != null && checkRestartDepth(build)) {
				try {
					String name = bfa.getFailureCauseName();
					Utils.restart((AbstractProject<?, ?>) build.getProject(),
							"(Afterbuild restart) Build Failure Cause hit: "
									+ name,
							bfa, Constants.AFTERBUILDQUIETPERIOD);
				} catch (AbortException e) {
					Utils.restart((AbstractProject<?, ?>) build.getProject(),
							"(Afterbuild restart) Build Failure Cause hit!",
							bfa, Constants.AFTERBUILDQUIETPERIOD);
				}
				return;
			}
		}
		final RegEx regEx = Utils.checkBuild(build);
		if (regEx != null && checkRestartDepth(build)) {
			Utils.restart((AbstractProject<?, ?>) build.getProject(),
					"(Afterbuild restart) RegEx hit in console output: "
							+ regEx.getValue(),
					regEx, Constants.AFTERBUILDQUIETPERIOD);
		}
	}

	/**
	 * Retrieves values from global or local config.
	 * 
	 * @param localconfig
	 *            Local configuration.
	 * @param config
	 *            Global configuration.
	 */
	private void setConfigVariables(JobLocalConfiguration localconfig,
			PeriodicReincarnationGlobalConfiguration config) {
		if (localconfig != null && localconfig.getIsLocallyConfigured()) {
			this.isEnabled = localconfig.getIsEnabled();
			this.isLocallyEnabled = localconfig.getIsEnabled();
			this.maxRestartDepth = localconfig.getMaxDepth();
		} else {
			this.isEnabled = config.isTriggerActive();
			this.maxRestartDepth = config.getMaxDepth();
		}
	}

	/**
	 * Checks the restart depth for the current project.
	 * 
	 * @param build
	 *            The current build.
	 * @return true if restart depth is larger than the consecutive restarts for
	 *         this project, false otherwise.
	 */
	private boolean checkRestartDepth(AbstractBuild<?, ?> build) {
		if (this.maxRestartDepth <= 0) {
			return true;
		}
		int count = 0;

		// count the number of restarts for the current project
		while (build != null) {
			PeriodicReincarnationBuildCause cause = build
					.getCause(PeriodicReincarnationBuildCause.class);
			if (cause == null)
				break;
			if (cause.getShortDescription()
					.contains(Constants.AFTERBUILDRESTART)) {
				count++;
			}
			if (count >= this.maxRestartDepth) {
				return false;
			}
			build = build.getPreviousBuild();
		}
		return true;
	}
	
	private boolean isMavenBuild(AbstractBuild<?, ?> build) {
		return (Utils.isMavenPluginAvailable() && build instanceof MavenBuild);
	}
}
