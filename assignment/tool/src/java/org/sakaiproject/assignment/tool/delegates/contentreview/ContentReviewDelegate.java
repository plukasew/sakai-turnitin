package org.sakaiproject.assignment.tool.delegates.contentreview;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.sakaiproject.assignment.api.AssignmentConstants;
import org.sakaiproject.assignment.api.AssignmentReferenceReckoner;
import org.sakaiproject.assignment.api.AssignmentService;
import org.sakaiproject.assignment.api.model.Assignment;
import org.sakaiproject.assignment.api.model.AssignmentSubmission;
import org.sakaiproject.assignment.tool.delegates.contentreview.turnitin.TurnitinDelegate;
import org.sakaiproject.authz.api.SecurityAdvisor;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.cheftool.Context;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.content.api.ContentHostingService;
import org.sakaiproject.contentreview.service.ContentReviewService;
import org.sakaiproject.contentreview.service.TurnitinExtendedContentReviewService;
import org.sakaiproject.event.api.SessionState;
import org.sakaiproject.util.ResourceLoader;
import org.sakaiproject.util.ParameterParser;
import org.sakaiproject.content.api.ContentResource;
import org.sakaiproject.contentreview.advisors.ContentReviewSiteAdvisor;
import org.sakaiproject.entity.api.Entity;
import org.sakaiproject.entity.api.EntityManager;
import org.sakaiproject.entity.api.Reference;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.entity.api.ResourcePropertiesEdit;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.exception.ServerOverloadException;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.user.api.User;

/**
 * Delegate for all AssignmentAction operations involving Content Review. Due to legacy UI requirements, includes
 * Turnitin-specific functionality.
 * 
 * @author plukasew
 */
@Slf4j
@RequiredArgsConstructor
public class ContentReviewDelegate
{
	// TIITODO: review all these constants and move those used solely by Turnitin into the TurnitinDelegate
	private static final String NEW_ASSIGNMENT_USE_REVIEW_SERVICE = "new_assignment_use_review_service";
    private static final String NEW_ASSIGNMENT_ALLOW_STUDENT_VIEW = "new_assignment_allow_student_view";
	private static final String NEW_ASSIGNMENT_ALLOW_STUDENT_VIEW_EXTERNAL_GRADE = "new_assignment_allow_student_view_external_grade";
    private static final String NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_RADIO = "submit_papers_to";
    private static final String NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_NONE = "0";
    private static final String NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_STANDARD = "1";
    private static final String NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_INSITUTION = "2";
    // When to generate reports
    // although the service allows for a value of "1" --> Generate report immediately but overwrite until due date,
    // this doesn't make sense for assignment2. We limit the UI to 0 - Immediately
    // or 2 - On Due Date
    private static final String NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_RADIO = "report_gen_speed";
    private static final String NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_IMMEDIATELY = "0";
	private static final String NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_IMMEDIATELY_RESUB = "1";
    private static final String NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_DUE = "2";
    private static final String NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_TURNITIN = "s_paper_check";
    private static final String NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INTERNET = "internet_check";
    private static final String NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_PUB = "journal_check";
    private static final String NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INSTITUTION = "institution_check";
    private static final String NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_BIBLIOGRAPHIC = "exclude_biblio";
    private static final String NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_QUOTED = "exclude_quoted";
    private static final String NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_SELF_PLAG = "exclude_self_plag";
    private static final String NEW_ASSIGNMENT_REVIEW_SERVICE_STORE_INST_INDEX = "store_inst_index";
    private static final String NEW_ASSIGNMENT_REVIEW_SERVICE_STUDENT_PREVIEW = "student_preview";
    private static final String NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_SMALL_MATCHES = "exclude_smallmatches";
    private static final String NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_TYPE = "exclude_type";
    private static final String NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_VALUE = "exclude_value";
	private static final String NEW_ASSIGNMENT_REVIEW_SERVICE_ALLOW_ANY_FILE = "allow_any_file";
	
	/**
     * ****************** instructor's export assignment *****************************
     */
	/**
     * Is review service enabled?
     */
    //private static final String ENABLE_REVIEW_SERVICE = "enable_review_service"; // TIITODO: appears unused, remove if possible
	
	private static final String SAK_PROP_ENABLE_GRADEMARK = "turnitin.grademark.integration.enabled";
	private static final boolean SAK_PROP_ENABLE_GRADEMARK_DEFAULT = true;
	
	private static final String HTML_EXT = ".html";
	
	private final ContentReviewService contentReviewService;
	// TIITODO: do we really need this advisor? or just a way to tell if TII uses LTI or not?
	// See if there is anything this gives us in a general sense that Federated doesn't already
	// provide. For example, do we need this for determining which CRS provider to use for a given site?
	// If not, this advisor should probably become a Turnitin implementation detail
	private final ContentReviewSiteAdvisor contentReviewSiteAdvisor;
	private final ServerConfigurationService serverConfigurationService;
	private final ResourceLoader rb;
	private final EntityManager entityManager;
	private final ContentHostingService contentHostingService;
	private final SecurityService securityService;
	private final AssignmentService assignmentService;
	private Optional<TurnitinDelegate> turnitin = Optional.empty();
	
	private boolean hideAllowAnyFileOption = false;
	private boolean allowAnyFileOptionDefault = false;
	
	private int contentreviewSiteYears;
	private int contentreviewSiteMin;
	private int contentreviewSiteMax;
	private int contentreviewAssignMin;
	private int contentreviewAssignMax;
	
	public static class AssignmentConfig
	{
		public Assignment assignment;
		public String assignmentRef;
		public Instant openTime, dueTime, closeTime;
		
		public AssignmentConfig()
		{
			assignment = null;
			assignmentRef = "";
			openTime = dueTime = closeTime = Instant.EPOCH;
		}
	}
	
	// TIITODO: this is a temp class until we figure out the best way to handle all these params
	public static class PostSaveContentReviewParams
	{
		public boolean useReviewService = false;
		public boolean allowStudentViewReport = false;
		public boolean allowStudentViewExternalGrade = false;
		public String submitReviewRepo = "";
		public String generateOriginalityReport = "";
		public boolean checkTurnitin = false;
		public boolean checkInternet = false;
		public boolean checkPublications = false;
		public boolean checkInstitution = false;
		public boolean allowAnyFile = false;
		public boolean excludeBibliographic = false;
		public boolean excludeQuoted = false;
		public boolean excludeSelfPlag = false;
		public boolean storeInstIndex = false;
		public boolean studentPreview = false;
		public boolean excludeSmallMatches = false;
		public int excludeType = 0;
		public int excludeValue = 1;
	}
	
	// TIITODO: maybe replace the lombok constructor/init() combo with a regular constructor
	public void init()
	{
		// find out what kind of CRS we have
		// TIITODO: how does this work with federated? Isn't it possible that multiple content review
		// providers are in play?
		if ("Turnitin".equals(contentReviewService.getServiceName()))
		{
			TurnitinExtendedContentReviewService tiiServ = (TurnitinExtendedContentReviewService) contentReviewService.getSelectedProvider();
			turnitin = Optional.of(new TurnitinDelegate(tiiServ, serverConfigurationService, rb, assignmentService,
					securityService));
		}
		
		hideAllowAnyFileOption = serverConfigurationService.getBoolean("turnitin.option.any_file.hide", false);
		allowAnyFileOptionDefault = serverConfigurationService.getBoolean("turnitin.option.any_file.default", false);
		
		contentreviewSiteYears = serverConfigurationService.getInt("contentreview.site.years", 0);//TII value = 6
		contentreviewSiteMin = serverConfigurationService.getInt("contentreview.site.min", 0);//TII value = 5
		contentreviewSiteMax = serverConfigurationService.getInt("contentreview.site.max", 0);//TII value = 50
		contentreviewAssignMin = serverConfigurationService.getInt("contentreview.assign.min", 0);//TII value = 3
		contentreviewAssignMax = serverConfigurationService.getInt("contentreview.assign.max", 0);//TII value = 100
	}
	
