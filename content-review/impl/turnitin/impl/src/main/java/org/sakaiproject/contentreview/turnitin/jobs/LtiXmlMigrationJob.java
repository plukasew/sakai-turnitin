package org.sakaiproject.contentreview.turnitin.jobs;

import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.StatefulJob;

import org.sakaiproject.contentreview.service.ContentReviewService;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;

/**
 * TIITODO: is this class necessary? If we're the only institution using some flavour of the LTI integration,
 * and this class is only responsible for migrating things from an older variant of the LTI integration to the updated
 * variant, then this who process should be irrelevant.
 * 
 * Migrates the original LTI XML settings from the assignments table into the new activity config table.
 * Also moves the external value from the content resource binary entity back into the contentreviewitem table.
 * You need to run this ONLY if you have previously deployed the LTI integration prior to the introduction of TII-219 and TII-221.
 * @author plukasew
 */
public class LtiXmlMigrationJob implements StatefulJob {

	private ContentReviewService contentReviewService;
	public void setContentReviewService(ContentReviewService sd){
		contentReviewService = sd;
	}
	
	private SessionManager sessionManager;
	public void setSessionManager(SessionManager s) {
		this.sessionManager = s;
	}
	
	public void execute(JobExecutionContext arg0) throws JobExecutionException {
		// TODO Auto-generated method stub
		Session sakaiSession = sessionManager.getCurrentSession();
		sakaiSession.setUserId("admin");
		sakaiSession.setUserEid("admin");
		// TIITODO: uncomment and fix the line below (BLOCKED BY DECISION, see class comments above)
		//contentReviewService.migrateLtiXml();
		
	}

}
