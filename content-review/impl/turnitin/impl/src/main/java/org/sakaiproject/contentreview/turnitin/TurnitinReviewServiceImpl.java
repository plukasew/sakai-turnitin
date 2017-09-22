/**********************************************************************************
 * $URL$
 * $Id$
 ***********************************************************************************
 *
 * Copyright (c) 2006 Sakai Foundation
 *
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.osedu.org/licenses/ECL-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 **********************************************************************************/
package org.sakaiproject.contentreview.turnitin;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.net.URLDecoder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.validator.EmailValidator;
import org.tsugi.basiclti.BasicLTIConstants;
import org.sakaiproject.api.common.edu.person.SakaiPerson;
import org.sakaiproject.api.common.edu.person.SakaiPersonManager;
// import org.sakaiproject.assignment.api.AssignmentContent; (removed)
import org.sakaiproject.assignment.api.AssignmentService;
import org.sakaiproject.assignment.api.model.AssignmentSubmission;
import org.sakaiproject.authz.api.SecurityAdvisor;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.component.cover.ComponentManager;
import org.sakaiproject.content.api.ContentHostingService;
import org.sakaiproject.content.api.ContentResource;
//import org.sakaiproject.contentreview.dao.impl.ContentReviewDao; Use TiiContentReviewItemDao and TiiContentReviewRosterSyncDao
import org.sakaiproject.contentreview.exception.QueueException;
import org.sakaiproject.contentreview.exception.ReportException;
import org.sakaiproject.contentreview.exception.SubmissionException;
import org.sakaiproject.contentreview.exception.TransientSubmissionException;
//import org.sakaiproject.contentreview.impl.hbm.TiiBaseReviewServiceImpl;
import org.sakaiproject.contentreview.turnitin.TiiBaseReviewServiceImpl;
//import org.sakaiproject.contentreview.model.ContentReviewItem;
import org.sakaiproject.contentreview.dao.ContentReviewItem;
import org.sakaiproject.db.api.SqlReader;
import org.sakaiproject.db.api.SqlService;
import org.sakaiproject.entity.api.Entity;
import org.sakaiproject.entity.api.EntityManager;
import org.sakaiproject.entity.api.EntityProducer;
import org.sakaiproject.entity.api.Reference;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.entity.api.ResourcePropertiesEdit;
import org.sakaiproject.entitybroker.EntityReference;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.exception.TypeException;
import org.sakaiproject.genericdao.api.search.Restriction;
import org.sakaiproject.genericdao.api.search.Search;
import org.sakaiproject.lti.api.LTIService;
import org.sakaiproject.service.gradebook.shared.AssessmentNotFoundException;
import org.sakaiproject.service.gradebook.shared.GradebookExternalAssessmentService;
import org.sakaiproject.service.gradebook.shared.GradebookService;
import org.sakaiproject.service.gradebook.shared.GradebookNotFoundException;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.service.gradebook.shared.Assignment;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;
//import org.sakaiproject.turnitin.util.TurnitinAPIUtil;
//import org.sakaiproject.turnitin.util.TurnitinLTIUtil;
//import org.sakaiproject.turnitin.util.TurnitinReturnValue;
import org.sakaiproject.contentreview.turnitin.util.TurnitinAPIUtil;
import org.sakaiproject.contentreview.turnitin.util.TurnitinLTIUtil;
import org.sakaiproject.contentreview.turnitin.util.TurnitinReturnValue;
import org.sakaiproject.user.api.PreferencesService;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserDirectoryService;
import org.sakaiproject.user.api.UserNotDefinedException;
import org.sakaiproject.util.ResourceLoader;
import org.w3c.dom.CharacterData;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import lombok.extern.slf4j.Slf4j;

import org.sakaiproject.turnitin.api.TiiActivityConfig;

@Slf4j
// TIITODO: make an interface that exposes the TII-specific methods that need to be called from outside the TII impl
// If we design this right, it is possible this may not be required to be put in the public ContentReview API at all
public class TurnitinReviewServiceImpl extends TiiBaseReviewServiceImpl
{

