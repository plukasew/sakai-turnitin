package org.sakaiproject.contentreview.turnitin.dao;

import java.util.Calendar;
import java.util.Optional;
import org.hibernate.Criteria;
import org.hibernate.criterion.Restrictions;
import org.sakaiproject.contentreview.dao.ContentReviewConstants;
import org.sakaiproject.contentreview.dao.ContentReviewItem;
import org.sakaiproject.contentreview.dao.ContentReviewItemDao;

/**
 *
 * @author plukasew
 */
public class ExtendedContentReviewItemDao extends ContentReviewItemDao
{
	public Optional<ContentReviewItem> findSingleItemToSubmitMissingExternalId(Integer providerId)
	{
		Calendar calendar = Calendar.getInstance();
		
		Criteria c = sessionFactory.getCurrentSession()
				.createCriteria(ContentReviewItem.class)
				.add(Restrictions.eq(PROVIDER_ID_COL, providerId))
				.add(Restrictions.in(STATUS_COL, new Long[]{ContentReviewConstants.CONTENT_REVIEW_SUBMITTED_AWAITING_REPORT_CODE,
					ContentReviewConstants.CONTENT_REVIEW_REPORT_ERROR_RETRY_CODE}))
				.add(Restrictions.isNull(EXTERNAL_ID_COL))
				.add(Restrictions.lt(NEXT_RETRY_TIME_COL, calendar.getTime()))
				.setMaxResults(1);
		
		return Optional.ofNullable((ContentReviewItem) c.uniqueResult());
	}
}
