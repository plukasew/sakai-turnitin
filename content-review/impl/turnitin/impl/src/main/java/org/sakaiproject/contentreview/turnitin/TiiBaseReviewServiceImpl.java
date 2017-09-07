/**********************************************************************************
 * $URL: https://source.sakaiproject.org/contrib/turnitin/trunk/contentreview-impl/impl/src/java/org/sakaiproject/contentreview/impl/turnitin/TurnitinReviewServiceImpl.java $
 * $Id: TurnitinReviewServiceImpl.java 69345 2010-07-22 08:11:44Z david.horwitz@uct.ac.za $
 ***********************************************************************************
 *
 * Copyright (c) 2006 Sakai Foundation
 *
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.osedu.org/licenses/ECL-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 **********************************************************************************/
package org.sakaiproject.contentreview.turnitin;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.apache.commons.lang.StringUtils;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.exception.ConstraintViolationException;
import org.sakaiproject.content.api.ContentResource;
import org.sakaiproject.contentreview.dao.ContentReviewItemDao;
import org.sakaiproject.contentreview.exception.QueueException;
import org.sakaiproject.contentreview.exception.ReportException;
import org.sakaiproject.contentreview.exception.SubmissionException;
import org.sakaiproject.contentreview.model.ContentReviewActivityConfigEntry;
import org.sakaiproject.contentreview.dao.ContentReviewItem;
import org.sakaiproject.contentreview.service.ContentReviewService;
import org.sakaiproject.contentreview.service.ContentReviewQueueService;
import org.sakaiproject.contentreview.service.ContentReviewSiteAdvisor;
import org.sakaiproject.contentreview.dao.ContentReviewConstants;
import org.sakaiproject.genericdao.api.search.Order;
import org.sakaiproject.genericdao.api.search.Restriction;
import org.sakaiproject.genericdao.api.search.Search;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.tool.api.ToolManager;
import org.sakaiproject.user.api.UserDirectoryService;
import org.springframework.dao.DataIntegrityViolationException;
import org.sakaiproject.contentreview.turnitin.util.TurnitinAPIUtil;
import org.sakaiproject.assignment.api.AssignmentService;

import lombok.extern.slf4j.Slf4j;
import lombok.Setter;
import org.apache.commons.validator.EmailValidator;
import org.sakaiproject.api.common.edu.person.SakaiPerson;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.contentreview.exception.TransientSubmissionException;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.exception.TypeException;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserNotDefinedException;
import org.w3c.dom.CharacterData;
import org.w3c.dom.Document;
import org.w3c.dom.Element;


/**
 * This class fulfills the basic contract of ContentReviewService, implementing the full interface.
 * Methods not part of the CRS interface should be private or protected.
 * Turnitin-specific public API service methods are implemented in TurnitinReviewServiceImpl. 
 * 
 * @author plukasew
 */
@Slf4j
public class TiiBaseReviewServiceImpl implements ContentReviewService
{
	private String defaultAssignmentName = null;
	
	//note that the assignment id actually has to be unique globally so use this as a prefix
	// eg. assignid = defaultAssignId + siteId
	private String defaultAssignId = null;

	private static final Log log = LogFactory
			.getLog(TiiBaseReviewServiceImpl.class);

	private ContentReviewItemDao dao;

	public void setDao(ContentReviewItemDao dao) {
		this.dao = dao;
	}

	private ToolManager toolManager;

	public void setToolManager(ToolManager toolManager) {
		this.toolManager = toolManager;
	}

	private UserDirectoryService userDirectoryService;

	public void setUserDirectoryService(
			UserDirectoryService userDirectoryService) {
		this.userDirectoryService = userDirectoryService;
	}
	
	protected ContentReviewSiteAdvisor siteAdvisor;
	public void setSiteAdvisor(ContentReviewSiteAdvisor crsa) {
		this.siteAdvisor = crsa;
	}
	
	@Setter
	protected ContentReviewQueueService crqServ;
	
	@Setter
	private ServerConfigurationService serverConfigurationService;
	
	@Setter
	private TurnitinAccountConnection turnitinConn;
	
	@Setter
	private AssignmentService assignmentService;
	
	private boolean studentAccountNotified = true;
	private int sendSubmissionNotification = 0;
	private String defaultClassPassword = null;
	
	private Long maxRetry = null;
	public void setMaxRetry(Long maxRetry) {
		this.maxRetry = maxRetry;
	}
	
	/**
	 *  If set to true in properties, will result in 3 random digits being appended
	 *  to the email name. In other words, adrian.r.fish@gmail.com will become something
	 *  like adrian.r.fish593@gmail.com
	 */
	private boolean spoilEmailAddresses = false;

	/** Prefer system profile email addresses */
	private boolean preferSystemProfileEmail = true;

	/** Use guest account eids as email addresses */
	private boolean preferGuestEidEmail = true;
	
	// Spring init
	// TIITODO: wire this up as a Spring bean
	public void init()
	{
		studentAccountNotified = turnitinConn.isStudentAccountNotified();
		sendSubmissionNotification = turnitinConn.getSendSubmissionNotification();
		maxRetry = turnitinConn.getMaxRetry();
		defaultAssignId = turnitinConn.getDefaultAssignId();
		defaultClassPassword = turnitinConn.getDefaultClassPassword();
		
		spoilEmailAddresses = serverConfigurationService.getBoolean("turnitin.spoilEmailAddresses", false);
		preferSystemProfileEmail = serverConfigurationService.getBoolean("turnitin.preferSystemProfileEmail", true);
		preferGuestEidEmail = serverConfigurationService.getBoolean("turnitin.preferGuestEidEmail", true);
		
		log.info("init(): spoilEmailAddresses=" + spoilEmailAddresses + 
		          " preferSystemProfileEmail=" + preferSystemProfileEmail + 
		          " preferGuestEidEmail=" + preferGuestEidEmail);
	}
	
	@Override
	public void queueContent(String userId, String siteId, String taskId, List<ContentResource> content)
			throws QueueException {

		// TIITODO: this is the 13.x implementation. See the QueueException handling in the commented out
		// method below (the old 11.x LTI implementation) and decide if we should deal with the
		// multiple attachments + resubmission scenario here, or in the ContentReviewQueueService, or let it
		// bubble up to the caller and deal with it there
		
		log.debug("Method called queueContent()");

		if (content == null || content.isEmpty()) {
			return;
		}

		if (userId == null) {
			log.debug("Using current user");
			userId = userDirectoryService.getCurrentUser().getId();
		}

		if (siteId == null) {
			log.debug("Using current site");
			siteId = toolManager.getCurrentPlacement().getContext();
		}

		if (taskId == null) {
			log.debug("Generating default taskId");
			taskId = siteId + " " + "defaultAssignment";
		}

		log.debug("Adding content from site " + siteId + " and user: " + userId + " for task: " + taskId
				+ " to submission queue");
		crqServ.queueContent(getProviderId(), userId, siteId, taskId, content);
	}

