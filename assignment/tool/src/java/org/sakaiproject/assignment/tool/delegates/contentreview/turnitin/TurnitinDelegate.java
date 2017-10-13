package org.sakaiproject.assignment.tool.delegates.contentreview.turnitin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.sakaiproject.assignment.api.AssignmentService;
import org.sakaiproject.assignment.api.AssignmentServiceConstants;
import org.sakaiproject.assignment.api.model.Assignment;
import org.sakaiproject.assignment.api.model.AssignmentSubmission;
import org.sakaiproject.assignment.tool.AssignmentAction;
import org.sakaiproject.assignment.tool.delegates.contentreview.ContentReviewDelegate;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.cheftool.Context;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.contentreview.service.ContentReviewService;
import org.sakaiproject.contentreview.service.TurnitinExtendedContentReviewService;
import org.sakaiproject.contentreview.service.dao.TiiActivityConfig;
import org.sakaiproject.event.api.SessionState;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.time.cover.TimeService;
import org.sakaiproject.user.api.User;
import org.sakaiproject.util.ResourceLoader;
import java.time.Instant;
import java.util.Map;
import org.sakaiproject.assignment.api.AssignmentConstants;
import org.sakaiproject.content.api.ContentResource;

/**
 * Delegate for Turnitin-specific functionality needed by ContentReviewDelegate
 * 
 * @author plukasew
 */
@Slf4j
@AllArgsConstructor
public class TurnitinDelegate
{
	private static final String SAK_PROP_JOB_BUFFER_MINUTES = "turnitin.due.date.buffer.minutes";
	
	private final TurnitinExtendedContentReviewService tiiServ;
	private final ServerConfigurationService serverConfigurationService;
	private final ResourceLoader rb;
	private final AssignmentService assignmentService;
	private final SecurityService securityService;
	
	public boolean createAssignment(ContentReviewDelegate.AssignmentConfig asnCfg, SessionState state)
	{
		Assignment assignment = asnCfg.assignment;
		Optional<TiiActivityConfig> tiiCfgOpt = tiiServ.getActivityConfig(AssignmentAction.ASSIGNMENT_TOOL_ID, assignment.getId());
		if (!tiiCfgOpt.isPresent())
		{
			// TIITODO: for now we just return false, but really we have to deal with legacy assignments
			// so we have to either provide a conversion of some kind, or fall back to the original logic
			// used by the non-Turnitin integrations
			return false;
		}

		TiiActivityConfig tiiCfg = tiiCfgOpt.get();
		ContentReviewService.CreateAssignmentOpts extraOpts = new ContentReviewService.CreateAssignmentOpts();
		
		//TIITODO: handle this stuff below, probably needs to be added to TiiActivityConfig/CreateAssignmentOpts?
		// probably have to build out the TiiActivityConfig API to expose these (see TiiInternalActivityConfig class)
        /* TII only has three settings for resubmissions:
         * 1) Generate reports immediately (resubmissions are not allowed)
         * 2) Generate reports immediately (resubmissions are allowed until due date)
         * 3) Generate reports on due date (resubmissions are allowed until due date)
         * 
         * Due to this combined with the fact that TII will refuse manually allowed resubmissions, we should default to always 
         * allowing resubmissions on the TII side. Sakai/Assignments will act as the 'gaurd' against invalid resubmissions.
         * 
         * So in effect, the only setting of consequence here is to either generate the reports immediately or on the due date.
         */
        /*String originalityReportVal = assign.getGenerateOriginalityReport();
        if (originalityReportVal.equals(NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_IMMEDIATELY))
        {
            opts.put("report_gen_speed", NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_IMMEDIATELY_RESUB);
        }
        else
        {
            opts.put("report_gen_speed", NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_DUE);
        }
		
		opts.put("allow_any_file", assign.isAllowAnyFile() ? "1" : "0");
		
        if(closeTime.getTime() > dueTime.getTime()){
			opts.put("late_accept_flag", "1");
        } else {
			opts.put("late_accept_flag", "0");
        }
		
        opts.put("dtstart", dform.format(openTime.getTime()));  // old Turnitin Sakai API integration
        // TII-245 - use latest date for TII's due date
        Time latest = closeTime;
        if (resubmitCloseTime != null && resubmitCloseTime.after(closeTime))
        {
            latest = resubmitCloseTime;
        }
        opts.put("dtdue", dform.format(dueTime.getTime()));  // old Turnitin Sakai API integration
        opts.put("timestampOpen", openTime.getTime());  // new Turnitin LTI integration
        opts.put("timestampDue", latest.getTime());  // new Turnitin LTI integration
		
		*/
		
		extraOpts.tiiActivityConfig = tiiCfg;
		// TIITODO: the method below doesn't exist yet
		//asnOpts.allowStudentsToViewReports = assignment.getAllowStudentToViewContentReviewReports();
		extraOpts.openTime = asnCfg.openTime;
		extraOpts.dueTime = asnCfg.dueTime;
		extraOpts.closeTime = asnCfg.closeTime;
		extraOpts.points = assignment.getMaxGradePoint();
		extraOpts.title = assignment.getTitle();
		extraOpts.instructions = assignment.getInstructions();
		extraOpts.attachments = new ArrayList<>(assignment.getAttachments());
		// NOTE: store_inst_index and student_preview are Vericite properties that we don't need here

		try
		{
			// TIITODO: implement this extension check as part of CRS?
            /*if (extension == null)
            {
                contentReviewService.createAssignment(assign.getContext(), assignmentRef, opts);
            }
            else
            {
                contentReviewService.offerIndividualExtension(assign.getContext(), assignmentRef, opts, extension);
            }*/

			// TIITODO: implement this, see updatePendingStatusForAssignment() impl
			/*if (StringUtils.isNotBlank(genReportsSetting)) {
				tiiServ.updatePendingStatusForAssignment(assignmentRef, genReportsSetting);
			}*/
			
			tiiServ.createAssignment(assignment.getContext(), asnCfg.assignmentRef, extraOpts);
			return true;
		}
		catch (Exception e)
		{
			log.error(e.getMessage());
			String uiService = serverConfigurationService.getString("ui.service", "Sakai");
			String[] args = new String[]{tiiServ.getServiceName(), uiService, e.toString()};
			state.setAttribute("alertMessageCR", rb.getFormattedMessage("content_review.error.createAssignment", args));
		}

		return false;
	}
	
