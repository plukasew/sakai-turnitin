package org.sakaiproject.contentreview.turnitin.dao;

import java.util.Date;
import java.util.Optional;
import org.hibernate.Criteria;
import org.hibernate.criterion.Restrictions;
import org.sakaiproject.contentreview.dao.HibernateCommonDao;
import org.sakaiproject.turnitin.api.TiiActivityConfig;

public class TiiActivityConfigDao extends HibernateCommonDao<TiiActivityConfig>
{
	private static final String TOOL_ID_COL = "toolId";
	private static final String ACTIVITY_ID_COL = "activityId";
	public Optional<TiiActivityConfig> findByToolIdActivityId(String toolId, String activityId)
	{
		Criteria c = sessionFactory.getCurrentSession().createCriteria(TiiActivityConfig.class)
				.add(Restrictions.eq(TOOL_ID_COL, toolId)).add(Restrictions.eq(ACTIVITY_ID_COL, activityId));

		return Optional.ofNullable((TiiActivityConfig) c.uniqueResult());
	}

	/**
	 * @param toolId the tool registration id to query on
	 * @param activityId the activityId to query on
	 * @param tiiAssignmentId the ID of the turnitin assignment (ie. from their end)
	 */
	public boolean updateTurnitinAssignmentId(String toolId, String activityId, String tiiAssignmentId)
	{
		Optional<TiiActivityConfig> optActivityConfig = findByToolIdActivityId(toolId, activityId);
		if (optActivityConfig.isPresent())
		{
			TiiActivityConfig activityConfig = optActivityConfig.get();
			activityConfig.setTurnitinAssignmentId(tiiAssignmentId);
			save(activityConfig);
			return true;
		}

		return false;
	}

	/**
	 * @param tooldId the tool registration id to query on
	 * @param activityId the activityId to query on
	 * @param latestIndividualExtensionDate the latest date from all student submissions that have individual extensions; pushes the turnitin close date so everyone's submission can get in
	 */
	public boolean updateLatestIndividualExtensionDate(String toolId, String activityId, Date latestIndividualExtensionDate)
	{
		Optional<TiiActivityConfig> optActivityConfig = findByToolIdActivityId(toolId, activityId);
		if (optActivityConfig.isPresent())
		{
			TiiActivityConfig activityConfig = optActivityConfig.get();
			activityConfig.setLatestIndividualExtensionDate(latestIndividualExtensionDate);
			save(activityConfig);
			return true;
		}

		return false;
	}
}
