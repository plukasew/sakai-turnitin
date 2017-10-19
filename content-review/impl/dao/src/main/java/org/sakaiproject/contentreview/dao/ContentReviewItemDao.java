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

import java.util.Calendar;
import java.util.List;
import java.util.Optional;

import org.hibernate.Criteria;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;

public class ContentReviewItemDao extends HibernateCommonDao<ContentReviewItem> {
	
	protected static final String PROVIDER_ID_COL = "providerId";
	protected static final String CONTENT_ID_COL = "contentId";
	protected static final String USER_ID_COL = "userId";
	protected static final String SITE_ID_COL = "siteId";
	protected static final String TASK_ID_COL = "taskId";
	protected static final String EXTERNAL_ID_COL = "externalId";
	protected static final String STATUS_COL = "status";
	protected static final String ERROR_CODE_COL = "errorCode";
	protected static final String NEXT_RETRY_TIME_COL = "nextRetryTime";
	
	@SuppressWarnings("unchecked")
	public List<ContentReviewItem> findBySearchParameters(SearchParameters params)
	{
		Criteria c = sessionFactory.getCurrentSession()
				.createCriteria(ContentReviewItem.class)
				.add(Restrictions.eq(PROVIDER_ID_COL, params.providerId));

		if (params.contentId != null) c.add(Restrictions.eq(CONTENT_ID_COL, params.contentId));
		if (params.userId != null) c.add(Restrictions.eq(USER_ID_COL, params.userId));
		if (params.siteId != null) c.add(Restrictions.eq(SITE_ID_COL, params.siteId));
		if (params.taskId != null) c.add(Restrictions.eq(TASK_ID_COL, params.taskId));
		if (params.externalId != null) c.add(Restrictions.eq(EXTERNAL_ID_COL, params.externalId));
		if (params.status != null) c.add(Restrictions.eq(STATUS_COL, params.status));
		if (params.errorCode != null) c.add(Restrictions.eq(ERROR_CODE_COL, params.errorCode));

		return c.list();
	}
	
	@SuppressWarnings("unchecked")
	public List<ContentReviewItem> findByProviderGroupedBySiteAndTask(Integer providerId) {

		Criteria c = sessionFactory.getCurrentSession()
				.createCriteria(ContentReviewItem.class)
				.add(Restrictions.eq(PROVIDER_ID_COL, providerId))
				.setProjection( Projections.projectionList()
						.add(Projections.groupProperty(SITE_ID_COL))
						.add(Projections.groupProperty(TASK_ID_COL)));

		return c.list();
	}
	
	@SuppressWarnings("unchecked")
	public List<ContentReviewItem> findByProviderAwaitingReports(Integer providerId) {

		Criteria c = sessionFactory.getCurrentSession()
				.createCriteria(ContentReviewItem.class)
				.add(Restrictions.eq(PROVIDER_ID_COL, providerId))
				.add(Restrictions.in(STATUS_COL, new Long[]{ContentReviewConstants.CONTENT_REVIEW_SUBMITTED_AWAITING_REPORT_CODE,
					ContentReviewConstants.CONTENT_REVIEW_REPORT_ERROR_RETRY_CODE}));
		
		return c.list();
	}
	
	public Optional<ContentReviewItem> findByProviderAndContentId(Integer providerId, String contentId) {

		Criteria c = sessionFactory.getCurrentSession()
				.createCriteria(ContentReviewItem.class)
				.add(Restrictions.eq(PROVIDER_ID_COL, providerId))
				.add(Restrictions.eq(CONTENT_ID_COL, contentId));
		
		return Optional.ofNullable((ContentReviewItem) c.uniqueResult());
	}

	public Optional<ContentReviewItem> findByProviderSingleItemToSubmit(Integer providerId) {

		Calendar calendar = Calendar.getInstance();
		
		Criteria c = sessionFactory.getCurrentSession()
				.createCriteria(ContentReviewItem.class)
				.add(Restrictions.eq(PROVIDER_ID_COL, providerId))
				.add(Restrictions.in(STATUS_COL, new Long[]{ContentReviewConstants.CONTENT_REVIEW_NOT_SUBMITTED_CODE,
					ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_RETRY_CODE,
					ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_USER_DETAILS_CODE}))
				.add(Restrictions.lt(NEXT_RETRY_TIME_COL, calendar.getTime()))
				.setMaxResults(1);
		
		return Optional.ofNullable((ContentReviewItem) c.uniqueResult());
	}

	public static class SearchParameters
	{
		public Integer providerId = null;
		public String contentId = null;
		public String userId = null;
		public String siteId = null;
		public String taskId = null;
		public String externalId = null;
		public Long status = null;
		public Integer errorCode = null;
	}
}