	final static long LOCK_PERIOD = 12000000;

	/**
	 *  Setters
	 */
	private GradebookService gradebookService = (GradebookService)
			ComponentManager.get("org.sakaiproject.service.gradebook.GradebookService");
	private GradebookExternalAssessmentService gradebookExternalAssessmentService =
			(GradebookExternalAssessmentService)ComponentManager.get("org.sakaiproject.service.gradebook.GradebookExternalAssessmentService");

	/**
	 * Place any code that should run when this class is initialized by spring
	 * here
	 */
	@Override
	public void init() {
		
		// TIITODO: remove this method since the base class has it already?

		if (siteAdvisor != null) {
			log.info("Using siteAdvisor: " + siteAdvisor.getClass().getName());
		}
	}
	
	// TIITODO ensure this is invoked when an assignment is deleted
	// TIITODO: consider if a general cleanup method in the CRS API is appropriate. This deletion work could be done there if so.
	public boolean deleteLTITool(String taskId, String contentId) {
		SecurityAdvisor advisor = new SimpleSecurityAdvisor(sessionManager.getCurrentSessionUserId(), "site.upd", "/site/!admin");

		// TIITODO: strategy to determine which tool registration id to use?
		Optional<TiiActivityConfig> activityConfig = getActivityConfig("sakai.assignment.grades", taskId);
		if (activityConfig.isPresent())
		{
			String ltiId = activityConfig.get().getStealthedLtiId();
			securityService.pushAdvisor(advisor);
			try
			{
				return tiiUtil.deleteTIIToolContent(ltiId);
			}
			catch (Exception e)
			{
				log.error("Unexpected exception deleting TII tool", e);
			}
			finally
			{
				securityService.popAdvisor(advisor);
			}
		}
		return false;
 	}
	
	// TIITODO: externalId is a part of the ContentReviewItem already, should this method just be added to CRS API, 
	// even though Turnitin is the only implementation that updates it?
	public boolean updateExternalId(String contentId, String externalId)
	{
		dao.findByProviderAndContentId(getProviderId(), contentId)
				.ifPresent(item ->
				{ 
					item.setExternalId(externalId);
					crqServ.update(item);
				});
		// TIITODO: this is supposed to return success/failure depending on if the external id was updated or not
		return true;
	}
		
	public boolean updateExternalGrade(String contentId, String score)
	{
		// TIITODO: does externalGrade belong in ContentReviewItem or TiiContentReviewItem?
		// Decide, and then implement as appropriate in the proper API
		/*ContentReviewItem cri = getFirstItemByContentId(contentId);
		if(cri != null){
			cri.setExternalGrade(score);
			dao.update(cri);
			return true;
		}*/
		return false;
	}
	
	public Optional<String> getExternalGradeForContentId(String contentId)
	{
		// TIITODO: see comment in method above and implement as appropriate
		/*ContentReviewItem cri = getFirstItemByContentId(contentId);
		if(cri != null){
			return cri.getExternalGrade();
		}
		return null;*/
		return Optional.empty();
	}
	
	public boolean isDirectAccess(Site s) {
		if (s == null) {
			return false;
		}

		log.debug("isDirectAccess: " + s.getId() + " / " + s.getTitle());
		// Delegated to another bean
		
		return siteAdvisor != null && siteAdvisor.siteCanUseReviewService(s) && siteAdvisor.siteCanUseLTIReviewService(s) && siteAdvisor.siteCanUseLTIDirectSubmission(s);
	}
	
	public boolean isDirectAccess(Site s, Date assignmentCreationDate)
	{
		if (s == null || siteAdvisor == null)
		{
			return false;
		}
		
		return siteAdvisor.siteCanUseReviewService(s) && siteAdvisor.siteCanUseLTIReviewServiceForAssignment(s, assignmentCreationDate)
				&& siteAdvisor.siteCanUseLTIDirectSubmission(s);
	}

	public boolean allowMultipleAttachments()
	{
		return serverConfigurationService.getBoolean("turnitin.allow.multiple.attachments", false);
	}

