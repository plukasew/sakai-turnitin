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
	private boolean isUrlAccessed;
	
	public TiiContentReviewItem(String contentId)
	{
		this.contentId = contentId;
		isUrlAccessed = false;
	}
}