	/*@Override
	public void queueContent(String userId, String siteId, String taskId, List<ContentResource> content, String submissionId, boolean isResubmission)
			throws QueueException {

		if (content == null || content.size() < 1) {
			return;
		}

		for (ContentResource contentRes : content)
		{
			try
			{
				queueContent(userId, siteId, taskId, contentRes.getId(), submissionId, isResubmission);
			}
			catch (QueueException qe)
			{
				// QueueException is thrown if this content item is already queued. This will be a problem for
				// a multiple attachments + resubmission scenario where a new file is added to an
				// already queued submission. Log but ignore the exception if this might be the case, and continue on to the 
				// next item. Otherwise, allow the exception to bubble up.
				if (!isResubmission || content.size() == 1)
				{
					throw qe;
				}
				
				log.info(String.format("Unable to queue content item %s for submission id %s (task: %s, site: %s). Error was: %s",
						contentRes.getId(), submissionId, taskId, siteId, qe.getMessage()));
			}
		}

	}*/

	/*public void queueContent(String userId, String siteId, String taskId, String contentId, String submissionId, boolean isResubmission)
		throws QueueException {
	
		log.debug("Method called queueContent(" + userId + "," + siteId + "," + contentId + ")");

		if (StringUtils.isBlank(userId))
		{
			throw new QueueException("Unable to queue content item " + contentId + ", a userId was not provided.");
		}

		if (siteId == null) {
			log.debug("Using current site");
			siteId = toolManager.getCurrentPlacement().getContext();
		}

		if (taskId == null) {
			log.debug("Generating default taskId");
			taskId = siteId + " " + defaultAssignmentName;
		}

		log.debug("Adding content: " + contentId + " from site " + siteId
					+ " and user: " + userId + " for task: " + taskId + " to submission queue");

		/*
		 * first check that this content has not been submitted before this may
		 * not be the best way to do this - perhaps use contentId as the primary
		 * key for now id is the primary key and so the database won't complain
		 * if we put in repeats necessitating the check
		 */

		/*List<ContentReviewItem> existingItems = getItemsByContentId(contentId);
		if (existingItems.size() > 0) {
			throw new QueueException("Content " + contentId + " is already queued, not re-queued");
		}
		ContentReviewItem item = new ContentReviewItem(userId, siteId, taskId, contentId, new Date(),
			ContentReviewItem.NOT_SUBMITTED_CODE);
		item.setNextRetryTime(new Date());
		item.setUrlAccessed(false);
		item.setSubmissionId(submissionId);
		if(isResubmission){
			item.setResubmission(true);
		}
		dao.save(item);
	}*/

	
	// TIITODO: the methods below can probably be replaced with calls to ContentReviewItemDao. Replace usages as required.
	/*protected List<ContentReviewItem> getItemsByContentId(String contentId) {
		Search search = new Search();
		search.addRestriction(new Restriction("contentId", contentId));
		List<ContentReviewItem> existingItems = dao.findBySearch(ContentReviewItem.class, search);
		return existingItems;
	}
	
	public ContentReviewItem getFirstItemByContentId(String contentId) {
		Search search = new Search();
		search.addRestriction(new Restriction("contentId", contentId));
		List<ContentReviewItem> existingItems = dao.findBySearch(ContentReviewItem.class, search);
		if (existingItems.isEmpty()) {
			log.debug("Content " + contentId + " has not been queued previously");
			return null;
		}

		if (existingItems.size() > 1){
			log.warn("More than one matching item - using first item found");
		}

		return existingItems.get(0);
	}
	
	public ContentReviewItem getFirstItemByExternalId(String externalId) {
		//due to the impossibility to get the right paper id from the turnitin callback
		//we need to get the paper id associated to the original submission
		Search search = new Search();
		search.addRestriction(new Restriction("externalId", externalId));
		search.addOrder(new Order("id", false));
		List<ContentReviewItem> existingItems = dao.findBySearch(ContentReviewItem.class, search);
		if (existingItems.isEmpty()) {
			log.debug("Content with paper id " + externalId + " has not been queued previously");
			return null;
		}

		if (existingItems.size() > 1){
			log.warn("More than one matching item - using first item found");
		}

		return existingItems.get(0);
	}
	
	public ContentReviewItem getItemById(String id) {
		Search search = new Search();
		search.addRestriction(new Restriction("id", Long.valueOf(id)));
		List<ContentReviewItem> existingItems = dao.findBySearch(ContentReviewItem.class, search);
		if (existingItems.isEmpty()) {
			log.debug("Content " + id + " has not been queued previously");
			return null;
		}

		if (existingItems.size() > 1){
			log.warn("More than one matching item - using first item found");
		}

		return existingItems.get(0);
	}*/
	
	@Override
	public int getReviewScore(String contentId, String taskId, String userId)
			throws QueueException, ReportException, Exception {
		log.debug("Getting review score for content: " + contentId);

		List<ContentReviewItem> matchingItems = dao.findByProviderAnyMatching(getProviderId(), contentId, null, null, null, null, null, null);
		if (matchingItems.isEmpty()) {
			log.debug("Content " + contentId + " has not been queued previously");
			throw new QueueException("Content " + contentId + " has not been queued previously");
		}

		if (matchingItems.size() > 1)
		{
			// TIITODO: probably not the best way to handle this. Should be db constraint on contentId
			// and then we can use dao.findByProviderAndContentId() to get a single result
			// see also similar check in getReviewReport()
			log.debug("More than one matching item - using first item found");
		}

		ContentReviewItem item = (ContentReviewItem) matchingItems.iterator().next();
		if (item.getStatus().compareTo(ContentReviewConstants.CONTENT_REVIEW_SUBMITTED_REPORT_AVAILABLE_CODE) != 0) {
			log.debug("Report not available: " + item.getStatus());
			throw new ReportException("Report not available: " + item.getStatus());
		}
		
		// TIITODO: in the 13.x implementation there is a bunch of grade syncing code here
		// if GradeMark is enabled. This is probably not the right place to sync grades, so
		// I've left it out. Grades should probably be sync'd at the point were the score
		// is written, not where it is read
		
		return item.getReviewScore();
	}
	
