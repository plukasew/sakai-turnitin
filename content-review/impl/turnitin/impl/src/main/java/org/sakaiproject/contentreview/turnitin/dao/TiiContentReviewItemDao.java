package org.sakaiproject.contentreview.turnitin.dao;

import java.util.Optional;
import org.hibernate.Criteria;
import org.hibernate.criterion.Restrictions;
import org.sakaiproject.contentreview.dao.HibernateCommonDao;
import org.sakaiproject.turnitin.api.TiiContentReviewItem;


public class TiiContentReviewItemDao extends HibernateCommonDao<TiiContentReviewItem>
{	
	private static final String CONTENT_ID_COL = "contentId";
	
	public Optional<TiiContentReviewItem> findByContentId(String contentId)
	{
		Criteria c = sessionFactory.getCurrentSession().createCriteria(TiiContentReviewItem.class)
				.add(Restrictions.eq(CONTENT_ID_COL, contentId));
		
		return Optional.ofNullable((TiiContentReviewItem) c.uniqueResult());
	}
	
	
	   /**
	    * Updates the 'isUrlAccessed' field of the content review item with the specified boolean value
	    * @param contentID the content ID of the content review item to be updated
	    * @param isUrlAccessed the boolean value to be updated for the given content review item
	    * @return whether the update was successful
	    */
	   public boolean updateIsUrlAccessed(String contentID, boolean isUrlAccessed)
	   {
		   Optional<TiiContentReviewItem> itemOpt = findByContentId(contentID);
		   if (itemOpt.isPresent())
		   {
			   TiiContentReviewItem item = itemOpt.get();
			   item.setUrlAccessed(isUrlAccessed);
			   save(item);
			   return true;
		   }
		   
		   return false;
	   }
}
