package org.sakaiproject.turnitin.api;

import lombok.Data;

/**
 * Represents properties of a content review item specific to Turnitin.
 * @author plukasew
 */
@Data
public class TiiContentReviewItem
{
	private long id;
	private String contentId;
	private boolean urlAccessed;
	private String submissionId;
	private boolean resubmission;
	private String externalGrade;
	
	public TiiContentReviewItem(String contentId)
	{
		this.contentId = contentId;
		urlAccessed = false;
		submissionId = null;
		resubmission = false;
		externalGrade = null;
	}
}
