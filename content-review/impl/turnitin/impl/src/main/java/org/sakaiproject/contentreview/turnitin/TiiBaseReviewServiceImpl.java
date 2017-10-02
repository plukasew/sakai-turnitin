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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.ArrayList;
import java.util.stream.Collectors;
import org.apache.commons.lang.StringUtils;

import org.sakaiproject.content.api.ContentResource;
import org.sakaiproject.contentreview.dao.ContentReviewItemDao;
import org.sakaiproject.contentreview.exception.QueueException;
import org.sakaiproject.contentreview.exception.ReportException;
import org.sakaiproject.contentreview.exception.SubmissionException;
import org.sakaiproject.contentreview.dao.ContentReviewItem;
import org.sakaiproject.contentreview.service.ContentReviewService;
import org.sakaiproject.contentreview.service.ContentReviewQueueService;
import org.sakaiproject.contentreview.advisors.ContentReviewSiteAdvisor;
import org.sakaiproject.contentreview.dao.ContentReviewConstants;
import org.sakaiproject.genericdao.api.search.Restriction;
import org.sakaiproject.genericdao.api.search.Search;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.tool.api.ToolManager;
import org.sakaiproject.user.api.UserDirectoryService;
import org.sakaiproject.contentreview.turnitin.util.TurnitinAPIUtil;
import org.sakaiproject.contentreview.turnitin.util.TurnitinReturnValue;
import org.sakaiproject.contentreview.turnitin.util.TurnitinLTIUtil;
import org.sakaiproject.assignment.api.AssignmentService;
import org.sakaiproject.turnitin.api.TiiInternalActivityConfig;

