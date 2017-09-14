package org.sakaiproject.contentreview.turnitin.dao;

import java.util.Optional;
import org.sakaiproject.contentreview.dao.HibernateCommonDao;
import org.sakaiproject.contentreview.model.ContentReviewRosterSyncItem;

/**
 *
 * @author plukasew
 */
public class TiiContentReviewRosterSyncDao extends HibernateCommonDao<ContentReviewRosterSyncItem>
{
	public Optional<ContentReviewRosterSyncItem> findByStatusAndSite(int status, String siteId)
	{
		// TIITODO: implement this (BLOCKED BY DECISION TO SUPPORT LEGACY API)
		
		return Optional.empty();
	}
}