	@Override
	@Deprecated
	public String getReviewReport(String contentId, String assignmentRef, String userId) throws QueueException, ReportException
	{
		// TIITODO: this method is deprecated but is used by the LTI integration. This should be fixed so that the LTI integration
		// uses the Instructor/Student methods below
		
		log.debug("getReviewReport for LTI integration");
		//should have already checked lti integration on assignments tool
		Search search = new Search();
		search.addRestriction(new Restriction("contentId", contentId));
		List<ContentReviewItem> matchingItems = dao.findByProviderAnyMatching(getProviderId(), contentId, null, null, null, null, null, null);
		if (matchingItems.isEmpty()) {
			log.debug("Content " + contentId + " has not been queued previously");
			throw new QueueException("Content " + contentId + " has not been queued previously");
		}

		if (matchingItems.size() > 1)
		{
			// TIITODO: probably not the best way to handle this. Should be db constraint on contentId
			// and then we can use dao.findByProviderAndContentId() to get a single result
			// see also similar check in getReviewScore()
			log.debug("More than one matching item found - using first item found");
		}

		// check that the report is available
		ContentReviewItem item = (ContentReviewItem) matchingItems.iterator().next();
		if (item.getStatus().compareTo(ContentReviewConstants.CONTENT_REVIEW_SUBMITTED_REPORT_AVAILABLE_CODE) != 0) {
			log.debug("Report not available: " + item.getStatus());
			throw new ReportException("Report not available: " + item.getStatus());
		}
		
		return getLTIReportAccess(item);
	}
	
	/**
	 * This uses the default Instructor information or current user.
	 *
	 * @param contentId
	 * @param assignmentRef
	 * @param userId
	 * @return 
	 * @throws org.sakaiproject.contentreview.exception.QueueException
	 * @throws org.sakaiproject.contentreview.exception.ReportException
	 * @see org.sakaiproject.contentreview.impl.hbm.TiiBaseReviewServiceImpl#getReviewReportInstructor(java.lang.String)
	 */
	@Override
	public String getReviewReportInstructor(String contentId, String assignmentRef, String userId) throws QueueException, ReportException
	{
		List<ContentReviewItem> matchingItems = dao.findByProviderAnyMatching(getProviderId(), contentId, null, null, null, null, null, null);
		if (matchingItems.isEmpty()) {
			log.debug("Content " + contentId + " has not been queued previously");
			throw new QueueException("Content " + contentId + " has not been queued previously");
		}

		if (matchingItems.size() > 1)
		{
			// TIITODO: see note in getReviewReport()
			log.debug("More than one matching item found - using first item found");
		}

		// check that the report is available
		// TODO if the database record does not show report available check with
		// turnitin (maybe)

		ContentReviewItem item = (ContentReviewItem) matchingItems.iterator().next();
		if (item.getStatus().compareTo(ContentReviewConstants.CONTENT_REVIEW_SUBMITTED_REPORT_AVAILABLE_CODE) != 0) {
			log.debug("Report not available: " + item.getStatus());
			throw new ReportException("Report not available: " + item.getStatus());
		}

		// report is available - generate the URL to display

		String oid = item.getExternalId();
		String fid = "6";
		String fcmd = "1";
		String cid = item.getSiteId();
		String assignid = defaultAssignId + item.getSiteId();
		String utp = "2";

		Map params = TurnitinAPIUtil.packMap(turnitinConn.getBaseTIIOptions(),
				"fid", fid,
				"fcmd", fcmd,
				"assignid", assignid,
				"cid", cid,
				"oid", oid,
				"utp", utp
		);

		params.putAll(getInstructorInfo(item.getSiteId()));

		return turnitinConn.buildTurnitinURL(params);
	}

	@Override
	public String getReviewReportStudent(String contentId, String assignmentRef, String userId) throws QueueException, ReportException
	{
		List<ContentReviewItem> matchingItems = dao.findByProviderAnyMatching(getProviderId(), contentId, null, null, null, null, null, null);
		if (matchingItems.isEmpty()) {
			log.debug("Content " + contentId + " has not been queued previously");
			throw new QueueException("Content " + contentId + " has not been queued previously");
		}

		if (matchingItems.size() > 1)
		{
			// TIITODO: see note in getReviewReport()
			log.debug("More than one matching item found - using first item found");
		}

		// check that the report is available
		// TODO if the database record does not show report available check with
		// turnitin (maybe)

		ContentReviewItem item = (ContentReviewItem) matchingItems.iterator().next();
		if (item.getStatus().compareTo(ContentReviewConstants.CONTENT_REVIEW_SUBMITTED_REPORT_AVAILABLE_CODE) != 0) {
			log.debug("Report not available: " + item.getStatus());
			throw new ReportException("Report not available: " + item.getStatus());
		}


		// report is available - generate the URL to display

		String oid = item.getExternalId();
		String fid = "6";
		String fcmd = "1";
		String cid = item.getSiteId();
		String assignid = defaultAssignId + item.getSiteId();

		User user = userDirectoryService.getCurrentUser();

		//USe the method to get the correct email
		String uem = getEmail(user);
		String ufn = getUserFirstName(user);
		String uln = getUserLastName(user);
		String uid = item.getUserId();
		String utp = "1";

		Map params = TurnitinAPIUtil.packMap(turnitinConn.getBaseTIIOptions(),
				"fid", fid,
				"fcmd", fcmd,
				"assignid", assignid,
				"uid", uid,
				"cid", cid,
				"oid", oid,
				"uem", uem,
				"ufn", ufn,
				"uln", uln,
				"utp", utp
		);

		return turnitinConn.buildTurnitinURL(params);
	}
	
	@Override
	public Long getReviewStatus(String contentId) throws QueueException
	{
		/*log.debug("Returning review status for content: " + contentId);

		List<ContentReviewItem> matchingItems = getItemsByContentId(contentId);
		
		if (matchingItems.isEmpty()) {
			log.debug("Content " + contentId + " has not been queued previously");
			throw new QueueException("Content " + contentId + " has not been queued previously");
		}

		if (matchingItems.size() > 1)
			log.debug("more than one matching item found - using first item found");

		return ((ContentReviewItem) matchingItems.iterator().next()).getStatus();*/
		
		// TIITODO: below is the 13.x implementation of this method, delegating to the queue service
		// this is probably okay as long as we're certain multiple contentIds can't exist in the db,
		// as the queue service ignores this possibility. As elsewhere, we need to confirm this and
		// then we can clean up all these matchingItems.size() > 1 checks
		
		return crqServ.getReviewStatus(getProviderId(), contentId);
	}

