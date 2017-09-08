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
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.net.URLDecoder;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
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
import org.sakaiproject.contentreview.turnitin.TurnitinConstants;
import org.sakaiproject.genericdao.api.search.Order;
import org.sakaiproject.genericdao.api.search.Restriction;
import org.sakaiproject.genericdao.api.search.Search;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.tool.api.ToolManager;
import org.sakaiproject.user.api.UserDirectoryService;
import org.springframework.dao.DataIntegrityViolationException;
import org.sakaiproject.contentreview.turnitin.util.TurnitinAPIUtil;
import org.sakaiproject.contentreview.turnitin.util.TurnitinReturnValue;
import org.sakaiproject.contentreview.turnitin.util.TurnitinLTIUtil;
import org.sakaiproject.assignment.api.AssignmentService;

import lombok.extern.slf4j.Slf4j;
import lombok.Setter;
import org.apache.commons.validator.EmailValidator;
import org.sakaiproject.api.common.edu.person.SakaiPerson;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.content.api.ContentHostingService;
import org.sakaiproject.contentreview.exception.TransientSubmissionException;
import static org.sakaiproject.contentreview.turnitin.TurnitinReviewServiceImpl.TURNITIN_DATETIME_FORMAT;
import org.sakaiproject.entity.api.Entity;
import org.sakaiproject.entity.api.EntityProducer;
import org.sakaiproject.entity.api.Reference;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.exception.TypeException;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserNotDefinedException;
import org.sakaiproject.util.ResourceLoader;
import org.w3c.dom.CharacterData;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;