	public Optional<String> buildStudentViewAssignmentContext(SessionState state, Context context,
			String contextString, Assignment assignment, Site site, String templateAux)
	{
		String template = null;
		
		if (putTiiLtiPropsIntoContext(state, context, assignment, site) && tiiServ.isDirectAccess(site, assignment.getDateCreated())
			&& Boolean.valueOf(assignmentService.canSubmit(contextString, assignment)))
		{
			log.debug("Allowing submission directly from TII");
			template = templateAux + "_lti_access";
		}

		return Optional.ofNullable(template);
	}
	
	public void buildStudentViewSubmissionConfirmationContext(Context context, Site site, Assignment asn, User user)
	{
		//warnings if user's fields are not set
		if (site != null && assignmentService.allowReviewService(site) && asn.getContentReview())
		{
			context.put("usingreview", true);
			boolean generateFirstNameIfMissing = serverConfigurationService.getBoolean( "turnitin.generate.first.name", false );
			boolean generateSurnameIfMissing = serverConfigurationService.getBoolean( "turnitin.generate.last.name", false );
			boolean detailsMissing = false;

			List<String> missingFields = new ArrayList<>();
			if( !generateFirstNameIfMissing && StringUtils.isBlank( user.getFirstName() ) )
			{
				detailsMissing = true;
				missingFields.add( rb.getString( "review.user.missing.details.firstName" ) );
			}
			if( !generateSurnameIfMissing && StringUtils.isBlank( user.getLastName() ) )
			{
				detailsMissing = true;
				missingFields.add( rb.getString( "review.user.missing.details.surname" ) );
			}
			if( StringUtils.isBlank( user.getEmail() ) )
			{
				detailsMissing = true;
				missingFields.add( rb.getString( "review.user.missing.details.email" ) );
			}

			String and = rb.getString( "review.user.missing.details.and" );
			StringBuilder sb = new StringBuilder();
			for( int i = 0; i < missingFields.size(); i++ )
			{
				sb.append( missingFields.get( i ) );
				if( i < missingFields.size() - 2 && missingFields.size() > 2 )
				{
					sb.append( ", " );
				}
				if( i == missingFields.size() - 2 )
				{
					sb.append( " " ).append( and ).append( " " );
				}
			}

			String missingUserDetailsMessage = rb.getFormattedMessage( "review.user.missing.details", new Object[]{ tiiServ.getServiceName(), sb.toString() } );
			context.put( "missingUserDetails", detailsMissing );
			context.put( "missingUserDetailsMessage", missingUserDetailsMessage);
		}
		else
		{
			context.put("usingreview", false);
		}
	}
	