	@Override
	public Date getDateQueued(String contentId)	throws QueueException
	{
		/*log.debug("Returning date queued for content: " + contentId);

		List<ContentReviewItem> matchingItems = getItemsByContentId(contentId);
		if (matchingItems.isEmpty()) {
			log.debug("Content " + contentId + " has not been queued previously");
			throw new QueueException("Content " + contentId + " has not been queued previously");
		}

		if (matchingItems.size() > 1)
			log.debug("more than one matching item found - using first item found");

		return ((ContentReviewItem) matchingItems.iterator().next()).getDateQueued();*/
		
		// TIITODO: see note in getReviewStatus() above
		
		return crqServ.getDateQueued(getProviderId(), contentId);
	}

	@Override
	public Date getDateSubmitted(String contentId) throws QueueException, SubmissionException
	{
		/*log.debug("Returning date queued for content: " + contentId);

		List<ContentReviewItem> matchingItems = getItemsByContentId(contentId);
		
		if (matchingItems.isEmpty()) {
			log.debug("Content " + contentId + " has not been queued previously");
			throw new QueueException("Content " + contentId + " has not been queued previously");
		}

		if (matchingItems.size() > 1)
			log.debug("more than one matching item found - using first item found");

		ContentReviewItem item = (ContentReviewItem) matchingItems.iterator().next();
		if (item.getDateSubmitted() == null) {
			log.debug("Content not yet submitted: " + item.getStatus());
			throw new SubmissionException("Content not yet submitted: " + item.getStatus());
		}

		return item.getDateSubmitted();*/
		
		// TIITODO: see note in getReviewStatus() above
		
		return crqServ.getDateSubmitted(getProviderId(), contentId);
	}
	
