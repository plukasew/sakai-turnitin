package org.sakaiproject.contentreview.model;

import java.util.Date;

/**
 * Represents the configuration associating a Sakai activity with a Turnitin LTI instance
 * @author plukasew, bbailla2
 */
public class TiiActivityConfig
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

	public TiiActivityConfig()
	{
		id = -1L;
		toolId = "";
		activityId = "";
		stealthedLtiId = "";
		turnitinAssignmentId = "";
		// TIITODO: review if null is appropriate; would an Optional<Date> work with our version of Hibernate?
		latestIndividualExtensionDate = null;
	}

	// TIITODO: I'm leaving out the tii_asn_id cause I assume it won't exist when this object is constructed; setTurnitinAssignmentId will be invoked by the callback servlet. Later on, review if this is appropriate
	// TIITODO: likewise for latestIndividualExtensionDate
	public TiiActivityConfig(String toolId, String activityId, String stealthedLtiId)
	{
		id = -1L;
		this.toolId = toolId;
		this.activityId = activityId;
		this.stealthedLtiId = stealthedLtiId;
		turnitinAssignmentId = "";
		latestIndividualExtensionDate = null;
	}

	// TIITODO: remove this; shouldn't be needed hibernate should auto-magically handle this
	/*public ContentReviewActivityConfig(Long id, String toolId, String activityId, String stealthedLtiId, String turnitinAssignmentId, Date latestIndividualExtensionDate)
	{
		this.id = id;
		this.toolId = tooLid;
		this.activityId = activityId;
		this.stealthedLtiId = stealtheLtiId;
		this.turnitinAssignmentId = turnitinAssignmentId;
		this.latestIndividualExtensionDate = latestIndividualExtensionDate;
	}*/

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

	public String getTurnitinAsssignmentId()
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
}