	/**
	 * Puts the TII LTI properties into the context if applicable
	 * @param state the session state
	 * @param context the Velocity context
	 * @param asn the assignment
	 * @return true if the properties were added, false otherwise
	 */
	public boolean putTiiLtiPropsIntoContext(SessionState state, Context context, Assignment asn, Site site)
	{
		if (state == null || asn == null)
		{
			return false;
		}
		
		if (assignmentService.allowReviewService(site) && tiiServ.usesLTI(site, asn.getDateCreated()))
		{
			//put the LTI assignment link in context
			String ltiLink = tiiServ.getLTIAccess(asn.getId(), site.getId());
			log.debug("ltiLink " + ltiLink);
			context.put("ltiLink", ltiLink);
			Integer maxGradePoints = asn.getMaxGradePoint();
			if (maxGradePoints != null)
			{
				int maxPointsInt = maxGradePoints / assignmentService.getScaleFactor();
				context.put("maxPointsInt", maxPointsInt);
			}
			return true;
		}
		
		return false;
		
	}
	
	public void buildInstructorNewEditAssignmentContext(Context context, Assignment asn)
	{
		int buffer = serverConfigurationService.getInt(SAK_PROP_JOB_BUFFER_MINUTES, 0);
		context.put("reviewServiceQueueBuffer", buffer);
		long effectiveDueDate = tiiServ.getEffectiveDueDate(asn.getId(), asn.getCloseDate().getTime(), asn.getProperties(), buffer);
		context.put("effectiveDueDate", TimeService.newTime(effectiveDueDate).getDisplay());
	}
	
	public String buildInstructorGradeAssignmentContext(SessionState state, Context context, Site site, Assignment asn)
	{
		if (asn != null)
		{
			// validate content review service configuration for this assignment so we can provide appropriate messaging
			// in the UI if there is a problem
			context.put("misconfiguredReviewService", !tiiServ.validateActivityConfiguration("sakai.assignment.grades", asn.getId()));
			context.put("crInvalidAsnConfig", tiiServ.getLocalizedInvalidAsnConfigError());
		}
		
		if (putTiiLtiPropsIntoContext(state, context, asn, site) && tiiServ.isDirectAccess(site, asn.getDateCreated()))
		{
				log.debug("Allowing submission directly from TII");
				return "_lti_access";
		}
		
		return "";
	}
	
	public void buildInstructorViewAssignmentContext(Context context, Site site, Assignment asn)
	{
		if (assignmentService.allowReviewService(site) && asn.getContentReview() && tiiServ.usesLTI(site, asn.getDateCreated())
				&& securityService.unlock(AssignmentServiceConstants.SECURE_GRADE_ASSIGNMENT_SUBMISSION, site.getReference()))
		{
			//put the LTI assignment link in context
			String ltiLink = tiiServ.getLTIAccess(asn.getId(), site.getId());
			log.debug("ltiLink " + ltiLink);
			context.put("ltiLink", ltiLink);
		}
	}
	
	/**
	 * Tells the content review service that we're offering an extension to an individual student; content review service will decide what to do with this
	 */
	// TIITODO: this might make sense in the general CRS API?
	public void handleIndividualExtension(SessionState state, AssignmentSubmission sub, Instant extension)
	{
		Assignment a = sub.getAssignment();
		if (a != null)
		{
			// Assignment open / due / close dates are easy to access, but the assignment level 'resubmit until' is a base64'd ResourceProperty
			Instant asnResubmitCloseTime = null;
			Map<String, String> props = a.getProperties();
			if (props != null)
			{
				String strResubmitCloseTime = props.get(AssignmentConstants.ALLOW_RESUBMIT_CLOSETIME);
				if (!StringUtils.isBlank(strResubmitCloseTime))
				{
					//asnResubmitCloseTime = TimeService.newTime(Long.parseLong(strResubmitCloseTime));
				}
			}
			// TIITODO: confirm the above property is correct and then sync the assignment as required
			//syncTIIAssignment(a.getContent(), a.getReference(), a.getOpenTime(), a.getDueTime(), a.getCloseTime(), asnResubmitCloseTime, new Date(extension.getTime()), state, null);
		}
	}
	
	public void handleDeleteAssignment(Site site, Assignment asn)
	{
		if (tiiServ.usesLTI(site, asn.getDateCreated()))
		{
			boolean removed = tiiServ.deleteLTITool(asn.getId(), site.getId());
			if (!removed)
			{
				log.warn("Could not delete Turnitin LTI tool associated with assignment " + asn.getId());
			}
		}
	}
	
	public boolean isAcceptableSize(ContentResource attachment)
	{
		return true; // TIITODO: implement the check
	}
}