	// TIITODO: in 11.x this was renamed syncTIIAssignment, should we rename still?
	public boolean createTIIAssignment(AssignmentConfig asnCfg, SessionState state, ResourceLoader rb)
	{	
		Assignment assignment = asnCfg.assignment;
		
		if (turnitin.isPresent())
		{
			return turnitin.get().createAssignment(asnCfg, state);
		}
		
		// TIITODO: figure out if we can get rid of this legacy impl that the other services still implement
		
        Map<String, Object> opts = new HashMap<>();
        Map<String, String> p = assignment.getProperties();

        opts.put("submit_papers_to", p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_RADIO));
        opts.put("report_gen_speed", p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_RADIO));
        opts.put("institution_check", Boolean.valueOf(p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INSTITUTION)) ? "1" : "0");
        opts.put("internet_check", Boolean.valueOf(p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INTERNET)) ? "1" : "0");
        opts.put("journal_check", Boolean.valueOf(p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_PUB)) ? "1" : "0");
        opts.put("s_paper_check", Boolean.valueOf(p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_TURNITIN)) ? "1" : "0");
        opts.put("s_view_report", Boolean.valueOf(p.get("s_view_report")) ? "1" : "0");

        if (serverConfigurationService.getBoolean("turnitin.option.exclude_bibliographic", true)) {
            //we don't want to pass parameters if the user didn't get an option to set it
            opts.put("exclude_biblio", Boolean.valueOf(p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_BIBLIOGRAPHIC)) ? "1" : "0");
        }
        //Rely on the deprecated "turnitin.option.exclude_quoted" setting if set, otherwise use "contentreview.option.exclude_quoted"
        boolean showExcludeQuoted = serverConfigurationService.getBoolean("turnitin.option.exclude_quoted", serverConfigurationService.getBoolean("contentreview.option.exclude_quoted", Boolean.TRUE));
        if (showExcludeQuoted) {
            opts.put("exclude_quoted", Boolean.valueOf(p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_QUOTED)) ? "1" : "0");
        } else {
            Boolean defaultExcludeQuoted = serverConfigurationService.getBoolean("contentreview.option.exclude_quoted.default", true);
            opts.put("exclude_quoted", defaultExcludeQuoted ? "1" : "0");
        }

        //exclude self plag
        if (serverConfigurationService.getBoolean("contentreview.option.exclude_self_plag", true)) {
            opts.put("exclude_self_plag", Boolean.valueOf(p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_SELF_PLAG)) ? "1" : "0");
        } else {
            Boolean defaultExcludeSelfPlag = serverConfigurationService.getBoolean("contentreview.option.exclude_self_plag.default", true);
            opts.put("exclude_self_plag", defaultExcludeSelfPlag ? "1" : "0");
        }

        //Store institutional Index
        if (serverConfigurationService.getBoolean("contentreview.option.store_inst_index", true)) {
            opts.put("store_inst_index", Boolean.valueOf(p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_STORE_INST_INDEX)) ? "1" : "0");
        } else {
            Boolean defaultStoreInstIndex = serverConfigurationService.getBoolean("contentreview.option.store_inst_index.default", true);
            opts.put("store_inst_index", defaultStoreInstIndex ? "1" : "0");
        }

        //Student preview
        if (serverConfigurationService.getBoolean("contentreview.option.student_preview", false)) {
            opts.put("student_preview", Boolean.valueOf(p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_STUDENT_PREVIEW)) ? "1" : "0");
        } else {
            Boolean defaultStudentPreview = serverConfigurationService.getBoolean("contentreview.option.student_preview.default", false);
            opts.put("student_preview", defaultStudentPreview ? "1" : "0");
        }

        int excludeType = Integer.parseInt(p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_TYPE));
        int excludeValue = Integer.parseInt(p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_VALUE));
        if ((excludeType == 1 || excludeType == 2)
                && excludeValue >= 0 && excludeValue <= 100) {
            opts.put("exclude_type", Integer.toString(excludeType));
            opts.put("exclude_value", Integer.toString(excludeValue));
        }
        opts.put("late_accept_flag", "1");

        SimpleDateFormat dform = ((SimpleDateFormat) DateFormat.getDateInstance());
        dform.applyPattern("yyyy-MM-dd HH:mm:ss");
        opts.put("dtstart", dform.format(asnCfg.openTime.toEpochMilli()));
        opts.put("dtdue", dform.format(asnCfg.dueTime.toEpochMilli()));
        //opts.put("dtpost", dform.format(closeTime.getTime()));
        opts.put("points", assignment.getMaxGradePoint());
        opts.put("title", assignment.getTitle());
        opts.put("instructions", assignment.getInstructions());
        if (!assignment.getAttachments().isEmpty()) {
            opts.put("attachments", new ArrayList<>(assignment.getAttachments()));
        }
        try {
            contentReviewService.createAssignment(assignment.getContext(), asnCfg.assignmentRef, opts);
            return true;
        } catch (Exception e) {
            log.error(e.getMessage());
            String uiService = serverConfigurationService.getString("ui.service", "Sakai");
            String[] args = new String[]{contentReviewService.getServiceName(), uiService, e.toString()};
			// TIITODO: determine if we need to use alertMessageCR for these other providers as well
            state.setAttribute("alertMessage", rb.getFormattedMessage("content_review.error.createAssignment", args));
        }
        return false;
    }
	
	public void putContentReviewSettingsIntoStudentViewSubmissionContext(Context context, Assignment assignment, boolean takesAttachments)
	{
		Map<String, String> properties = assignment.getProperties();
		context.put("plagiarismNote", rb.getFormattedMessage("gen.yoursubwill", contentReviewService.getServiceName()));
		if (!contentReviewService.allowAllContent() && takesAttachments) {
			context.put("plagiarismFileTypes", rb.getFormattedMessage("gen.onlythefoll", getContentReviewAcceptedFileTypesMessage()));

			// SAK-31649 commenting this out to remove file picker filters, as the results vary depending on OS and browser.
			// If in the future browser support for the 'accept' attribute on a file picker becomes more robust and
			// ubiquitous across browsers, we can re-enable this feature.
			//context.put("content_review_acceptedMimeTypes", getContentReviewAcceptedMimeTypes());
		}
		try {
			if (Boolean.valueOf(properties.get(NEW_ASSIGNMENT_REVIEW_SERVICE_STUDENT_PREVIEW))) {
				context.put("plagiarismStudentPreview", rb.getString("gen.subStudentPreview"));
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}
	
	public void putContentReviewSettingsIntoAssignmentFormContext(Context context, SessionState state)
	{
		context.put("name_UseReviewService", NEW_ASSIGNMENT_USE_REVIEW_SERVICE);
        context.put("name_AllowStudentView", NEW_ASSIGNMENT_ALLOW_STUDENT_VIEW);
		context.put("name_AllowStudentViewExternalGrade", NEW_ASSIGNMENT_ALLOW_STUDENT_VIEW_EXTERNAL_GRADE);
        context.put("name_NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_RADIO", NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_RADIO);
        context.put("name_NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_NONE", NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_NONE);
        context.put("name_NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_STANDARD", NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_STANDARD);
        context.put("name_NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_INSITUTION", NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_INSITUTION);
        context.put("name_NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_RADIO", NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_RADIO);
        context.put("name_NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_IMMEDIATELY", NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_IMMEDIATELY);
        context.put("name_NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_DUE", NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_DUE);
        context.put("name_NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_TURNITIN", NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_TURNITIN);
        context.put("name_NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INTERNET", NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INTERNET);
        context.put("name_NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_PUB", NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_PUB);
        context.put("name_NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INSTITUTION", NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INSTITUTION);
        context.put("name_NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_BIBLIOGRAPHIC", NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_BIBLIOGRAPHIC);
        context.put("name_NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_QUOTED", NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_QUOTED);
        context.put("name_NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_SELF_PLAG", NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_SELF_PLAG);
        context.put("name_NEW_ASSIGNMENT_REVIEW_SERVICE_STORE_INST_INDEX", NEW_ASSIGNMENT_REVIEW_SERVICE_STORE_INST_INDEX);
        context.put("name_NEW_ASSIGNMENT_REVIEW_SERVICE_STUDENT_PREVIEW", NEW_ASSIGNMENT_REVIEW_SERVICE_STUDENT_PREVIEW);
        context.put("name_NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_SMALL_MATCHES", NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_SMALL_MATCHES);
        context.put("name_NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_TYPE", NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_TYPE);
        context.put("name_NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_VALUE", NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_VALUE);
		context.put("name_NEW_ASSIGNMENT_REVIEW_SERVICE_ALLOW_ANY_FILE", NEW_ASSIGNMENT_REVIEW_SERVICE_ALLOW_ANY_FILE);
		
		// Keep the use review service setting
        context.put("value_UseReviewService", state.getAttribute(ContentReviewDelegate.NEW_ASSIGNMENT_USE_REVIEW_SERVICE));
        if (!contentReviewService.allowAllContent()) {
            String fileTypesMessage = getContentReviewAcceptedFileTypesMessage();
            String contentReviewNote = rb.getFormattedMessage("content_review.note", new Object[]{fileTypesMessage});
            context.put("content_review_note", contentReviewNote);
        }
        context.put("turnitin_forceSingleAttachment", serverConfigurationService.getBoolean("turnitin.forceSingleAttachment", false));
        //Rely on the deprecated "turnitin.allowStudentView.default" setting if set, otherwise use "contentreview.allowStudentView.default"
        boolean defaultAllowStudentView = serverConfigurationService.getBoolean("turnitin.allowStudentView.default", serverConfigurationService.getBoolean("contentreview.allowStudentView.default", Boolean.FALSE));
        context.put("value_AllowStudentView", state.getAttribute(ContentReviewDelegate.NEW_ASSIGNMENT_ALLOW_STUDENT_VIEW) == null ?
				Boolean.toString(defaultAllowStudentView) : state.getAttribute(ContentReviewDelegate.NEW_ASSIGNMENT_ALLOW_STUDENT_VIEW));
		String defaultAllowStudentViewExternalGrade = Boolean.toString(serverConfigurationService.getBoolean("turnitin.allowStudentViewExternalGrade.default", false));
		Object allowStudentViewExternalGrade = state.getAttribute(NEW_ASSIGNMENT_ALLOW_STUDENT_VIEW_EXTERNAL_GRADE) == null ?
				defaultAllowStudentViewExternalGrade : state.getAttribute(NEW_ASSIGNMENT_ALLOW_STUDENT_VIEW_EXTERNAL_GRADE);
		context.put("value_AllowStudentViewExternalGrade", allowStudentViewExternalGrade);

        List<String> subOptions = getSubmissionRepositoryOptions();
        String submitRadio = serverConfigurationService.getString("turnitin.repository.setting.value", null) == null ? ContentReviewDelegate.NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_NONE : serverConfigurationService.getString("turnitin.repository.setting.value");
        if (state.getAttribute(ContentReviewDelegate.NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_RADIO) != null && subOptions.contains(state.getAttribute(ContentReviewDelegate.NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_RADIO)))
            submitRadio = state.getAttribute(ContentReviewDelegate.NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_RADIO).toString();
        context.put("value_NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_RADIO", submitRadio);
        context.put("show_NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT", subOptions);

        List<String> reportGenOptions = getReportGenOptions();
        String reportRadio = serverConfigurationService.getString("turnitin.report_gen_speed.setting.value", null) == null ? ContentReviewDelegate.NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_IMMEDIATELY : serverConfigurationService.getString("turnitin.report_gen_speed.setting.value");
        if (state.getAttribute(ContentReviewDelegate.NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_RADIO) != null && reportGenOptions.contains(state.getAttribute(ContentReviewDelegate.NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_RADIO)))
            reportRadio = state.getAttribute(ContentReviewDelegate.NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_RADIO).toString();
        context.put("value_NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_RADIO", reportRadio);
        context.put("show_NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT", reportGenOptions);

        context.put("show_NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_TURNITIN", serverConfigurationService.getBoolean("turnitin.option.s_paper_check", true));
        context.put("show_NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INTERNET", serverConfigurationService.getBoolean("turnitin.option.internet_check", true));
        context.put("show_NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_PUB", serverConfigurationService.getBoolean("turnitin.option.journal_check", true));
        context.put("show_NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INSTITUTION", serverConfigurationService.getBoolean("turnitin.option.institution_check", false));

		context.put( "show_NEW_ASSIGNMENT_REVIEW_SERVICE_GRADEMARK_ENABLED", serverConfigurationService.getBoolean( SAK_PROP_ENABLE_GRADEMARK, SAK_PROP_ENABLE_GRADEMARK_DEFAULT ) );
		
        context.put("value_NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_TURNITIN", (state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_TURNITIN) == null) ? Boolean.toString(serverConfigurationService.getBoolean("turnitin.option.s_paper_check.default", serverConfigurationService.getBoolean("turnitin.option.s_paper_check", true) ? true : false)) : state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_TURNITIN));
        context.put("value_NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INTERNET", state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INTERNET) == null ? Boolean.toString(serverConfigurationService.getBoolean("turnitin.option.internet_check.default", serverConfigurationService.getBoolean("turnitin.option.internet_check", true) ? true : false)) : state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INTERNET));
        context.put("value_NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_PUB", state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_PUB) == null ? Boolean.toString(serverConfigurationService.getBoolean("turnitin.option.journal_check.default", serverConfigurationService.getBoolean("turnitin.option.journal_check", true) ? true : false)) : state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_PUB));
        context.put("value_NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INSTITUTION", state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INSTITUTION) == null ? Boolean.toString(serverConfigurationService.getBoolean("turnitin.option.institution_check.default", serverConfigurationService.getBoolean("turnitin.option.institution_check", true) ? true : false)) : state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INSTITUTION));

		context.put("value_NEW_ASSIGNMENT_REVIEW_SERVICE_HIDE_ALLOW_ANY_FILE", Boolean.toString(hideAllowAnyFileOption));
		context.put("value_NEW_ASSIGNMENT_REVIEW_SERVICE_ALLOW_ANY_FILE", state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_ALLOW_ANY_FILE) == null ?
				Boolean.toString(allowAnyFileOptionDefault) : state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_ALLOW_ANY_FILE));

        //exclude bibliographic materials
        context.put("show_NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_BIBLIOGRAPHIC", serverConfigurationService.getBoolean("turnitin.option.exclude_bibliographic", true));
        context.put("value_NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_BIBLIOGRAPHIC", (state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_BIBLIOGRAPHIC) == null) ? Boolean.toString(serverConfigurationService.getBoolean("turnitin.option.exclude_bibliographic.default", serverConfigurationService.getBoolean("turnitin.option.exclude_bibliographic", true) ? true : false)) : state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_BIBLIOGRAPHIC));

        //exclude quoted materials
        //Rely on the deprecated "turnitin.option.exclude_quoted" setting if set, otherwise use "contentreview.option.exclude_quoted"
        boolean showExcludeQuoted = serverConfigurationService.getBoolean("turnitin.option.exclude_quoted", serverConfigurationService.getBoolean("contentreview.option.exclude_quoted", Boolean.TRUE));
        context.put("show_NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_QUOTED", showExcludeQuoted);
        //Rely on the deprecated "turnitin.option.exclude_quoted.default" setting if set, otherwise use "contentreview.option.exclude_quoted.default"
        boolean defaultExcludeQuoted = serverConfigurationService.getBoolean("turnitin.option.exclude_quoted.default", serverConfigurationService.getBoolean("contentreview.option.exclude_quoted.default", showExcludeQuoted));
        context.put("value_NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_QUOTED", (state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_QUOTED) == null) ? Boolean.toString(defaultExcludeQuoted) : state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_QUOTED));

        //exclude self plag
        boolean showExcludeSelfPlag = serverConfigurationService.getBoolean("contentreview.option.exclude_self_plag", Boolean.TRUE);
        context.put("show_NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_SELF_PLAG", showExcludeSelfPlag);
        //Rely on the deprecated "turnitin.option.exclude_self_plag.default" setting if set, otherwise use "contentreview.option.exclude_self_plag.default"
        boolean defaultExcludeSelfPlag = serverConfigurationService.getBoolean("contentreview.option.exclude_self_plag.default", showExcludeSelfPlag);
        context.put("value_NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_SELF_PLAG", (state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_SELF_PLAG) == null) ? Boolean.toString(defaultExcludeSelfPlag) : state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_SELF_PLAG));

        //Store Inst Index
        boolean showStoreInstIndex = serverConfigurationService.getBoolean("contentreview.option.store_inst_index", Boolean.TRUE);
        context.put("show_NEW_ASSIGNMENT_REVIEW_SERVICE_STORE_INST_INDEX", showStoreInstIndex);
        //Rely on the deprecated "turnitin.option.store_inst_index.default" setting if set, otherwise use "contentreview.option.store_inst_index.default"
        boolean defaultStoreInstIndex = serverConfigurationService.getBoolean("contentreview.option.store_inst_index.default", showStoreInstIndex);
        context.put("value_NEW_ASSIGNMENT_REVIEW_SERVICE_STORE_INST_INDEX", (state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_STORE_INST_INDEX) == null) ? Boolean.toString(defaultStoreInstIndex) : state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_STORE_INST_INDEX));
        //Use Student Preview
        boolean showStudentPreview = serverConfigurationService.getBoolean("contentreview.option.student_preview", Boolean.FALSE);
        context.put("show_NEW_ASSIGNMENT_REVIEW_SERVICE_STUDENT_PREVIEW", showStudentPreview);
        boolean defaultStudentPreview = serverConfigurationService.getBoolean("contentreview.option.student_preview.default", Boolean.FALSE);
        context.put("value_NEW_ASSIGNMENT_REVIEW_SERVICE_STUDENT_PREVIEW", (state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_STUDENT_PREVIEW) == null) ? Boolean.toString(defaultStudentPreview) : state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_STUDENT_PREVIEW));
        //exclude small matches
        boolean displayExcludeType = serverConfigurationService.getBoolean("turnitin.option.exclude_smallmatches", true);
        context.put("show_NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_SMALL_MATCHES", displayExcludeType);
        if (displayExcludeType) {
            context.put("value_NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_TYPE", (state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_TYPE) == null) ? Integer.toString(serverConfigurationService.getInt("turnitin.option.exclude_type.default", 0)) : state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_TYPE));
            context.put("value_NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_VALUE", (state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_VALUE) == null) ? Integer.toString(serverConfigurationService.getInt("turnitin.option.exclude_value.default", 1)) : state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_VALUE));
        }
	}
	
	public List<String> putContentReviewSettingsIntoNewAssignmentState(SessionState state, ParameterParser params, Assignment.SubmissionType subType)
	{
		List<String> alerts = new ArrayList<>(2);
		String r, b;
		//REVIEW SERVICE
        r = params.getString(NEW_ASSIGNMENT_USE_REVIEW_SERVICE);
        // set whether we use the review service or not
        if (r == null) b = Boolean.FALSE.toString();
        else {
            b = Boolean.TRUE.toString();
            if (Assignment.SubmissionType.NON_ELECTRONIC_ASSIGNMENT_SUBMISSION.equals(subType)) {
                //can't use content-review with non-electronic submissions
                //addAlert(state, rb.getFormattedMessage("review.switch.ne.1", contentReviewService.getServiceName()));
				alerts.add(rb.getFormattedMessage("review.switch.ne.1", contentReviewService.getServiceName()));
            }
			
			// TIITODO: looks like we're getting into TII-specific territory here with these checks....
			// Might want some kind of CRS API assignment validator method here than can be implemented
			// by each provider. Evaluate and implement as required.
			/*Site st = null;
			try {
				st = siteService.getSite((String) state.getAttribute(STATE_CONTEXT_STRING));
			} catch (IdUnusedException iue) {
				M_log.warn(this + ":setNewAssignmentParameters: Site not found!" + iue.getMessage());
			}

			//TODO depending on new federated integration, use one property or another to check we're using TII
			String reviewServiceName = contentReviewService.getServiceName();
			if (contentReviewSiteAdvisor.siteCanUseReviewService(st))
			{
				if (!contentReviewService.allowMultipleAttachments()
					&&((Integer) state.getAttribute(NEW_ASSIGNMENT_SUBMISSION_TYPE)) != Assignment.SINGLE_ATTACHMENT_SUBMISSION &&
						((Integer) state.getAttribute(NEW_ASSIGNMENT_SUBMISSION_TYPE)) != Assignment.TEXT_ONLY_ASSIGNMENT_SUBMISSION )
				{
					addAlert(state, rb.getFormattedMessage("gen.cr.submit", new Object[]{reviewServiceName}));
				}
			}

			if (allowReviewService && st != null && contentReviewSiteAdvisor.siteCanUseLTIReviewServiceForAssignment( st, new Date() ) && validify)
			{
				if (title != null && contentreviewAssignMin > 0 && title.length() < contentreviewAssignMin){
					// if the title is shorter than the minimum post the message
					// One could ignore the message and still post the assignment
					if (state.getAttribute(NEW_ASSIGNMENT_SHORT_TITLE) == null){
						state.setAttribute(NEW_ASSIGNMENT_SHORT_TITLE, Boolean.TRUE.toString());
					} else {
						state.removeAttribute(NEW_ASSIGNMENT_SHORT_TITLE);
					}
				} else {
					state.removeAttribute(NEW_ASSIGNMENT_SHORT_TITLE);
				}
				if (title != null && contentreviewAssignMax > 0 && title.length() > contentreviewAssignMax){
					// if the title is longer than the maximum post the message
					// One could ignore the message and still post the assignment
					if (state.getAttribute(NEW_ASSIGNMENT_LONG_TITLE) == null){
						state.setAttribute(NEW_ASSIGNMENT_LONG_TITLE, Boolean.TRUE.toString());
					} else {
						state.removeAttribute(NEW_ASSIGNMENT_LONG_TITLE);
					}
				} else {
					state.removeAttribute(NEW_ASSIGNMENT_LONG_TITLE);
				}
				User user = (User) state.getAttribute(STATE_USER);
				if(StringUtils.isEmpty(user.getFirstName()) || StringUtils.isEmpty(user.getLastName()) || StringUtils.isEmpty(user.getEmail())){
					if (state.getAttribute(NEW_ASSIGNMENT_INSTRUCTOR_FIELDS) == null){
						state.setAttribute(NEW_ASSIGNMENT_INSTRUCTOR_FIELDS, Boolean.TRUE.toString());
					} else {
						state.removeAttribute(NEW_ASSIGNMENT_INSTRUCTOR_FIELDS);
					}
				} else {
					state.removeAttribute(NEW_ASSIGNMENT_INSTRUCTOR_FIELDS);
				}
				if (StringUtils.isBlank( title ) || (contentreviewAssignMin > 0 && title.length() < contentreviewAssignMin)){
					addAlert(state, rb.getFormattedMessage("review.assignchars", new Object[]{reviewServiceName, contentreviewAssignMin}));
				}
				if (StringUtils.isNotBlank( title ) && contentreviewAssignMax > 0 && title.length() > contentreviewAssignMax){
					addAlert(state, rb.getFormattedMessage("review.assigncharslong", new Object[]{reviewServiceName, contentreviewAssignMax}));
				}
				if (state.getAttribute(NEW_ASSIGNMENT_INSTRUCTOR_FIELDS) != null){
					addAlert(state, rb.getFormattedMessage("review.instructor.fields", new Object[]{ reviewServiceName}));
				}
				if (contentreviewSiteMin > 0 && st.getTitle().length() < contentreviewSiteMin){
					addAlert(state, rb.getFormattedMessage("review.sitechars", new Object[]{reviewServiceName, contentreviewSiteMin}));
				} else if (contentreviewSiteMax > 0 && st.getTitle().length() > contentreviewSiteMax){
					addAlert(state, rb.getFormattedMessage("review.sitecharslong", new Object[]{reviewServiceName, contentreviewSiteMax}));
				}
				if(contentreviewSiteYears > 0){
					GregorianCalendar agoCalendar = new GregorianCalendar();
					agoCalendar.add(GregorianCalendar.YEAR, -contentreviewSiteYears);
					Date agoDate = agoCalendar.getTime();
					if (st.getCreatedDate().before(agoDate)){
						addAlert(state, rb.getFormattedMessage("review.oldsite", new Object[]{contentreviewSiteYears, reviewServiceName}));
					}
				}
			}*/			
        }
        state.setAttribute(NEW_ASSIGNMENT_USE_REVIEW_SERVICE, b);

        //set whether students can view the review service results
        r = params.getString(NEW_ASSIGNMENT_ALLOW_STUDENT_VIEW);
        if (r == null) b = Boolean.FALSE.toString();
        else b = Boolean.TRUE.toString();
        state.setAttribute(NEW_ASSIGNMENT_ALLOW_STUDENT_VIEW, b);

        //set submit options
        r = params.getString(NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_RADIO);
        if (r == null || (!NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_STANDARD.equals(r) && !NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_INSITUTION.equals(r)))
            r = NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_NONE;
        state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_RADIO, r);
        //set originality report options
        r = params.getString(NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_RADIO);
        if (r == null || !NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_DUE.equals(r))
            r = NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_IMMEDIATELY;
        state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_RADIO, r);
        //set check repository options:
        r = params.getString(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_TURNITIN);
        if (r == null) b = Boolean.FALSE.toString();
        else b = Boolean.TRUE.toString();
        state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_TURNITIN, b);

        r = params.getString(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INTERNET);
        if (r == null) b = Boolean.FALSE.toString();
        else b = Boolean.TRUE.toString();
        state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INTERNET, b);

        r = params.getString(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_PUB);
        if (r == null) b = Boolean.FALSE.toString();
        else b = Boolean.TRUE.toString();
        state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_PUB, b);

        r = params.getString(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INSTITUTION);
        if (r == null) b = Boolean.FALSE.toString();
        else b = Boolean.TRUE.toString();
        state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INSTITUTION, b);

        //exclude bibliographic materials:
        r = params.getString(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_BIBLIOGRAPHIC);
        if (r == null) b = Boolean.FALSE.toString();
        else b = Boolean.TRUE.toString();
        state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_BIBLIOGRAPHIC, b);

        //exclude quoted materials:
        r = params.getString(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_QUOTED);
        if (r == null) b = Boolean.FALSE.toString();
        else b = Boolean.TRUE.toString();
        state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_QUOTED, b);

        //exclude self plag
        r = params.getString(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_SELF_PLAG);
        if (r == null) b = Boolean.FALSE.toString();
        else b = Boolean.TRUE.toString();
        state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_SELF_PLAG, b);

        //store inst index
        r = params.getString(NEW_ASSIGNMENT_REVIEW_SERVICE_STORE_INST_INDEX);
        if (r == null) b = Boolean.FALSE.toString();
        else b = Boolean.TRUE.toString();
        state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_STORE_INST_INDEX, b);

        //student preview
        r = params.getString(NEW_ASSIGNMENT_REVIEW_SERVICE_STUDENT_PREVIEW);
        b = (r == null) ? Boolean.FALSE.toString() : Boolean.TRUE.toString();
        state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_STUDENT_PREVIEW, b);

        //exclude small matches
        r = params.getString(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_SMALL_MATCHES);
        if (r == null) b = Boolean.FALSE.toString();
        else b = Boolean.TRUE.toString();
        state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_SMALL_MATCHES, b);
		
		//allow any type
		if (hideAllowAnyFileOption)
		{
			state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_ALLOW_ANY_FILE, Boolean.toString(allowAnyFileOptionDefault));
		}
		else
		{
			r = params.getString(NEW_ASSIGNMENT_REVIEW_SERVICE_ALLOW_ANY_FILE);
			state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_ALLOW_ANY_FILE, Boolean.toString(r != null));
		}

		//set whether students can view the review service grades
		r = params.getString(NEW_ASSIGNMENT_ALLOW_STUDENT_VIEW_EXTERNAL_GRADE);
		if (r == null) b = Boolean.FALSE.toString();
		else b = Boolean.TRUE.toString();
		state.setAttribute(NEW_ASSIGNMENT_ALLOW_STUDENT_VIEW_EXTERNAL_GRADE, b);

        //exclude type:
        //only options are 0=none, 1=words, 2=percentages
        r = params.getString(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_TYPE);
        if (!"0".equals(r) && !"1".equals(r) && !"2".equals(r)) {
            //this really shouldn't ever happen (unless someone's messing with the parameters)
            r = "0";
        }
        state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_TYPE, r);

        //exclude value
        if (!"0".equals(r)) {
            r = params.getString(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_VALUE);
            try {
                int rInt = Integer.parseInt(r);
                if (rInt < 0 || rInt > 100) {
                    //addAlert(state, rb.getString("review.exclude.matches.value_error"));
					alerts.add(rb.getString("review.exclude.matches.value_error"));
                }
            } catch (Exception e) {
                //addAlert(state, rb.getString("review.exclude.matches.value_error"));
				alerts.add(rb.getString("review.exclude.matches.value_error"));
            }
            state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_VALUE, r);
        } else {
            state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_VALUE, "1");
        }
		
		return alerts;
	}
	
	public PostSaveContentReviewParams postSaveAssignmentContentReviewSettings(SessionState state, Assignment asn, Map<String, String> asnProps, Assignment.SubmissionType submissionType)
	{
		//assume creating the assignment with the content review service will be successful later
		state.setAttribute("contentReviewSuccess", Boolean.TRUE);
		
		PostSaveContentReviewParams params = new PostSaveContentReviewParams();
		params.useReviewService = "true".equalsIgnoreCase((String) state.getAttribute(NEW_ASSIGNMENT_USE_REVIEW_SERVICE));

		params.allowStudentViewReport = "true".equalsIgnoreCase((String) state.getAttribute(NEW_ASSIGNMENT_ALLOW_STUDENT_VIEW));
		params.allowStudentViewExternalGrade = "true".equalsIgnoreCase((String) state.getAttribute(NEW_ASSIGNMENT_ALLOW_STUDENT_VIEW_EXTERNAL_GRADE));
		

		// If the assignment switched to non-electronic, we need to use some of the assignment's previous content-review settings.
		// This way, students will maintain access to their originality reports when appropriate.
		if (submissionType == Assignment.SubmissionType.NON_ELECTRONIC_ASSIGNMENT_SUBMISSION) {
			params.useReviewService = asn.getContentReview();
			params.allowStudentViewReport = Boolean.valueOf(asnProps.get(NEW_ASSIGNMENT_ALLOW_STUDENT_VIEW));
		}

		params.submitReviewRepo = (String) state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_RADIO);
		params.generateOriginalityReport = (String) state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_RADIO);
		params.checkTurnitin = "true".equalsIgnoreCase((String) state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_TURNITIN));
		params.checkInternet = "true".equalsIgnoreCase((String) state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INTERNET));
		params.checkPublications = "true".equalsIgnoreCase((String) state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_PUB));
		params.checkInstitution = "true".equalsIgnoreCase((String) state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INSTITUTION));
		params.allowAnyFile = "true".equalsIgnoreCase((String) state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_ALLOW_ANY_FILE));
		//exclude bibliographic materials
		params.excludeBibliographic = "true".equalsIgnoreCase((String) state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_BIBLIOGRAPHIC));
		//exclude quoted materials
		params.excludeQuoted = "true".equalsIgnoreCase((String) state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_QUOTED));
		//exclude self plag
		params.excludeSelfPlag = "true".equalsIgnoreCase((String) state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_SELF_PLAG));
		//store inst index
		params.storeInstIndex = "true".equalsIgnoreCase((String) state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_STORE_INST_INDEX));
		//student preview
		params.studentPreview = "true".equalsIgnoreCase((String) state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_STUDENT_PREVIEW));
		//exclude small matches
		params.excludeSmallMatches = "true".equalsIgnoreCase((String) state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_SMALL_MATCHES));
		//exclude type 0=none, 1=words, 2=percentages
		params.excludeType = 0;
		params.excludeValue = 1;
		if (params.excludeSmallMatches) {
			try {
				params.excludeType = Integer.parseInt((String) state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_TYPE));
				if (params.excludeType < 0 || params.excludeType > 2) {
					params.excludeType = 0;
				}
			} catch (Exception e) {
				//Numberformatexception
			}
			//exclude value
			try {
				params.excludeValue = Integer.parseInt((String) state.getAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_VALUE));
				if (params.excludeValue < 0 || params.excludeValue > 100) {
					params.excludeValue = 1;
				}
			} catch (Exception e) {
				//Numberformatexception
			}
		}
		
		return params;
	}
	
	public void commitAssignmentContentReviewSettings(PostSaveContentReviewParams params, Assignment asn)
	{
		asn.setContentReview(params.useReviewService);
		Map<String, String> p = asn.getProperties();
        p.put("s_view_report", Boolean.toString(params.allowStudentViewReport));
		p.put(NEW_ASSIGNMENT_ALLOW_STUDENT_VIEW_EXTERNAL_GRADE, Boolean.toString(params.allowStudentViewExternalGrade));
        p.put(NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_RADIO, params.submitReviewRepo);
        p.put(NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_RADIO, params.generateOriginalityReport);
        p.put(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INSTITUTION, Boolean.toString(params.checkInstitution));
        p.put(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INTERNET, Boolean.toString(params.checkInternet));
        p.put(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_PUB, Boolean.toString(params.checkPublications));
        p.put(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_TURNITIN, Boolean.toString(params.checkTurnitin));
        p.put(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_BIBLIOGRAPHIC, Boolean.toString(params.excludeBibliographic));
        p.put(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_QUOTED, Boolean.toString(params.excludeQuoted));
        p.put(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_SELF_PLAG, Boolean.toString(params.excludeSelfPlag));
        p.put(NEW_ASSIGNMENT_REVIEW_SERVICE_STORE_INST_INDEX, Boolean.toString(params.storeInstIndex));
        p.put(NEW_ASSIGNMENT_REVIEW_SERVICE_STUDENT_PREVIEW, Boolean.toString(params.studentPreview));
        p.put(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_TYPE, Integer.toString(params.excludeType));
        p.put(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_VALUE, Integer.toString(params.excludeValue));
		p.put(NEW_ASSIGNMENT_REVIEW_SERVICE_ALLOW_ANY_FILE, Boolean.toString(params.allowAnyFile));
	}
	
	public void commitAssignmentContentReview(SessionState state, AssignmentConfig cfg, ResourceLoader rb)
	{
		if (!createTIIAssignment(cfg, state, rb))
		{
			state.setAttribute("contentReviewSuccess", Boolean.FALSE);
		}
	}
	
	public void doEditAssignmentContentReview(SessionState state, Assignment asn)
	{
		state.setAttribute(NEW_ASSIGNMENT_USE_REVIEW_SERVICE, asn.getContentReview());

		//set whether students can view the review service results
		Map<String, String> p = asn.getProperties();
		state.setAttribute(NEW_ASSIGNMENT_ALLOW_STUDENT_VIEW, Boolean.valueOf(p.get("s_view_report")).toString());
		//set whether students can view the review service grades
		state.setAttribute(NEW_ASSIGNMENT_ALLOW_STUDENT_VIEW_EXTERNAL_GRADE, p.get(NEW_ASSIGNMENT_ALLOW_STUDENT_VIEW_EXTERNAL_GRADE));

		state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_RADIO, p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_RADIO));
		state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_RADIO, p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_RADIO));
		state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_TURNITIN, p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_TURNITIN));
		state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INTERNET, p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INTERNET));
		state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_PUB, p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_PUB));
		state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INSTITUTION, p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INSTITUTION));
		state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_ALLOW_ANY_FILE, p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_ALLOW_ANY_FILE));
		//exclude bibliographic
		state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_BIBLIOGRAPHIC, p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_BIBLIOGRAPHIC));
		//exclude quoted
		state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_QUOTED, p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_QUOTED));
		//exclude self plag
		state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_SELF_PLAG, p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_SELF_PLAG));
		//store inst index
		state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_STORE_INST_INDEX, p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_STORE_INST_INDEX));
		//student preview
		state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_STUDENT_PREVIEW, p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_STUDENT_PREVIEW));
		//exclude type
		state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_TYPE, p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_TYPE));
		//exclude value
		state.setAttribute(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_VALUE, p.get(NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_VALUE));
		
		// TIITODO: figure out if we should implement this in this way. Seems like it is
		// TII-specfic and mostly here to deal with legacy assignments. Also appears to be duplicated logic from elsewhere
		// in this class
		// generate alert when editing an assignment from old site
		/*if(allowReviewService && allowLTIReviewService(getSiteFromState(state), a) && a.getContent().getAllowReviewService()){
			String reviewServiceName = contentReviewService.getServiceName();
			if (contentreviewAssignMin > 0 && a.getTitle().length() < contentreviewAssignMin){
				addAlert(state, rb.getFormattedMessage("review.assignchars", new Object[]{reviewServiceName, contentreviewAssignMin}));
			}
			if (contentreviewAssignMax > 0 && a.getTitle().length() > contentreviewAssignMax){
				addAlert(state, rb.getFormattedMessage("review.assigncharslong", new Object[]{reviewServiceName, contentreviewAssignMax}));
			}
			User user = (User) state.getAttribute(STATE_USER);
			if(StringUtils.isEmpty(user.getFirstName()) || StringUtils.isEmpty(user.getLastName()) || StringUtils.isEmpty(user.getEmail())){
				addAlert(state, rb.getFormattedMessage("review.instructor.fields", new Object[]{ reviewServiceName}));
			}
			Site st = null;
			try {
				st = SiteService.getSite((String) state.getAttribute(STATE_CONTEXT_STRING));
				if (contentreviewSiteMin > 0 && st.getTitle().length() < contentreviewSiteMin){
					addAlert(state, rb.getFormattedMessage("review.sitechars", new Object[]{reviewServiceName, contentreviewSiteMin}));
				}
				if (contentreviewSiteMax > 0 && st.getTitle().length() > contentreviewSiteMax){
					addAlert(state, rb.getFormattedMessage("review.sitecharslong", new Object[]{reviewServiceName, contentreviewSiteMax}));
				}
				if(contentreviewSiteYears > 0){
					GregorianCalendar agoCalendar = new GregorianCalendar();
					agoCalendar.add(GregorianCalendar.YEAR, -contentreviewSiteYears);
					Date agoDate = agoCalendar.getTime();
					if (st.getCreatedDate().before(agoDate)){
						addAlert(state, rb.getFormattedMessage("review.oldsite", new Object[]{contentreviewSiteYears, reviewServiceName}));
					}
				}
			} catch (IdUnusedException iue) {
				M_log.warn(this + ":doEdit_Assignment: Site not found!" + iue.getMessage());
			}
		}*/
	}
	
	public Optional<String> checkFileUpload(boolean inPeerReviewMode, boolean allowReviewService, Assignment asn, ContentResource attachment)
	{
		String alert = null;

		// general check for file types accepted by CRS
		if (!inPeerReviewMode && allowReviewService && asn.getContentReview() && !contentReviewService.isAcceptableContent(attachment))
		{
			alert = rb.getFormattedMessage("review.file.not.accepted", new Object[]{contentReviewService.getServiceName(),
				getContentReviewAcceptedFileTypesMessage()});
			// TODO: delete the file? Could we have done this check without creating it in the first place?
		}
		else if (turnitin.isPresent() && !turnitin.get().isAcceptableSize(attachment))
		{
			alert = rb.getFormattedMessage("cr.size.warning", new Object[]{contentReviewService.getServiceName()});
		}

		return Optional.ofNullable(alert);
	}
	
	public void buildMainPanelContextReviewService(Context context, boolean allowReviewService)
	{
		// Check whether content review service is enabled, present and enabled for this site
		
        context.put("allowReviewService", allowReviewService);

        if (allowReviewService)
		{
            //put the review service strings in context
            String reviewServiceName = contentReviewService.getServiceName();
            String reviewServiceTitle = rb.getFormattedMessage("review.title", new Object[]{reviewServiceName});
            String reviewServiceUse = rb.getFormattedMessage("review.use", new Object[]{reviewServiceName});
            String reviewServiceNonElectronic1 = rb.getFormattedMessage("review.switch.ne.1", reviewServiceName);
            String reviewServiceNonElectronic2 = rb.getFormattedMessage("review.switch.ne.2", reviewServiceName);
            context.put("reviewServiceName", reviewServiceName);
            context.put("reviewServiceTitle", reviewServiceTitle);
            context.put("reviewServiceUse", reviewServiceUse);
            context.put("reviewIndicator", rb.getFormattedMessage("review.contentReviewIndicator", new Object[]{reviewServiceName}));
            context.put("reviewSwitchNe1", reviewServiceNonElectronic1);
            context.put("reviewSwitchNe2", reviewServiceNonElectronic2);
        }
	}
	
	/**
	 * Takes the inline submission, prepares it as an attachment to the submission and queues the attachment with the content review service
	 * 
	 * @param text the inline part of the submission
	 * @param submission the submission
	 * @param student the user this submission is for
	 * @param sa security advisor
	 * @param currentUser the current user
	 * @param siteId the site id
	 * @param isResubmission true if this is a resubmission
	 * @param doQueue whether the inline attachment should be queued for the Content Review Service
	 * If false, the file will still be created in case the instructor enables content review in the future,
	 * at which point it will be queued by the sync'ing process.
	 * @return a localized alert message to present in the UI, if necessary 
	 */
    public Optional<String> prepareInlineForContentReview(String text, AssignmentSubmission submission, User student,
			SecurityAdvisor sa, User currentUser, String siteId, boolean isResubmission, boolean doQueue)
	{
		// TIITODO: do something with the isResubmission and doQueue params we added...see TIITODO later on in this method
		
		String alert = null;
        // Why does it need to remove the users submission?
        // If it needs to remove the submission it should first add a new one and only remove the old one if the new one was added successfully.

        //We will be replacing the inline submission's attachment
        //firstly, disconnect any existing attachments with AssignmentSubmission.PROP_INLINE_SUBMISSION set
        Set<String> attachments = submission.getAttachments();
        for (String attachment : attachments) {
            Reference reference = entityManager.newReference(attachment);
            ResourceProperties referenceProperties = reference.getProperties();
            if ("true".equals(referenceProperties.getProperty(AssignmentConstants.PROP_INLINE_SUBMISSION))) {
                attachments.remove(attachment);
            }
        }

        //now prepare the new resource
        //provide lots of info for forensics - filename=InlineSub_assignmentId_userDisplayId_(for_studentDisplayId)_date.html
        String currentDisplayName = currentUser.getDisplayId();
        SimpleDateFormat dform = ((SimpleDateFormat) DateFormat.getDateInstance());
        //avoid semicolons in filenames, right?
        dform.applyPattern("yyyy-MM-dd_HH-mm-ss");
        StringBuilder sb_resourceId = new StringBuilder("InlineSub_");
        String u = "_";
        sb_resourceId.append(submission.getAssignment().getId()).append(u).append(currentDisplayName).append(u);
        boolean isOnBehalfOfStudent = student != null && !student.equals(currentUser);
        if (isOnBehalfOfStudent) {
            // We're submitting on behalf of somebody
            sb_resourceId.append("for_").append(student.getDisplayId()).append(u);
        }
        sb_resourceId.append(dform.format(new Date()));

        String fileExtension = HTML_EXT;

		/*
		 * TODO: add and use a method in ContentHostingService to get the length of the ID of an attachment collection
		 * Attachment collections currently look like this:
		 * /attachment/dc126c4a-a48f-42a6-bda0-cf7b9c4c5c16/Assignments/eac7212a-9597-4b7d-b958-89e1c47cdfa7/
		 * See BaseContentService.addAttachmentResource for more information
		 */
        String toolName = "Assignments";
        // TODO: add and use a method in IdManager to get the maxUuidLength
        int maxUuidLength = 36;
        int esl = Entity.SEPARATOR.length();
        int attachmentCollectionLength = ContentHostingService.ATTACHMENTS_COLLECTION.length() + siteId.length() + esl + toolName.length() + esl + maxUuidLength + esl;
        int maxChars = ContentHostingService.MAXIMUM_RESOURCE_ID_LENGTH - attachmentCollectionLength - fileExtension.length() - 1;
        String resourceId = StringUtils.substring(sb_resourceId.toString(), 0, maxChars) + fileExtension;

        ResourcePropertiesEdit inlineProps = contentHostingService.newResourceProperties();
		String fileName = rb.getString("submission.inline");
		if (!fileName.endsWith(fileExtension))
		{
			fileName += fileExtension;
		}
        inlineProps.addProperty(ResourceProperties.PROP_DISPLAY_NAME, fileName);
        inlineProps.addProperty(ResourceProperties.PROP_DESCRIPTION, resourceId);
        inlineProps.addProperty(AssignmentConstants.PROP_INLINE_SUBMISSION, "true");

        //create a byte array input stream
        //text is almost in html format, but it's missing the start and ending tags
        //(Is this always the case? Does the content review service care?)
        String toHtml = "<html><head></head><body>" + text + "</body></html>";
        InputStream contentStream = new ByteArrayInputStream(toHtml.getBytes());

        String contentType = "text/html";

        //duplicating code from doAttachUpload. TODO: Consider refactoring into a method

        try {
            securityService.pushAdvisor(sa);
            ContentResource attachment = contentHostingService.addAttachmentResource(resourceId, siteId, toolName, contentType, contentStream, inlineProps);
            // TODO: need to put this file in some kind of list to improve performance with web service impls of content-review service
            String contentUserId = isOnBehalfOfStudent ? student.getId() : currentUser.getId();
			// TIITODO: handle isResubmission and doQueue params here
			// may need to switch to the TurnitinExtendedReviewService API for that?
            contentReviewService.queueContent(contentUserId, siteId, AssignmentReferenceReckoner.reckoner().assignment(submission.getAssignment()).reckon().getReference(), Collections.singletonList(attachment));

            try {
                Reference ref = entityManager.newReference(contentHostingService.getReference(attachment.getId()));
                attachments.add(ref.getReference());
                assignmentService.updateSubmission(submission);
            } catch (Exception e) {
                log.warn(this + "prepareInlineForContentReview() cannot find reference for " + attachment.getId() + e.getMessage());
            }
        } catch (PermissionException e) {
            alert = rb.getString("notpermis4");
        } catch (RuntimeException e) {
            if (contentHostingService.ID_LENGTH_EXCEPTION.equals(e.getMessage())) {
                alert = rb.getFormattedMessage("alert.toolong", resourceId);
            }
        } catch (ServerOverloadException e) {
            log.debug(this + ".prepareInlineForContentReview() ***** DISK IO Exception ***** " + e.getMessage());
            alert = rb.getString("failed.diskio");
        } catch (Exception ignore) {
            log.debug(this + ".prepareInlineForContentReview() ***** Unknown Exception ***** " + ignore.getMessage());
            alert = rb.getString("failed");
        } finally {
            securityService.popAdvisor(sa);
        }
		
		return Optional.ofNullable(alert);
    }
	
	public Optional<String> manageContentReviewAlertMessages(SessionState state)
	{
		String alert = null;
		
		//SAK-30430 managing the content review error when creating assignment
		if (state.getAttribute("alertMessageCR") != null)
		{
			String uiService = serverConfigurationService.getString("ui.service", "Sakai");
			String[] args = new String[]{contentReviewService.getServiceName(), uiService};
			alert = rb.getFormattedMessage("content_review.error.createAssignment", args);
			state.removeAttribute("alertMessageCR");
		}
		
		return Optional.ofNullable(alert);
	}
	
	public Optional<String> buildStudentViewAssignmentContext(SessionState state, Context context,
			String contextString, Assignment assignment, Site site, String templateAux)
	{	
		if (turnitin.isPresent())
		{
			return turnitin.get().buildStudentViewAssignmentContext(state, context, contextString, assignment, site, templateAux);
		}

		return Optional.empty();
	}
	
	public void buildStudentViewSubmissionConfirmationContext(Context context, Site site, Assignment asn, User user)
	{
		turnitin.ifPresent(tii -> tii.buildStudentViewSubmissionConfirmationContext(context, site, asn, user));
	}
	
	public void buildStudentViewGradeContext(SessionState state, Context context, Assignment asn, Site site)
	{
		turnitin.ifPresent(tii -> tii.putTiiLtiPropsIntoContext(state, context, asn, site));
	}
	
	public void buildInstructorNewEditAssignmentContext(Context context, Assignment asn)
	{
		turnitin.ifPresent(tii -> tii.buildInstructorNewEditAssignmentContext(context, asn));
	}
	
	public void buildInstructorGradeSubmissionContext(SessionState state, Context context, Assignment asn, Site site)
	{
		turnitin.ifPresent(tii -> tii.putTiiLtiPropsIntoContext(state, context, asn, site));
	}
	
	public String buildInstructorGradeAssignmentContext(SessionState state, Context context, Site site, Assignment asn)
	{
		return turnitin.map(tii -> tii.buildInstructorGradeAssignmentContext(state, context, site, asn)).orElse("");
	}
	
	public void buildInstructorViewAssignmentContext(Context context, Site site, Assignment asn)
	{
		turnitin.ifPresent(tii -> tii.buildInstructorViewAssignmentContext(context, site, asn));
	}
	
	/**
	 * Tells the content review service that we're offering an extension to an individual student; content review service will decide what to do with this
	 */
	// TIITODO: this might make sense in the general CRS API?
	public void handleIndividualExtension(SessionState state, AssignmentSubmission sub, Instant extension)
	{
		turnitin.ifPresent(tii -> tii.handleIndividualExtension(state, sub, extension));
	}
	
	public void cleanSubmissionProperties(AssignmentSubmission submission)
	{
		Map<String, String> props = submission.getProperties();
		props.put(AssignmentConstants.REVIEW_SCORE, "-2"); // the default is -2 (e.g., for a new submission)
        props.put(AssignmentConstants.REVIEW_STATUS, null);
		
		submission.setExternalGradeDifferent(false);
		
		// TIITODO: determine if we still need to reset other CR attributes in the properties or submission object
		
	}
	
	public void doDeleteAssignment(Site site, Assignment asn)
	{
		turnitin.ifPresent(tii -> tii.handleDeleteAssignment(site, asn));
	}
	
	public void removeAllSettingsFromState(SessionState state)
	{
		state.removeAttribute( NEW_ASSIGNMENT_USE_REVIEW_SERVICE );
		state.removeAttribute( NEW_ASSIGNMENT_ALLOW_STUDENT_VIEW );
		state.removeAttribute( NEW_ASSIGNMENT_ALLOW_STUDENT_VIEW_EXTERNAL_GRADE );
		state.removeAttribute( NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_RADIO );
		state.removeAttribute( NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_NONE );
		state.removeAttribute( NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_STANDARD );
		state.removeAttribute( NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_INSITUTION );
		state.removeAttribute( NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_RADIO );
		state.removeAttribute( NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_IMMEDIATELY );
		state.removeAttribute( NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_IMMEDIATELY_RESUB );
		state.removeAttribute( NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_DUE );
		state.removeAttribute( NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_TURNITIN );
		state.removeAttribute( NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INTERNET );
		state.removeAttribute( NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_PUB );
		state.removeAttribute( NEW_ASSIGNMENT_REVIEW_SERVICE_CHECK_INSTITUTION );
		state.removeAttribute( NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_BIBLIOGRAPHIC );
		state.removeAttribute( NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_QUOTED );
		state.removeAttribute( NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_SMALL_MATCHES );
		state.removeAttribute( NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_TYPE );
		state.removeAttribute( NEW_ASSIGNMENT_REVIEW_SERVICE_EXCLUDE_VALUE );
		state.removeAttribute( NEW_ASSIGNMENT_REVIEW_SERVICE_ALLOW_ANY_FILE );		
	}
	
	/**
     * Get a user facing String message represeting the list of file types that are accepted by the content review service
     * They appear in this form: PowerPoint (.pps, .ppt, .ppsx, .pptx), plain text (.txt), ...
     */
    private String getContentReviewAcceptedFileTypesMessage() {
        StringBuilder sb = new StringBuilder();
        Map<String, SortedSet<String>> fileTypesToExtensions = contentReviewService.getAcceptableFileTypesToExtensions();
        // The delimiter is a comma. Commas still need to be internationalized (the arabic comma is not the english comma)
        String i18nDelimiter = rb.getString("content_review.accepted.types.delimiter") + " ";
        String i18nLParen = " " + rb.getString("content_review.accepted.types.lparen");
        String i18nRParen = rb.getString("content_review.accepted.types.rparen");
        String fDelimiter = "";
        // don't worry about conjunctions; just separate with commas
        for (Map.Entry<String, SortedSet<String>> entry : fileTypesToExtensions.entrySet()) {
            String fileType = entry.getKey();
            SortedSet<String> extensions = entry.getValue();
            sb.append(fDelimiter).append(fileType).append(i18nLParen);
            String eDelimiter = "";
            for (String extension : extensions) {
                sb.append(eDelimiter).append(extension);
                // optimized by java compiler
                eDelimiter = i18nDelimiter;
            }
            sb.append(i18nRParen);

            // optimized by java compiler
            fDelimiter = i18nDelimiter;
        }

        return sb.toString();
    }
	
	private List<String> getSubmissionRepositoryOptions() {
        List<String> submissionRepoSettings = new ArrayList<>();
        String[] propertyValues = serverConfigurationService.getStrings("turnitin.repository.setting");
        if (propertyValues != null && propertyValues.length > 0) {
            for (int i = 0; i < propertyValues.length; i++) {
                String propertyVal = propertyValues[i];
                if (propertyVal.equals(NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_NONE) ||
                        propertyVal.equals(NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_INSITUTION) ||
                        propertyVal.equals(NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_STANDARD)) {
                    submissionRepoSettings.add(propertyVal);
                }
            }
        }

        // if there are still no valid settings in the list at this point, use the default
        if (submissionRepoSettings.isEmpty()) {
            // add all three
            submissionRepoSettings.add(NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_NONE);
            submissionRepoSettings.add(NEW_ASSIGNMENT_REVIEW_SERVICE_SUBMIT_STANDARD);
        }

        return submissionRepoSettings;
    }

    private List<String> getReportGenOptions() {
        List<String> reportGenSettings = new ArrayList<>();
        String[] propertyValues = serverConfigurationService.getStrings("turnitin.report_gen_speed.setting");
        if (propertyValues != null && propertyValues.length > 0) {
            for (int i = 0; i < propertyValues.length; i++) {
                String propertyVal = propertyValues[i];
                if (propertyVal.equals(NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_DUE) ||
                        propertyVal.equals(NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_IMMEDIATELY)) {
                    reportGenSettings.add(propertyVal);
                }
            }
        }

        // if there are still no valid settings in the list at this point, use the default
        if (reportGenSettings.isEmpty()) {
            // add all three
            reportGenSettings.add(NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_DUE);
            reportGenSettings.add(NEW_ASSIGNMENT_REVIEW_SERVICE_REPORT_IMMEDIATELY);
        }

        return reportGenSettings;
    }
	
	/**
     * Get a list of accepted mime types suitable for an 'accept' attribute in an html file picker
     *
     * @throws IllegalArgumentException if the assignment accepts all attachments
     */
    private String getContentReviewAcceptedMimeTypes() {
        if (contentReviewService.allowAllContent()) {
            throw new IllegalArgumentException("getContentReviewAcceptedMimeTypes invoked, but the content review service accepts all attachments");
        }

        StringBuilder mimeTypes = new StringBuilder();
        Collection<SortedSet<String>> mimeTypesCollection = contentReviewService.getAcceptableExtensionsToMimeTypes().values();
        String delimiter = "";
        for (SortedSet<String> mimeTypesList : mimeTypesCollection) {
            for (String mimeType : mimeTypesList) {
                mimeTypes.append(delimiter).append(mimeType);
                delimiter = ",";
            }
        }
        return mimeTypes.toString();
    }
	
	/**
	 * Tells the content review service that we're offering an extension to an individual student; content review service will decide what to do with this
	 */
	// TIITODO: implement this
	/*private void handleIndividualExtensionInContentReview(AssignmentSubmission sub, Time extension, SessionState state)
	{
		Assignment a = sub.getAssignment();
		if (a != null)
		{
			// Assignment open / due / close dates are easy to access, but the assignment level 'resubmit until' is a base64'd ResourceProperty
			Time asnResubmitCloseTime = null;
			ResourceProperties props = a.getProperties();
			if (props != null)
			{
				String strResubmitCloseTime = props.getProperty(AssignmentSubmission.ALLOW_RESUBMIT_CLOSETIME);
				if (!StringUtils.isBlank(strResubmitCloseTime))
				{
					asnResubmitCloseTime = TimeService.newTime(Long.parseLong(strResubmitCloseTime));
				}
			}
			syncTIIAssignment(a.getContent(), a.getReference(), a.getOpenTime(), a.getDueTime(), a.getCloseTime(), asnResubmitCloseTime, new Date(extension.getTime()), state, null);
		}
	}*/
}