	@Override
	public void processQueue()
	{		
		log.info("Processing submission queue");
		int errors = 0;
		int success = 0;

		final int providerId = getProviderId();
		Optional<ContentReviewItem> nextItem;
		
		while ((nextItem = crqServ.getNextItemInQueueToSubmit(providerId)).isPresent())
		{
			ContentReviewItem currentItem = nextItem.get();
			log.debug("Attempting to submit content: " + currentItem.getContentId() + " for user: " + currentItem.getUserId() + " and site: " + currentItem.getSiteId());

			// Attempt to get the contentreview_item's associated assignment
			org.sakaiproject.assignment.api.model.Assignment a = null;
			try {
				a = assignmentService.getAssignment(currentItem.getTaskId());
			}
			catch (IdUnusedException e) {
				// If the assignment no longer exists, delete the contentreview_item and continue to next iteration
				log.warn("No assignment with ID = " + currentItem.getTaskId() + ", deleting contentreview_item", e);
				dao.delete(currentItem);
				continue;
			} catch (PermissionException e) {
				log.warn("No permission for assignment with ID = " + currentItem.getTaskId(), e);
			}

			// If associated assignment does not have content review enabled, delete the contentreview_item and continue to next iteration
			if (a != null && !a.getContentReview())
			{
				log.warn("Assignment with ID = " + currentItem.getTaskId() + " does not have content review enabled; deleting contentreview_item");
				dao.delete(currentItem);
				continue;
			}

			if (currentItem.getRetryCount() == null ) {
				currentItem.setRetryCount(0L);
				currentItem.setNextRetryTime(getNextRetryTime(0));
				dao.update(currentItem);
			} else if (currentItem.getRetryCount().intValue() > maxRetry) {
				processError( currentItem, ContentReviewItem.SUBMISSION_ERROR_RETRY_EXCEEDED, null, null );
				errors++;
				continue;
			} else {
				long l = currentItem.getRetryCount();
				l++;
				currentItem.setRetryCount(l);
				currentItem.setNextRetryTime(this.getNextRetryTime(l));
				dao.update(currentItem);
			}

			User user;

			try {
				user = userDirectoryService.getUser(currentItem.getUserId());
			} catch (UserNotDefinedException e1) {
				log.error("Submission attempt unsuccessful - User not found.", e1);
				processError(currentItem, ContentReviewItem.SUBMISSION_ERROR_NO_RETRY_CODE, null, null);
				errors++;
				continue;
			}


			String uem = getEmail(user);
			if (uem == null ){
				if( currentItem.getRetryCount() == 0 )
				{
					log.error("User: " + user.getEid() + " has no valid email");
				}
				processError( currentItem, ContentReviewItem.SUBMISSION_ERROR_USER_DETAILS_CODE, "no valid email", null );
				errors++;
				continue;
			}

			String ufn = getUserFirstName(user);
			if (ufn == null || ufn.equals("")) {
				if( currentItem.getRetryCount() == 0 )
				{
					log.error("Submission attempt unsuccessful - User has no first name");
				}
				processError(currentItem, ContentReviewItem.SUBMISSION_ERROR_USER_DETAILS_CODE, "has no first name", null);
				errors++;
				continue;
			}

			String uln = getUserLastName(user);
			if (uln == null || uln.equals("")) {
				if( currentItem.getRetryCount() == 0 )
				{
					log.error("Submission attempt unsuccessful - User has no last name");
				}
				processError(currentItem, ContentReviewItem.SUBMISSION_ERROR_USER_DETAILS_CODE, "has no last name", null);
				errors++;
				continue;
			}
			
			Site s;
			try {
				s = siteService.getSite(currentItem.getSiteId());
			}
			catch (IdUnusedException iue) {
				log.error("processQueue: Site " + currentItem.getSiteId() + " not found!" + iue.getMessage());
				processError(currentItem, ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE, "site not found", null);
				errors++;
				continue;
			}
			
			//to get the name of the initial submited file we need the title
			ContentResource resource;
			ResourceProperties resourceProperties;
			String fileName;
			try {
				try {
					resource = contentHostingService.getResource(currentItem.getContentId());
				} catch (IdUnusedException e4) {
					// Remove this item
					log.warn("IdUnusedException: no resource with id " + currentItem.getContentId());
					dao.delete(currentItem);
					releaseLock(currentItem);
					errors++;
					continue;
				}
				resourceProperties = resource.getProperties();
				fileName = resourceProperties.getProperty(resourceProperties.getNamePropDisplayName());
				fileName = escapeFileName(fileName, resource.getId());
			}
			catch (PermissionException e2) {
				log.error("Submission failed due to permission error.", e2);
				processError(currentItem, ContentReviewItem.SUBMISSION_ERROR_NO_RETRY_CODE, "permission exception", null);
				errors++;
				continue;
			}
			catch (TypeException e) {
				log.error("Submission failed due to content Type error.", e);
				processError(currentItem, ContentReviewItem.SUBMISSION_ERROR_NO_RETRY_CODE, "Type Exception: " + e.getMessage(), null);
				errors++;
				continue;
			}

			//TII-97 filenames can't be longer than 200 chars
			if (fileName != null && fileName.length() >=200 ) {
				fileName = truncateFileName(fileName, 198);
			}
		
			//////////////////////////////  NEW LTI INTEGRATION  ///////////////////////////////
			Optional<Date> dateOpt = getAssignmentCreationDate(currentItem.getTaskId());
			if(dateOpt.isPresent() && siteAdvisor.siteCanUseLTIReviewServiceForAssignment(s, dateOpt.get()) && currentItem.getSubmissionId()!=null){
				
				Map<String,String> ltiProps = new HashMap<>();
				ltiProps.put("context_id", currentItem.getSiteId());
				ltiProps.put("resource_link_id", currentItem.getTaskId());
				ltiProps.put("roles", "Learner");
				//student
				ltiProps.put("lis_person_name_family", uln);
				ltiProps.put("lis_person_contact_email_primary", uem);
				ltiProps.put("lis_person_name_full", ufn + " " + uln);
				ltiProps.put("lis_person_name_given", ufn);
				ltiProps.put("user_id", currentItem.getUserId());

				String[] parts = currentItem.getTaskId().split("/");
				log.debug(parts[parts.length -1] + " " + parts.length);
				String httpAccess = serverConfigurationService.getServerUrl() + "/access/assignment/s/" + currentItem.getSiteId() + "/" + parts[parts.length -1] + "/" + currentItem.getSubmissionId();
				httpAccess += ":" + currentItem.getId() + ":" + currentItem.getContentId().hashCode();
				log.debug("httpAccess url: " + httpAccess);//debug
				ltiProps.put("submission_url", httpAccess);
				ltiProps.put("submission_title", fileName);
				// must have an extension or they can't process it
				if (fileName.equals("Inline_Submission")) {
					fileName = "Inline_Submission.html";
				}
				ltiProps.put("submission_filename", fileName);
				ltiProps.put("ext_outcomes_tool_placement_url", serverConfigurationService.getServerUrl() + "/sakai-contentreview-tool-tii/submission-servlet");
				ltiProps.put("lis_outcome_service_url", serverConfigurationService.getServerUrl() + "/sakai-contentreview-tool-tii/grading-servlet");
				ltiProps.put("lis_result_sourcedid", currentItem.getContentId());
				ltiProps.put("xmlresponse","1");//mandatatory

				String tiiId = "";
				if (a != null)
				{
					tiiId = getActivityConfigValue(TurnitinConstants.TURNITIN_ASN_ID, a.getId(), TurnitinConstants.SAKAI_ASSIGNMENT_TOOL_ID,
						TurnitinConstants.PROVIDER_ID);
				}

				if(tiiId.isEmpty()){
					log.error("Could not find tiiId for assignment: " + currentItem.getTaskId());
					processError(currentItem, ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE, "Could not find tiiId", null);
					errors++;
					continue;
				}
				
				TurnitinReturnValue result = new TurnitinReturnValue();
				result.setResult( -1 );
				boolean isResubmission = false;
				if(currentItem.isResubmission())
				{
					AssignmentContent ac = a.getContent();
					// Resubmit only for submission types that allow only one file per submission
					String tiiPaperId = currentItem.getExternalId();
					// 1 - inline, 2 - attach, 3 - both, 4 - non elec, 5 - single file
					int type = ac.getTypeOfSubmission();
					if (tiiPaperId != null && (type == 1 || type == 5))
					{
						isResubmission = true;
						result = tiiUtil.makeLTIcall(TurnitinLTIUtil.RESUBMIT, tiiPaperId, ltiProps);
					}
				}
				if (!isResubmission)
				{
					result = tiiUtil.makeLTIcall(TurnitinLTIUtil.SUBMIT, tiiId, ltiProps);
				}

				if(result.getResult() >= 0){
					log.debug("LTI submission successful");
					//problems overriding this on callback
					//currentItem.setExternalId(externalId);
					currentItem.setStatus(ContentReviewItem.SUBMITTED_AWAITING_REPORT_CODE);
					currentItem.setRetryCount(Long.valueOf(0));
					currentItem.setLastError(null);
					currentItem.setErrorCode(null);
					currentItem.setDateSubmitted(new Date());
					success++;
					dao.update(currentItem);
					releaseLock(currentItem);
				} else {
					Long errorCode = ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE;
					// TII-242 - evaluate result.getErrorMessage() to prevent unnecessary retries if the error is terminal
					if (terminalQueueErrors.contains(result.getErrorMessage()))
					{
						errorCode = ContentReviewItem.SUBMISSION_ERROR_NO_RETRY_CODE;
					}
					else
					{
						long l = currentItem.getRetryCount();
						l++;
						currentItem.setRetryCount(l);
						currentItem.setNextRetryTime(this.getNextRetryTime(l));
					}
					processError( currentItem, errorCode, "Submission Error: " + result.getErrorMessage(), null );
					errors++;
				}

				continue;
			}
			
			//////////////////////////////  OLD API INTEGRATION  ///////////////////////////////

			if (!turnitinConn.isUseSourceParameter()) {
				try {
					createClass(currentItem.getSiteId());
				} catch (SubmissionException t) {
					log.error ("Submission attempt unsuccessful: Could not create class", t);
					processError( currentItem, ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE, "Class creation error: " + t.getMessage(), null );
					errors++;
					continue;
				} catch (TransientSubmissionException tse) {
					processError( currentItem, ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE, "Class creation error: " + tse.getMessage(), null );
					errors++;
					continue;
				}
			}

			try {
				enrollInClass(currentItem.getUserId(), uem, currentItem.getSiteId());
			} catch (Exception t) {
				log.error("Submission attempt unsuccessful: Could not enroll user in class", t);

				Long status;
				String error;
				if (t.getClass() == IOException.class) {
					error = "Enrolment error: " + t.getMessage();
					status = ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE;
				} else {
					error = "Enrolment error: " + t.getMessage();
					status = ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE;
				}

				processError( currentItem, status, error, null );
				errors++;
				continue;
			}

			if (!turnitinConn.isUseSourceParameter()) {
				try {
					Map tiiresult = this.getAssignment(currentItem.getSiteId(), currentItem.getTaskId());
					if (tiiresult.get("rcode") != null && !tiiresult.get("rcode").equals("85")) {
						createAssignment(currentItem.getSiteId(), currentItem.getTaskId());
					}
				} catch (SubmissionException se) {
					processError( currentItem, ContentReviewItem.SUBMISSION_ERROR_NO_RETRY_CODE, "Assignment creation error: " + se.getMessage(), se.getErrorCode() );
					errors++;
					continue;
				} catch (TransientSubmissionException tse) {
					if (tse.getErrorCode() != null) {
						currentItem.setErrorCode(tse.getErrorCode());
					}

					processError( currentItem, ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE, "Assignment creation error: " + tse.getMessage(), null );
					errors++;
					continue;

				}
			}

			//get all the info for the api call
			//we do this before connecting so that if there is a problem we can jump out - saves time
			//these errors should probably be caught when a student is enrolled in a class
			//but we check again here to be sure

			String fcmd = "2";
			String fid = "5";

			String userEid = currentItem.getUserId();
			try {
				userEid = userDirectoryService.getUserEid(currentItem.getUserId());
			}
			catch (UserNotDefinedException unde) {
				//nothing realy to do?
			}

			String ptl =  userEid  + ":" + fileName;
			String ptype = "2";

			String uid = currentItem.getUserId();
			String cid = currentItem.getSiteId();
			String assignid = currentItem.getTaskId();

			// TODO ONC-1292 How to get this, and is it still required with src=9?
			String tem = getTEM(cid);

			String utp = "1";

			log.debug("Using Emails: tem: " + tem + " uem: " + uem);

			String assign = getAssignmentTitle(currentItem.getTaskId());
			String ctl = currentItem.getSiteId();

			Map params = TurnitinAPIUtil.packMap( turnitinConn.getBaseTIIOptions(),
					"assignid", assignid,
					"uid", uid,
					"cid", cid,
					"assign", assign,
					"ctl", ctl,
					"dis", sendSubmissionNotification > 0 ? "0" : "1", // dis=1 means disable sending email
					"fcmd", fcmd,
					"fid", fid,
					"ptype", ptype,
					"ptl", ptl,
					"tem", tem,
					"uem", uem,
					"ufn", ufn,
					"uln", uln,
					"utp", utp,
					"resource_obj", resource
			);

			Document document;
			try {
				document = turnitinConn.callTurnitinReturnDocument(params, true);
			}
			catch (TransientSubmissionException | SubmissionException e) {
				processError( currentItem, ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE, "Error Submitting Assignment for Submission: " + e.getMessage() + ". Assume unsuccessful", null );
				errors++;
				continue;
			}

			Element root = document.getDocumentElement();

			String rMessage = ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData();
			String rCode = ((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData();

			if (rCode == null)
			{
				rCode = "";
			}
			else
			{
				rCode = rCode.trim();
			}

			if (rMessage == null)
			{
				rMessage = rCode;
			}
			else
			{
				rMessage = rMessage.trim();
			}

			if (rCode.compareTo("51") == 0) {
				String externalId = ((CharacterData) (root.getElementsByTagName("objectID").item(0).getFirstChild())).getData().trim();
				if (externalId != null && externalId.length() >0 ) {
					log.debug("Submission successful");
					currentItem.setExternalId(externalId);
					currentItem.setStatus(ContentReviewItem.SUBMITTED_AWAITING_REPORT_CODE);
					currentItem.setRetryCount(Long.valueOf(0));
					currentItem.setLastError(null);
					currentItem.setErrorCode(null);
					currentItem.setDateSubmitted(new Date());
					success++;
					dao.update(currentItem);
					dao.updateExternalId(currentItem.getContentId(), externalId);
					releaseLock( currentItem );
				} else {
					log.warn("invalid external id");
					processError( currentItem, ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE, "Submission error: no external id received", null );
					errors++;
				}
			} else {
				log.debug("Submission not successful: " + ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData().trim());

				Long status;
				if (rMessage.equals("User password does not match user email")
						|| "1001".equals(rCode) || "".equals(rMessage) || "413".equals(rCode) || "1025".equals(rCode) || "250".equals(rCode)) {
					status = ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE;
					log.warn("Submission not successful. It will be retried.");
				} else if (rCode.equals("423")) {
					status = ContentReviewItem.SUBMISSION_ERROR_USER_DETAILS_CODE;
				} else if (rCode.equals("301")) {
					//this took a long time
					log.warn("Submission not successful due to timeout. It will be retried.");
					status = ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE;
					Calendar cal = Calendar.getInstance();
					cal.set(Calendar.HOUR_OF_DAY, 22);
					currentItem.setNextRetryTime(cal.getTime());
				}else {
					log.error("Submission not successful. It will NOT be retried.");
					status = ContentReviewItem.SUBMISSION_ERROR_NO_RETRY_CODE;
				}

				processError( currentItem, status, "Submission Error: " + rMessage + "(" + rCode + ")", Integer.valueOf(rCode) );
				errors++;
			}
		}

		log.info("Submission queue run completed: " + success + " items submitted, " + errors + " errors.");
	}
	


	public List<ContentReviewItem> getReportList(String siteId, String taskId) {
		log.debug("Returning list of reports for site: " + siteId + ", task: " + taskId);
		Search search = new Search();
		//TII-99 siteId can be null
		if (siteId != null) {
			search.addRestriction(new Restriction("siteId", siteId));
		}
		search.addRestriction(new Restriction("taskId", taskId));
		search.addRestriction(new Restriction("status", ContentReviewItem.SUBMITTED_REPORT_AVAILABLE_CODE));
		
		List<ContentReviewItem> existingItems = dao.findBySearch(ContentReviewItem.class, search);
		
		
		return existingItems;
	}
	
	public List<ContentReviewItem> getAllContentReviewItems(String siteId, String taskId) {
            log.debug("Returning list of reports for site: " + siteId + ", task: " + taskId);
            Search search = new Search();
            //TII-99 siteId can be null
            if (siteId != null) {
                    search.addRestriction(new Restriction("siteId", siteId));
            }
            search.addRestriction(new Restriction("taskId", taskId));
            
            List<ContentReviewItem> existingItems = dao.findBySearch(ContentReviewItem.class, search);
            
            
            return existingItems;
    }
	
	public List<ContentReviewItem> getReportList(String siteId) {
		log.debug("Returning list of reports for site: " + siteId);
		
		Search search = new Search();
		search.addRestriction(new Restriction("siteId", siteId));
		search.addRestriction(new Restriction("status", ContentReviewItem.SUBMITTED_REPORT_AVAILABLE_CODE));
		
		return dao.findBySearch(ContentReviewItem.class, search);
	}
	

	
	public void resetUserDetailsLockedItems(String userId) {
		Search search = new Search();
		search.addRestriction(new Restriction("userId", userId));
		search.addRestriction(new Restriction("status", ContentReviewItem.SUBMISSION_ERROR_USER_DETAILS_CODE));
		
		
		List<ContentReviewItem> lockedItems = dao.findBySearch(ContentReviewItem.class, search);
		for (int i =0; i < lockedItems.size();i++) {
			ContentReviewItem thisItem = (ContentReviewItem) lockedItems.get(i);
			thisItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE);
			dao.update(thisItem);
		}
	}
	

	public void removeFromQueue(String ContentId) {
		List<ContentReviewItem> object = getItemsByContentId(ContentId);
		dao.delete(object);
		
		
	}

	public boolean updateItemAccess(String contentId){
		return dao.updateIsUrlAccessed( contentId, true );
	}

	public boolean updateExternalId(String contentId, String externalId)
	{
		return dao.updateExternalId(contentId, externalId);
	}
		
	public boolean updateExternalGrade(String contentId, String score){
		ContentReviewItem cri = getFirstItemByContentId(contentId);
		if(cri != null){
			cri.setExternalGrade(score);
			dao.update(cri);
			return true;
		}
		return false;
	}
	
	public String getExternalGradeForContentId(String contentId){
		ContentReviewItem cri = getFirstItemByContentId(contentId);
		if(cri != null){
			return cri.getExternalGrade();
		}
		return null;
	}

	public boolean allowResubmission() {
		return true;
	}

	public boolean isSiteAcceptable(Site s) {
		throw new UnsupportedOperationException("Not implemented");
	}

	public void checkForReports() {
		// TODO Auto-generated method stub
		
	}

	public String getIconUrlforScore(Long score) {
		// TODO Auto-generated method stub
		return null;
	}


	
	public boolean isAcceptableSize(ContentResource resource) {
		throw new UnsupportedOperationException("Not implemented");
	}

	public String getServiceName() {
		// TODO Auto-generated method stub
		return null;
	}

	public boolean isAcceptableContent(ContentResource resource) {
		throw new UnsupportedOperationException("This is not yet implemented");
	}

	public void processQueue() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public String getActivityConfigValue(String name, String activityId, String toolId, int providerId)
	{
		return getActivityConfigEntry(name, activityId, toolId, providerId)
				.map(e -> StringUtils.trimToEmpty(e.getValue())).orElse("");

	}
	
	private Optional<ContentReviewActivityConfigEntry> getActivityConfigEntry(String name, String activityId, String toolId, int providerId)
	{
		Search search = new Search();
		search.addRestriction(new Restriction("name", name));
		search.addRestriction(new Restriction("activityId", activityId));
		search.addRestriction(new Restriction("toolId", toolId));
		search.addRestriction(new Restriction("providerId", providerId));
		return Optional.ofNullable(dao.findOneBySearch(ContentReviewActivityConfigEntry.class, search));
	}

	@Override
	public boolean saveOrUpdateActivityConfigEntry(String name, String value, String activityId, String toolId, int providerId, boolean overrideIfSet)
	{
		if (StringUtils.isBlank(name) || StringUtils.isBlank(value) || StringUtils.isBlank(activityId) || StringUtils.isBlank(toolId))
		{
			return false;
		}
		
		Optional<ContentReviewActivityConfigEntry> optEntry = getActivityConfigEntry(name, activityId, toolId, providerId);
		if (!optEntry.isPresent())
		{
			try
			{
				dao.create(new ContentReviewActivityConfigEntry(name, value, activityId, toolId, providerId));
				return true;
			}
			catch (DataIntegrityViolationException | ConstraintViolationException e)
			{
				// there is a uniqueness constraint on entry keys in the database
				// a row with the same key was written after we checked, retrieve new data and continue
				optEntry = getActivityConfigEntry(name, activityId, toolId, providerId);
			}
		}

		if (overrideIfSet)
		{
			ContentReviewActivityConfigEntry entry = optEntry.orElseThrow( () -> new RuntimeException("Unique constraint violated during insert attempt, yet unable to retrieve row."));
			entry.setValue(value);
			dao.update(entry);
			return true;
		}

		return false;
	}
	
	/* ------------------------------ PRIVATE / PROTECTED only below this line ------------------------------ */
	// TIITODO: separate the legacy and LTI methods, move legacy methods to delegate class for easy removal later
	
	
	protected String getLTIReportAccess(ContentReviewItem item)
	{
		String ltiReportsUrl = null;
		String contentId = item.getContentId();
		String assignmentId = item.getTaskId();
		String siteId = item.getSiteId();
		try
		{
			String ltiReportsId = siteService.getSite(siteId).getProperties().getProperty("turnitin_reports_lti_id");
			String ltiResourceId = item.getExternalId();
			if (ltiResourceId == null)
			{
				// Fallback: link to assignment
				return getLTIAccess(assignmentId, siteId);
			}
			ltiReportsUrl = "/access/basiclti/site/" + siteId + "/content:" + ltiReportsId + ",resource:" + ltiResourceId;
			log.debug("getLTIRepotAccess: " + ltiReportsUrl);
		}
		catch (Exception e)
		{
			log.warn("Exception while trying to get LTI Reports access for assignment "  + assignmentId + ", resource " + contentId + ", and site " + siteId + ": " + e.getMessage());
		}
		return ltiReportsUrl;
	}
	
	protected String getLTIAccess(String taskId, String contextId){
		String ltiUrl = null;
		try{
			String ltiId = getActivityConfigValue(TurnitinConstants.STEALTHED_LTI_ID, asnRefToId(taskId), TurnitinConstants.SAKAI_ASSIGNMENT_TOOL_ID,
					TurnitinConstants.PROVIDER_ID);
			ltiUrl = "/access/basiclti/site/" + contextId + "/content:" + ltiId;
			log.debug("getLTIAccess: " + ltiUrl);
		} catch(Exception e) {
			log.error( "Unexpected exception getting LTI access", e );
		}
		return ltiUrl;
	}
	
		/**
	 * This will return a map of the information for the instructor such as
	 * uem, username, ufn, etc. If the system is configured to use src9
	 * provisioning, this will draw information from the current thread based
	 * user. Otherwise it will use the default Instructor information that has
	 * been configured for the system.
	 *
	 * @param siteId
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private Map getInstructorInfo(String siteId)
	{
		log.debug("Getting instructor info for site " + siteId);

		Map togo = new HashMap();
		if (!turnitinConn.isUseSourceParameter()) {
			togo.put("uem", turnitinConn.getDefaultInstructorEmail());
			togo.put("ufn", turnitinConn.getDefaultInstructorFName());
			togo.put("uln", turnitinConn.getDefaultInstructorLName());
			togo.put("uid", turnitinConn.getDefaultInstructorId());
		}
		else {
			String INST_ROLE = "section.role.instructor";
			User inst = null;
			try {
				Site site = siteService.getSite(siteId);
				User user = userDirectoryService.getCurrentUser();
	
				log.debug("Current user: " + user.getId());

				if (site.isAllowed(user.getId(), INST_ROLE)) {
					inst = user;
				}
				else {
					Set<String> instIds = getActiveInstructorIds(INST_ROLE,
							site);
					if (instIds.size() > 0) {
						inst = userDirectoryService.getUser((String) instIds.toArray()[0]);
					}
				}
			} catch (IdUnusedException e) {
				log.error("Unable to fetch site in getAbsoluteInstructorInfo: " + siteId, e);
			} catch (UserNotDefinedException e) {
				log.error("Unable to fetch user in getAbsoluteInstructorInfo", e);
			}


			if (inst == null) {
				log.error("Instructor is null in getAbsoluteInstructorInfo");
			}
			else {
				togo.put("uem", getEmail(inst));
				togo.put("ufn", inst.getFirstName());
				togo.put("uln", inst.getLastName());
				togo.put("uid", inst.getId());
				togo.put("username", inst.getDisplayName());
			}
		}

		return togo;
	}
	
	// TIITODO: move these email/firstname/lastname methods into a delegate class
	
	// returns null if no valid email exists
	private String getEmail(User user)
	{
		String uem = null;

		// Check account email address
		String account_email = null;

		if (isValidEmail(user.getEmail())) {
			account_email = user.getEmail().trim();
		}

		// Lookup system profile email address if necessary
		String profile_email = null;
		if (account_email == null || preferSystemProfileEmail) {
			SakaiPerson sp = sakaiPersonManager.getSakaiPerson(user.getId(), sakaiPersonManager.getSystemMutableType());
			if (sp != null && isValidEmail(sp.getMail())) {
				profile_email = sp.getMail().trim();
			}
		}

		// Check guest accounts and use eid as the email if preferred
		if (this.preferGuestEidEmail && isValidEmail(user.getEid())) {
			uem = user.getEid();
		}

		if (uem == null && preferSystemProfileEmail && profile_email != null) {
			uem = profile_email;
		}

		if (uem == null && account_email != null) {
			uem = account_email;
		}

		// Randomize the email address if preferred
		if (spoilEmailAddresses && uem != null) {
			// Scramble it
			String[] parts = uem.split("@");

			String emailName = parts[0];

			Random random = new Random();
			int int1 = random.nextInt();
			int int2 = random.nextInt();
			int int3 = random.nextInt();

			emailName += (int1 + int2 + int3);

			uem = emailName + "@" + parts[1];

			if (log.isDebugEnabled()) {
				log.debug("SCRAMBLED EMAIL:" + uem);
			}
		}

		log.debug("Using email " + uem + " for user eid " + user.getEid() + " id " + user.getId());
		return uem;
	}

	/**
	 * Is this a valid email the service will recognize
	 * @param email
	 * @return
	 */
	private boolean isValidEmail(String email) {

		// TODO: Use a generic Sakai utility class (when a suitable one exists)

		if (email == null || email.equals(""))
		{
			return false;
		}

		email = email.trim();
		//must contain @
		if (!email.contains( "@" ))
		{
			return false;
		}

		//an email can't contain spaces
		if (email.indexOf(" ") > 0)
		{
			return false;
		}

		//use commons-validator
		EmailValidator validator = EmailValidator.getInstance();
		return validator.isValid(email);
	}
	
	/**
	 * Gets a first name for a user or generates an initial from the eid
	 * @param user a sakai user
	 * @return the first name or at least an initial if possible, "X" if no fn can be made
	 */
	private String getUserFirstName(User user) {
		String ufn = user.getFirstName().trim();
		if (ufn == null || ufn.equals("")) {
			boolean genFN = (boolean) serverConfigurationService.getBoolean("turnitin.generate.first.name", true);
			if (genFN) {
				String eid = user.getEid();
				if (eid != null
						&& eid.length() > 0) {
					ufn = eid.substring(0,1);
				} else {
					ufn = "X";
				}
			}
		}
		return ufn;
	}
	
	/**
	 * Get user last Name. If turnitin.generate.last.name is set to true last name is
	 * anonamised
	 * @param user
	 * @return
	 */
	private String getUserLastName(User user){
		String uln = user.getLastName().trim();
		if (uln == null || uln.equals("")) {
			boolean genLN = serverConfigurationService.getBoolean("turnitin.generate.last.name", false);
			if (genLN) {
				String eid = user.getEid();
				if (eid != null 
						&& eid.length() > 0) {
					uln = eid.substring(0,1);        
				} else {
					uln = "X";
				}
			}
		}
		return uln;
	}
	
	/**
	 * find the next time this item should be tried
	 * @param retryCount
	 * @return
	 */
	private Date getNextRetryTime(long retryCount) {
		int offset =5;

		if (retryCount > 9 && retryCount < 20) {

			offset = 10;

		} else if (retryCount > 19 && retryCount < 30) {
			offset = 20;
		} else if (retryCount > 29 && retryCount < 40) {
			offset = 40;
		} else if (retryCount > 39 && retryCount < 50) {
			offset = 80;
		} else if (retryCount > 49 && retryCount < 60) {
			offset = 160;
		} else if (retryCount > 59) {
			offset = 220;
		}

		Calendar cal = Calendar.getInstance();
		cal.add(Calendar.MINUTE, offset);
		return cal.getTime();
	}
}
