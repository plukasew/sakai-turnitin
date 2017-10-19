package org.sakaiproject.assignment.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.sakaiproject.assignment.api.AssignmentConstants;
import org.sakaiproject.assignment.api.AssignmentReferenceReckoner;
import org.sakaiproject.assignment.api.ContentReviewResult;
import org.sakaiproject.assignment.api.model.Assignment;
import org.sakaiproject.assignment.api.model.AssignmentSubmission;
import org.sakaiproject.content.api.ContentHostingService;
import org.sakaiproject.content.api.ContentResource;
import org.sakaiproject.contentreview.dao.ContentReviewConstants.ReviewStatus;
import org.sakaiproject.contentreview.dao.ContentReviewItem;
import org.sakaiproject.contentreview.exception.QueueException;
import org.sakaiproject.contentreview.exception.ReportException;
import org.sakaiproject.contentreview.exception.SubmissionException;
import org.sakaiproject.contentreview.service.ContentReviewService;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.exception.TypeException;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.authz.api.SecurityAdvisor;
import org.sakaiproject.entity.api.Reference;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.entity.api.EntityManager;

/**
 *
 * @author plukasew
 */
@Slf4j
public class AssignmentServiceContentReviewDelegate
{
	private final ContentReviewService contentReviewService;
	private final ContentHostingService contentHostingService;
	private final ServerConfigurationService serverConfigurationService;
	private final SecurityService securityService;
	private final SessionManager sessionManager;
	private final EntityManager entityManager;
	private final boolean exposeTurnitinErrorToUI;
	
	public AssignmentServiceContentReviewDelegate(ContentReviewService crs, ContentHostingService chs,
			ServerConfigurationService scs, SecurityService ss, SessionManager sm, EntityManager em)
	{
		contentReviewService = crs;
		contentHostingService = chs;
		serverConfigurationService = scs;
		securityService = ss;
		sessionManager = sm;
		entityManager = em;
		
		// TIITODO: generalize this sakai.property name if it useful for all services, or move this setting into
		// the Turnitin-specific code area
		exposeTurnitinErrorToUI = serverConfigurationService.getBoolean("turnitin.exposeErrorToUI", false);
	}
	
	/**
	 * Get the content review results for the given submissions in an appropriate format for display by Assignments tool
	 * @param submissions
	 * @return 
	 */
	public Map<String, List<ContentReviewResult>> getContentReviewResults(String siteId, Assignment asn, List<AssignmentSubmission> submissions)
	{
		Map<String, List<ContentReviewResult>> results = new HashMap<>(submissions.size());
		boolean allowAnyFile = true; // TIITODO: initialize properly. TiiActivityCfg? Is this a turnitin-only property? do we need a CRS method for this?
		
		// get the content review items for this assignment
		// TIITODO: do we have this already so we can pass it in?
		Map<String, ContentReviewItem> crItems = Collections.emptyMap();
		try
		{
			List<ContentReviewItem> items = contentReviewService.getAllContentReviewItems(siteId, getAsnRef(asn));
			crItems = items.stream().collect(Collectors.toMap(ContentReviewItem::getContentId, Function.identity()));
		}
		catch (QueueException | SubmissionException | ReportException e)
		{
			// TIITODO: now what?
			// if QueueException, should we queue it here like the old code did? 
		}
		
		for (AssignmentSubmission sub : submissions)
		{
			// get the attachments, turns out these will be attachment refs not attachment ids
			Set<String> attachments = sub.getAttachments();
			if (attachments.isEmpty())
			{
				results.put(sub.getId(), Collections.emptyList());
			}
			
			List<ContentReviewResult> subResults = new ArrayList<>(attachments.size());
			results.put(sub.getId(), subResults);
			
			for (String attachmentRef : attachments)
			{
				Reference ref = entityManager.newReference(attachmentRef);
				SecurityAdvisor resAdvisor = new ContentResourceSecurityAdvisor(sessionManager.getCurrentSessionUserId(), attachmentRef);
				securityService.pushAdvisor(resAdvisor);
				try
				{
					
					ContentResource res = contentHostingService.getResource(ref.getId());
					if (allowAnyFile || contentReviewService.isAcceptableContent(res))
					{
						ContentReviewItem crItem = crItems.get(res.getId());

						if (crItem == null)
						{
							// TIITODO: no content review item found, what to do?
						}
						else
						{
							ContentReviewResult crResult = new ContentReviewResult();
							crResult.setContentResource(res);
							crResult.setReviewScore(getReviewScore(crItem));
							crResult.setReviewReport(getReviewReport(crItem));
							crResult.setReviewIconCssClass(contentReviewService.getIconCssClassforScore(crResult.getReviewScore(), crItem.getContentId()));
							//skip review status, it's unused
							crResult.setReviewError(getReviewError(crItem));
							if ("true".equals(res.getProperties().getProperty(AssignmentConstants.PROP_INLINE_SUBMISSION)))
							{
								subResults.add(0, crResult);
							}
							else
							{
								subResults.add(crResult);
							}
						}
					}
				}
				catch (IdUnusedException | PermissionException | TypeException e)
				{
					//TIITODO: what now?
					log.warn("Unable to access content resource", e);
				}
				finally
				{
					securityService.popAdvisor(resAdvisor);
				}
			}
		}
		
		return results;
	}
	