    /**
    * Get additional data from String if available
    * @return array containing site ID, Task ID, Task Title
    */
    private String[] getAssignData(String data){
        String[] assignData = null;
        try{
            if(data.contains("#")){
                assignData = data.split("#");
            }
        }catch(Exception e){
        }
        return assignData;
    }

    public String getInlineTextId(String assignmentReference, String userId, long submissionTime){
	return "";
    } 	

    public boolean acceptInlineAndMultipleAttachments(){
	return false;
    }

	/**
	 * Check if grade sync has been run already for the specified site
	 * @param sess Current Session
	 * @param taskId
	 * @return
	 */
	public boolean gradesChecked(Session sess, String taskId){
		String sessSync;
		try{
			sessSync = sess.getAttribute("sync").toString();
			if(sessSync.equals(taskId)){
				return true;
			}
		}catch(Exception e){
			//log.error("(gradesChecked)"+e);
		}
		return false;
	}

        /**
    * Check if the specified user has the student role on the specified site.
    * @param siteId Site ID
    * @param userId User ID
    * @return true if user has student role on the site.
    */
	public boolean isUserStudent(String  siteId, String userId){
		boolean isStudent=false;
		try{
					Set<String> studentIds = siteService.getSite(siteId).getUsersIsAllowed("section.role.student");
					List<User> activeUsers = userDirectoryService.getUsers(studentIds);
					for (int i = 0; i < activeUsers.size(); i++) {
						User user = activeUsers.get(i);
						if(userId.equals(user.getId())){
							return true;
						}
					}
			}catch(Exception e){
				log.info("(isStudentUser)"+e);
			}
		return isStudent;
	}

        /**
    * Return the Gradebook item associated with an assignment.
    * @param data Map containing Site/Assignment IDs
    * @return Associated gradebook item
    */
        public Assignment getAssociatedGbItem(Map data){
            Assignment assignment = null;
            String taskId = data.get("taskId").toString();
            String siteId = data.get("siteId").toString();
            String taskTitle = data.get("taskTitle").toString();

            pushAdvisor();
            try {
                List<Assignment> allGbItems = gradebookService.getAssignments(siteId);
                for (Assignment assign : allGbItems) {
                        //Match based on External ID / Assignment title
                        if(taskId.equals(assign.getExternalId()) || assign.getName().equals(taskTitle) ){
                            assignment = assign;
                            break;
                        }
                }
            } catch (Exception e) {
                    log.error("(allGbItems)"+e.toString());
            } finally{
                popAdvisor();
            }
            return assignment;
        }

