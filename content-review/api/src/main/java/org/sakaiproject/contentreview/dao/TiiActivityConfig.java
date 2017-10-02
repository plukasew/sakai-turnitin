package org.sakaiproject.contentreview.service.dao;

/**
 * Represents Turnitin specific settings for activities that are integrated with Turnitin assignments
 */
public interface TiiActivityConfig
{
	// TIITODO: ensure the visibility is controlled by sakai.properties; we keep institutional paper repository disabled
	public enum PaperRepository
	{
		NONE,
		STANDARD,
		INSTITUTION
	}

	public enum SmallMatchType
	{
		WORD_COUNT,
		PERCENTAGE
	}

	public enum ReportGenerationSpeed
	{
		IMMEDIATELY,
		ON_DUE_DATE
	}

	public void setAllowAnyFile(boolean allowAnyFile);

	public void setAllowStudentViewExternalGrade(boolean allowStudentViewExternalGrade);

	public void setPaperRepository(PaperRepository paperRepository);

	public void setReportGenerationSpeed(ReportGenerationSpeed reportGenerationSpeed);

	public void setCheckTiiPaperRepository(boolean checkTiiPaperRepository);

	public void setCheckCurrentAndArchived(boolean checkCurrentAndArchived);

	public void setCheckPeriodicalsJournalsPublications(boolean checkPeriodicalsJournalsPublications);

	public void setCheckInstitutionalRepository(boolean checkInstitutionalRepository);

	public void setExcludeBibliographic(boolean excludeBibliographic);

	public void setExcludeQuoted(boolean excludeQuoted);

	public void setExcludeSmallMatches(boolean excludeSmallMatches);

	public void setSmallMatchType(SmallMatchType smallMatchType);

	public void setSmallMatchValue(int smallMatchValue);
}
