package org.sakaiproject.contentreview.turnitin;

/**
 *
 * @author plukasew
 */
public class TurnitinConstants
{
	// TIITODO: this class was originally created to hold constants to share between the tool (webapp/servlets) and impl
	// perhaps we don't need to expose these outside of impl if we have appropriate service methods to do the work?
	// then we can split up these constants into appropriate locations depending on how they are used.
	
	public static final String SERVICE_NAME = "Turnitin";
	
	// Site property to enable or disable use of Turnitin for the site
	public static final String TURNITIN_SITE_PROPERTY = "turnitin";
	
	public static final String TURNITIN_DATETIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
	
	public static final String STEALTHED_LTI_ID = "stealthed_lti_id";
	public static final String TURNITIN_ASN_ID = "turnitin_asn_id";
	public static final int PROVIDER_ID = 1;
	public static final String SAKAI_ASSIGNMENT_TOOL_ID = "sakai.assignment.grades";
	public static final String TURNITIN_ASN_LATEST_INDIVIDUAL_EXTENSION_DATE = "turnitin_asn_latest_individual_extension_date";
	
	public static final String[] DEFAULT_TERMINAL_QUEUE_ERRORS = new String[] { "Your submission does not contain valid text.",
		"Your submission must contain 20 words or more.", "You must upload a supported file type for this assignment."};
	
	public static final String SAK_PROP_ACCEPT_ALL_FILES = "turnitin.accept.all.files";
	public static final String SAK_PROP_ACCEPTABLE_FILE_EXTENSIONS = "turnitin.acceptable.file.extensions";
	public static final String SAK_PROP_ACCEPTABLE_MIME_TYPES = "turnitin.acceptable.mime.types";
	// A list of the displayable file types (ie. "Microsoft Word", "WordPerfect document", "Postscript", etc.)
	public static final String SAK_PROP_ACCEPTABLE_FILE_TYPES = "turnitin.acceptable.file.types";
	
	public static final String KEY_FILE_TYPE_PREFIX = "file.type";
	
	// Define Turnitin's acceptable file extensions and MIME types, order of these arrays DOES matter
	public static final String[] DEFAULT_ACCEPTABLE_FILE_EXTENSIONS = new String[] {
		".doc", 
		".docx", 
		".xls", 
		".xls", 
		".xls", 
		".xls", 
		".xlsx", 
		".ppt", 
		".ppt", 
		".ppt", 
		".ppt", 
		".pptx", 
		".pps", 
		".pps", 
		".ppsx", 
		".pdf", 
		".ps", 
		".eps", 
		".txt", 
		".html", 
		".htm", 
		".wpd", 
		".wpd", 
		".odt", 
		".rtf", 
		".rtf", 
		".rtf", 
		".rtf", 
		".hwp"
	};
	public static final String[] DEFAULT_ACCEPTABLE_MIME_TYPES = new String[] {
		"application/msword", 
		"application/vnd.openxmlformats-officedocument.wordprocessingml.document", 
		"application/excel", 
		"application/vnd.ms-excel", 
		"application/x-excel", 
		"application/x-msexcel", 
		"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 
		"application/mspowerpoint", 
		"application/powerpoint", 
		"application/vnd.ms-powerpoint", 
		"application/x-mspowerpoint", 
		"application/vnd.openxmlformats-officedocument.presentationml.presentation", 
		"application/mspowerpoint", 
		"application/vnd.ms-powerpoint", 
		"application/vnd.openxmlformats-officedocument.presentationml.slideshow", 
		"application/pdf", 
		"application/postscript", 
		"application/postscript", 
		"text/plain", 
		"text/html", 
		"text/html", 
		"application/wordperfect", 
		"application/x-wpwin", 
		"application/vnd.oasis.opendocument.text", 
		"text/rtf", 
		"application/rtf", 
		"application/x-rtf", 
		"text/richtext", 
		"application/x-hwp"
	};
}
