package org.sakaiproject.contentreview.turnitin.dao;

import java.util.Optional;
import org.hibernate.Criteria;
import org.hibernate.criterion.Restrictions;
import org.sakaiproject.contentreview.dao.HibernateCommonDao;
import org.sakaiproject.contentreview.model.ContentReviewRosterSyncItem;

/**
 *
 * @author plukasew
 */
public class TiiContentReviewRosterSyncDao extends HibernateCommonDao<ContentReviewRosterSyncItem>
{
	protected static final String SITE_ID_COL = "siteId";
	protected static final String DATE_QUEUED_COL = "dateQueued";
	protected static final String LAST_TRIED_COL = "lastTried";
	protected static final String STATUS_COL = "status";
	protected static final String MESSAGES_COL = "messages";

	public Optional<ContentReviewRosterSyncItem> findByStatusAndSite(int status, String siteId)
	{
		// TIITODO: implement this (BLOCKED BY DECISION TO SUPPORT LEGACY API)
		Criteria c = sessionFactory.getCurrentSession().createCriteria(ContentReviewRosterSyncItem.class)
			.add(Restrictions.eq(STATUS_COL, status))
			.add(Restrictions.eq(SITE_ID_COL, siteId));
		
		return Optional.ofNullable((ContentReviewRosterSyncItem) c.uniqueResult());
	}
}
