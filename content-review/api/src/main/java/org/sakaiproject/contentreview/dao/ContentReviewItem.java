/**
 * Copyright (c) 2003 The Apereo Foundation
 *
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://opensource.org/licenses/ecl2
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sakaiproject.contentreview.dao;

import java.util.Date;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * This is a POJO (data storage object)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContentReviewItem {

	private Long id;
	private String contentId;
	private String userId;
	private String siteId;
	private String taskId;
	private String externalId;
	private Date dateQueued;
	private Date dateSubmitted;
	private Date dateReportReceived;
	private Date nextRetryTime;
	private Integer errorCode;
	private Integer providerId;
	private Long status;
	private Integer reviewScore;
	private String lastError;
	private Long retryCount;
	private Integer version;

	public ContentReviewItem(String contentId, Integer providerId) {
		this(contentId, null, null, null, new Date(), ContentReviewConstants.CONTENT_REVIEW_NOT_SUBMITTED_CODE, providerId);
	}

	public ContentReviewItem(String contentId, String userId, String siteId, String taskId, Date dateQueued, Long status, Integer providerId) {
		this.contentId = contentId;
		this.userId = userId;
		this.siteId = siteId;
		this.taskId = taskId;
		this.dateQueued = dateQueued;
		this.status = status;
		this.providerId = providerId;
		this.nextRetryTime = new Date();
	}
	
	// TIITODO: re-evaluate this and re-implement as required. It sounds like a Turnitin-specific implementation issue that
	// other providers may not like
	/**
	 * NB: If this is a new content review item, this value will be inserted; but any changes will not be updated. You must use ContentReviewService.updateExternalId(String contentId, String externalId) to persist changes.
	 * Rationale: Hibernate updates every attribute on the object; Turnitin's LTI integration uses asynchronous callbacks to update this attribute. So a race condition is common:
	 * 1) ProcessQueue job retrieves ContentReviewItem from the db
	 * 2) Submit to remote CRS
	 * 3) Asynchronous callback from remote CRS; sets externalId, persists this ContentReviewItem in the db
	 *     -Note this is a separate thread; working on a separate ContentReviewItem instance
	 * 4) ProcessQueue job continues, marking the original ContentReviewItem's status to indicate that the call to TII was successful
	 *     -*The externalID is still null on this instance, so it is overwritten, and our previous externalID is lost forever!*
	 * Solution: set the externalId property in the ContentReviewItem.hbm.xml to insert="true" update="false"
	 *     -the externalId will be inserted when first persisting the object, but ignored when updating the object unless we use an hql query
	 */
 	/*public void setExternalId(String externalId) {
 		this.externalId = externalId;
 	}*/
}