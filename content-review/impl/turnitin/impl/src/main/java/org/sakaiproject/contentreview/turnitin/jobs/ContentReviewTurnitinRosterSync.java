package org.sakaiproject.contentreview.turnitin.jobs;

import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.StatefulJob;
//import org.sakaiproject.contentreview.impl.turnitin.TurnitinRosterSync;
import org.sakaiproject.contentreview.turnitin.TurnitinRosterSync;

public class ContentReviewTurnitinRosterSync implements StatefulJob {
	
	private TurnitinRosterSync turnitinRosterSync;
	public void setTurnitinRosterSync(TurnitinRosterSync turnitinRosterSync) {
		this.turnitinRosterSync = turnitinRosterSync;
	}
	
	public void execute(JobExecutionContext arg0) throws JobExecutionException {
		turnitinRosterSync.processSyncQueue();
	}
}