import lombok.extern.slf4j.Slf4j;
import lombok.Setter;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.validator.EmailValidator;
import org.sakaiproject.api.common.edu.person.SakaiPerson;
import org.sakaiproject.api.common.edu.person.SakaiPersonManager;
import org.sakaiproject.assignment.api.model.AssignmentSubmission;
import org.sakaiproject.authz.api.SecurityAdvisor;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.content.api.ContentHostingService;
import org.sakaiproject.contentreview.exception.TransientSubmissionException;
import org.sakaiproject.contentreview.turnitin.dao.ExtendedContentReviewItemDao;
import org.sakaiproject.db.api.SqlReader;
import org.sakaiproject.entity.api.Entity;
import org.sakaiproject.entity.api.EntityManager;
import org.sakaiproject.entity.api.EntityProducer;
import org.sakaiproject.entity.api.Reference;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.entity.api.ResourcePropertiesEdit;
import org.sakaiproject.entitybroker.EntityReference;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.exception.TypeException;
import org.sakaiproject.lti.api.LTIService;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserNotDefinedException;
import org.sakaiproject.util.ResourceLoader;
import org.tsugi.basiclti.BasicLTIConstants;
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
	
	private List<String> enabledSiteTypes;

	@Setter
	protected ExtendedContentReviewItemDao dao;

	@Setter
	private ToolManager toolManager;

	@Setter
	protected UserDirectoryService userDirectoryService;
	
	@Setter
	protected ContentReviewSiteAdvisor siteAdvisor;
	
	@Setter
	protected ContentReviewQueueService crqServ;
	
	@Setter
	protected ServerConfigurationService serverConfigurationService;
	
	@Setter
	protected TurnitinAccountConnection turnitinConn;
	
	@Setter
	protected AssignmentService assignmentService;
	
	@Setter
	private ContentHostingService contentHostingService;
	
	@Setter
	protected SiteService siteService;
	
	@Setter
	protected TurnitinLTIUtil tiiUtil;
	
	@Setter	
	private TurnitinContentValidator turnitinContentValidator;
	
	@Setter
	private SakaiPersonManager sakaiPersonManager;
	
	@Setter
	private EntityManager entityManager;

	@Setter
	protected SecurityService securityService;
	
	@Setter
	protected SessionManager sessionManager;

	
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
		enabledSiteTypes = Arrays.asList(ArrayUtils.nullToEmpty(serverConfigurationService.getStrings("turnitin.sitetypes")));
		if (!enabledSiteTypes.isEmpty())
		{
			log.info("Turnitin is enabled for site types: " + StringUtils.join(enabledSiteTypes, ","));
		}
		
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
		
		if (!turnitinConn.isUseSourceParameter()) {
			if (serverConfigurationService.getBoolean("turnitin.updateAssingments", false))
				doAssignments();
		}
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
		if (item.getStatus().compareTo(ContentReviewConstants.CONTENT_REVIEW_SUBMITTED_REPORT_AVAILABLE_CODE) != 0)
		{
			String msg = String.format(ContentReviewConstants.MSG_REPORT_NOT_AVAILABLE, item.getId(), item.getStatus());
			log.debug(msg);
		}
		
		// TIITODO: in the original 13.x implementation there is a bunch of grade syncing code here
		// if GradeMark is enabled. This is probably not the right place to sync grades, so
		// I've commented it out. Grades should probably be sync'd at the point were the score
		// is written, not where it is read. Find out where this is and implement the sync there.
		
		/*String[] assignData = null;
		try {
			assignData = getAssignData(contentId);
		} catch (Exception e) {
			log.error("(assignData)" + e);
		}

		String siteId = "", taskId = "", taskTitle = "";
		Map<String, Object> data = new HashMap<String, Object>();
		if (assignData != null) {
			siteId = assignData[0];
			taskId = assignData[1];
			taskTitle = assignData[2];
		} else {
			siteId = item.getSiteId();
			taskId = item.getTaskId();
			taskTitle = getAssignmentTitle(taskId);
			data.put("assignment1", "assignment1");
		}
		// Sync Grades
		if (turnitinConn.getUseGradeMark()) {
			try {
				data.put("siteId", siteId);
				data.put("taskId", taskId);
				data.put("taskTitle", taskTitle);
				syncGrades(data);
			} catch (Exception e) {
				log.error("Error syncing grades. " + e);
			}
		}*/
		
		// Note: with the grademark sync missing, this implementation is basically the same for both
		// legacy and LTI integrations.
		
		// TIITODO: what to return here if getReviewScore is null?
		return item.getReviewScore();
	}
	
	// TIITODO: see comments about grade sync above and (re)move this method when possible
	/**
	 * Get additional data from String if available
	 * 
	 * @return array containing site ID, Task ID, Task Title
	 */
	/*private String[] getAssignData(String data) {
		String[] assignData = null;
		try {
			if (data.contains("#")) {
				assignData = data.split("#");
			}
		} catch (Exception e) {
		}
		return assignData;
	}*/
	
	@Override
	@Deprecated
	public String getReviewReport(String contentId, String assignmentRef, String userId) throws QueueException, ReportException
	{
		// TIITODO: this method is deprecated but is used by the LTI integration. This should be fixed so that the LTI integration
		// uses the Instructor/Student methods below
		
		log.debug("getReviewReport for LTI integration");
		//should have already checked lti integration on assignments tool
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
		Long status = item.getStatus();
		if (ContentReviewConstants.SUBMITTED_REPORT_ON_DUE_DATE_CODE.equals(status)
				|| ContentReviewConstants.CONTENT_REVIEW_SUBMITTED_AWAITING_REPORT_CODE.equals(status))
		{
			log.debug("Report pending for item: " + item.getId());
			return "Pending"; // TIITODO: constant or better return object
		}
		if (item.getStatus().compareTo(ContentReviewConstants.CONTENT_REVIEW_SUBMITTED_REPORT_AVAILABLE_CODE) != 0)
		{
			String msg = String.format(ContentReviewConstants.MSG_REPORT_NOT_AVAILABLE, item.getId(), status);
			log.debug(msg);
			throw new ReportException(msg);
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
		if (item.getStatus().compareTo(ContentReviewConstants.CONTENT_REVIEW_SUBMITTED_REPORT_AVAILABLE_CODE) != 0)
		{
			String msg = String.format(ContentReviewConstants.MSG_REPORT_NOT_AVAILABLE, item.getId(), item.getStatus());
			log.debug(msg);
			throw new ReportException(msg);
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
		if (item.getStatus().compareTo(ContentReviewConstants.CONTENT_REVIEW_SUBMITTED_REPORT_AVAILABLE_CODE) != 0)
		{
			String msg = String.format(ContentReviewConstants.MSG_REPORT_NOT_AVAILABLE, item.getId(), item.getStatus());
			log.debug(msg);
			throw new ReportException(msg);
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

		Optional<ContentReviewItem> nextItem;
		
		while ((nextItem = getNextItemInSubmissionQueue()).isPresent())
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
				processError(currentItem, ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_NO_RETRY_CODE, "User not found", null);
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
					/*tiiId = getActivityConfigValue(TurnitinConstants.TURNITIN_ASN_ID, a.getId(), TurnitinConstants.SAKAI_ASSIGNMENT_TOOL_ID,
						TurnitinConstants.PROVIDER_ID);*/
					
					// TIITODO: strategy to determine which tool registration id to use?
					Optional<TiiInternalActivityConfig> activityConfig = getActivityConfig(TurnitinConstants.SAKAI_ASSIGNMENT_TOOL_ID, a.getId());
					tiiId = activityConfig.map(TiiInternalActivityConfig::getTurnitinAssignmentId).orElse("");
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
					
					// If assignment set to generate reports on due date, set status appropriately
					// TIITODO: fix this to work with new Assignments API
					/*if (ac != null && TurnitinConstants.GEN_REPORTS_ON_DUE_DATE_SETTING.equals(ac.getGenerateOriginalityReport()))*/
					if (false)  // TIITODO: remove when above is fixed
					{
						currentItem.setStatus(ContentReviewConstants.SUBMITTED_REPORT_ON_DUE_DATE_CODE);
					}
					else
					{
						currentItem.setStatus(ContentReviewConstants.CONTENT_REVIEW_SUBMITTED_AWAITING_REPORT_CODE);
					}
					
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

		if (s == null)
		{
			return false;
		}

		log.debug("isSiteAcceptable: " + s.getId() + " / " + s.getTitle());

		// Delegated to another bean
		if (siteAdvisor != null)
		{
			return siteAdvisor.siteCanUseReviewService(s);
		}

		// Check site property
		ResourceProperties properties = s.getProperties();

		String prop = (String) properties.get(TurnitinConstants.TURNITIN_SITE_PROPERTY);
		if (prop != null)
		{
			log.debug("Using site property: " + prop);
			return Boolean.parseBoolean(prop);
		}

		// Check list of allowed site types, if defined
		if (!enabledSiteTypes.isEmpty())
		{
			log.debug("Using site type: " + s.getType());
			return enabledSiteTypes.contains(s.getType());
		}

		// No property set, no restriction on site types, so allow
        return true; 
    }
	
	@Override
	public String getIconCssClassforScore(int score, String contentId)
	{
		// TIITODO: constants?
		if (score == 0) {
			return "contentReviewIconThreshold-5";
		} else if (score < 25) {
			return "contentReviewIconThreshold-4";
		} else if (score < 50) {
			return "contentReviewIconThreshold-3";
		} else if (score < 75) {
			return "contentReviewIconThreshold-2";
		}
		
		return "contentReviewIconThreshold-1";
	}
	
	
	@Override
	public boolean allowResubmission()
	{
		return true;
	}
	
	@Override
	public void removeFromQueue(String contentId)
	{
		crqServ.removeFromQueue(getProviderId(), contentId);
	}
	
	@Override
	public String getLocalizedStatusMessage(String messageCode, String userRef)
	{
		String userId = EntityReference.getIdFromRef(userRef);
		ResourceLoader resourceLoader = new ResourceLoader(userId, "turnitin");
		return resourceLoader.getString(messageCode);
	}
	
	@Override
	public String getLocalizedStatusMessage(String messageCode)
	{
		return getLocalizedStatusMessage(messageCode, userDirectoryService.getCurrentUser().getReference());
	}
	
	@Override
	public String getReviewError(String contentId)
	{
    	return getLocalizedReviewErrorMessage(contentId);
    }

	@Override
	public String getLocalizedStatusMessage(String messageCode, Locale locale)
	{
		//TODO not sure how to do this with  the sakai resource loader
		return null;
	}
	
	/**
	 * Works by fetching the Instructor User info based on defaults or current
	 * user.
	 *
	 * @param siteId
	 * @param taskId
	 * @return
	 * @throws SubmissionException
	 * @throws TransientSubmissionException
	 */
	@SuppressWarnings("unchecked")
	@Override
	public Map getAssignment(String siteId, String taskId) throws SubmissionException, TransientSubmissionException {
		String taskTitle = getAssignmentTitle(taskId);

		Map params = TurnitinAPIUtil.packMap(turnitinConn.getBaseTIIOptions(),
			"assign", taskTitle, "assignid", taskId, "cid", siteId, "ctl", siteId,
			"fcmd", "7", "fid", "4", "utp", "2" );

		params.putAll(getInstructorInfo(siteId));

		return turnitinConn.callTurnitinReturnMap(params);
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
	public void createAssignment(String siteId, String asnId, Map extraAsnOpts) throws SubmissionException, TransientSubmissionException
	{
		syncAssignment(siteId, asnId, extraAsnOpts, null);
	}

	
	/* ------------------------------- END CONTENT REVIEW SERVICE API METHODS ------------------------------- */
	/*                                                                                                        */
	/* ------------------------------ PRIVATE / PROTECTED only below this line ------------------------------ */
	// TIITODO: separate the legacy and LTI methods, move legacy methods to delegate class for easy removal later?
	
	
	protected String getLTIReportAccess(ContentReviewItem item)
	{
		String ltiReportsUrl = null;
		String contentId = item.getContentId();
		String assignmentId = item.getTaskId();
		String siteId = item.getSiteId();
		
		// TIITODO: will we need to write a conversion script of sakai_site_property -> TiiActivityConfig?
		// TIITODO: strategy to determine which tool registration id to use?
		Optional<TiiInternalActivityConfig> activityConfig = getActivityConfig(TurnitinConstants.SAKAI_ASSIGNMENT_TOOL_ID, assignmentId);
		if (activityConfig.isPresent())
		{
			String ltiReportsId = activityConfig.get().getStealthedLtiId();
			String ltiResourceId = item.getExternalId();
			if (ltiResourceId == null)
			{
				// Fallback: link to assignment
				return getLTIAccess(activityConfig.get(), siteId);
			}
			// TIITODO: String.format + constant
			ltiReportsUrl = "/access/basiclti/site/" + siteId + "/content:" + ltiReportsId + ",resource:" + ltiResourceId;
			log.debug("getLTIReportAccess: " + ltiReportsUrl);
		}
		
		return ltiReportsUrl;
	}
	
	protected String getLTIAccess(TiiInternalActivityConfig activityConfig, String contextId)
	{
		String ltiId = activityConfig.getStealthedLtiId();
		if (ltiId == null)
		{
			return null;
		}
		// TIITODO: String.format + constant
		return "/access/basiclti/site/" + contextId + "/content:" + ltiId;
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
	protected Map getInstructorInfo(String siteId)
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
	
	protected Set<String> getActiveInstructorIds(String INST_ROLE, Site site)
	{
		log.debug("Getting active instructor IDs for permission " + INST_ROLE + " in site " + site.getId());

		Set<String> instIds = site.getUsersIsAllowed(INST_ROLE);

		//the site could contain references to deleted users
		List<User> activeUsers = userDirectoryService.getUsers(instIds);
		Set<String> ret =  new HashSet<>();
		for (int i = 0; i < activeUsers.size(); i++) {
			User user = activeUsers.get(i);
			// Ignore users who do not have a first and/or last name set or do not have
			// a valid email address, as this will cause a TII API call to fail
			if (user.getFirstName() != null && !user.getFirstName().trim().isEmpty() && 
		   	    user.getLastName() != null && !user.getLastName().trim().isEmpty() &&
			    getEmail(user) != null) {
				ret.add(user.getId());
			}
		}

		return ret;
	}
	
	// TIITODO: move these email/firstname/lastname methods into a delegate class
	
	// returns null if no valid email exists
	protected String getEmail(User user)
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
	protected String getUserFirstName(User user) {
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
	protected String getUserLastName(User user){
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
			log.debug(e.getMessage(), e);
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
		Date date = new Date(asn.getDateCreated().getTime());
		return Optional.of(date);
	}
	
	protected String getTEM(String cid) {
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
	protected String getAssignmentTitle(String taskId){
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
				log.debug(e.getMessage(), e);
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
            log.debug(e.getMessage(), e);
        }

        return title;
    }
	
	/*
	 * Fetch reports on a class by class basis
	 */
	@SuppressWarnings({ "deprecation", "unchecked" })
	private void checkForReportsBulk() {

		SimpleDateFormat dform = ((SimpleDateFormat) DateFormat.getDateInstance());
		dform.applyPattern(TurnitinConstants.TURNITIN_DATETIME_FORMAT);

		log.info("Fetching reports from Turnitin");
		
		// get the list of all items that are waiting for reports
		// but skip items with externalId = null, this happens when the LTI integration's callback fails. In this case, they'll be resubmitted by the queue job.
		// For the Sakai API integration, we should never enter the report state with externalId = null
		List<ContentReviewItem> awaitingReport = crqServ.getAwaitingReports(getProviderId()).stream()
				.filter(item -> item.getExternalId() != null).collect(Collectors.toList());
		
		// Iterate through all items in status 10 (report pending, generated on due date)
		awaitingReport.addAll(dao.findAwaitingReportsOnDueDate(getProviderId()));
		
		Iterator<ContentReviewItem> listIterator = awaitingReport.iterator();
		HashMap<String, Integer> reportTable = new HashMap<>();

		log.debug("There are " + awaitingReport.size() + " submissions awaiting reports");

		ContentReviewItem currentItem;
		while (listIterator.hasNext()) {
			currentItem = (ContentReviewItem) listIterator.next();
			
			org.sakaiproject.assignment.api.model.Assignment assignment;
			try
			{
				assignment = assignmentService.getAssignment(currentItem.getTaskId());
				if (assignment != null)
				{
					// If the assignment is set to generate reports on the due date, and the effective due date is in the future,
					// skip to next item without incrementing retry count
					if (ContentReviewConstants.SUBMITTED_REPORT_ON_DUE_DATE_CODE.equals(currentItem.getStatus()))
					{
						int dueDateBuffer = serverConfigurationService.getInt("contentreview.due.date.queue.job.buffer.minutes", 0);
						if (System.currentTimeMillis() < getEffectiveDueDate(currentItem.getTaskId(),
								assignment.getCloseDate().getTime(), assignment.getProperties(), dueDateBuffer))
						{
							continue;
						}
					}
				}
			}
			catch (IdUnusedException | PermissionException e)
			{
				// If the assignment no longer exists or if there was a permission exception, increment the item's retry count and skip to next item
				String errorMsg = "Cant get assignment by taskID = " + currentItem.getTaskId() + ", skipping to next item";
				log.warn(errorMsg, e);
				incrementRetryCountAndProcessError(currentItem, currentItem.getStatus(), errorMsg, null);
				continue;
			}

			// has the item reached its next retry time?
			if (currentItem.getNextRetryTime() == null)
			{
				currentItem.setNextRetryTime(new Date());
			}
			else if (currentItem.getNextRetryTime().after(new Date()))
			{
				//we haven't reached the next retry time
				log.info("next retry time not yet reached for item: " + currentItem.getId());
				crqServ.update(currentItem);
				continue;
			}

			if (currentItem.getRetryCount() == null )
			{
				currentItem.setRetryCount(Long.valueOf(0));
				currentItem.setNextRetryTime(this.getNextRetryTime(0));
			}
			else if (currentItem.getRetryCount().intValue() > maxRetry)
			{
				processError( currentItem, ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_RETRY_EXCEEDED_CODE, null, null );
				continue;
			}
			else
			{
				// If associated assignment does not have content review enabled, increment the item's retry count and skip to next item
				if (assignment != null && !assignment.getContentReview())
				{
					String errorMsg = "Assignment with ID = " + currentItem.getTaskId() + " does not have content review enabled; skipping to next item";
					log.warn(errorMsg);
					incrementRetryCountAndProcessError(currentItem, currentItem.getStatus(), errorMsg, null);
					continue;
				}
				
				log.debug("Still have retries left, continuing. ItemID: " + currentItem.getId());
			}

			Site s;
			try
			{
				s = siteService.getSite(currentItem.getSiteId());
			}
			catch (IdUnusedException iue)
			{
				log.warn("checkForReportsBulk: Site " + currentItem.getSiteId() + " not found!" + iue.getMessage());
				// long l = currentItem.getRetryCount().longValue();
				// l++;
				// currentItem.setRetryCount(Long.valueOf(l));
				// currentItem.setNextRetryTime(this.getNextRetryTime(Long.valueOf(l)));
				// missing site is unrecoverable(?), skip to final retry immediately
				// TIITODO: confirm for softly deleted sites, switch back to commented out logic above if required
				currentItem.setRetryCount(maxRetry);
				currentItem.setNextRetryTime(getNextRetryTime(maxRetry));
				currentItem.setLastError("Site not found");
				crqServ.update(currentItem);
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
					String errorMsg = "Could not find TII paper id for the content " + currentItem.getContentId();
					log.warn(errorMsg);
					incrementRetryCountAndProcessError(currentItem, currentItem.getStatus(), errorMsg, null);
					long l = currentItem.getRetryCount();
					l++;
					currentItem.setRetryCount(l);
					currentItem.setNextRetryTime(this.getNextRetryTime(l));
					currentItem.setLastError("Could not find TII paper id for the submission");
					crqServ.update(currentItem);
					continue;
				}

				TurnitinReturnValue result = tiiUtil.makeLTIcall(TurnitinLTIUtil.INFO_SUBMISSION, paperId, ltiProps);
				if(result.getResult() >= 0){
					currentItem.setReviewScore(result.getResult());
					currentItem.setStatus(ContentReviewConstants.CONTENT_REVIEW_SUBMITTED_REPORT_AVAILABLE_CODE);
					currentItem.setDateReportReceived(new Date());
					currentItem.setLastError(null);
					currentItem.setErrorCode(null);
					crqServ.update(currentItem);

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
							// If assignment set to generate reports on due date, set status appropriately
							String errorMsg = "";
							// TIITODO: fix this so it works with the new Assignments API or TiiActivityConfig, depending on what we decide
							/*if(assignmentContent != null && TurnitinConstants.GEN_REPORTS_ON_DUE_DATE_SETTING.equals(
									assignmentContent.getGenerateOriginalityReport())) {
								currentItem.setStatus(ContentReviewConstants.SUBMITTED_REPORT_ON_DUE_DATE_CODE);
								errorMsg = "Report is still pending for paper " + paperId + "; will be generated on due date";
							} else {
								currentItem.setStatus(ContentReviewConstants.CONTENT_REVIEW_SUBMITTED_AWAITING_REPORT_CODE);
								errorMsg = "Report is still pending for paper " + paperId;
							}*/

							log.debug(errorMsg);
							processError(currentItem, currentItem.getStatus(), result.getErrorMessage(), result.getResult());
					} else {
							String errorMsg = "Error making LTI call; report data error: " + result.getResult();
							log.error(errorMsg);
							incrementRetryCountAndProcessError(currentItem, ContentReviewConstants.CONTENT_REVIEW_REPORT_ERROR_RETRY_CODE, errorMsg, null);
					}
				}

				continue;
			}
			//////////////////////////////  OLD API INTEGRATION  ///////////////////////////////

			if (currentItem.getExternalId() == null || currentItem.getExternalId().equals("")) {
				currentItem.setStatus(ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_RETRY_CODE);
				crqServ.update(currentItem);
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
						retform.applyPattern(TurnitinConstants.TURNITIN_DATETIME_FORMAT);
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
							if (ContentReviewConstants.CONTENT_REVIEW_REPORT_ERROR_RETRY_CODE.equals(currentItem.getStatus())) {
								processError(currentItem, ContentReviewConstants.CONTENT_REVIEW_SUBMITTED_AWAITING_REPORT_CODE, null, null);
							}
							continue;
						}
						else {
							log.debug("Incrementing retry count for currentItem: " + currentItem.getId());
							incrementRetryCountAndProcessError(currentItem, currentItem.getStatus(), null, null);
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
					processError(currentItem, ContentReviewConstants.CONTENT_REVIEW_REPORT_ERROR_RETRY_CODE, e.getMessage(), null);
					break;
				}
				catch (SubmissionException e) {
					log.warn("Update failed due to SubmissionException error: " + e.toString(), e);
					processError(currentItem, ContentReviewConstants.CONTENT_REVIEW_REPORT_ERROR_RETRY_CODE, e.getMessage(), null);
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
					currentItem.setStatus(ContentReviewConstants.CONTENT_REVIEW_SUBMITTED_REPORT_AVAILABLE_CODE);
					currentItem.setDateReportReceived(new Date());
					currentItem.setLastError(null);
					currentItem.setErrorCode(null);
					crqServ.update(currentItem);

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
	private String[] getAcceptableFileExtensions()
	{
		String[] extensions = serverConfigurationService.getStrings(TurnitinConstants.SAK_PROP_ACCEPTABLE_FILE_EXTENSIONS);
		if (extensions != null && extensions.length > 0)
		{
			return extensions;
		}
		return TurnitinConstants.DEFAULT_ACCEPTABLE_FILE_EXTENSIONS;
	}

	// TII-157	--bbailla2
	private String[] getAcceptableMimeTypes()
	{
		String[] mimeTypes = serverConfigurationService.getStrings(TurnitinConstants.SAK_PROP_ACCEPTABLE_MIME_TYPES);
		if (mimeTypes != null && mimeTypes.length > 0)
		{
			return mimeTypes;
		}
		return TurnitinConstants.DEFAULT_ACCEPTABLE_MIME_TYPES;
	}
	
	// TII-157	--bbailla2
	private String [] getAcceptableFileTypes()
	{
		// TIITODO: no default value?
		return serverConfigurationService.getStrings(TurnitinConstants.SAK_PROP_ACCEPTABLE_FILE_TYPES);
	}
	
	// TIITODO: complete these two methods - public? Or private with specific methods for individual properties?
	protected Optional<TiiInternalActivityConfig> getActivityConfig(String toolId, String activityId)
	{
		/*Search search = new Search();
		search.addRestricion(new Restriction("toolId", toolId));
		search.addRestriction(new Restriction("activityId", activityId));
		return Optional.ofNullable(dao.findOneBySearch(TiiActivityConfig.class, search));*/
		
		return Optional.empty();
	}

	protected boolean saveOrUpdateActivityConfig(TiiInternalActivityConfig activityConfig)
	{
		// TIITODO: implement this using TiiActivityConfigDao
		// ContentReviewItemDao extends HibernateCommonDao; save() invokes Session.saverOrUpdate()
		//dao.save(activityConfig);
		
		// TIITODO: we need to return a boolean value because if the save fails we need to know
		// probably can just catch whatever runtime exception hibernate throws these days
		return true;
 	}
	
	/* TIITODO: remove these commented out methods after the switch to TiiActivityConfig is complete and working
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
	}*/
	
	private String getLocalizedReviewErrorMessage(String contentId)
	{
		log.debug("Returning review error for content: " + contentId);

		Optional<ContentReviewItem> item = crqServ.getQueuedItem(getProviderId(), contentId);
		
		if (item.isPresent())
		{
			// its possible the error code column is not populated
			Integer errorCode = item.get().getErrorCode();
			if (errorCode == null)
			{
				return item.get().getLastError();
			}
			
			return getLocalizedStatusMessage(errorCode.toString());
		}

		log.debug("Content " + contentId + " has not been queued previously");
		return null;
	}
	
	/**
	 * Syncs an assignment and handles individual student extensions
	 */
	protected void syncAssignment(String siteId, String taskId, Map<String, Object> extraAsnnOpts, Date extensionDate) throws SubmissionException, TransientSubmissionException
	{
		Site s = null;
		try {
			s = siteService.getSite(siteId);
		}
		catch (IdUnusedException iue) {
			log.warn("createAssignment: Site " + siteId + " not found!" + iue.getMessage());
			throw new TransientSubmissionException("Create Assignment not successful. Site " + siteId + " not found");
		}
		org.sakaiproject.assignment.api.model.Assignment asn;
		try
		{
			asn = assignmentService.getAssignment(taskId);
		}
		catch (IdUnusedException|PermissionException e)
		{
			asn = null;
		}

		//////////////////////////////  NEW LTI INTEGRATION  ///////////////////////////////
		
		Optional<Date> asnCreationDateOpt = getAssignmentCreationDate(asn);
		if(asnCreationDateOpt.isPresent() && siteAdvisor.siteCanUseLTIReviewServiceForAssignment(s, asnCreationDateOpt.get())){
			log.debug("Creating new TII assignment using the LTI integration");
			
			String asnId = asnRefToId(taskId);  // taskId is an assignment reference, but we sometimes only want the assignment id
			//String ltiId = getActivityConfigValue(TurnitinConstants.STEALTHED_LTI_ID, asnId, TurnitinConstants.SAKAI_ASSIGNMENT_TOOL_ID, TurnitinConstants.PROVIDER_ID);
			String ltiId = getActivityConfig(TurnitinConstants.SAKAI_ASSIGNMENT_TOOL_ID, asnId)
					.map(TiiInternalActivityConfig::getStealthedLtiId).orElse("");
			String ltiReportsId = null;

			ltiReportsId = s.getProperties().getProperty("turnitin_reports_lti_id");
			log.debug("This assignment has associated the following LTI Reports id: " + ltiReportsId);
			
			Map<String,String> ltiProps = new HashMap<>();
			if (extraAsnnOpts == null){
				throw new TransientSubmissionException("Create Assignment not successful. Empty extraAsnnOpts map");
			}

			ltiProps.put("context_id", siteId);
			ltiProps.put("context_title", s.getTitle());
			String contextLabel = s.getTitle();
			if(s.getShortDescription() != null){
				contextLabel = s.getShortDescription();
			}
			ltiProps.put("context_label", contextLabel);
			ltiProps.put("resource_link_id", taskId);
			String title = extraAsnnOpts.get("title").toString();
			ltiProps.put("resource_link_title", title);
			String description = extraAsnnOpts.get("instructions").toString();
			if(description != null){
				description = description.replaceAll("\\<.*?>","");//TODO improve this
				int instructionsMax = serverConfigurationService.getInt("contentreview.instructions.max", 1000);
				if(description.length() > instructionsMax){
					description = description.substring(0, instructionsMax);
				}
			}
			ltiProps.put("resource_link_description", description);

			// TII-245
			if (!StringUtils.isBlank(ltiId))
			{
				// This is an existing LTI instance, need to handle student extensions
				handleIndividualExtension(extensionDate, taskId, extraAsnnOpts);
			}
			
			String custom = BasicLTIConstants.RESOURCE_LINK_ID + "=" + taskId;
			custom += "\n" + BasicLTIConstants.RESOURCE_LINK_TITLE + "=" + title;
			custom += "\n" + BasicLTIConstants.RESOURCE_LINK_DESCRIPTION + "=" + description;

			try
			{
				long timestampOpen = (Long) extraAsnnOpts.get("timestampOpen");
				long timestampDue = (Long) extraAsnnOpts.get("timestampDue");
				// TII-245 - add a buffer to the TII due date to give time for the process queue job
				timestampDue += serverConfigurationService.getInt("contentreview.due.date.queue.job.buffer.minutes", 0) * 60000;
				ZonedDateTime open = ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestampOpen), ZoneOffset.UTC);
				ZonedDateTime due = ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestampDue), ZoneOffset.UTC);
				// Turnitin requires dates in ISO8601 format. The example from their documentation is "2014-12-10T07:43:43Z".
				// This matches the Java formatter ISO_INSTANT
				String isoStart = open.format(DateTimeFormatter.ISO_INSTANT);
				String isoDue = due.format(DateTimeFormatter.ISO_INSTANT);
				ltiProps.put("startdate", isoStart);
				ltiProps.put("duedate", isoDue);
				ltiProps.put("feedbackreleasedate", isoDue);
				custom += "\n" + "startdate=" + isoStart;
				custom += "\n" + "duedate=" + isoDue;
				custom += "\n" + "feedbackreleasedate=" + isoDue;
			}
			catch (DateTimeException e)
			{
				log.error(e.getMessage(), e);
				throw new TransientSubmissionException("Create Assignment not successful. Invalid open and/or due date.");
			}
			
			ltiProps = putInstructorInfo(ltiProps, siteId);

			/*
			 * Force TII max points to 100 so we can interpret the result as a direct percentage.
			 * This is done because Assignments now has the ability to grade to an arbitrary number of decimal places.
			 * Due to the limitation of TII requiring whole integers for grading, we would have to inflate the grade by a
			 * factor to the power of the number of decimal places allowed. This would result in unusually large numbers
			 * on the TII, which could be confusing for the end user.
			 */
			ltiProps.put("maxpoints", "100");
			custom += "\n" + "maxpoints=100";

	        ltiProps.put("studentpapercheck", extraAsnnOpts.get("s_paper_check").toString());
	        ltiProps.put("journalcheck",extraAsnnOpts.get("journal_check").toString());

	        ltiProps.put("internetcheck", extraAsnnOpts.get("internet_check").toString());
	        ltiProps.put("institutioncheck",extraAsnnOpts.get("institution_check").toString());
			ltiProps.put("allow_non_or_submissions", extraAsnnOpts.get("allow_any_file").toString());

			//ONLY FOR TII UK
			//ltiProps.setProperty("anonymous_marking_enabled", extraAsnnOpts.get("s_paper_check"));
			
			custom += "\n" + "studentpapercheck=" + extraAsnnOpts.get("s_paper_check").toString();
			custom += "\n" + "journalcheck=" + extraAsnnOpts.get("journal_check").toString();
			custom += "\n" + "internetcheck=" + extraAsnnOpts.get("internet_check").toString();
			custom += "\n" + "institutioncheck=" + extraAsnnOpts.get("institution_check").toString();
			custom += "\n" + "allow_non_or_submissions=" + extraAsnnOpts.get("allow_any_file").toString();
 
			if (extraAsnnOpts.containsKey("exclude_type") && extraAsnnOpts.containsKey("exclude_value")){
				//exclude type 0=none, 1=words, 2=percentages
				String typeAux = "words";
				if(extraAsnnOpts.get("exclude_type").toString().equals("2")){
					typeAux = "percentage";
				}
				ltiProps.put("exclude_type", typeAux);
				ltiProps.put("exclude_value", extraAsnnOpts.get("exclude_value").toString());
				custom += "\n" + "exclude_type=" + typeAux;
				custom += "\n" + "exclude_value=" + extraAsnnOpts.get("exclude_value").toString();
			}

	        ltiProps.put("late_accept_flag", extraAsnnOpts.get("late_accept_flag").toString());
	        ltiProps.put("report_gen_speed", extraAsnnOpts.get("report_gen_speed").toString());
	        ltiProps.put("s_view_reports", extraAsnnOpts.get("s_view_report").toString());			
	        ltiProps.put("submit_papers_to", extraAsnnOpts.get("submit_papers_to").toString());
			
			custom += "\n" + "late_accept_flag=" + extraAsnnOpts.get("late_accept_flag").toString();			
			custom += "\n" + "report_gen_speed=" + extraAsnnOpts.get("report_gen_speed").toString();
			custom += "\n" + "s_view_reports=" + extraAsnnOpts.get("s_view_report").toString();
			custom += "\n" + "submit_papers_to=" + extraAsnnOpts.get("submit_papers_to").toString();

			if (extraAsnnOpts.containsKey("exclude_biblio")){
				ltiProps.put("use_biblio_exclusion", extraAsnnOpts.get("exclude_biblio").toString());
				custom += "\n" + "use_biblio_exclusion=" + extraAsnnOpts.get("exclude_biblio").toString();
			}
			if (extraAsnnOpts.containsKey("exclude_quoted")){
				ltiProps.put("use_quoted_exclusion", extraAsnnOpts.get("exclude_quoted").toString());
				custom += "\n" + "use_quoted_exclusion=" + extraAsnnOpts.get("exclude_quoted").toString();
			}
 			
			//adding callback url
			String callbackUrl = serverConfigurationService.getServerUrl() + "/sakai-contentreview-tool-tii/tii-servlet";
			log.debug("callbackUrl: " + callbackUrl);
			ltiProps.put("ext_resource_tool_placement_url", callbackUrl);
			
			TurnitinReturnValue result = tiiUtil.makeLTIcall(TurnitinLTIUtil.BASIC_ASSIGNMENT, null, ltiProps);
			if(result.getResult() < 0){
				log.error("Error making LTI call");
				throw new TransientSubmissionException("Create Assignment not successful. Check the logs to see message.");
			}
			
			Properties sakaiProps = new Properties();
			String globalId = tiiUtil.getGlobalTurnitinLTIToolId();
			String globalReportsId = tiiUtil.getGlobalTurnitinReportsLTIToolId();
			if(globalId == null){
				throw new TransientSubmissionException("Create Assignment not successful. TII LTI global id not set");
			}
			if (globalReportsId == null){
				throw new TransientSubmissionException("Create Assignment not successful. TII Reports LTI global id not set");
			}

			sakaiProps.setProperty(LTIService.LTI_SITE_ID,siteId);
			sakaiProps.setProperty(LTIService.LTI_TITLE,title);

			log.debug("Storing custom params: " + custom);
			sakaiProps.setProperty(LTIService.LTI_CUSTOM,custom);

			SecurityAdvisor advisor = new TurnitinReviewServiceImpl.SimpleSecurityAdvisor(sessionManager.getCurrentSessionUserId(), "site.upd", "/site/!admin");
			Object ltiContent = null;
			Object ltiReportsContent = null;
			try{
				securityService.pushAdvisor(advisor);
				sakaiProps.setProperty(LTIService.LTI_TOOL_ID, globalId);
				if(StringUtils.isEmpty(ltiId)){
					ltiContent = tiiUtil.insertTIIToolContent(globalId, sakaiProps);
				} else {//don't create lti tool if exists
					ltiContent = tiiUtil.updateTIIToolContent(ltiId, sakaiProps);
				}				
				// replace the property
				sakaiProps.setProperty(LTIService.LTI_TOOL_ID, globalReportsId);
				if (StringUtils.isEmpty(ltiReportsId))
				{
					ltiReportsContent = tiiUtil.insertTIIToolContent(globalReportsId, sakaiProps);
				}
				else
				{
					ltiReportsContent = tiiUtil.updateTIIToolContent(ltiReportsId, sakaiProps);
				}
			} catch(Exception e){
				throw new TransientSubmissionException("Create Assignment not successful. Error trying to insert TII tool content: " + e.getMessage());
			} finally {
				securityService.popAdvisor(advisor);
			}
				
			if(ltiContent == null){
				throw new TransientSubmissionException("Create Assignment not successful. Could not create LTI tool for the task: " + custom);

			} else if (ltiReportsContent == null){
				throw new TransientSubmissionException("Create Assignment not successful. Could not create LTI Reports tool for the task: " + custom);
			} else if (!StringUtils.isEmpty(ltiId) && !Boolean.TRUE.equals(ltiContent)){
				// if ltiId is not empty, the lti already exists, so we did an update. ltiContent is Boolean.TRUE if the update was successful
				throw new TransientSubmissionException("Update Assignment not successful. Error updating LTI stealthed tool: " + ltiId);
			} else if (ltiReportsId != null && !Boolean.TRUE.equals(ltiReportsContent)){
				throw new TransientSubmissionException("Update Assignment not successful. Error updating LTI reports stealthed tool: " + ltiReportsContent);
			} else if (StringUtils.isEmpty(ltiId) && !(ltiContent instanceof Long)){
				// if ltiId is empty, the lti is new, so we did an insert. ltiContent is a Long primary key if the update was successful
				throw new TransientSubmissionException("Create Assignment not successful. Error creating LTI stealthed tool: " + ltiContent);
			} else if (ltiReportsId == null && !(ltiReportsContent instanceof Long)){
				throw new TransientSubmissionException("Create Assignment not successful. Error creating LTI stealthed tool: " + ltiReportsContent);
			}
			if (StringUtils.isEmpty(ltiId) || ltiReportsId == null) {//we inserted, need to record the IDs
				log.debug("LTI content tool id: " + ltiContent);
				try{

					if (ltiReportsId == null)
					{
						ResourcePropertiesEdit rpe = s.getPropertiesEdit();
						rpe.addProperty("turnitin_reports_lti_id", String.valueOf(ltiReportsContent));
						siteService.save(s);
					}
				}
				catch (IdUnusedException e)
				{
					log.error("Could not store reports LTI tool ID " + ltiReportsContent + " for site " + s.getId(), e);
					throw new TransientSubmissionException("Create Assignment not successful. Error storing LTI stealthed reports tool: " + ltiReportsContent);
				}
				catch (PermissionException e)
				{
					log.error("Could not store reports LTI tool ID " + ltiReportsContent + " for site " + s.getId(), e);
					throw new TransientSubmissionException("Create Assignment not successful. Error storing LTI stealthed reports tool: " + ltiReportsContent);
				}

				/*boolean added = saveOrUpdateActivityConfigEntry(TurnitinConstants.STEALTHED_LTI_ID, String.valueOf(ltiContent), asnId, TurnitinConstants.SAKAI_ASSIGNMENT_TOOL_ID,
						TurnitinConstants.PROVIDER_ID, true);*/
				TiiInternalActivityConfig cfg = new TiiInternalActivityConfig(TurnitinConstants.SAKAI_ASSIGNMENT_TOOL_ID, asnId, String.valueOf(ltiContent));
				boolean added = saveOrUpdateActivityConfig(cfg);
				if (!added)
				{
					log.error("Could not store LTI tool ID " + ltiContent + " for assignment " + taskId);
					throw new TransientSubmissionException("Create Assignment not successful. Error storing LTI stealthed tool: " + ltiContent);
				}
			}
			
			//add submissions to the queue if there is any
			try{
				log.debug("Adding previous submissions");
				//this will be done always - no problem, extra checks
				log.debug("Assignment " + asn.getId() + " - " + asn.getTitle());
				Set<AssignmentSubmission> submissions = assignmentService.getSubmissions(asn);
				if(submissions != null){
					for(AssignmentSubmission sub : submissions){
						//if submitted
						if(sub.getSubmitted()){
							log.debug("Submission " + sub.getId());
							// TIITODO: figure out/add the appropriate replacement for isAllowAnyFile()
							//boolean allowAnyFile = asn.getContent().isAllowAnyFile();
							boolean allowAnyFile = true; // TIITODO: remove this temp line once above is worked out
							List<ContentResource> resources = getAllAcceptableAttachments(sub,allowAnyFile);

							// determine the owner of the submission for purposes of content review
							// TIITODO: figure out/add the appropriate replacements for the two sub methods below
							//String ownerId = asn.getIsGroup() ? sub.getSubmittedForGroupByUserId() : sub.getSubmitterId();
							String ownerId = "";  // TIITODO: remove this temp line once above is worked out
							if (ownerId.isEmpty())
							{
								String msg = "Unable to submit content items to review service for submission %s to assignment %s. "
										+ "An appropriate owner for the submission cannot be determined.";
								log.warn(String.format(msg, sub.getId(), asn.getId()));
								continue;
							}

							List<ContentResource> toQueue = new ArrayList<>();
							for(ContentResource resource : resources)
							{
								//if it wasnt added
								if (!dao.findByProviderAndContentId(getProviderId(), resource.getId()).isPresent())
								{
									log.debug(resource.getId() + " was not added previously, queueing now");
									toQueue.add(resource);
								}
								//else - is there anything or any status we should check?
							}
							
							if (!toQueue.isEmpty())
							{
								//queueContent(ownerId, null, asn.getReference(), resource.getId(), sub.getId(), false);
								// TIITODO: confirm the below call is an appropriate replacement for the above. There
								// is no longer a getReference() method on asn, and the queueContent signature has changed.
								queueContent(ownerId, null, asn.getId(), toQueue);
							}
						}
					}
				}	
			} catch(Exception e){
				log.warn("Error while tying to queue previous submissions.");
			}
			
			return;
		}
		
		//////////////////////////////  OLD API INTEGRATION  ///////////////////////////////
	
		//get the assignment reference
		String taskTitle = "";
		if(extraAsnnOpts.containsKey("title")){
			taskTitle = extraAsnnOpts.get("title").toString();
		}else{
			getAssignmentTitle(taskId);
		}
		log.debug("Creating assignment for site: " + siteId + ", task: " + taskId +" tasktitle: " + taskTitle);

		SimpleDateFormat dform = ((SimpleDateFormat) DateFormat.getDateInstance());
		dform.applyPattern(TurnitinConstants.TURNITIN_DATETIME_FORMAT);
		Calendar cal = Calendar.getInstance();
		//set this to yesterday so we avoid timezone problems etc
		//TII-143 seems this now causes problems may need a finner tweak than 1 day like midnight +1 min or something
		cal.set(Calendar.HOUR, 0);
		cal.set(Calendar.MINUTE, 1);
		//cal.add(Calendar.DAY_OF_MONTH, -1);
		String dtstart = dform.format(cal.getTime());
		String today = dtstart;

		//set the due dates for the assignments to be in 5 month's time
		//turnitin automatically sets each class end date to 6 months after it is created
		//the assignment end date must be on or before the class end date

		String fcmd = "2";                                            //new assignment
		boolean asnnExists = false;
		// If this assignment already exists, we should use fcmd 3 to update it.
		Map tiiresult = this.getAssignment(siteId, taskId);
		if (tiiresult.get("rcode") != null && tiiresult.get("rcode").equals("85")) {
		    fcmd = "3";
		    asnnExists = true;
		}

		/* Some notes about start and due dates. This information is
		 * accurate as of Nov 12, 2009 and was determined by testing
		 * and experimentation with some Sash scripts.
		 *
		 * A turnitin due date, must be after the start date. This makes
		 * sense and follows the logic in both Assignments 1 and 2.
		 *
		 * When *creating* a new Turnitin Assignment, the start date
		 * must be todays date or later.  The format for dates only
		 * includes the day, and not any specific times. I believe that,
		 * in order to make up for time zone differences between your
		 * location and the turnitin cloud, it can be basically the
		 * current day anywhere currently, with some slack. For instance
		 * I can create an assignment for yesterday, but not for 2 days
		 * ago. Doing so causes an error.
		 *
		 * However!  For an existing turnitin assignment, you appear to
		 * have the liberty of changing the start date to sometime in
		 * the past. You can also change an assignment to have a due
		 * date in the past as long as it is still after the start date.
		 *
		 * So, to avoid errors when syncing information, or adding
		 * turnitin support to new or existing assignments we will:
		 *
		 * 1. If the assignment already exists we'll just save it.
		 *
		 * 2. If the assignment does not exist, we will save it once using
		 * todays date for the start and due date, and then save it again with
		 * the proper dates to ensure we're all tidied up and in line.
		 *
		 * Also, with our current class creation, due dates can be 5
		 * years out, but not further. This seems a bit lower priortity,
		 * but we still should figure out an appropriate way to deal
		 * with it if it does happen.
		 *
		 */



		//TODO use the 'secret' function to change this to longer
		cal.add(Calendar.MONTH, 5);
		String dtdue = dform.format(cal.getTime());
		log.debug("Set date due to: " + dtdue);
		if (extraAsnnOpts != null && extraAsnnOpts.containsKey("dtdue")) {
			dtdue = extraAsnnOpts.get("dtdue").toString();
			log.debug("Settign date due from external to: " + dtdue);
			extraAsnnOpts.remove("dtdue");
		}

		String fid = "4";						//function id
		String utp = "2"; 					//user type 2 = instructor
		String s_view_report = "1";
		if (extraAsnnOpts != null && extraAsnnOpts.containsKey("s_view_report")) {
			s_view_report = extraAsnnOpts.get("s_view_report").toString();
			extraAsnnOpts.remove("s_view_report");
		}

		//erater
		String erater = (serverConfigurationService.getBoolean("turnitin.option.erater.default", false)) ? "1" : "0";
		String ets_handbook ="1";
		String ets_dictionary="en";
		String ets_spelling = "1";
		String ets_style = "1";
		String ets_grammar = "1";
		String ets_mechanics = "1";
		String ets_usage = "1";

		try{
			if (extraAsnnOpts != null && extraAsnnOpts.containsKey("erater")) {
				erater = extraAsnnOpts.get("erater").toString();
				extraAsnnOpts.remove("erater");

				ets_handbook = extraAsnnOpts.get("ets_handbook").toString();
				extraAsnnOpts.remove("ets_handbook");

				ets_dictionary = extraAsnnOpts.get("ets_dictionary").toString();
				extraAsnnOpts.remove("ets_dictionary");

				ets_spelling = extraAsnnOpts.get("ets_spelling").toString();
				extraAsnnOpts.remove("ets_spelling");

				ets_style = extraAsnnOpts.get("ets_style").toString();
				extraAsnnOpts.remove("ets_style");

				ets_grammar = extraAsnnOpts.get("ets_grammar").toString();
				extraAsnnOpts.remove("ets_grammar");

				ets_mechanics = extraAsnnOpts.get("ets_mechanics").toString();
				extraAsnnOpts.remove("ets_mechanics");

				ets_usage = extraAsnnOpts.get("ets_usage").toString();
				extraAsnnOpts.remove("ets_usage");
			}
		}catch(Exception e){
			log.info("(createAssignment)erater extraAsnnOpts. "+e);
		}

		String cid = siteId;
		String assignid = taskId;
		String ctl = siteId;

		Map params = TurnitinAPIUtil.packMap(turnitinConn.getBaseTIIOptions(),
				"assign", taskTitle,
				"assignid", assignid,
				"cid", cid,
				"ctl", ctl,
				"dtdue", dtdue,
				"dtstart", dtstart,
				"fcmd", "3",
				"fid", fid,
				"s_view_report", s_view_report,
				"utp", utp,
				"erater",erater,
				"ets_handbook",ets_handbook,
				"ets_dictionary",ets_dictionary,
				"ets_spelling",ets_spelling,
				"ets_style",ets_style,
				"ets_grammar",ets_grammar,
				"ets_mechanics",ets_mechanics,
				"ets_usage",ets_usage
				);

		// Save instructorInfo up here to reuse for calls in this
		// method, since theoretically getInstructorInfo could return
		// different instructors for different invocations and we need
		// the same one since we're using a session id.
		Map instructorInfo = getInstructorInfo(siteId);
		params.putAll(instructorInfo);

		if (extraAsnnOpts != null) {
			for (Object key: extraAsnnOpts.keySet()) {
				if (extraAsnnOpts.get(key) == null) {
					continue;
				}
				params = TurnitinAPIUtil.packMap(params, key.toString(),
						extraAsnnOpts.get(key).toString());
			}
		}

		// We only need to use a session id if we are creating this
		// assignment for the first time.
		String sessionid = null;
		Map sessionParams = null;

		if (!asnnExists) {
			// Try adding the user in case they don't exist TII-XXX
			addTurnitinInstructor(instructorInfo);

			sessionParams = turnitinConn.getBaseTIIOptions();
			sessionParams.putAll(instructorInfo);
			sessionParams.put("utp", utp);
			sessionid = TurnitinSessionFuncs.getTurnitinSession(turnitinConn, sessionParams);

			Map firstparams = new HashMap();
			firstparams.putAll(params);
			firstparams.put("session-id", sessionid);
			firstparams.put("dtstart", today);

			// Make the due date in the future
			Calendar caldue = Calendar.getInstance();
			caldue.add(Calendar.MONTH, 5);
			String dtdue_first = dform.format(caldue.getTime());
			firstparams.put("dtdue", dtdue_first);

			log.debug("date due is: " + dtdue);
			log.debug("Start date: " + today);
			firstparams.put("fcmd", "2");
			Document firstSaveDocument =
				turnitinConn.callTurnitinReturnDocument(firstparams);
			Element root = firstSaveDocument.getDocumentElement();
			int rcode = new Integer(((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData().trim());
			if ((rcode > 0 && rcode < 100) || rcode == 419) {
				log.debug("Create FirstDate Assignment successful");
				log.debug("tii returned " + ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData().trim() + ". Code: " + rcode);
			} else {
				log.debug("FirstDate Assignment creation failed with message: " + ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData().trim() + ". Code: " + rcode);
				//log.debug(root);
				throw new TransientSubmissionException("FirstDate Create Assignment not successful. Message: " + ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData().trim() + ". Code: " + rcode
						, rcode);
			}
		}
		log.debug("going to attempt second update");
		if (sessionid != null) {
		    params.put("session-id", sessionid);
		}
		Document document = turnitinConn.callTurnitinReturnDocument(params);

		Element root = document.getDocumentElement();
		int rcode = new Integer(((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData().trim());
		if ((rcode > 0 && rcode < 100) || rcode == 419) {
			log.debug("Create Assignment successful");
			log.debug("tii returned " + ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData().trim() + ". Code: " + rcode);
		} else {
			log.debug("Assignment creation failed with message: " + ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData().trim() + ". Code: " + rcode);
			//log.debug(root);
			throw new TransientSubmissionException("Create Assignment not successful. Message: " + ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData().trim() + ". Code: " + rcode
					, rcode);
		}

		if (sessionid != null) {
		    TurnitinSessionFuncs.logoutTurnitinSession(turnitinConn, sessionid, sessionParams);
		}
	}
	
	protected boolean updateItemAccess(String contentId)
	{
		//return dao.updateIsUrlAccessed( contentId, true );
		// TIITODO: implement above using TiiContentReviewItemDao or equivalent
		
		return true; // TIITODO: remove this temp line once above is worked out
	}
	
	/**
	 * This will add to the LTI map the information for the instructor such as
	 * uem, username, ufn, etc. If the system is configured to use src9
	 * provisioning, this will draw information from the current thread based
	 * user. Otherwise it will use the default Instructor information that has
	 * been configured for the system.
	 *
	 * @param ltiProps
	 * @param siteId
	 * @return
	 */
	@SuppressWarnings("unchecked")
	// TIITODO: generics on these maps?
	private Map putInstructorInfo(Map ltiProps, String siteId) {

		log.debug("Putting instructor info for site " + siteId);

		if (!turnitinConn.isUseSourceParameter()) {
			ltiProps.put("roles", "Instructor");
			ltiProps.put("user_id", turnitinConn.getDefaultInstructorId());
			ltiProps.put("lis_person_contact_email_primary", turnitinConn.getDefaultInstructorEmail());
			ltiProps.put("lis_person_name_given", turnitinConn.getDefaultInstructorFName());
			ltiProps.put("lis_person_name_family", turnitinConn.getDefaultInstructorLName());
			ltiProps.put("lis_person_name_full", turnitinConn.getDefaultInstructorFName() + " " + turnitinConn.getDefaultInstructorLName());
		} else {
			String INST_ROLE = "section.role.instructor";
			User inst = null;
			try {
				Site site = siteService.getSite(siteId);
				User user = userDirectoryService.getCurrentUser();
	
				log.debug("Current user: " + user.getId());

				if (site.isAllowed(user.getId(), INST_ROLE)) {
					inst = user;
				} else {
					Set<String> instIds = getActiveInstructorIds(INST_ROLE,	site);
					if (instIds.size() > 0) {
						inst = userDirectoryService.getUser((String) instIds.toArray()[0]);
					}
				}
			} catch (IdUnusedException e) {
				log.error("Unable to fetch site in putInstructorInfo: " + siteId, e);
			} catch (UserNotDefinedException e) {
				log.error("Unable to fetch user in putInstructorInfo", e);
			}

			if (inst == null) {
				log.error("Instructor is null in putInstructorInfo");
			} else {
				ltiProps.put("roles", "Instructor");
				ltiProps.put("user_id", inst.getId());
				ltiProps.put("lis_person_contact_email_primary", getEmail(inst));
				ltiProps.put("lis_person_name_given", inst.getFirstName());
				ltiProps.put("lis_person_name_family", inst.getLastName());
				ltiProps.put("lis_person_name_full", inst.getDisplayName());
			}
		}

		return ltiProps;
	}
	
	//Methods for updating all assignments that exist
	private void doAssignments()
	{
		log.info("About to update all turnitin assignments");
		
		List<ContentReviewItem> items = crqServ.getAllContentReviewItemsGroupedBySiteAndTask(getProviderId());
		
		for (ContentReviewItem item : items)
		{
			try
			{
				updateAssignment(item.getSiteId(), item.getTaskId());
			}
			catch (SubmissionException e)
			{
				log.warn(e.getMessage(), e);
			}
		}
	}
	
	// copied from protected method assignmentId() in BaseAssignmentService
	// might be better to just make that method public
	private String asnRefToId(String ref)
	{
		if (ref == null) return ref;
		int i = ref.lastIndexOf(Entity.SEPARATOR);
		if (i == -1) return ref;
		String id = ref.substring(i + 1);
		return id;
	}
	
	/**
	 * TII-245
	 * Handles individual extensions
	 * After Turnitin's due date, it will only accept a paper if one has not already been submitted.
	 * When "Select User(s)and Allow Resubmission" is used, we have to push the due date back on the TII end to accommodate multiple submissions
	 */
	private void handleIndividualExtension(Date extensionDate, String taskId, Map<String, Object> extraAsnOpts)
	{	
		// Get the latest offered extenion.
		// We keep track of this in the activity config table to save us from querying every submission to find the latest extension.
		// This comes at the cost that we can never move TII's due date earlier once we've granted an extension; we can only push it out
		/*String strLatestExtensionDate = getActivityConfigValue(TurnitinConstants.TURNITIN_ASN_LATEST_INDIVIDUAL_EXTENSION_DATE,
				TurnitinConstants.SAKAI_ASSIGNMENT_TOOL_ID, taskId, TurnitinConstants.PROVIDER_ID);*/
		long latestExtensionDate = getActivityConfig(TurnitinConstants.SAKAI_ASSIGNMENT_TOOL_ID, taskId)
				.map(cfg -> cfg.getLatestIndividualExtensionDate().getTime()).orElse(0L);
		if (extensionDate != null)
		{
			// The due date we're sending to TII (latest of accept until / resubmit accept until)
			long timestampDue = (Long) extraAsnOpts.get("timestampDue");
			try
			{
				// Find what's later: the new extension date or the latest existing extension date

				// We are offering a student an individual extension, handle if it's later than the current latest extension date
				long lExtensionDate = extensionDate.getTime();
				if (lExtensionDate > latestExtensionDate)
				{
					// we have a new latest extension date
					// TIITODO: save/update this using the appropriate TiiActivityConfigDao method
					/*saveOrUpdateActivityConfigEntry(TurnitinConstants.TURNITIN_ASN_LATEST_INDIVIDUAL_EXTENSION_DATE,
							String.valueOf(lExtensionDate), TurnitinConstants.SAKAI_ASSIGNMENT_TOOL_ID, taskId,
							TurnitinConstants.PROVIDER_ID, true);*/
					latestExtensionDate = lExtensionDate;
				}

				// Push Turnitin's due date back if we need to accommodate an extension later than the due date
				if (latestExtensionDate > timestampDue)
				{
					// push the due date to the latest extension date
					extraAsnOpts.put("timestampDue", latestExtensionDate);
				}
			}
			catch (NumberFormatException nfe)
			{
				log.warn("NumberFormatException thrown when parsing either the timestampDue option: " + timestampDue);
			}
		}
	}
	
	/**
	 * A simple SecurityAdviser that can be used to override permissions for one user for one function.
	 */
	protected class SimpleSecurityAdvisor implements SecurityAdvisor
	{
		protected String m_userId;
		protected String m_function;
		protected String m_reference;

		public SimpleSecurityAdvisor(String userId, String function, String reference)
		{
			m_userId = userId;
			m_function = function;
			m_reference = reference;
		}

		public SecurityAdvisor.SecurityAdvice isAllowed(String userId, String function, String reference)
		{
			SecurityAdvisor.SecurityAdvice rv = SecurityAdvisor.SecurityAdvice.PASS;
			if (m_userId.equals(userId) && m_function.equals(function) && m_reference.equals(reference))
			{
				rv = SecurityAdvisor.SecurityAdvice.ALLOWED;
			}
			return rv;
		}
	}
	
	private List<ContentResource> getAllAcceptableAttachments(AssignmentSubmission sub, boolean allowAnyFile)
	{
		// TIITODO: implement the missing method on AssignmentSubmission or replace with suitable alternative
		//List attachments = sub.getSubmittedAttachments();
		List attachments = Collections.emptyList();  // TIITODO: replace this temp line after above is worked out
		List<ContentResource> resources = new ArrayList<>();
        for (int i = 0; i < attachments.size(); i++) {
            Reference attachment = (Reference) attachments.get(i);
            try {
                ContentResource res = contentHostingService.getResource(attachment.getId());
                if (isAcceptableSize(res) && (allowAnyFile || isAcceptableContent(res))) {
                    resources.add(res);
                }
            } catch (PermissionException | IdUnusedException | TypeException e) {
                log.warn(":getAllAcceptableAttachments " + e.getMessage());
            }
        }
        return resources;
	}
	
	protected boolean isAcceptableSize(ContentResource resource)
	{
		return turnitinContentValidator.isAcceptableSize(resource);
	}
	
	private void addTurnitinInstructor(Map userparams) throws SubmissionException, TransientSubmissionException {
		Map params = new HashMap();
		params.putAll(userparams);
		params.putAll(turnitinConn.getBaseTIIOptions());
		params.put("fid", "1");
		params.put("fcmd", "2");
		params.put("utp", "2");
		turnitinConn.callTurnitinReturnMap(params);
	}
	
	/**
	 * Update Assignment. This method is not currently called by Assignments 1.
	 * @param siteId
	 * @param taskId
	 * @throws org.sakaiproject.contentreview.exception.SubmissionException
	 */
	private void updateAssignment(String siteId, String taskId) throws SubmissionException {
		log.info("updateAssignment(" + siteId +" , " + taskId + ")");
		//get the assignment reference
		String taskTitle = getAssignmentTitle(taskId);
		log.debug("Creating assignment for site: " + siteId + ", task: " + taskId +" tasktitle: " + taskTitle);

		SimpleDateFormat dform = ((SimpleDateFormat) DateFormat.getDateInstance());
		dform.applyPattern(TurnitinConstants.TURNITIN_DATETIME_FORMAT);
		Calendar cal = Calendar.getInstance();
		//set this to yesterday so we avoid timezpne problems etc
		cal.add(Calendar.DAY_OF_MONTH, -1);
		String dtstart = dform.format(cal.getTime());


		//set the due dates for the assignments to be in 5 month's time
		//turnitin automatically sets each class end date to 6 months after it is created
		//the assignment end date must be on or before the class end date

		//TODO use the 'secret' function to change this to longer
		cal.add(Calendar.MONTH, 5);
		String dtdue = dform.format(cal.getTime());

		String fcmd = "3";						//new assignment
		String fid = "4";						//function id
		String utp = "2"; 					//user type 2 = instructor
		String s_view_report = "1";

                                            //erater
		String erater = "0";
                                           String ets_handbook ="1";
                                           String ets_dictionary="en";
                                           String ets_spelling = "1";
                                           String ets_style = "1";
                                           String ets_grammar = "1";
                                           String ets_mechanics = "1";
                                           String ets_usage = "1";

		String cid = siteId;
		String assignid = taskId;
		String assign = taskTitle;
		String ctl = siteId;

		String assignEnc = assign;
		try {
			if (assign.contains("&")) {
				//log.debug("replacing & in assignment title");
				assign = assign.replace('&', 'n');

			}
			assignEnc = assign;
			log.debug("Assign title is " + assignEnc);

		}
		catch (Exception e) {
			log.debug( e.getMessage(), e );
		}

		Map params = TurnitinAPIUtil.packMap(turnitinConn.getBaseTIIOptions(),
				"assign", assignEnc,
				"assignid", assignid,
				"cid", cid,
				"ctl", ctl,
				"dtdue", dtdue,
				"dtstart", dtstart,
				"fcmd", fcmd,
				"fid", fid,
				"s_view_report", s_view_report,
				"utp", utp,
                                                                                      "erater",erater,
                                                                                      "ets_handbook",ets_handbook,
                                                                                      "ets_dictionary",ets_dictionary,
                                                                                      "ets_spelling",ets_spelling,
                                                                                      "ets_style",ets_style,
                                                                                      "ets_grammar",ets_grammar,
                                                                                      "ets_mechanics",ets_mechanics,
                                                                                      "ets_usage",ets_usage
		);

		params.putAll(getInstructorInfo(siteId));

		Document document;

		try {
			document = turnitinConn.callTurnitinReturnDocument(params);
		}
		catch (TransientSubmissionException | SubmissionException tse) {
			log.error("Error on API call in updateAssignment siteid: " + siteId + " taskid: " + taskId, tse);
			return;
		}

		Element root = document.getDocumentElement();
		int rcode = new Integer(((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData().trim());
		if ((rcode > 0 && rcode < 100) || rcode == 419) {
			log.debug("Create Assignment successful");
		} else {
			log.debug("Assignment creation failed with message: " + ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData().trim() + ". Code: " + rcode);
			throw new SubmissionException("Create Assignment not successful. Message: " + ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData().trim() + ". Code: " + rcode);
		}
	}
	
	protected long getEffectiveDueDate(String assignmentID, long assignmentDueDate, Map<String, String> assignmentProperties, int dueDateBuffer)
	{
		long dueDateMillis = assignmentDueDate;
		if (assignmentProperties != null ) {
			// TIITODO: update this call so it works with the new Assignments API, and remove the temp line below it
			//String strResubmitCloseTime = assignmentProperties.getProperty(AssignmentSubmission.ALLOW_RESUBMIT_CLOSETIME);
			String strResubmitCloseTime = "";
			if (!StringUtils.isBlank(strResubmitCloseTime)) {
				try {
					// If the resubmit close time is after the close time, it effectively becomes the due date (on the TII side)
					long resubmitCloseTime = Long.parseLong(strResubmitCloseTime);
					if (resubmitCloseTime > dueDateMillis) {
						dueDateMillis = resubmitCloseTime;
					}
				} catch (NumberFormatException ex) {
					log.warn("NumberFormatException thrown when parsing the resubmitCloseTime: " + strResubmitCloseTime);
				}
			}
		}

		// If we've previously saved the date for a manually allowed student resubmission (extension), and it's after the previously
		// determined effective due date, the extension then becomes the effective due date
		// TIITODO: remove commented out line below once the new activity config is working
		/*String strLatestExtensionDate = getActivityConfigValue( TurnitinConstants.TURNITIN_ASN_LATEST_INDIVIDUAL_EXTENSION_DATE,
														 TurnitinConstants.SAKAI_ASSIGNMENT_TOOL_ID, assignmentID,
														 TurnitinConstants.PROVIDER_ID );*/
		long latestExtensionDate = getActivityConfig(TurnitinConstants.SAKAI_ASSIGNMENT_TOOL_ID, assignmentID)
				.map(cfg -> cfg.getLatestIndividualExtensionDate().getTime()).orElse(0L);

		if (latestExtensionDate > dueDateMillis) {
			dueDateMillis = latestExtensionDate;
		}

		// Push the due date by the necessary padding
		dueDateMillis += dueDateBuffer * 60000; // TIITODO: constant/sakai.property?
		return dueDateMillis;
	}
	
	private void incrementRetryCountAndProcessError(ContentReviewItem item, Long status, String error, Integer errorCode)
	{
		long l = item.getRetryCount();
		l++;
		item.setRetryCount(l);
		item.setNextRetryTime(getNextRetryTime(l));
		processError(item, status, error, errorCode);
	}
	
	/*
	 * Get the next item that needs to be submitted
	 *
	 */
	private Optional<ContentReviewItem> getNextItemInSubmissionQueue()
	{
		Optional<ContentReviewItem> nextItem = crqServ.getNextItemInQueueToSubmit(getProviderId());
		if (!nextItem.isPresent())
		{
			nextItem = dao.findSingleItemToSubmitMissingExternalId(getProviderId());
		}
		
		// TIITODO: old code used to handle setting the retry time if it was null. Do we still need to do this?
		// Possibly for items that have not yet been submitted?
		
		return nextItem;
		
		// TIITODO: remove commented out code (and methods) below once we know above code works		
		
		// Submit items that haven't yet been submitted
		/*Search search = new Search();
		search.addRestriction(new Restriction("status", ContentReviewItem.NOT_SUBMITTED_CODE));
		List<ContentReviewItem> notSubmittedItems = dao.findBySearch(ContentReviewItem.class, search);
		for( ContentReviewItem item : notSubmittedItems ) {

			// can we get a lock?
			if (obtainLock("item." + item.getId().toString())) {
				return item;
			}
		}

		// Submit items that should be retried
		search = new Search();
		search.addRestriction(new Restriction("status", ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE));
		notSubmittedItems = dao.findBySearch(ContentReviewItem.class, search);
		ContentReviewItem nextItem = getNotSubmittedItemPastRetryTime( notSubmittedItems );
		if( nextItem != null )
		{
			return nextItem;
		}

		// submit items that are awaiting reports, but the externalId is null (Ie. they've been submitted, but the callback to set the externalId failed).
		search = new Search();
		search.addRestriction(new Restriction("status", ContentReviewItem.SUBMITTED_AWAITING_REPORT_CODE));
		search.addRestriction(new Restriction("externalId", "", Restriction.NULL));
		notSubmittedItems = dao.findBySearch(ContentReviewItem.class, search);
		nextItem = getNotSubmittedItemPastRetryTime(notSubmittedItems);
		if (nextItem != null)
		{
			return nextItem;
		}

		// submit items that are awaiting reports in an errory_retry state, and the externalId is null (similar to above condition, just happens to be in an errory_retry state)
		search = new Search();
		search.addRestriction(new Restriction("status", ContentReviewItem.REPORT_ERROR_RETRY_CODE));
		search.addRestriction(new Restriction("externalId", "", Restriction.NULL));
		notSubmittedItems = dao.findBySearch(ContentReviewItem.class, search);
		nextItem = getNotSubmittedItemPastRetryTime(notSubmittedItems);
		if (nextItem != null)
		{
			return nextItem;
		}

		// Submit items that were previously marked as missing submitter details (first name, last name, email)
		search = new Search();
		search.addRestriction( new Restriction( "status", ContentReviewItem.SUBMISSION_ERROR_USER_DETAILS_CODE ) );
		notSubmittedItems = dao.findBySearch( ContentReviewItem.class, search );
		nextItem = getNotSubmittedItemPastRetryTime( notSubmittedItems );

		// At this point, nextItem could be null (indicating the submission queue is empty)
		return nextItem;*/
	}
	
	// TIITODO: remove if we don't need these (see above)
	/**
	 * Returns the first item in the list which has surpassed it's next retry time, and we can get a lock on the object.
	 * Otherwise returns null.
	 * 
	 * @param notSubmittedItems the list of ContentReviewItems to iterate over.
	 * @return the first item in the list that meets the requirements, or null.
	 */
	/*private ContentReviewItem getNotSubmittedItemPastRetryTime( List<ContentReviewItem> notSubmittedItems )
	{
		for( ContentReviewItem item : notSubmittedItems )
		{
			if( hasReachedRetryTime( item ) && obtainLock( "item." + item.getId().toString() ) )
			{
				return item;
			}
		}

		return null;
	}

	private boolean hasReachedRetryTime(ContentReviewItem item) {

		// has the item reached its next retry time?
		if (item.getNextRetryTime() == null)
		{
			item.setNextRetryTime(new Date());
		}

		if (item.getNextRetryTime().after(new Date())) {
			//we haven't reached the next retry time
			log.info("next retry time not yet reached for item: " + item.getId());
			crqServ.update(item);
			return false;
		}

		return true;

	}*/
}