	private int getReviewScore(ContentReviewItem item)
	{
		ReviewStatus reviewStatus = ReviewStatus.fromItemStatus(item.getStatus());
		if (reviewStatus == ReviewStatus.NOT_SUBMITTED || reviewStatus == ReviewStatus.UNKNOWN
				|| reviewStatus == ReviewStatus.SUBMITTED_AWAITING_REPORT)
		{
			return -2; // TIITODO: this is interpreted how? should the CRS check this instead?
		}
		
		// TIITODO: this seems like an inefficient way to get the score, is it not already on the item?
		//int score = contentReviewService.getReviewScore(APPLICATION_ID, APPLICATION_ID, APPLICATION_ID)
		return item.getReviewScore();  // TIITODO: what if this is null?
	}
	
	private String getReviewReport(ContentReviewItem item)
	{
		try
		{
			String contentId = item.getContentId();
			// TIITODO: for now assume LTI service
			/*try {
				Site site = SiteService.getSite(m_context);
				Date asnCreationDate = new Date(m_asn.getTimeCreated().getTime());
				boolean siteCanUseLTIReviewService = contentReviewSiteAdvisor.siteCanUseLTIReviewServiceForAssignment(site, asnCreationDate);
				if (siteCanUseLTIReviewService) {
					return contentReviewService.getReviewReport(contentId, null, null);
				} else {
					if (allowGradeSubmission(getReference())){
						return contentReviewService.getReviewReportInstructor(contentId, getAssignment().getReference(), UserDirectoryService.getCurrentUser().getId());
					} else {
						return contentReviewService.getReviewReportStudent(contentId, getAssignment().getReference(), UserDirectoryService.getCurrentUser().getId());
					}
				}
			} catch (IdUnusedException _iue) {
				M_log.debug(this + " getReviewReport Could not find site from m_context value " + m_context);
				return "error";
			}*/
			return contentReviewService.getReviewReport(contentId, null, null);
			
		}
		catch (QueueException | ReportException e)
		{
			// TIITODO: if there is a QueueException, do we queue the item here?
			
			log.debug(":getReviewReport(ContentResource) " + e.getMessage());
			return "Error"; // TIITODO: better return values
		}
	}
	
	private String getReviewError(ContentReviewItem item)
	{
		try
		{
			String contentId = item.getContentId();
			ReviewStatus reviewStatus = ReviewStatus.fromItemStatus(item.getStatus());
			// TIITODO: does the decision to expose the error belong here or in CRS?
			boolean exposeError = reviewStatus == ReviewStatus.SUBMISSION_ERROR_NO_RETRY || reviewStatus == ReviewStatus.SUBMISSION_ERROR_RETRY;
			
			String errorMessage = contentReviewService.getReviewError(contentId);
			
			// TIITODO: probably a better way to handle this error messaging
			if (StringUtils.isBlank(errorMessage))
			{
				//errorMessage = contentReviewService.getLocalizedStatusMessage(reviewStatus);
				// the above looks right but with the current impl this wrong!
				return "TIITODO: show the localized status message here";
			}

			// Expose the underlying CRS error to the UI
			if(exposeError && exposeTurnitinErrorToUI)
			{
				errorMessage += " " + getLastErrorForContentReviewItem(item);
			}

			return errorMessage;
		}
		catch (Exception e)
		{
			log.warn(this + ":getReviewError(ContentResource) " + e.getMessage());
			return null;
		}
	}
	
	/**
	 * Convenience method to retrieve the lastError for a content review item, given the content resource ID
	 * @return a formatted String containing the lastError message for the content review item asked for
	 */
	private String getLastErrorForContentReviewItem(ContentReviewItem item)
	{
		Object[] args = new String[] { contentReviewService.getServiceName(), item.getLastError() };
		// TIITODO: one could argue that this localization should happen in CRS or in the tool layer, not the service
		// figure out the best way to handle this
		//return rb.getFormattedMessage( "content_review.errorFromSource", args );
		return "TIITODO AssignmentServiceContentReviewDelegate.java";
	}
	
	private String getAsnRef(Assignment asn)
	{
		return AssignmentReferenceReckoner.reckoner().assignment(asn).reckon().getReference();
	}
	
	@AllArgsConstructor
	private class ContentResourceSecurityAdvisor implements SecurityAdvisor
	{
		private static final String PERMISSION = "content.read";
		
		private final String user;
		private final String ref;

		@Override
		public SecurityAdvice isAllowed(String userId, String function, String reference)
		{
			if (PERMISSION.equals(function) && user.equals(userId) && ref.equals(reference))
			{
				return SecurityAdvisor.SecurityAdvice.ALLOWED;
			}
			
			return SecurityAdvisor.SecurityAdvice.PASS;
		}
	}
}
