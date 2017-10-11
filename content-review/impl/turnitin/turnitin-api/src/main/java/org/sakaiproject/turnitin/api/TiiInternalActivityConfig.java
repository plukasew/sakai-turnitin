package org.sakaiproject.turnitin.api;

import java.util.Date;
import org.sakaiproject.contentreview.service.dao.TiiActivityConfig;

/**
 * Represents Turnitin specific settings for activities that are integrated with Turnitin assignments and configuration required for Turnitin LTI connections
 * @author plukasew, bbailla2
 */
public class TiiInternalActivityConfig implements TiiActivityConfig
{
	// pk
	private Long id;

	/** the Sakai tool registration id associated with this activity (ie. sakai.assignment.grades) **/
	private String toolId;

	/** id of the activity that is linked to a TII assignment **/
	private String activityId; // TIITODO: ensure the associated DB column is indexed; I believe the assignments tool will ask the CRS for the ltiID, and this column will be queried heavily

	/** the id of the LTI instance in Sakai **/
	private String stealthedLtiId;

	/** the turnitin assignment id on their servers; assigned to us by their callback */
	private String turnitinAssignmentId;

	/**
	 * Represents the latest individual extension date of the assignment. Ie. turnitin will not accept submissions after the close date, 
	 * so if individual users are given extensions, we have to extend the deadline for the entire assignment. We need to track this.
	 **/
	private Date latestIndividualExtensionDate;

	// -------------- User specified settings --------------

	// TIITODO: allowStudentViewReport will remain in the tool's domain; the LTI launch will have to pull it from, say, Assignment APIs
	// Title, instructions, open date, due date stuff will have to come from Assignment APIs anyway

	// TIITODO: I think the following settings have yet to be displayed in the Assignments UI

	// TIITODO: this should be hideable from the UI with turnitin.option.any_file.hide (default: false), and turnitin.option.any_file.default (default: false)
	private boolean allowAnyFile;

	/**
	 * Represents whether students are allowed to view grades entered in turnitin
	 */
	private boolean allowStudentViewExternalGrade;

	/**
	 * Represents which paper repository the content will be submitted to
	 */
	private PaperRepository paperRepository;

	/**
	 * Determines when the originality report is to be generated
	 */
	private ReportGenerationSpeed reportGenerationSpeed;

	// Represents which content the paper will be checked against
	private boolean checkTiiPaperRepository;
	private boolean checkCurrentAndArchived;
	private boolean checkPeriodicalsJournalsPublications;
	private boolean checkInstitutionalRepository;

	private boolean excludeBibliographic;
	private boolean excludeQuoted;
	private boolean excludeSmallMatches;

	private SmallMatchType smallMatchType;
	int smallMatchValue;    // integer representing wc, or percentage

	// TIITODO: could add a UI setting, but we're leaning towards implementing acceptLateSubmissions = closeTime.getTime() > dueTime.getTime() as per previous implementations
	// private boolean acceptLateSubmissions;

	public TiiInternalActivityConfig()
	{
		id = -1L;
		toolId = "";
		activityId = "";
		stealthedLtiId = "";
		turnitinAssignmentId = "";
		// TIITODO: review if null is appropriate; would an Optional<Date> work with our version of Hibernate?
		latestIndividualExtensionDate = null;

		allowAnyFile = false;
		allowStudentViewExternalGrade = false;
		paperRepository = PaperRepository.NONE;
		reportGenerationSpeed = ReportGenerationSpeed.IMMEDIATELY;
		checkTiiPaperRepository = true;
		checkCurrentAndArchived = true;
		checkPeriodicalsJournalsPublications = true;
		checkInstitutionalRepository = true;
		excludeBibliographic = true;
		excludeQuoted = true;
		excludeSmallMatches = false;
		smallMatchType = SmallMatchType.WORD_COUNT;  // default; only used if excludeSmallMatches is true
		smallMatchValue = -1;
	}

	// TIITODO: I'm leaving out the tii_asn_id cause I assume it won't exist when this object is constructed; setTurnitinAssignmentId will be invoked by the callback servlet. Later on, review if this is appropriate
	// TIITODO: likewise for latestIndividualExtensionDate
	public TiiInternalActivityConfig(String toolId, String activityId, String stealthedLtiId)
	{
		id = -1L;
		this.toolId = toolId;
		this.activityId = activityId;
		this.stealthedLtiId = stealthedLtiId;
		turnitinAssignmentId = "";
		latestIndividualExtensionDate = null;

		allowAnyFile = false;
		allowStudentViewExternalGrade = false;
		paperRepository = PaperRepository.NONE;
		reportGenerationSpeed = ReportGenerationSpeed.IMMEDIATELY;
		checkTiiPaperRepository = true;
		checkCurrentAndArchived = true;
		checkPeriodicalsJournalsPublications = true;
		checkInstitutionalRepository = true;
		excludeBibliographic = true;
		excludeQuoted = true;
		excludeSmallMatches = false;
		smallMatchType = SmallMatchType.WORD_COUNT;
		smallMatchValue = -1;
	}

	public Long getId()
	{
		return id;
	}

	public void setId(Long id)
	{
		this.id = id;
	}

	public String getToolId()
	{
		return toolId;
	}

	public void setToolId(String toolId)
	{
		this.toolId = toolId;
	}

	public String getActivityId()
	{
		return activityId;
	}

	public void setActivityId(String activityId)
	{
		this.activityId = activityId;
	}

	public String getStealthedLtiId()
	{
		return stealthedLtiId;
	}

