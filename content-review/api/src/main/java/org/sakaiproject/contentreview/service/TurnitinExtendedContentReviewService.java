package org.sakaiproject.contentreview.service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.sakaiproject.content.api.ContentResource;
import org.sakaiproject.contentreview.dao.ContentReviewItem;
import org.sakaiproject.contentreview.exception.QueueException;
import org.sakaiproject.contentreview.exception.ReportException;
import org.sakaiproject.contentreview.exception.SubmissionException;
import org.sakaiproject.contentreview.exception.TransientSubmissionException;
import org.sakaiproject.site.api.Site;

/**
 * Extension methods to the base ContentReviewService API that are applicable only to Turnitin
 * 
 * @author plukasew
 */
public interface TurnitinExtendedContentReviewService extends ContentReviewService
{
	// TIITODO: this overloaded queueContent is used in 11.x, re-evaluate why we need the submissionId and isResubmission properties
	// and remove this method if not required.
	//public void queueContent(String userId, String siteId, String taskId, List<ContentResource> content, String submissionId, boolean isResubmission) throws QueueException;
	
	// TIITODO: is this a candidate for inclusion in the main CRS API? Seems like all services would have some kind of file size limit.
	/**
	 * Is the content resource of a size that can be accepted by the service implementation
	 * @param resource
	 * @return
	 */
	public boolean isAcceptableSize(ContentResource resource);
	
	/**
	 *  Can this site make use of the direct TII submission mode
	 * 
	 * @param s the site
	 * @return
	 * 
	 */
	public boolean isDirectAccess(Site s);
	
	/**
	 * Version of the above method compatible with date-aware site advisors. This is a transitional
	 * method that should be removed when TII legacy api support ends
	 * @param s the site
	 * @param assignmentCreationDate
	 * @return 
	 */
	@Deprecated
	public boolean isDirectAccess(Site s, Date assignmentCreationDate);
	
	// TIITODO: is this a candidate for inclusion in the main CRS API?
	/**
	 * Does the service support multiple attachments on a single submission?
	 * @return
	 */
	public boolean allowMultipleAttachments();
	
	/**
	 * Syncs the assignment with consideration for a student's 'resubmit accept until' date. Otherwise, behavior is identical to createAssignment()
	 * @param extensionDate date of the extension
	 */
	public void offerIndividualExtension(String siteId, String asnId, Map<String, Object> extraAsnOpts, Date extensionDate)
 	throws SubmissionException, TransientSubmissionException;
	
	/**
	 * Get the URL to access the LTI tool associated with the task
	 *
	 * @param taskId
	 * @param siteId
	 * @return
	 * @throws QueueException
	 * @throws ReportException
	 */
	public String getLTIAccess(String taskId, String siteId);

	// TIITODO: should this be included in the main CRS API under a more general name like handleActivityDeletion() that gets
	// called when assignments are deleted to perform any required cleanup?
	/**
	 * Delete the LTI tool associated with the task
	 *
	 * @param taskId
	 * @param siteId
	 * @return
	 * @throws QueueException
	 * @throws ReportException
	 */
	public boolean deleteLTITool(String taskId, String siteId);
	
	// TIITODO: do we need to expose these next three ContentReviewItem methods outside the Turnitin implementation?
	// Do these instead belong in the internal TII API?
	
	// TIITODO: this might be needed by Assignments, re-evaluate and implement if necessary. The CRQService/DAO can
	// do the heavy lifting
	/**
	 * Get the ContentReviewItem that matches the id
	 *
	 * @param id
	 * @return
	 */
	//public ContentReviewItem getItemById(String id);

	// TIITODO: this might be needed by Assignments, re-evaluate and implement if necessary. The CRQService/DAO can
	// do the heavy lifting
	/**
	 * Get the first ContentReviewItem that matches the param
	 *
	 * @param id
	 * @return
	 */
	//public ContentReviewItem getFirstItemByContentId(String contentId);

	// TIITODO: this is only used by the GradingCallbackServlet. Move it to the internal TII API?
	/**
	 * Get the first ContentReviewItem that matches the param
	 *
	 * @param id
	 * @return
	 */
	//public ContentReviewItem getFirstItemByExternalId(String externalId);
	
	// TIITODO: do we need to expose these three ContentReviewItem methods outside the Turnitin implementation?
	// Do these instead belong in the internal TII API?
	/**
	 * Sets the url as accessed for a submission content
	 *
	 * @param contentId
	 * @return
	 */
	public boolean updateItemAccess(String contentId);

	/**
	 * Updates the externalId field of the contentreview_item with the specified contentId
	 * @param contentId the contentId of the contentreview_item to be updated
	 * @param externalId the ID supplied remotely by the content review servie for this item
	 * @return whether the update was successful
	 */
	public boolean updateExternalId(String contentId, String externalId);

	/**
	 * Sets the grade for a submission content
	 *
	 * @param contentId
	 * @return
	 */
	public boolean updateExternalGrade(String contentId, String score);
	
	/**
	 * Gets the grade for a submission content
	 *
	 * @param contentId
	 * @return
	 */
	public Optional<String> getExternalGradeForContentId(String contentId);
	
	// TIITODO: should probably just remove this
	/**
	 * Migrates the original LTI XML settings from the assignments table into the new activity config table.
	 * Also moves the external value from the content resource binary entity back into the contentreviewitem table.
	 * You need to run this ONLY if you have previously deployed the LTI integration prior to the introduction of TII-219 and TII-221.
	 */
	//public void migrateLtiXml();
	
	/**
	 * Returns the implementation-specific localized error message for an invalid assignment configuration
	 * @return the localized message indicating the problem with the content review configuration for this assignment
	 */
	public String getLocalizedInvalidAsnConfigError();
	
	/**
	 * Returns true if the Sakai activity is configured correctly for this content review service
	 * @param toolId the Sakai tool id that the activity belongs to (ex: sakai.assignment.grades)
	 * @param activityId the unique identifier for the activity (ex: an assignment id)
	 * @return true if the given activity is configured correctly for use with the review service
	 */
	public boolean validateActivityConfiguration(String toolId, String activityId);
	
	/**
	 * Get the effective due date for the given ContentReviewItem. Takes into account assignment due date,
	 * assignment resubmit due date, any manually set student-specific resubmit date if present, and the due
	 * date buffer controlled by the "contentreview.due.date.queue.job.buffer.minutes" sakai.property
	 *
	 * @param assignmentID the ID of the assignment in question
	 * @param assignmentDueDate the due date of the assignment
	 * @param assignmentProperties the properties for the assignment
	 * @param dueDateBuffer the due date buffer in minutes, from sakai.properties
	 * @return the effective due date in milliseconds (long)
	 */
	public long getEffectiveDueDate(String assignmentID, long assignmentDueDate, Map<String, String> assignmentProperties, int dueDateBuffer);

	/**
	 * Updates the status of all ContentReviewItems for the given assignment. If the assignment is being changed to generate reports immediately,
	 * all items in status 10 (pending, due date) need to be flipped to status 2 (pending). Vice versa for an assignment being changed to generate
	 * reports on the due date.
	 *
	 * @param assignmentRef the ref of the assignment in question
	 * @param generateReportsSetting the assignment's new setting for generating originality reports (2 = on due date)
	 */
	public void updatePendingStatusForAssignment(String assignmentRef, String generateReportsSetting);
	
}