// TIITODO: there is a lot going on in this class. If it makes sense, consider splitting up into two delegate classes,
// one for dealing with the queue and one for dealing with the reports. Common methods can be left in this class.
// Further modularization with delegate classes dedicated to specific purposes should also be considered.

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
	
	@Setter
	private ContentHostingService contentHostingService;
	
	@Setter
	private SiteService siteService;
	
	@Setter
	private TurnitinLTIUtil tiiUtil;
	
	@Setter	
	private TurnitinContentValidator turnitinContentValidator;
	
	private boolean studentAccountNotified = true;
	private int sendSubmissionNotification = 0;
	private String defaultClassPassword = null;
	
	private Long maxRetry = null;
	public void setMaxRetry(Long maxRetry) {
		this.maxRetry = maxRetry;
	}
	
	// These are error messages from turnitin for which the item should never be retried,
	// because it will never recover (Eg. less than 20 words, invalid file, etc.)
	private Set<String> terminalQueueErrors;
	
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
	// TIITODO: wire this up as a Spring bean? Or is wiring up the subclass TurnitinReviewServiceImpl enough?
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
		
		String[] strTerminalQueueErrors = serverConfigurationService.getStrings("turnitin.terminalQueueErrors");
		if (strTerminalQueueErrors == null)
		{
			strTerminalQueueErrors = TurnitinConstants.DEFAULT_TERMINAL_QUEUE_ERRORS;
		}
		terminalQueueErrors = new HashSet<>(strTerminalQueueErrors.length);
		Collections.addAll(terminalQueueErrors, strTerminalQueueErrors);
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
				crqServ.update(currentItem);
			} else if (currentItem.getRetryCount().intValue() > maxRetry) {
				processError( currentItem, ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_RETRY_EXCEEDED_CODE, null, null );
				errors++;
				continue;
			} else {
				long l = currentItem.getRetryCount();
				l++;
				currentItem.setRetryCount(l);
				currentItem.setNextRetryTime(this.getNextRetryTime(l));
				crqServ.update(currentItem);
			}

			User user;

			try {
				user = userDirectoryService.getUser(currentItem.getUserId());
			} catch (UserNotDefinedException e1) {
				log.error("Submission attempt unsuccessful - User not found.", e1);
				processError(currentItem, ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_NO_RETRY_CODE, null, null);
				errors++;
				continue;
			}


			String uem = getEmail(user);
			if (uem == null ){
				if( currentItem.getRetryCount() == 0 )
				{
					log.error("User: " + user.getEid() + " has no valid email");
				}
				processError( currentItem, ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_USER_DETAILS_CODE, "no valid email", null );
				errors++;
				continue;
			}

			String ufn = getUserFirstName(user);
			if (ufn == null || ufn.equals("")) {
				if( currentItem.getRetryCount() == 0 )
				{
					log.error("Submission attempt unsuccessful - User has no first name");
				}
				processError(currentItem, ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_USER_DETAILS_CODE, "has no first name", null);
				errors++;
				continue;
			}

			String uln = getUserLastName(user);
			if (uln == null || uln.equals("")) {
				if( currentItem.getRetryCount() == 0 )
				{
					log.error("Submission attempt unsuccessful - User has no last name");
				}
				processError(currentItem, ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_USER_DETAILS_CODE, "has no last name", null);
				errors++;
				continue;
			}
			
			Site currentSite;
			try {
				currentSite = siteService.getSite(currentItem.getSiteId());
			}
			catch (IdUnusedException iue) {
				log.error("processQueue: Site " + currentItem.getSiteId() + " not found!" + iue.getMessage());
				processError(currentItem, ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_RETRY_CODE, "site not found", null);
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
					crqServ.delete(currentItem);
					errors++;
					continue;
				}
				resourceProperties = resource.getProperties();
				fileName = resourceProperties.getProperty(resourceProperties.getNamePropDisplayName());
				fileName = escapeFileName(fileName, resource.getId());
			}
			catch (PermissionException e2) {
				log.error("Submission failed due to permission error.", e2);
				processError(currentItem, ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_NO_RETRY_CODE, "permission exception", null);
				errors++;
				continue;
			}
			catch (TypeException e) {
				log.error("Submission failed due to content Type error.", e);
				processError(currentItem, ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_NO_RETRY_CODE, "Type Exception: " + e.getMessage(), null);
				errors++;
				continue;
			}

			//TII-97 filenames can't be longer than 200 chars
			if (fileName != null && fileName.length() >=200 ) {
				fileName = truncateFileName(fileName, 198);
			}
		
			//////////////////////////////  NEW LTI INTEGRATION  ///////////////////////////////
			Optional<Date> dateOpt = getAssignmentCreationDate(currentItem.getTaskId());
			
			// TIITODO: this code uses the submissionId and resubmission properties that were added to contentreviewitem for LTI support
			// Determine if these properties make sense for general purpose content review items, or if they are specific to the Turnitin
			// LTI implementation and belong in a separate table (with the isUrlAccessed property)
			// for now, references to these properties are commented out
			
			if(dateOpt.isPresent() && siteAdvisor.siteCanUseLTIReviewServiceForAssignment(currentSite, dateOpt.get()) /* TIITODO: && currentItem.getSubmissionId()!=null*/){
				
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
				String httpAccess = serverConfigurationService.getServerUrl() + "/access/assignment/s/" + currentItem.getSiteId() + "/" 
						+ parts[parts.length -1] + "/"/* TIITODO: + currentItem.getSubmissionId()*/;
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
					// TIITODO: re-evaluate the activity config table, consider a dedicated, properly-typed table just for Turnitin to store these values
					tiiId = getActivityConfigValue(TurnitinConstants.TURNITIN_ASN_ID, a.getId(), TurnitinConstants.SAKAI_ASSIGNMENT_TOOL_ID,
						TurnitinConstants.PROVIDER_ID);
				}

				if(tiiId.isEmpty()){
					log.error("Could not find tiiId for assignment: " + currentItem.getTaskId());
					processError(currentItem, ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_RETRY_CODE, "Could not find tiiId", null);
					errors++;
					continue;
				}
				
				TurnitinReturnValue result = new TurnitinReturnValue();
				result.setResult( -1 );
				boolean isResubmission = false;
				// TIITODO: fix below
				/*if(currentItem.isResubmission()) 
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
				}*/
				if (!isResubmission)
				{
					result = tiiUtil.makeLTIcall(TurnitinLTIUtil.SUBMIT, tiiId, ltiProps);
				}

				if(result.getResult() >= 0){
					log.debug("LTI submission successful");
					//problems overriding this on callback
					//currentItem.setExternalId(externalId);
					currentItem.setStatus(ContentReviewConstants.CONTENT_REVIEW_SUBMITTED_AWAITING_REPORT_CODE);
					currentItem.setRetryCount(Long.valueOf(0));
					currentItem.setLastError(null);
					currentItem.setErrorCode(null);
					currentItem.setDateSubmitted(new Date());
					success++;
					crqServ.update(currentItem);
				} else {
					Long errorCode = ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_RETRY_CODE;
					// TII-242 - evaluate result.getErrorMessage() to prevent unnecessary retries if the error is terminal
					if (terminalQueueErrors.contains(result.getErrorMessage()))
					{
						errorCode = ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_NO_RETRY_CODE;
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
					processError( currentItem, ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_RETRY_CODE, "Class creation error: " + t.getMessage(), null );
					errors++;
					continue;
				} catch (TransientSubmissionException tse) {
					processError( currentItem, ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_RETRY_CODE, "Class creation error: " + tse.getMessage(), null );
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
					status = ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_RETRY_CODE;
				} else {
					error = "Enrolment error: " + t.getMessage();
					status = ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_RETRY_CODE;
				}

				processError( currentItem, status, error, null );
				errors++;
				continue;
			}

			if (!turnitinConn.isUseSourceParameter()) {
				try {
					Map tiiresult = this.getAssignment(currentItem.getSiteId(), currentItem.getTaskId());
					if (tiiresult.get("rcode") != null && !tiiresult.get("rcode").equals("85")) {
						createAssignment(currentItem.getSiteId(), currentItem.getTaskId(), null);
					}
				} catch (SubmissionException se) {
					processError( currentItem, ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_NO_RETRY_CODE,
							"Assignment creation error: " + se.getMessage(), se.getErrorCode() );
					errors++;
					continue;
				} catch (TransientSubmissionException tse) {
					if (tse.getErrorCode() != null) {
						currentItem.setErrorCode(tse.getErrorCode());
					}

					processError( currentItem, ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_RETRY_CODE,
							"Assignment creation error: " + tse.getMessage(), null );
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
				processError( currentItem, ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_RETRY_CODE, "Error Submitting Assignment for Submission: " + e.getMessage() + ". Assume unsuccessful", null );
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
					currentItem.setStatus(ContentReviewConstants.CONTENT_REVIEW_SUBMITTED_AWAITING_REPORT_CODE);
					currentItem.setRetryCount(Long.valueOf(0));
					currentItem.setLastError(null);
					currentItem.setErrorCode(null);
					currentItem.setDateSubmitted(new Date());
					success++;
					crqServ.update(currentItem);
				} else {
					log.warn("invalid external id");
					processError( currentItem, ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_RETRY_CODE,
							"Submission error: no external id received", null );
					errors++;
				}
			} else {
				log.debug("Submission not successful: " + ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData().trim());

				Long status;
				if (rMessage.equals("User password does not match user email")
						|| "1001".equals(rCode) || "".equals(rMessage) || "413".equals(rCode) || "1025".equals(rCode) || "250".equals(rCode)) {
					status = ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_RETRY_CODE;
					log.warn("Submission not successful. It will be retried.");
				} else if (rCode.equals("423")) {
					status = ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_USER_DETAILS_CODE;
				} else if (rCode.equals("301")) {
					//this took a long time
					log.warn("Submission not successful due to timeout. It will be retried.");
					status = ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_RETRY_CODE;
					Calendar cal = Calendar.getInstance();
					cal.set(Calendar.HOUR_OF_DAY, 22);
					currentItem.setNextRetryTime(cal.getTime());
				}else {
					log.error("Submission not successful. It will NOT be retried.");
					status = ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_NO_RETRY_CODE;
				}

				processError( currentItem, status, "Submission Error: " + rMessage + "(" + rCode + ")", Integer.valueOf(rCode) );
				errors++;
			}
			// TIITODO: below is new code straight out of the 13.x implementation, is this right? won't it skip items?
			// what does it have to do with releasing locks?
			
			// release the lock so the reports job can handle it
			crqServ.getNextItemInQueueToSubmit(getProviderId());
		}

		log.info("Submission queue run completed: " + success + " items submitted, " + errors + " errors.");
	} // end processQueue()
	
	/**
	 * This method was originally private, but is being made public for the
	 * moment so we can run integration tests. TODO Revisit this decision.
	 *
	 * @param siteId
	 * @throws SubmissionException
	 * @throws TransientSubmissionException
	 */
	@SuppressWarnings("unchecked")
	public void createClass(String siteId) throws SubmissionException, TransientSubmissionException {
		log.debug("Creating class for site: " + siteId);

		String cpw = defaultClassPassword;
		String ctl = siteId;
		String fcmd = "2";
		String fid = "2";
		String utp = "2"; 					//user type 2 = instructor
		String cid = siteId;

		Document document;

		Map params = TurnitinAPIUtil.packMap(turnitinConn.getBaseTIIOptions(),
				"cid", cid,
				"cpw", cpw,
				"ctl", ctl,
				"fcmd", fcmd,
				"fid", fid,
				"utp", utp
		);

		params.putAll(getInstructorInfo(siteId));

		document = turnitinConn.callTurnitinReturnDocument(params);

		Element root = document.getDocumentElement();
		String rcode = ((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData().trim();

		if (((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData().trim().compareTo("20") == 0 ||
				((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData().trim().compareTo("21") == 0 ) {
			log.debug("Create Class successful");
		} else {
			if ("218".equals(rcode) || "9999".equals(rcode)) {
				throw new TransientSubmissionException("Create Class not successful. Message: " + ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData().trim() + ". Code: " + ((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData().trim());
			} else {
				throw new SubmissionException("Create Class not successful. Message: " + ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData().trim() + ". Code: " + ((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData().trim());
			}
		}
	}
	
	/**
	 * Currently public for integration tests. TODO Revisit visibility of
	 * method.
	 *
	 * @param userId
	 * @param uem
	 * @param siteId
	 * @throws SubmissionException
	 * @throws org.sakaiproject.contentreview.exception.TransientSubmissionException
	 */
	public void enrollInClass(String userId, String uem, String siteId) throws SubmissionException, TransientSubmissionException {

		String uid = userId;
		String cid = siteId;

		String ctl = siteId;
		String fid = "3";
		String fcmd = "2";
		String tem = getTEM(cid);

		User user;
		try {
			user = userDirectoryService.getUser(userId);
		} catch (Exception t) {
			throw new SubmissionException ("Cannot get user information", t);
		}

		log.debug("Enrolling user " + user.getEid() + "(" + userId + ")  in class " + siteId);

		String ufn = getUserFirstName(user);
		if (ufn == null) {
			throw new SubmissionException ("User has no first name");
		}

		String uln = getUserLastName(user);
		if (uln == null) {
			throw new SubmissionException ("User has no last name");
		}

		String utp = "1";

		Map params = TurnitinAPIUtil.packMap(turnitinConn.getBaseTIIOptions(),
				"fid", fid,
				"fcmd", fcmd,
				"cid", cid,
				"tem", tem,
				"ctl", ctl,
				"dis", studentAccountNotified ? "0" : "1",
				"uem", uem,
				"ufn", ufn,
				"uln", uln,
				"utp", utp,
				"uid", uid
		);

		Document document = turnitinConn.callTurnitinReturnDocument(params);

		Element root = document.getDocumentElement();

		String rMessage = ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData();
		String rCode = ((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData();
		if ("31".equals(rCode)) {
			log.debug("Results from enrollInClass with user + " + userId + " and class title: " + ctl + ".\n" +
					"rCode: " + rCode + " rMessage: " + rMessage);
		} else {
			//certain return codes need to be logged
			log.warn("Results from enrollInClass with user + " + userId + " and class title: " + ctl + ". " +
					"rCode: " + rCode + ", rMessage: " + rMessage);
			//TODO for certain types we should probably throw an exception here and stop the proccess
		}

	}
	
	@Override
	public void checkForReports() {
		checkForReportsBulk();
	}

	@Override
	public List<ContentReviewItem> getReportList(String siteId, String taskId) {
		log.debug("Returning list of reports for site: " + siteId + ", task: " + taskId);
		
		// TIITODO: make this a method of ContentReviewQueueService?
		return dao.findByProviderAnyMatching(getProviderId(), null, null, siteId, taskId, null,
				ContentReviewConstants.CONTENT_REVIEW_SUBMITTED_REPORT_AVAILABLE_CODE, null);
	}
	
	@Override
	public List<ContentReviewItem> getReportList(String siteId)
	{
		return getReportList(siteId, null);
	}
	
	@Override
	public List<ContentReviewItem> getAllContentReviewItems(String siteId, String taskId)
	{
		log.debug("Returning list of reports for site: " + siteId + ", task: " + taskId);
		
		return crqServ.getContentReviewItems(getProviderId(), siteId, taskId);
    }
	
	@Override
	public String getServiceName()
	{
		return TurnitinConstants.SERVICE_NAME;
	}
	
	@Override
	public void resetUserDetailsLockedItems(String userId)
	{
		crqServ.resetUserDetailsLockedItems(getProviderId(), userId);
	}
	
	// SAK-27857	--bbailla2
	@Override
	public boolean allowAllContent()
	{
		// Turntin reports errors when content is submitted that it can't check originality against. So we will block unsupported content.
		return serverConfigurationService.getBoolean(TurnitinConstants.SAK_PROP_ACCEPT_ALL_FILES, false);
	}
	
	/* (non-Javadoc)
	 * @see org.sakaiproject.contentreview.service.ContentReviewService#isAcceptableContent(org.sakaiproject.content.api.ContentResource)
	 */
	@Override
	public boolean isAcceptableContent(ContentResource resource)
	{
		return turnitinContentValidator.isAcceptableContent(resource);
	}
	
	// TII-157	--bbailla2
	@Override
	public Map<String, SortedSet<String>> getAcceptableExtensionsToMimeTypes()
	{
		// TIITODO: consider moving these mime/file type implementations to a delegate class dedicated to this purpose
		// also, can we just compute this once and store it?
		
		Map<String, SortedSet<String>> acceptableExtensionsToMimeTypes = new HashMap<>();
		String[] acceptableFileExtensions = getAcceptableFileExtensions();
		String[] acceptableMimeTypes = getAcceptableMimeTypes();
		int min = Math.min(acceptableFileExtensions.length, acceptableMimeTypes.length);
		for (int i = 0; i < min; i++)
		{
			appendToMap(acceptableExtensionsToMimeTypes, acceptableFileExtensions[i], acceptableMimeTypes[i]);
		}

		return acceptableExtensionsToMimeTypes;
	}

	// TII-157	--bbailla2
	@Override
	public Map<String, SortedSet<String>> getAcceptableFileTypesToExtensions()
	{
		// TIITODO: see notes above
		
		Map<String, SortedSet<String>> acceptableFileTypesToExtensions = new LinkedHashMap<>();
		String[] acceptableFileTypes = getAcceptableFileTypes();
		String[] acceptableFileExtensions = getAcceptableFileExtensions();
		if (acceptableFileTypes != null && acceptableFileTypes.length > 0)
		{
			// The acceptable file types are listed in sakai.properties. Sakai.properties takes precedence.
			int min = Math.min(acceptableFileTypes.length, acceptableFileExtensions.length);
			for (int i = 0; i < min; i++)
			{
				appendToMap(acceptableFileTypesToExtensions, acceptableFileTypes[i], acceptableFileExtensions[i]);
			}
		}
		else
		{
			/*
			 * acceptableFileTypes not specified in sakai.properties (this is normal).
			 * Use ResourceLoader to resolve the file types.
			 * If the resource loader doesn't find the file extenions, log a warning and return the [missing key...] messages
			 */
			ResourceLoader resourceLoader = new ResourceLoader("turnitin");
			for( String fileExtension : acceptableFileExtensions )
			{
				String key = TurnitinConstants.KEY_FILE_TYPE_PREFIX + fileExtension;
				if (!resourceLoader.getIsValid(key))
				{
					log.warn("While resolving acceptable file types for Turnitin, the sakai.property "
							+ TurnitinConstants.SAK_PROP_ACCEPTABLE_FILE_TYPES + " is not set, and the message bundle "
							+ key + " could not be resolved. Displaying [missing key ...] to the user");
				}
				String fileType = resourceLoader.getString(key);
				appendToMap( acceptableFileTypesToExtensions, fileType, fileExtension );
			}
		}

		return acceptableFileTypesToExtensions;
	}
	
		/**
	 * Allow Turnitin for this site?
	 * @param s
	 * @return 
	 */
	@Override
	public boolean isSiteAcceptable(Site s)
	{
		// TIITODO: dedicated class for this stuff? it is already partially delegated to siteAdvisor
		
		if (s == null) {
			return false;
		}

		log.debug("isSiteAcceptable: " + s.getId() + " / " + s.getTitle());

		// Delegated to another bean
		if (siteAdvisor != null) {
			return siteAdvisor.siteCanUseReviewService(s);
		}

		// Check site property
		ResourceProperties properties = s.getProperties();

                String prop = (String) properties.get(TURNITIN_SITE_PROPERTY);
                if (prop != null) {
			log.debug("Using site property: " + prop);
                        return Boolean.parseBoolean(prop);
                }

		// Check list of allowed site types, if defined
		if (enabledSiteTypes != null && !enabledSiteTypes.isEmpty()) {
			log.debug("Using site type: " + s.getType());
			return enabledSiteTypes.contains(s.getType());
		}

		// No property set, no restriction on site types, so allow
        return true; 
    }
	
	
	@Override
	public boolean allowResubmission()
	{
		return true;
	}
	
	@Override
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

	public String getIconUrlforScore(Long score) {
		// TODO Auto-generated method stub
		return null;
	}


	
	public boolean isAcceptableSize(ContentResource resource) {
		throw new UnsupportedOperationException("Not implemented");
	}
	
	/**
	 * Creates or Updates an Assignment
	 *
	 * This method will look at the current user or default instructor for it's
	 * user information.
	 *
	 *
	 * @param siteId
	 * @param taskId an assignment reference
	 * @param extraAsnOpts
	 * @throws SubmissionException
	 * @throws TransientSubmissionException
	 */
	@SuppressWarnings("unchecked")
	@Override
	public void createAssignment(String siteId, String asnId, Map extraAsnOpts) throws SubmissionException, TransientSubmissionException {
		syncAssignment(siteId, asnId, extraAsnOpts, null);
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
	// TIITODO: separate the legacy and LTI methods, move legacy methods to delegate class for easy removal later?
	
	
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
	
	private void processError( ContentReviewItem item, Long status, String error, Integer errorCode )
	{
		try
		{
			if( status == null )
			{
				IllegalArgumentException ex = new IllegalArgumentException( "Status is null; you must supply a valid status to update when calling processError()" );
				throw ex;
			}
			else
			{
				item.setStatus( status );
			}
			if( error != null )
			{
				item.setLastError(error);
			}
			if( errorCode != null )
			{
				item.setErrorCode( errorCode );
			}

			crqServ.update( item );

			// Update urlAccessed to true if status is being updated to one of the dead states (5, 6, 8 and 9)
			if( isDeadState( status ) )
			{
				try
				{
					// TIITODO: why do we need to get a ContentResourse to set the isUrlAccessed property?
					// This is stored in the content review item table (and will be moved to a dedicated table probably)
					// so do we need to involve contentHostingService?
					ContentResource resource = contentHostingService.getResource( item.getContentId() );
					boolean itemUpdated = updateItemAccess( resource.getId() );
					if (!itemUpdated)
					{
						log.error( "Could not update cr item access status" );
					}
				}
				catch( PermissionException | IdUnusedException | TypeException ex )
				{
					log.error( "Error updating cr item access status; item id = " + item.getContentId(), ex );
				}
			}
		}
		finally
		{
			// TIITODO: clean up this try/finally if we don't need it. The 13.x impl doesn't use explicit locking like this
			//releaseLock( item );  
		}
	}

	/**
	 * Returns true/false if the given status is one of the 'dead' TII states
	 * @param status the status to check
	 * @return true if the status given is of one of the dead states; false otherwise
	 */
	private boolean isDeadState( Long status )
	{
		return status != null && (status.equals( ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_NO_RETRY_CODE )
				|| status.equals( ContentReviewConstants.CONTENT_REVIEW_REPORT_ERROR_NO_RETRY_CODE ) 
			|| status.equals( ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_RETRY_EXCEEDED_CODE ));
	}
	
	public String escapeFileName(String fileName, String contentId) {
		log.debug("original filename is: " + fileName);
		if (fileName == null) {
			//use the id
			fileName  = contentId;
		} else if (fileName.length() > 199) {
			fileName = fileName.substring(0, 199);
		}
		log.debug("fileName is :" + fileName);
		try {
			fileName = URLDecoder.decode(fileName, "UTF-8");
			//in rare cases it seems filenames can be double encoded
			while (fileName.indexOf("%20")> 0 || fileName.contains("%2520") ) {
				try {
					fileName = URLDecoder.decode(fileName, "UTF-8");
				}
				catch (IllegalArgumentException eae) {
					log.warn("Unable to decode fileName: " + fileName, eae);
					//as the result is likely to cause a MD5 exception use the ID
					return contentId;
				}
			}
		}
		catch (IllegalArgumentException eae) {
			log.warn("Unable to decode fileName: " + fileName, eae);
			return contentId;
		} catch (UnsupportedEncodingException e) {
			log.debug( e );
		}

		fileName = fileName.replace(' ', '_');
		//its possible we have double _ as a result of this lets do some cleanup
		fileName = StringUtils.replace(fileName, "__", "_");

		log.debug("fileName is :" + fileName);
		return fileName;
	}
	
	private String truncateFileName(String fileName, int i) {
		//get the extension for later re-use
		String extension = "";
		if (fileName.contains(".")) {
			 extension = fileName.substring(fileName.lastIndexOf("."));
		}

		fileName = fileName.substring(0, i - extension.length());
		fileName = fileName + extension;

		return fileName;
	}
	
	private Optional<Date> getAssignmentCreationDate(String assignmentRef)
	{
		try
		{
			org.sakaiproject.assignment.api.model.Assignment asn = assignmentService.getAssignment(assignmentRef);
			return getAssignmentCreationDate(asn);
		}
		catch(IdUnusedException | PermissionException e)
		{
			return Optional.empty();
		}
	}

	private Optional<Date> getAssignmentCreationDate(org.sakaiproject.assignment.api.model.Assignment asn)
	{
		if (asn == null)
		{
			return Optional.empty();
		}
		Date date = new Date(asn.getTimeCreated().getTime());
		return Optional.of(date);
	}
	
	private String getTEM(String cid) {
        if (turnitinConn.isUseSourceParameter()) {
            return getInstructorInfo(cid).get("uem").toString();
        } else {
            return turnitinConn.getDefaultInstructorEmail();
        }
    }
	
	/**
	 * This returns the String that will be used as the Assignment Title
	 * in Turn It In.
	 *
	 * The current implementation here has a few interesting caveats so that
	 * it will work with both, the existing Assignments 1 integration, and
	 * the new Assignments 2 integration under development.
	 *
	 * We will check and see if the taskId starts with /assignment/. If it
	 * does we will look up the Assignment Entity on the legacy Entity bus.
	 * (not the entitybroker).  This needs some general work to be made
	 * generally modular ( and useful for more than just Assignments 1 and 2
	 * ). We will need to look at some more concrete use cases and then
	 * factor it accordingly in the future when the next scenerio is
	 * required.
	 *
	 * Another oddity is that to get rid of our hard dependency on Assignments 1
	 * we are invoking the getTitle method by hand. We probably need a
	 * mechanism to register a title handler or something as part of the
	 * setup process for new services that want to be reviewable.
	 *
	 * @param taskId
	 * @return
	 */
	private String getAssignmentTitle(String taskId){
		String togo = taskId;
		if (taskId.startsWith("/assignment/")) {
			try {
				Reference ref = entityManager.newReference(taskId);
				log.debug("got ref " + ref + " of type: " + ref.getType());
				EntityProducer ep = ref.getEntityProducer();

				Entity ent = ep.getEntity(ref);
				log.debug("got entity " + ent);
				String title = scrubSpecialCharacters(ent.getClass().getMethod("getTitle").invoke(ent).toString());
				log.debug("Got reflected assignemment title from entity " + title);
				togo = URLDecoder.decode(title,"UTF-8");
				
				togo=togo.replaceAll("\\W+","");

			} catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | UnsupportedEncodingException e) {
				log.debug( e );
			} catch (Exception e) {
				log.error( "Unexpected exception getting assignment title", e );
			}
		}

		// Turnitin requires Assignment titles to be at least two characters long
		if (togo.length() == 1) {
			togo = togo + "_";
		}

		return togo;

	}

    private String scrubSpecialCharacters(String title) {

        try {
            if (title.contains("&")) {
                title = title.replace('&', 'n');
            }
            if (title.contains("%")) {
                title = title.replace("%", "percent");
            }
        }
        catch (Exception e) {
            log.debug( e );
        }

        return title;
    }
	
	/*
	 * Fetch reports on a class by class basis
	 */
	@SuppressWarnings({ "deprecation", "unchecked" })
	private void checkForReportsBulk() {

		SimpleDateFormat dform = ((SimpleDateFormat) DateFormat.getDateInstance());
		dform.applyPattern(TURNITIN_DATETIME_FORMAT);

		log.info("Fetching reports from Turnitin");

		// get the list of all items that are waiting for reports
		// but skip items with externalId = null, this happens when the LTI integration's callback fails. In this case, they'll be resubmitted by the queue job.
		// For the Sakai API integration, we should never enter the report state with externalId = null
		List<ContentReviewItem> awaitingReport = dao.findByProperties(ContentReviewItem.class,
				new String[] { "status", "externalId" },
				new Object[] { ContentReviewItem.SUBMITTED_AWAITING_REPORT_CODE, "" },
				new int[] { dao.EQUALS, dao.NOT_NULL });

		awaitingReport.addAll(dao.findByProperties(ContentReviewItem.class,
				new String[] { "status", "externalId" },
				new Object[] { ContentReviewItem.REPORT_ERROR_RETRY_CODE, "" },
				new int[] { dao.EQUALS, dao.NOT_NULL }));

		Iterator<ContentReviewItem> listIterator = awaitingReport.iterator();
		HashMap<String, Integer> reportTable = new HashMap<>();

		log.debug("There are " + awaitingReport.size() + " submissions awaiting reports");

		ContentReviewItem currentItem;
		while (listIterator.hasNext()) {
			currentItem = (ContentReviewItem) listIterator.next();

			try
			{
				// has the item reached its next retry time?
				if (currentItem.getNextRetryTime() == null)
					currentItem.setNextRetryTime(new Date());

				else if (currentItem.getNextRetryTime().after(new Date())) {
					//we haven't reached the next retry time
					log.info("next retry time not yet reached for item: " + currentItem.getId());
					dao.update(currentItem);
					continue;
				}

				if (currentItem.getRetryCount() == null ) {
					currentItem.setRetryCount(Long.valueOf(0));
					currentItem.setNextRetryTime(this.getNextRetryTime(0));
				} else if (currentItem.getRetryCount().intValue() > maxRetry) {
					processError( currentItem, ContentReviewItem.SUBMISSION_ERROR_RETRY_EXCEEDED, null, null );
					continue;
				} else {
					log.debug("Still have retries left, continuing. ItemID: " + currentItem.getId());
					// Moving down to check for report generate speed.
					//long l = currentItem.getRetryCount().longValue();
					//l++;
					//currentItem.setRetryCount(Long.valueOf(l));
					//currentItem.setNextRetryTime(this.getNextRetryTime(Long.valueOf(l)));
					//dao.update(currentItem);
				}
				
				Site s;
				try {
					s = siteService.getSite(currentItem.getSiteId());
				}
				catch (IdUnusedException iue) {
					log.warn("checkForReportsBulk: Site " + currentItem.getSiteId() + " not found!" + iue.getMessage());
					long l = currentItem.getRetryCount();
					l++;
					currentItem.setRetryCount(l);
					currentItem.setNextRetryTime(this.getNextRetryTime(l));
					currentItem.setLastError("Site not found");
					dao.update(currentItem);
					continue;
				}
				//////////////////////////////  NEW LTI INTEGRATION  ///////////////////////////////
				Optional<Date> dateOpt = getAssignmentCreationDate(currentItem.getTaskId());
				if(dateOpt.isPresent() && siteAdvisor.siteCanUseLTIReviewServiceForAssignment(s, dateOpt.get())){			
					log.debug("getReviewScore using the LTI integration");			
					
					Map<String,String> ltiProps = new HashMap<> ();
					ltiProps = putInstructorInfo(ltiProps, currentItem.getSiteId());
					
					String paperId = currentItem.getExternalId();
					
					if(paperId == null){
						log.warn("Could not find TII paper id for the content " + currentItem.getContentId());
						long l = currentItem.getRetryCount();
						l++;
						currentItem.setRetryCount(l);
						currentItem.setNextRetryTime(this.getNextRetryTime(l));
						currentItem.setLastError("Could not find TII paper id for the submission");
						dao.update(currentItem);
						continue;
					}
					
					TurnitinReturnValue result = tiiUtil.makeLTIcall(TurnitinLTIUtil.INFO_SUBMISSION, paperId, ltiProps);
					if(result.getResult() >= 0){
						currentItem.setReviewScore(result.getResult());
						currentItem.setStatus(ContentReviewItem.SUBMITTED_REPORT_AVAILABLE_CODE);
						currentItem.setDateReportReceived(new Date());
						currentItem.setLastError(null);
						currentItem.setErrorCode(null);
						dao.update(currentItem);

						try
						{
							ContentResource resource = contentHostingService.getResource( currentItem.getContentId() );
							boolean itemUpdated = updateItemAccess( resource.getId() );
							if( !itemUpdated )
							{
								log.error( "Could not update cr item access status" );
							}
						}
						catch( PermissionException | IdUnusedException | TypeException ex )
						{
							log.error( "Could not update cr item access status", ex );
						}

						//log.debug("new report received: " + currentItem.getExternalId() + " -> " + currentItem.getReviewScore());
						log.debug("new report received: " + paperId + " -> " + currentItem.getReviewScore());
					} else {
						if(result.getResult() == -7){
							log.debug("report is still pending for paper " + paperId);
							currentItem.setStatus(ContentReviewItem.SUBMITTED_AWAITING_REPORT_CODE);
							currentItem.setLastError( result.getErrorMessage() );
							currentItem.setErrorCode( result.getResult() );
						} else {
							log.error("Error making LTI call");
							long l = currentItem.getRetryCount();
							l++;
							currentItem.setRetryCount(l);
							currentItem.setNextRetryTime(this.getNextRetryTime(l));
							currentItem.setStatus(ContentReviewItem.REPORT_ERROR_RETRY_CODE);
							currentItem.setLastError("Report Data Error: " + result.getResult());
						}
						dao.update(currentItem);
					}
					
					continue;
				}
				//////////////////////////////  OLD API INTEGRATION  ///////////////////////////////

				if (currentItem.getExternalId() == null || currentItem.getExternalId().equals("")) {
					currentItem.setStatus(Long.valueOf(4));
					dao.update(currentItem);
					continue;
				}

				if (!reportTable.containsKey(currentItem.getExternalId())) {
					// get the list from turnitin and see if the review is available

					log.debug("Attempting to update hashtable with reports for site " + currentItem.getSiteId());

					String fcmd = "2";
					String fid = "10";

					try {
						User user = userDirectoryService.getUser(currentItem.getUserId());
					} catch (Exception e) {
						log.error("Unable to look up user: " + currentItem.getUserId() + " for contentItem: " + currentItem.getId(), e);
					}

					String cid = currentItem.getSiteId();
					String tem = getTEM(cid);

					String utp = "2";

					String assignid = currentItem.getTaskId();

					String assign = currentItem.getTaskId();
					String ctl = currentItem.getSiteId();

					// TODO FIXME Current sgithens
					// Move the update setRetryAttempts to here, and first call and
					// check the assignment from TII to see if the generate until
					// due is enabled. In that case we don't want to waste retry
					// attempts and should just continue.
					try {
						// TODO FIXME This is broken at the moment because we need
						// to have a userid, but this is assuming it's coming from
						// the thread, but we're in a quartz job.
						//Map curasnn = getAssignment(currentItem.getSiteId(), currentItem.getTaskId());
						// TODO FIXME Parameterize getAssignment method to take user information
						Map getAsnnParams = TurnitinAPIUtil.packMap(turnitinConn.getBaseTIIOptions(),
								"assign", getAssignmentTitle(currentItem.getTaskId()), "assignid", currentItem.getTaskId(), "cid", currentItem.getSiteId(), "ctl", currentItem.getSiteId(),
								"fcmd", "7", "fid", "4", "utp", "2" );

						getAsnnParams.putAll(getInstructorInfo(currentItem.getSiteId()));

						Map curasnn = turnitinConn.callTurnitinReturnMap(getAsnnParams);

						if (curasnn.containsKey("object")) {
							Map curasnnobj = (Map) curasnn.get("object");
							String reportGenSpeed = (String) curasnnobj.get("generate");
							String duedate = (String) curasnnobj.get("dtdue");
							SimpleDateFormat retform = ((SimpleDateFormat) DateFormat.getDateInstance());
							retform.applyPattern(TURNITIN_DATETIME_FORMAT);
							Date duedateObj = null;
							try {
								if (duedate != null) {
									duedateObj = retform.parse(duedate);
								}
							} catch (ParseException pe) {
								log.warn("Unable to parse turnitin dtdue: " + duedate, pe);
							}
							if (reportGenSpeed != null && duedateObj != null &&
								reportGenSpeed.equals("2") && duedateObj.after(new Date())) {
								log.info("Report generate speed is 2, skipping for now. ItemID: " + currentItem.getId());
								// If there was previously a transient error for this item, reset the status
								if (ContentReviewItem.REPORT_ERROR_RETRY_CODE.equals(currentItem.getStatus())) {
									currentItem.setStatus(ContentReviewItem.SUBMITTED_AWAITING_REPORT_CODE);
									currentItem.setLastError(null);
									currentItem.setErrorCode(null);
									dao.update(currentItem);
								}
								continue;
							}
							else {
								log.debug("Incrementing retry count for currentItem: " + currentItem.getId());
								long l = currentItem.getRetryCount();
								l++;
								currentItem.setRetryCount(l);
								currentItem.setNextRetryTime(this.getNextRetryTime(l));
								dao.update(currentItem);
							}
						}
					} catch (SubmissionException | TransientSubmissionException e) {
						log.error("Unable to check the report gen speed of the asnn for item: " + currentItem.getId(), e);
					}

					Map params = TurnitinAPIUtil.packMap(turnitinConn.getBaseTIIOptions(),
								"fid", fid,
								"fcmd", fcmd,
								"tem", tem,
								"assign", assign,
								"assignid", assignid,
								"cid", cid,
								"ctl", ctl,
								"utp", utp
						);
						params.putAll(getInstructorInfo(currentItem.getSiteId()));

					Document document;

					try {
						document = turnitinConn.callTurnitinReturnDocument(params);
					}
					catch (TransientSubmissionException e) {
						log.warn("Update failed due to TransientSubmissionException error: " + e.toString(), e);
						currentItem.setStatus(ContentReviewItem.REPORT_ERROR_RETRY_CODE);
						currentItem.setLastError(e.getMessage());
						dao.update(currentItem);
						break;
					}
					catch (SubmissionException e) {
						log.warn("Update failed due to SubmissionException error: " + e.toString(), e);
						currentItem.setStatus(ContentReviewItem.REPORT_ERROR_RETRY_CODE);
						currentItem.setLastError(e.getMessage());
						dao.update(currentItem);
						break;
					}

					Element root = document.getDocumentElement();
					if (((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData().trim().compareTo("72") == 0) {
						log.debug("Report list returned successfully");

						NodeList objects = root.getElementsByTagName("object");
						String objectId;
						String similarityScore;
						String overlap = "";
						log.debug(objects.getLength() + " objects in the returned list");
						for (int i=0; i<objects.getLength(); i++) {
							similarityScore = ((CharacterData) (((Element)(objects.item(i))).getElementsByTagName("similarityScore").item(0).getFirstChild())).getData().trim();
							objectId = ((CharacterData) (((Element)(objects.item(i))).getElementsByTagName("objectID").item(0).getFirstChild())).getData().trim();
							if (similarityScore.compareTo("-1") != 0) {
								overlap = ((CharacterData) (((Element)(objects.item(i))).getElementsByTagName("overlap").item(0).getFirstChild())).getData().trim();
								reportTable.put(objectId, Integer.valueOf(overlap));
							} else {
								reportTable.put(objectId, -1);
							}

							log.debug("objectId: " + objectId + " similarity: " + similarityScore + " overlap: " + overlap);
						}
					} else {
						log.debug("Report list request not successful");
						log.debug(document.getTextContent());

					}
				}

				int reportVal;
				// check if the report value is now there (there may have been a
				// failure to get the list above)
				if (reportTable.containsKey(currentItem.getExternalId())) {
					reportVal = ((reportTable.get(currentItem.getExternalId())));
					log.debug("reportVal for " + currentItem.getExternalId() + ": " + reportVal);
					if (reportVal != -1) {
						currentItem.setReviewScore(reportVal);
						currentItem.setStatus(ContentReviewItem.SUBMITTED_REPORT_AVAILABLE_CODE);
						currentItem.setDateReportReceived(new Date());
						currentItem.setLastError(null);
						currentItem.setErrorCode(null);
						dao.update(currentItem);

						try
						{
							ContentResource resource = contentHostingService.getResource( currentItem.getContentId() );
							boolean itemUpdated = updateItemAccess( resource.getId() );
							if( !itemUpdated )
							{
								log.error( "Could not update cr item access status" );
							}
						}
						catch( PermissionException | IdUnusedException | TypeException ex )
						{
							log.error( "Could not update cr item access status", ex );
						}

						log.debug("new report received: " + currentItem.getExternalId() + " -> " + currentItem.getReviewScore());
					}
				}
			}
			catch (Exception e)
			{
				log.error(e.getMessage() + "\n" + e.getStackTrace());
			}
		}

		log.info("Finished fetching reports from Turnitin");
	}
	
	// TII-157	--bbailla2
	/**
	 * Inserts (key, value) into a Map<String, Set<String>> such that value is inserted into the value Set associated with key.
	 * The value set is implemented as a TreeSet, so the Strings will be in alphabetical order
	 * Eg. if we insert (a, b) and (a, c) into map, then map.get(a) will return {b, c}
	 */
	private void appendToMap(Map<String, SortedSet<String>> map, String key, String value)
	{
		SortedSet<String> valueList = map.get(key);
		if (valueList == null)
		{
			valueList = new TreeSet<>();
			map.put(key, valueList);
		}
		valueList.add(value);
	}
	
	// SAK-27857	--bbailla2
	public String[] getAcceptableFileExtensions()
	{
		String[] extensions = serverConfigurationService.getStrings(TurnitinConstants.SAK_PROP_ACCEPTABLE_FILE_EXTENSIONS);
		if (extensions != null && extensions.length > 0)
		{
			return extensions;
		}
		return TurnitinConstants.DEFAULT_ACCEPTABLE_FILE_EXTENSIONS;
	}

	// TII-157	--bbailla2
	public String[] getAcceptableMimeTypes()
	{
		String[] mimeTypes = serverConfigurationService.getStrings(TurnitinConstants.SAK_PROP_ACCEPTABLE_MIME_TYPES);
		if (mimeTypes != null && mimeTypes.length > 0)
		{
			return mimeTypes;
		}
		return TurnitinConstants.DEFAULT_ACCEPTABLE_MIME_TYPES;
	}
	
	// TII-157	--bbailla2
	public String [] getAcceptableFileTypes()
	{
		// TIITODO: no default value?
		return serverConfigurationService.getStrings(TurnitinConstants.SAK_PROP_ACCEPTABLE_FILE_TYPES);
	}
}
