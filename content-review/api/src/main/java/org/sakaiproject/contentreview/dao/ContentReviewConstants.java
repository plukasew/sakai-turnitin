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

public class ContentReviewConstants
{
	public enum ReviewStatus
	{
		UNKNOWN(0, "Unknown status"),
		NOT_SUBMITTED(1, "Content awaiting submission"),
		SUBMITTED_AWAITING_REPORT(2, "Content submitted for review and awaiting report"),
		SUBMITTED_REPORT_AVAILABLE(3, "Content submitted and report available"),
		SUBMISSION_ERROR_RETRY(4, "Temporary error occurred submitting content - will retry"),
		SUBMISSION_ERROR_NO_RETRY(5, "Error occurred submitting content - will not retry"),
		SUBMISSION_ERROR_USER_DETAILS(6, "Error occurred submitting content - incomplete or invalid user details"),
		REPORT_ERROR_RETRY(7, "Temporary error occurred retrieving report - will retry"),
		REPORT_ERROR_NO_RETRY(8, "Error occurred retrieving report - will not retry"),
		SUBMISSION_ERROR_RETRY_EXCEEDED(9, "Error number of retries exceeded"),
		SUBMITTED_REPORT_ON_DUE_DATE(10, "Reports not available until due date");
		
		public final int code;
		public final String description;
		
		ReviewStatus(int c, String d)
		{
			code = c;
			description = d;
		}
		
		public static ReviewStatus fromInt(Integer i)
		{
			if (i == null)
			{
				return UNKNOWN;
			}
			
			switch (i)
			{
				case 1: return NOT_SUBMITTED;
				case 2: return SUBMITTED_AWAITING_REPORT;
				case 3: return SUBMITTED_REPORT_AVAILABLE;
				case 4: return SUBMISSION_ERROR_RETRY;
				case 5: return SUBMISSION_ERROR_NO_RETRY;
				case 6: return SUBMISSION_ERROR_USER_DETAILS;
				case 7: return REPORT_ERROR_RETRY;
				case 8: return REPORT_ERROR_NO_RETRY;
				case 9: return SUBMISSION_ERROR_RETRY_EXCEEDED;
				case 10: return SUBMITTED_REPORT_ON_DUE_DATE;
				default: return UNKNOWN;
			}
		}
		
		public static ReviewStatus fromItemStatus(Long status)
		{
			if (status == null)
			{
				return UNKNOWN;
			}
			
			return fromInt(status.intValue());
		}
	}
	
	
	
	// TIITODO: the prefix "CONTENT_REVIEW" is redundant and should be removed from all these variable names once this all builds.
	// Additionally, the messages are not localized so they should not be used outside of the database/log messages.
	// Check all usages and confirm they are not used inappropriately where localized text should be used instead.
	// Actually just use the enum above?
	
	public static final String CONTENT_REVIEW_NOT_SUBMITTED = "Content awaiting submission";
	public static final Long CONTENT_REVIEW_NOT_SUBMITTED_CODE = new Long(1);

	public static final String CONTENT_REVIEW_SUBMITTED_AWAITING_REPORT = "Content submitted for review and awaiting report";
	public static final Long CONTENT_REVIEW_SUBMITTED_AWAITING_REPORT_CODE = new Long(2);

	public static final String CONTENT_REVIEW_SUBMITTED_REPORT_AVAILABLE = "Content submitted and report available";
	public static final Long CONTENT_REVIEW_SUBMITTED_REPORT_AVAILABLE_CODE = new Long(3);

	public static final String CONTENT_REVIEW_SUBMISSION_ERROR_RETRY = "Temporary error occurred submitting content - will retry";
	public static final Long CONTENT_REVIEW_SUBMISSION_ERROR_RETRY_CODE = new Long(4);

	public static final String CONTENT_REVIEW_SUBMISSION_ERROR_NO_RETRY = "Error occurred submitting content - will not retry";
	public static final Long CONTENT_REVIEW_SUBMISSION_ERROR_NO_RETRY_CODE = new Long(5);

	public static final String CONTENT_REVIEW_SUBMISSION_ERROR_USER_DETAILS = "Error occurred submitting content - inconplete or Ivalid user details";
	public static final Long CONTENT_REVIEW_SUBMISSION_ERROR_USER_DETAILS_CODE = new Long(6);

	public static final String CONTENT_REVIEW_REPORT_ERROR_RETRY = "Temporary error occurred retrieving report - will retry";
	public static final Long CONTENT_REVIEW_REPORT_ERROR_RETRY_CODE = new Long(7);

	public static final String CONTENT_REVIEW_REPORT_ERROR_NO_RETRY = "Error occurred retrieving report - will not retry";
	public static final Long CONTENT_REVIEW_REPORT_ERROR_NO_RETRY_CODE = new Long(8);

	public static final String CONTENT_REVIEW_SUBMISSION_ERROR_RETRY_EXCEEDED = "Error number of retries exceeded";
	public static final Long CONTENT_REVIEW_SUBMISSION_ERROR_RETRY_EXCEEDED_CODE = new Long(9);
	
	// TIITODO: does this belong here or is it something that only affects Turnitin?
	public static final String SUBMITTED_REPORT_ON_DUE_DATE = "Reports not available until due date";
	public static final Long SUBMITTED_REPORT_ON_DUE_DATE_CODE = new Long(10);
	
	public static final String MSG_REPORT_NOT_AVAILABLE = "Report not available for contentreview_item <%d>, status = %d";
}
