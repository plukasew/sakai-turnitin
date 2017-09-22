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
package org.sakaiproject.contentreview.advisors;

import java.util.Date;
import org.sakaiproject.site.api.Site;

public interface ContentReviewSiteAdvisor {

	public boolean siteCanUseReviewService(Site site);
	
	// TIITODO: methods below this line are TII-specific and need to be moved to a more appropriate class...
	// -----------------------------------------------------------------------------------------------------

	public boolean siteCanUseLTIReviewService(Site site);

	// TIITODO: can this be removed now? Can we set a flag on the assignment on creation instead?
	// Will that work when editing assignments? With assignments that have existing submissions?
	/**
	 * Returns true if the TII LTI review service should be used, given the
	 * assignment creation date. This is a transitional method that should be removed
	 * once TII legacy api support ends.
	 * @param site
	 * @param assignmentCreationDate
	 * @return
	 */
	@Deprecated
	public boolean siteCanUseLTIReviewServiceForAssignment(Site site, Date assignmentCreationDate);

	public boolean siteCanUseLTIDirectSubmission(Site site);
}