        /**
    * Check Turnitin for grades and write them to the associated gradebook
    * @param data Map containing relevant IDs (site ID, Assignment ID, Title)
    */
	public void syncGrades(Map<String,Object>data)
	{
		//Get session and check if gardes have already been synced
		Session sess = sessionManager.getCurrentSession();
		boolean runOnce=gradesChecked(sess, data.get("taskId").toString());
		boolean isStudent = isUserStudent(data.get("siteId").toString(), sess.getUserId());

		if(turnitinConn.getUseGradeMark() && runOnce == false && isStudent == false){
			log.info("Syncing Grades with Turnitin");

			String siteId = data.get("siteId").toString();
			String taskId = data.get("taskId").toString();

			HashMap<String, Integer> reportTable = new HashMap<>();
			HashMap<String, String> additionalData = new HashMap<>();
			String tiiUserId;

			String assign = taskId;
			if(data.containsKey("assignment1")){
				//Assignments 1 uses the actual title whereas Assignments 2 uses the ID
				assign = getAssignmentTitle(taskId);
			}

			//Run once
			sess.setAttribute("sync", taskId);

			//Get students enrolled on class in Turnitin
			Map<String,Object> enrollmentInfo = getAllEnrollmentInfo(siteId);

			//Get Associated GB item
			Assignment assignment = getAssociatedGbItem(data);

			//List submissions call
			Map params = TurnitinAPIUtil.packMap(turnitinConn.getBaseTIIOptions(),
						"fid", "10",
						"fcmd", "2",
						"tem", getTEM(siteId),
						"assign", assign,
						"assignid", taskId,
						"cid", siteId,
						"ctl", siteId,
						"utp", "2"
			);
			params.putAll(getInstructorInfo(siteId));

			Document document = null;
			try {
					document = turnitinConn.callTurnitinReturnDocument(params);
			}catch (TransientSubmissionException e) {
				log.error(e.getMessage(), e);
			}catch (SubmissionException e) {
				log.warn("SubmissionException error. "+e);
			}
			Element root = document.getDocumentElement();
			if (((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData().trim().compareTo("72") == 0) {
					NodeList objects = root.getElementsByTagName("object");
					String grade;
					log.debug(objects.getLength() + " objects in the returned list");

					for (int i=0; i<objects.getLength(); i++) {
							tiiUserId = ((CharacterData) (((Element)(objects.item(i))).getElementsByTagName("userid").item(0).getFirstChild())).getData().trim();
							additionalData.put("tiiUserId",tiiUserId);
							//Get GradeMark Grade
							try{
								grade = ((CharacterData) (((Element)(objects.item(i))).getElementsByTagName("score").item(0).getFirstChild())).getData().trim();
								reportTable.put("grade"+tiiUserId, Integer.valueOf(grade));
							} catch(DOMException | NumberFormatException e){
								//No score returned
								grade="";
							} catch(Exception e) {
								grade="";
								log.error( "Unexpected exception getting grade", e );
							}

							if(!grade.equals("")){
								//Update Grade ----------------
								if(gradebookService.isGradebookDefined(siteId)){
									writeGrade(assignment,data,reportTable,additionalData,enrollmentInfo);
								}
							}
					}
			} else {
					log.debug("Report list request not successful");
					log.debug(document.getTextContent());
			}
		}
	}

    /**
    * Check if a grade returned from Turnitin is greater than the max points for
    * an assignment. If so then set to max points.
    * (Grade is unchanged in Turnitin)
    * @param grade Grade returned from Turnitin
    * @param assignment
    * @return
    */
    public String processGrade(String grade,Assignment assignment){
        String processedGrade="";
        try{
            int gradeVal = Integer.parseInt(grade);
            if(gradeVal > assignment.getPoints()){
                processedGrade = Double.toString(assignment.getPoints());
                log.info("Grade exceeds maximum point value for this assignment("
                        +assignment.getName()+") Setting to Max Points value");
            }else{
                processedGrade = grade;
            }
        }catch(NumberFormatException e){
            log.warn("Error parsing grade");
        }catch(Exception e){
            log.warn("Error processing grade");
        }
        return processedGrade;
    }


    /**
     * Write a grade to the gradebook for the current specified user
     * @param assignment
     * @param data
     * @param reportTable
     * @param additionalData
     * @param enrollmentInfo
     * @return
     */
    public boolean writeGrade(Assignment assignment, Map<String,Object> data, HashMap reportTable,HashMap additionalData,Map enrollmentInfo){
            boolean success = false;
            String grade;
            String siteId = data.get("siteId").toString();
            String currentStudentUserId = additionalData.get("tiiUserId").toString();
            String tiiExternalId ="";

            if(!enrollmentInfo.isEmpty()){
                if(enrollmentInfo.containsKey(currentStudentUserId)){
                    tiiExternalId = enrollmentInfo.get(currentStudentUserId).toString();
                    log.info("tiiExternalId: "+tiiExternalId);
                }
            }else{
                return false;
            }

            //Check if the returned grade is greater than the maximum possible grade
            //If so then set to the maximum grade
            grade = processGrade(reportTable.get("grade"+currentStudentUserId).toString(),assignment);

            pushAdvisor();
            try {
                        if(grade!=null){
                                try{
                                    if(data.containsKey("assignment1")){
                                        gradebookExternalAssessmentService.updateExternalAssessmentScore(siteId, assignment.getExternalId(),tiiExternalId,grade);
                                    }else{
                                        gradebookService.setAssignmentScoreString(siteId, data.get("taskTitle").toString(), tiiExternalId, grade, "SYNC");
                                    }
                                    log.info("UPDATED GRADE ("+grade+") FOR USER ("+tiiExternalId+") IN ASSIGNMENT ("+assignment.getName()+")");
                                    success = true;
                                }catch(GradebookNotFoundException e){
                                    log.error("Error update grade GradebookNotFoundException "+e.toString());
                                }catch(AssessmentNotFoundException e){
                                    log.error("Error update grade "+e.toString());
                                }catch(Exception e){
                                    log.error("Unexpected exception updating grade", e);
                                }
                        }
            } catch (Exception e) {
                log.error("Error setting grade "+e.toString());
            } finally {
                    popAdvisor();
            }
            return success;
        }

        /**
   * Get a list of students enrolled on a class in Turnitin
   * @param siteId Site ID
   * @return Map containing Students turnitin / Sakai ID
   */
	public Map getAllEnrollmentInfo(String siteId){
			Map<String,String> enrollmentInfo=new HashMap();
			String tiiExternalId;//the ID sakai stores
			String tiiInternalId;//Turnitin internal ID
			User user = null;
			Map instructorInfo = getInstructorInfo(siteId,true);
			try{
				user = userDirectoryService.getUser(instructorInfo.get("uid").toString());
			}catch(UserNotDefinedException e){
				log.error("(getAllEnrollmentInfo)User not defined. "+e);
			}
			Map params = TurnitinAPIUtil.packMap(turnitinConn.getBaseTIIOptions(),
							"fid", "19",
							"fcmd", "5",
							"tem", getTEM(siteId),
							"ctl", siteId,
							"cid", siteId,
							"utp", "2",
							"uid", user.getId(),
							"uem",getEmail(user),
							"ufn",user.getFirstName(),
							"uln",user.getLastName()
			);
			Document document = null;
			try {
					document = turnitinConn.callTurnitinReturnDocument(params);
			}catch (TransientSubmissionException | SubmissionException e) {
					log.warn("Failed to get enrollment data using user: "+user.getDisplayName(), e);
			}catch (Exception e) {
					log.error( "Unexpected exception getting document", e );
			}

			Element root = document.getDocumentElement();
			if (((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData().trim().compareTo("93") == 0) {
					NodeList objects = root.getElementsByTagName("student");
					for (int i=0; i<objects.getLength(); i++) {
							tiiExternalId = ((CharacterData) (((Element)(objects.item(i))).getElementsByTagName("uid").item(0).getFirstChild())).getData().trim();
							tiiInternalId = ((CharacterData) (((Element)(objects.item(i))).getElementsByTagName("userid").item(0).getFirstChild())).getData().trim();
							enrollmentInfo.put(tiiInternalId, tiiExternalId);
					}
			}
			return enrollmentInfo;
	}

	 public void pushAdvisor() {
			securityService.pushAdvisor(new SecurityAdvisor() {

					public SecurityAdvisor.SecurityAdvice isAllowed(String userId, String function,
							String reference) {
							return SecurityAdvisor.SecurityAdvice.ALLOWED;
					}
			});
	}
	public void popAdvisor() {
			securityService.popAdvisor();
	}


	/**
	 * @param siteId
	 * @param taskId
	 * @throws SubmissionException
	 * @throws TransientSubmissionException
	 */
	public void createAssignment(String siteId, String taskId) throws SubmissionException, TransientSubmissionException {
		createAssignment(siteId, taskId, null);
	}

	/**
	 * @inheritDoc
	 */
	public void offerIndividualExtension(String siteId, String asnId, Map<String, Object> extraAsnOpts, Date extensionDate) throws SubmissionException, TransientSubmissionException
	{
		syncAssignment(siteId, asnId, extraAsnOpts, extensionDate);
	}
	
	// TIITODO: 13.x doesn't seem to use this explicit locking methods. Can we remove them?

	/*
	 * Obtain a lock on the item
	 */
	private boolean obtainLock(String itemId) {
		/*Boolean lock = dao.obtainLock(itemId, serverConfigurationService.getServerId(), LOCK_PERIOD);
		return (lock != null) ? lock : false;*/
		return true;
	}

	private void releaseLock(ContentReviewItem currentItem) {
		/*dao.releaseLock("item." + currentItem.getId().toString(), serverConfigurationService.getServerId());*/
	}

	
	

	@SuppressWarnings("unchecked")
	public Map getInstructorInfo(String siteId, boolean ignoreUseSource) {
		Map togo = new HashMap();
		if (!turnitinConn.isUseSourceParameter() && ignoreUseSource == false ) {
			togo.put("uem", turnitinConn.getDefaultInstructorEmail());
			togo.put("ufn", turnitinConn.getDefaultInstructorFName());
			togo.put("uln", turnitinConn.getDefaultInstructorLName());
			togo.put("uid", turnitinConn.getDefaultInstructorId());
		}
		else {
			String INST_ROLE = "section.role.instructor";
			User inst = null;
			try {
				Site site = siteService.getSite(siteId);
				User user = userDirectoryService.getCurrentUser();
				if (site.isAllowed(user.getId(), INST_ROLE)) {
					inst = user;
				}
				else {
					Set<String> instIds = getActiveInstructorIds(INST_ROLE,
							site);
					if (instIds.size() > 0) {
						inst = userDirectoryService.getUser((String) instIds.toArray()[0]);
					}
				}
			} catch (IdUnusedException e) {
				log.error("Unable to fetch site in getAbsoluteInstructorInfo: " + siteId, e);
			} catch (UserNotDefinedException e) {
				log.error("Unable to fetch user in getAbsoluteInstructorInfo", e);
			}


			if (inst == null) {
				log.error("Instructor is null in getAbsoluteInstructorInfo");
			}
			else {
				togo.put("uem", getEmail(inst));
				togo.put("ufn", inst.getFirstName());
				togo.put("uln", inst.getLastName());
				togo.put("uid", inst.getId());
				togo.put("username", inst.getDisplayName());
			}
		}

		return togo;
	}

	// TIITODO: can we remove these?
	/*public String getLegacyReviewReportStudent(String contentId) throws QueueException, ReportException{
		return getReviewReportStudent(contentId);
	}
	
	public String getLegacyReviewReportInstructor(String contentId) throws QueueException, ReportException{
		return getReviewReportStudent(contentId);
	}*/
	
	// TIITODO: this method should probably just be removed at this point
	/**
	 * Migrates the original LTI XML settings from the assignments table into the new activity config table.
	 * Also moves the external value from the assignment submission/content resource binary entity back into the contentreviewitem table.
	 * You need to run this ONLY if you have previously deployed the LTI integration prior to the introduction of TII-219 and TII-221.
	 */
	/*@Override
	public void migrateLtiXml()
	{
		// 1. find all the assignments that have the "turnitin_id" and/or "lti_id" values in their content XML
		// For each assignment, insert a row for the turnitin/lti id values
		// Use LTI service to find all the assignments that have Turnitin LTI instances
		Set<String> tiiSites = tiiUtil.getSitesUsingLTI();
		for (String siteId : tiiSites)
		{
			Iterator iter = assignmentService.getAssignmentsForContext(siteId);
			while(iter.hasNext())
			{
				org.sakaiproject.assignment.api.Assignment asn = (org.sakaiproject.assignment.api.Assignment) iter.next();
				AssignmentContent asnContent = asn.getContent();
				if (asnContent == null)
				{
					log.error("No content for assignment: " + asn.getId());
					continue;
				}
				ResourceProperties asnProps = asnContent.getProperties();
				if (asnProps == null)
				{
					log.error("No properties for assignment: " + asn.getId());
					continue;
				}
				String turnitinId = (String) asnProps.get("turnitin_id");
				String ltiId = (String) asnProps.get("lti_id");
				if (StringUtils.isNotBlank(turnitinId))
				{
					// update cfg table
					log.info(String.format("Add tii id %s for asn %s", turnitinId, asn.getId()));
					boolean success = saveOrUpdateActivityConfigEntry(TurnitinConstants.TURNITIN_ASN_ID, turnitinId, asn.getId(),
					TurnitinConstants.SAKAI_ASSIGNMENT_TOOL_ID, TurnitinConstants.PROVIDER_ID, false);
					if (!success)
					{
						log.error(String.format("Unable to migrate turnitinId %s for assignment %s to the activity_cfg table. An entry for this assignment may already exist.", turnitinId, asn.getId()));
					}
				}
				if (StringUtils.isNotBlank(ltiId))
				{
					//update cfg table
					log.info(String.format("Add lti id %s for asn %s", ltiId, asn.getId()));
					boolean success = saveOrUpdateActivityConfigEntry(TurnitinConstants.STEALTHED_LTI_ID, ltiId, asn.getId(),
					TurnitinConstants.SAKAI_ASSIGNMENT_TOOL_ID, TurnitinConstants.PROVIDER_ID, false);
					if (!success)
					{
						log.error(String.format("Unable to migrate ltiId %s for assignment %s to the activity_cfg table. An entry for this assignment may already exist.", ltiId, asn.getId()));
					}
				}
				
				// 2. for each contentreviewitem related to this assignment with a null external id, retrieve the 
				// assignment submission and perhaps the binary entity for the resource item.
				// If the submission/entity has a turnitin_id value, insert it into the contentreviewitem table
				Search search = new Search();
				search.addRestriction(new Restriction("siteId", siteId));
				search.addRestriction(new Restriction("taskId", asn.getReference()));
				List<ContentReviewItem> ltiContentItems = dao.findBySearch(ContentReviewItem.class, search);
				for (ContentReviewItem item : ltiContentItems)
				{
					if (StringUtils.isNotBlank(item.getExternalId()))
					{
						continue;
					}
					
					try
					{
						String tiiPaperId;
						AssignmentSubmission as = assignmentService.getSubmission(item.getSubmissionId());						
						ResourceProperties aProperties = as.getProperties();
						tiiPaperId = aProperties.getProperty("turnitin_id");
						if (StringUtils.isBlank(tiiPaperId)) // not found in submission, check content item
						{
							ContentResource content = contentHostingService.getResource(item.getContentId());
							ResourceProperties cProperties = content.getProperties();
							tiiPaperId = cProperties.getProperty("turnitin_id");
						}
						
						if (StringUtils.isNotBlank(tiiPaperId))
						{
							log.info("Will write " + tiiPaperId + " as external id for item " + item.getId());
							dao.updateExternalId(item.getContentId(), tiiPaperId);
						}
					}
					catch (Exception e)
					{
						log.error("Exception attempting to read/write paperId/externalId", e);
					}
				}
				
			}
			
		}
	}*/

	public boolean validateActivityConfiguration(String toolId, String activityId)
	{
		// if new integration, check for the turnitin assignment id and the stealthed lti id
		boolean useLTI;
		try
		{
			// assume we're always in assignments since this is just a temporary check until
			// we remove the legacy integration
			org.sakaiproject.assignment.api.model.Assignment asn = assignmentService.getAssignment(activityId);
			Site site = siteService.getSite(asn.getContext());
			useLTI = siteAdvisor.siteCanUseLTIReviewServiceForAssignment(site, new Date(asn.getDateCreated().getTime()));
		}
		catch (IdUnusedException | PermissionException e)
		{
			log.debug("Unable to find Assignment for the given activity id (" + activityId + ")", e);
			return false;
		}

		return !useLTI || getActivityConfig(toolId, activityId)
				.map(cfg -> !cfg.getTurnitinAsssignmentId().isEmpty() && !cfg.getStealthedLtiId().isEmpty())
				.orElse(false);
	}

	public String getLocalizedInvalidAsnConfigError()
	{
		ResourceLoader rl = new ResourceLoader(userDirectoryService.getCurrentUser().getId(), "turnitin");
		
		return rl.getString("invalid_asn_config");
	}



}