	public void setStealthedLtiId(String stealthedLtiId)
	{
		this.stealthedLtiId = stealthedLtiId;
	}

	public String getTurnitinAssignmentId()
	{
		return turnitinAssignmentId;
	}

	public void setTurnitinAssignmentId(String turnitinAssignmentId)
	{
		this.turnitinAssignmentId = turnitinAssignmentId;
	}

	public Date getLatestIndividualExtensionDate()
	{
		return latestIndividualExtensionDate;
	}

	public void setLatestIndividualExtensionDate(Date latestIndividualExtensionDate)
	{
		this.latestIndividualExtensionDate = latestIndividualExtensionDate;
	}

	public boolean isAllowAnyFile()
	{
		return allowAnyFile;
	}

	@Override
	public void setAllowAnyFile(boolean allowAnyFile)
	{
		this.allowAnyFile = allowAnyFile;
	}

	public boolean isAllowStudentViewExternalGrade()
	{
		return allowStudentViewExternalGrade;
	}

	@Override
	public void setAllowStudentViewExternalGrade(boolean allowStudentViewExternalGrade)
	{
		this.allowStudentViewExternalGrade = allowStudentViewExternalGrade;
	}

	@Override
	public PaperRepository getPaperRepository()
	{
		return paperRepository;
	}

	@Override
	public void setPaperRepository(PaperRepository paperRepository)
	{
		this.paperRepository = paperRepository;
	}

	/**
	 * DO NOT USE! This is strictly for hibernate; use the PaperRepository enum
	 */
	public String getStrPaperRepository()
	{
		return paperRepository.name();
	}

	/**
	 * DO NOT USE! This is strictly for hibernate; use the PaperRepository enum
	 */
	public void setStrPaperRepository(String strPaperRepository)
	{
		paperRepository = Enum.valueOf(PaperRepository.class, strPaperRepository);
	}

	public ReportGenerationSpeed getReportGenerationSpeed()
	{
		return reportGenerationSpeed;
	}

	@Override
	public void setReportGenerationSpeed(ReportGenerationSpeed reportGenerationSpeed)
	{
		this.reportGenerationSpeed = reportGenerationSpeed;
	}

	/**
	 * DO NOT USE! This is strictly for hibernate; use the ReportGenerationSpeed enum
	 */
	public String getStrReportGenerationSpeed()
	{
		return reportGenerationSpeed.name();
	}

	/**
	 * DO NOT USE! This is strictly for hibernate; use the ReportGenerationSpeed enum
	 */
	public void setStrReportGenerationSpeed(String strReportGenerationSpeed)
	{
		reportGenerationSpeed = Enum.valueOf(ReportGenerationSpeed.class, strReportGenerationSpeed);
	}

	@Override
	public boolean isCheckTiiPaperRepository()
	{
		return checkTiiPaperRepository;
	}

	@Override
	public void setCheckTiiPaperRepository(boolean checkTiiPaperRepository)
	{
		this.checkTiiPaperRepository = checkTiiPaperRepository;
	}

	public boolean isCheckCurrentAndArchived()
	{
		return checkCurrentAndArchived;
	}

	@Override
	public void setCheckCurrentAndArchived(boolean checkCurrentAndArchived)
	{
		this.checkCurrentAndArchived = checkCurrentAndArchived;
	}

	public boolean isCheckPeriodicalsJournalsPublications()
	{
		return checkPeriodicalsJournalsPublications;
	}

	@Override
	public void setCheckPeriodicalsJournalsPublications(boolean checkPeriodicalsJournalsPublications)
	{
		this.checkPeriodicalsJournalsPublications = checkPeriodicalsJournalsPublications;
	}

	public boolean isCheckInstitutionalRepository()
	{
		return checkInstitutionalRepository;
	}

	@Override
	public void setCheckInstitutionalRepository(boolean checkInstitutionalRepository)
	{
		this.checkInstitutionalRepository = checkInstitutionalRepository;
	}

	public boolean isExcludeBibliographic()
	{
		return this.excludeBibliographic;
	}

	@Override
	public void setExcludeBibliographic(boolean excludeBibliographic)
	{
		this.excludeBibliographic = excludeBibliographic;
	}

	public boolean isExcludeQuoted()
	{
		return excludeQuoted;
	}

	@Override
	public void setExcludeQuoted(boolean excludeQuoted)
	{
		this.excludeQuoted = excludeQuoted;
	}

	public boolean isExcludeSmallMatches()
	{
		return excludeSmallMatches;
	}

	@Override
	public void setExcludeSmallMatches(boolean excludeSmallMatches)
	{
		this.excludeSmallMatches = excludeSmallMatches;
	}

	public SmallMatchType getSmallMatchType()
	{
		return smallMatchType;
	}

	@Override
	public void setSmallMatchType(SmallMatchType smallMatchType)
	{
		this.smallMatchType = smallMatchType;
	}

	/**
	 * DO NOT USE! This is strictly for hibernate; use the SmallMatchType enum
	 */
	public String getStrSmallMatchType()
	{
		return smallMatchType.name();
	}

	/**
	 * DO NOT USE! This is strictly for hibernate; use the SmallMatchType enum
	 */
	public void setStrSmallMatchType(String strSmallMatchType)
	{
		this.smallMatchType = Enum.valueOf(SmallMatchType.class, strSmallMatchType);
	}

	public int getSmallMatchValue()
	{
		return smallMatchValue;
	}

	@Override
	public void setSmallMatchValue(int smallMatchValue)
	{
		this.smallMatchValue = smallMatchValue;
	}
}
