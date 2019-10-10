package seriesRenamer;

import com.moviejukebox.thetvdb.TheTVDB;
import com.moviejukebox.thetvdb.model.Episode;
import com.moviejukebox.thetvdb.model.Series;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rename files that are episodes of a series to a format "&lt;Showname&gt; - &lt;seasonNumber&gt;x&lt;episodeNumber&gt; - &lt;episodeTitle&gt;" or a given custom format. All files in the provided directory are checked for a valid extension, as defined in the properties file, before being renamed. It is also possible to define pre-execution aliases to change the name of the series as it appears in the file to more closely match the title that will be scraped. Similarly, post-execution aliases can be defined to change the scraped name of the series to something less formal to be used in the renamed filename.</br>
 * </br>
 * The data for renaming this correctly will be retrieved from TheTVDB.com.</br>
 *  </br>
 * This application assumes that the current file name starts with the show name, followed by the episode number in the format S01E01 or 1x01. It may contain dots instead of spaces, which can be defined in the properties file.</br>
 *  </br>
 * Configuration files are stored in the user's home directory in a subdirectory called ".SeriesRenamer". This can be changed by using the command-line option -c, --conf. 
 */
public class SeriesRenamer {
    private static final String version = "0.2_2012_01_20";
    private static final String APIKEY = "F1050217CEE6719C";
    private static String configPath = String.valueOf(System.getProperty("user.home", ".")) + "/.SeriesRenamer";
    private static String preExecFile = "/etc/preExecAlias.txt";
    private static String postExecFile = "/etc/postExecAlias.txt";
    private static String logFile = "/var/log/seriesRenamer.log";
    private static String propsPath = "/etc/seriesRenamer.properties";
    private static Properties props = new Properties();
    private static String targetDir = "";
    private static String season = "((?i)s?[0-9]{1,2}?)";
    private static String episode = "((?i)[ex]?[0-9]{2})";
    private static final String multipartSeason = "((?i)s?[0-9]{0,2})";
    private static final String multipartEpisode = "((?i)[ex]?[0-9]{2,2})";
    private static final String multipart = "(-?"+multipartSeason+multipartEpisode+")?";
    private static String seasonNumbers = "";
    private static final int seasonGroup = 1;
    private static final int episodeGroup = 2;
    private static final int multipartEpisodeGroup = 5;
    private static final int SEASON = 0;
    private static final int EPISODE = 1;
    private static final int MPEPISODE = 2;
    private static boolean quiet = false;
    private static boolean verbose = false;
    private static boolean recursive = false;
    private static String format = "<SeriesName> - <SeasonNumber>x<EpisodeNumber>[-<MultipartEpNum>][ - <EpisodeTitle>][-<MultipartEpTitle>]";
    private static String sortType = "default";
    private static int state = -1;
    private static boolean fullSeries = false;
    private static boolean fullSeason = false;
    private static boolean eptitleFound = false;
    private static boolean multiEptitleFound = false;
    private static boolean multiEpFound = true;
    private static String seriesID = "";
    private static List<Episode> batchEpisodes = null;
    private static boolean simulate;

    public static void main(String[] args) {
	processArgs(args);
	log("Renaming started at: " + (new Timestamp(System.currentTimeMillis())).toString(), false);
	boolean propsLoaded = loadProperties();
	if (!propsLoaded) {
	    log("The properties could not be loaded", true);
	    System.exit(-1);
	}
	season = props.getProperty("season");
	episode = props.getProperty("episode");
	seasonNumbers = "[^0-9(]" + season + episode + multipart + "[^0-9)]";
	File folder = new File((new File(targetDir)).getAbsolutePath());
	renameFiles(folder);
	log("Done renaming files with SeriesRenamer at: " + (new Timestamp(System.currentTimeMillis())).toString(),
		false);
	System.exit(state);
    }

    private static void processArgs(String[] args) {
	for (int curArgIndex = 0; curArgIndex < args.length; curArgIndex++) {
	    String curArg = args[curArgIndex];
	    if (curArg != "" && !curArg.isEmpty()) {
		if (curArg.charAt(0) == '-') {
		    if (curArg.charAt(1) == '-') {
			if (curArg.length() <= 2) {
			    curArgIndex++;
			    String target = "";
			    while (curArgIndex < args.length) {
				target = String.valueOf(target) + " " + curArg;
			    }
			    targetDir = target.trim();
			} else {
			    String curLongOpt = curArg.substring(2);
			    String[] splitOpt = curLongOpt.split("=", -1);
			    if (splitOpt.length < 1) {
				log("The command-line option: " + curLongOpt
					+ " could not be recognized after attempting to split it on the = character",
					true);
			    } else if (splitOpt.length == 1) {
				if (optionRequiresValue(curLongOpt)) {
				    processArg(curLongOpt, args[curArgIndex + 1]);
				    curArgIndex++;
				} else {
				    processArg(curLongOpt, null);
				}
			    } else if (splitOpt.length == 2) {
				processArg(splitOpt[0], splitOpt[1]);
			    } else if (splitOpt.length > 2) {
				String optVal = splitOpt[1];
				for (int i = 2; i < splitOpt.length; i++) {
				    optVal = String.valueOf(optVal) + "=" + splitOpt[i];
				}
				processArg(splitOpt[0], optVal);
			    }
			}
		    } else {
			String curShortOpt = curArg.substring(1);
			if (curShortOpt.length() <= 1) {
			    if (optionRequiresValue(curShortOpt)) {
				processArg(curShortOpt, args[curArgIndex + 1]);
				curArgIndex++;
			    } else {
				processArg(curShortOpt, null);
			    }
			} else {
			    String[] splitOpt = curShortOpt.split("=", -1);
			    if (splitOpt.length < 1) {
				log("The command-line option: " + curShortOpt
					+ " could not be recognized after attempting to split it on the = character",
					true);
			    } else if (splitOpt.length == 1) {
				if (optionRequiresValue(Character.toString(splitOpt[0].charAt(0)))) {
				    processArg(Character.toString(splitOpt[0].charAt(0)), splitOpt[0].substring(1));
				} else {
				    for (int i = 0; i < splitOpt[0].length(); i++) {
					if (optionRequiresValue(Character.toString(splitOpt[0].charAt(i)))) {
					    log("One of the arguments was provided in a combined set of arguments, but requires a value. This needs to be specified separately",
						    true);
					} else {
					    processArg(Character.toString(splitOpt[0].charAt(i)), null);
					}
				    }
				}
			    } else if (splitOpt.length > 1) {
				if (splitOpt[0].length() > 1) {
				    log("An option was provided with a long name without putting -- in front of it",
					    true);
				} else {
				    String optVal = splitOpt[1];
				    for (int i = 2; i < splitOpt.length; i++) {
					optVal = String.valueOf(optVal) + "=" + splitOpt[i];
				    }
				    processArg(splitOpt[0], optVal);
				}
			    }
			}
		    }
		} else if (curArgIndex >= args.length - 1) {
		    targetDir = curArg;
		} else {
		    String target = curArg;
		    while (curArgIndex < args.length) {
			target = String.valueOf(target) + " " + args[curArgIndex];
			curArgIndex++;
		    }
		    targetDir = target;
		}
	    }
	}
    }

    private static boolean optionRequiresValue(String option) {
	boolean reqVal = false;
	if (option.equals("c") || option.equals("config") || option.equals("f") || option.equals("format")
		|| option.equals("o") || option.equals("output") || option.equals("s") || option.equals("sort")) {
	    reqVal = true;
	}
	return reqVal;
    }

    private static void processArg(String opt, String val) {
	if (opt.equals("c") || opt.equals("config")) {
	    configPath = val;
	}
	if (opt.equals("f") || opt.equals("format")) {
	    format = val;
	}
	if (opt.equals("h") || opt.equals("help")) {
	    System.out.print(
		    "Name\n\tseriesRenamer - rename files that are episodes of a series\n\nSynopsis\n\tseriesRenamer [OPTIONS] FILE\n\tseriesRenamer [OPTIONS] [DIRECTORY]\n\nDescription\n\tRename files that are episodes of a series to a format \"<Showname> - <seasonNumber>x<episodeNumber> - <episodeTitle>\" or a given custom format.\n\n\tAll files in the DIRECTORY, or the provided file FILE, are checked for a valid extension, as defined in the properties file, before being renamed.\n\tIt is also possible to define pre-execution aliases to change the name of the series as it appears in the file to more closely match the title that will be scraped.Similarly, post-execution aliases can be defined to change the scraped name of the series to something less formal to be used in the renamed filename.\n\n\tThe data for renaming this correctly will be retrieved from TheTVDB.com.\n\n\tThis application assumes that the current file name starts with the show name, followed by the episode number in the format S01E01 or 1x01. It may contain dots instead of spaces, which can be defined in the properties file.\n\n\t-c, --config path\n\t\tspecify the path \"path\" where the configuration files will be stored\n\t\toverrides the default value\n\t\t(default: (user.home)/.SeriesRenamer or current directory if not available)\n\n\t-f, --format pattern\n\t\trename the episodes with a custom format \"pattern\". The pattern must be enclosed by double-quotes and should itself contain no double-quotes and can use the following variables:\n\t\t\t* <SeriesName> for the name of the series\n\t\t\t* <SeasonNumber> for the season number without any leading zeroes\n\t\t\t* <EpisodeNumber> for the episode number within a specific season with 1 leading zero\n\t\t\t* <EpisodeTitle> for the name of the episode\n\t\t\t* <multipartEpNum> for the episode number when a file represents multiple episodes\n\t\t\t* <multipartEpTitle> for the name of the episode when a file represents multiple episodes\n\t\tThese are the only variables currently available for use in the name. Note that the multipartEpTitle isn't written twice if the first one matches the second one.Optional sections are defined by square brackets which can not be nested. \n\t\t(default: \"<SeriesName> - <SeasonNumber>x<EpisodeNumber>[-<MultipartEpNum>][ - <EpisodeTitle>][-<MultipartEpTitle>]\")\n\n\t    --full-series\n\t\trename the episodes of an entire series at once. This can provide a performance enhancement, because it retrieves the information for all episodes at once, instead of one by one.\n\n\t    --full-season\n\t\trename the episodes of an entire season at once. Like --full-series, this can provide a performance enhancement by retrieving the information for all episodes (of the entire series) at once, but only looking through the episodes of a single season. It is preferred to use --full-series on the entire series than --full-season on multiple seasons of the series, from a performance perspective.\n\n\t-h, --help\n\t\tshow this help message\n\n\t-q, --quiet\n\t\tsuppress output to console\n\t\t(default: false)\n\n\t-r, --recursive\n\t\tsearch subfolders recursively to find files to rename\n\t\t(default: false)\n\n\t-s, --sort type\n\t\trename the episodes according to the provided sorting type.\n\t\tThis can be default, dvd or absolute.\n\t\t(default: default)\n\n\t    --simulate\n\t\tSimulate the renaming of the episodes. This shows new name of the files but doesn't actually rename them\n\t\t(default: false)\n\n\t    --version\n\t\tshow current version\n\n\t-v, --verbose\n\t\tshow information about what the program is doing\n\n\t--\n\t\tterminates all options, any options entered after this are not recognized as options and as such everything after this will be treated as DIRECTORY\n\n\tFILE\n\t\tthe name of the file representing the episode.If not provided, seriesRenamer will use the default value for DIRECTORY\n\tDIRECTORY\n\t\tthe absolute path to the directory which holds the files you wish to rename\n\t\t(default: current directory)\n\nAliases\n\tYou can define aliases for the program to use as series name before as well as after trying to rename the file, which will be matched using regular expressions. These are called pre-execution (preExec) and post-execution (postExec) aliases.\n\tThe preExec alias can be used to define an alias that can correctly be looked up on TheTVDB.com for a file that uses a different name for the show, e.g. using the alias \"Human Target (2010)\" for the files with \"Human Target\" as series name. The regex to match this could be \"human.target\"\n\tThe postExec alias can be used to define an alias that renames the file to something other than then official TheTVDB.com names (which have to be unique), e.g. using the alias \"Human Target\" for the series with \"Human Target (2010)\" as name. The regex to match this could be \"human.target.\\(2010\\)\". (Note that the brackets need to be escaped for this to remain a valid regular expression)\n\tNote that the examples show that you can use the aliases to make sure that the correct series is found (in this example, the original version of the series would be found instead of the 2010 remake) and then renamed similar to the original name (without a year indication).\n\n\tThe aliases have to be saved in the etc/ folder in the configuration directory that's being used (see -c, --config) under the names preExecAlias.txt and postExecAlias.txt for the preExec and postExec aliases respectively.\n\tEach alias is represented by a key-value pair in this .txt file and is written on a single line with the key and value separated by the equals (=) character.\n\tComments in the .properties files can be entered on a line with a pound (#) character at the beginning of the line.\n\tThe key for an alias entry is a regex that the series name must match, the value represents the (plain)text that it will be replaced with.\n\tNote that these aliases are used on the entire filename and that the regex matching is case-insensitive.\n\nExit status\n\tThe program exits with a status of zero if at least one file has been renamed or when viewing this help or the version info, otherwise it exits with a nonzero status. \n\nReporting bugs\n\tReport bugs to arucard21@gmail.com\n");
	    System.exit(0);
	}
	if (opt.equals("q") || opt.equals("quiet")) {
	    quiet = true;
	}
	if (opt.equals("r") || opt.equals("recursive")) {
	    recursive = true;
	}
	if (opt.equals("s") || opt.equals("sort")) {
	    sortType = val;
	}
	if (opt.equals("version")) {
	    System.out.println("SeriesRenamer " + version);
	    System.out.println(
		    "This software can be used for free, but it can not be redistributed or changed without the express permission of the author.");
	    System.exit(0);
	}
	if (opt.equals("v") || opt.equals("verbose")) {
	    verbose = true;
	}
	if (opt.equals("full-series")) {
	    fullSeason = false;
	    fullSeries = true;
	}
	if (opt.equals("full-season")) {
	    fullSeries = false;
	    fullSeason = true;
	}
	if (opt.equals("simulate")) {
	    simulate = true;
	}
    }

    private static boolean loadProperties() {
	FileReader propsReader = null;
	try {
	    File propsFile = new File(String.valueOf(configPath) + propsPath);
	    if (!propsFile.exists()) {
		File propsDir = propsFile.getParentFile();
		if (!propsDir.exists()) {
		    if (!propsDir.mkdirs()) {
			log("The directory for the properties file " + propsPath + " could not be created", true);
		    }
		}
		propsFile.createNewFile();
		Properties defProps = new Properties();
		defProps.setProperty("season", season);
		defProps.setProperty("episode", episode);
		defProps.setProperty("validExtensions", "avi;mkv;mp4;mpg;srt;idx;sub");
		defProps.setProperty("reservedCharacters", "/;\\\\;\\?;%;\\*;:;|;\";<;>");
		defProps.setProperty("replaceCharacters", "/,-;\\\\,-");
		defProps.setProperty("wordSeparators", "\\s;\\.;_");
		FileWriter propsWriter = new FileWriter(propsFile);
		defProps.store(new BufferedWriter(propsWriter), "Properties for the SeriesRenamer");
		propsWriter.close();
	    }
	    propsReader = new FileReader(propsFile);
	    props.load(new BufferedReader(propsReader));
	    propsReader.close();
	} catch (FileNotFoundException noFile) {
	    log("Properties file " + propsPath + " can not be read", true);
	    StackTraceElement[] stacktrace = noFile.getStackTrace();
	    String stString = String.valueOf(noFile.toString()) + "\n";
	    for(StackTraceElement ste : stacktrace) {
		stString = String.valueOf(stString) + "\t" + ste.toString() + "\n";
	    }
	    log(stString, true);
	} catch (IOException IO) {
	    if (propsReader == null) {
		log("Properties file " + propsPath + " can not be created", true);
	    } else {
		log("Properties could not be loaded from the properties file " + propsPath, true);
	    }
	    StackTraceElement[] stacktrace = IO.getStackTrace();
	    String stString = String.valueOf(IO.toString()) + "\n";
	    for(StackTraceElement ste : stacktrace) {
		stString = String.valueOf(stString) + "\t" + ste.toString() + "\n";
	    }
	    log(stString, true);
	} catch (Exception other) {
	    log("An unexpected error has occurred, please see the stacktrace for more info", true);
	    StackTraceElement[] stacktrace = other.getStackTrace();
	    String stString = String.valueOf(other.toString()) + "\n";
	    for(StackTraceElement ste : stacktrace) {
		stString = String.valueOf(stString) + "\t" + ste.toString() + "\n";
	    }
	    log(stString, true);
	}
	return (props != null && !props.isEmpty());
    }

    private static void renameFiles(File target) {
	File[] files = new File[1];
	if (target.isDirectory()) {
	    files = target.listFiles();
	} else {
	    files[0] = target;
	}
	for (int i = 0; i < files.length; i++) {
	    if (files[i].isDirectory()) {
		if (recursive) {
		    renameFiles(files[i]);
		}
	    } else {
		eptitleFound = false;
		multiEptitleFound = false;
		multiEpFound = false;
		seriesID = "";
		String name = files[i].getName();
		if (checkFileExtension(name)) {
		    String newName = getNewName(name);
		    newName = newName.replaceAll("&quot;", "\"");
		    newName = newName.replaceAll("&amp;", "&");
		    newName = newName.replaceAll("&apos;", "'");
		    newName = newName.replaceAll("&lt;", "<");
		    newName = newName.replaceAll("&gt;", ">");
		    String[] replacePairs = props.getProperty("replaceCharacters").split(";");
		    for (String replacePair : replacePairs) {
			String[] replaceKeyValue = replacePair.split(",");
			String key = replaceKeyValue[0].trim();
			String value = replaceKeyValue[1].trim();
			newName = newName.replaceAll(key, value);
		    }
		    String[] reservedChars = props.getProperty("reservedCharacters").split(";");
		    for (String reservedChar : reservedChars) {
			newName = newName.replaceAll(reservedChar, "");
		    }
		    if (!newName.equals(name)) {
			File renamedFile = new File(files[i].getParent(), newName);
			if (eptitleFound && (!multiEpFound || multiEptitleFound) && !renamedFile.exists()) {
			    if (simulate) {
				log("The file:\t\t\t" + name + "\nwould have been renamed to:\t" + newName, false);
				state = 0;
			    } else if (files[i].renameTo(renamedFile)) {
				log("The file:\t\t" + name + "\nhas been renamed to:\t" + newName, false);
				state = 0;
			    }
			} else {
			    log("The file " + name
				    + " was not renamed because the episode title could not be found or the file already exists",
				    true);
			}
		    } else {
			log("The file " + name + " was already correctly named", false);
		    }
		} else {
		    log("The file:\t\t" + name + " did not have a valid extension and was not renamed", false);
		}
	    }
	}
    }

    public static String getNewName(String name) {
	name = handleAliases(name, true);
	String show = getSeriesName(name);
	String[] seasonEp = getEpisodeNumbers(name);
	String seasonNum = seasonEp[SEASON];
	String episodeNum = seasonEp[EPISODE];
	String mpEpisodeNum = seasonEp[MPEPISODE];
	if ((fullSeason || fullSeries) && batchEpisodes == null) {
	    if (fullSeries) {
		batchEpisodes = getEpisodes(show, "");
	    } else if (fullSeason) {
		batchEpisodes = getEpisodes(show, seasonNum);
	    }
	}
	String episodeTitle = getEpisodeTitle(show, seasonNum, episodeNum);
	if (!episodeTitle.isEmpty()) {
	    eptitleFound = true;
	}
	String[] nameArr = name.split("\\.");
	String extension = "";
	if (nameArr.length > 0) {
	    extension = nameArr[nameArr.length - 1];
	}
	String multiEpTitle = "";
	if (mpEpisodeNum != null && !mpEpisodeNum.isEmpty()) {
	    multiEpTitle = getEpisodeTitle(show, seasonNum, mpEpisodeNum);
	    multiEpFound = true;
	    if (!multiEpTitle.equals("") && multiEpTitle != null) {
		multiEptitleFound = true;
		String episodeCompare = episodeTitle.replaceAll("\\(1\\)", "").trim();
		String mpEpisodeCompare = multiEpTitle.replaceAll("\\(2\\)", "").trim();
		if (episodeCompare.equalsIgnoreCase(mpEpisodeCompare)) {
		    episodeTitle = episodeTitle.replaceAll("\\(1\\)", "(1-2)").trim();
		    multiEpTitle = "";
		}
	    } else {
		multiEpTitle = "";
	    }
	}
	show = handleAliases(show, false);
	String newName = format;
	if (episodeNum.length() < 1) {
	    episodeNum = "00" + episodeNum;
	} else if (episodeNum.length() == 1) {
	    episodeNum = "0" + episodeNum;
	}
	if (mpEpisodeNum != null && mpEpisodeNum.length() < 1) {
	    mpEpisodeNum = "00" + mpEpisodeNum;
	} else if (mpEpisodeNum != null && mpEpisodeNum.length() == 1) {
	    mpEpisodeNum = "0" + mpEpisodeNum;
	}
	newName = replaceTemplateVars(newName, "<SeriesName>", show, false);
	newName = replaceTemplateVars(newName, "<SeasonNumber>", seasonNum, false);
	newName = replaceTemplateVars(newName, "<EpisodeNumber>", episodeNum, false);
	newName = replaceTemplateVars(newName, "<MultipartEpNum>", mpEpisodeNum, true);
	newName = replaceTemplateVars(newName, "<EpisodeTitle>", episodeTitle, true);
	newName = replaceTemplateVars(newName, "<MultipartEpTitle>", multiEpTitle, true);
	return String.valueOf(newName) + "." + extension;
    }

    private static String replaceTemplateVars(String template, String templVar, String templVarVal, boolean optional) {
	if (templVarVal == null || templVarVal.isEmpty()) {
	    if (optional) {
		template = template.replaceAll("\\[[^\\[]*?" + templVar + "[^\\]]*?\\]", "");
	    } else {
		log("The value for " + templVar + " could not be found", true);
	    }
	} else if (template.matches(".*(" + templVar + ")+?.*")) {
	    if (optional) {
		Pattern brackets = Pattern.compile("\\[([^\\[]*?" + templVar + "[^\\]]*?)\\]");
		Matcher brMatch = brackets.matcher(template);
		brMatch.find();
		String brRepl = "";
		try {
		    brRepl = brMatch.group(1);
		    template = brMatch.replaceAll(brRepl);
		} catch (IllegalStateException noMatch) {
		    log("A format has been provided with an optional template variable that's not contained within brackets",
			    true);
		}
	    }
	    template = template.replaceFirst(templVar, Matcher.quoteReplacement(templVarVal));
	} else {
	    log("No " + templVar + " was provided in the format template", true);
	}
	return template;
    }

    private static String getSeriesName(String name) {
	String seriesNameFile = "";
	String seriesRegex = "";
	if (sortType.equalsIgnoreCase("absolute")) {
	    seriesRegex = "(.*?)\\s?-?\\s?[^0-9(]" + episode + "(-?" + multipartEpisode + ")?" + "[^0-9)]"
		    + ".*";
	} else {
	    seriesRegex = "(.*?)\\s?-?\\s?" + seasonNumbers + ".*";
	}
	Pattern seriesPattern = Pattern.compile(seriesRegex);
	Matcher seriesMatcher = seriesPattern.matcher(name);
	if (seriesMatcher.find()) {
	    seriesNameFile = seriesMatcher.group(1);
	} else {
	    log("The series name could not be parsed from the filename", true);
	}
	seriesNameFile = seriesNameFile.replaceAll(props.getProperty("wordSeparators").replaceAll(";", "|"), " ")
		.trim();
	String[] wordSeparators = props.getProperty("wordSeparators").split(";");
	for(String wordSeparator : wordSeparators) {
	    seriesNameFile = seriesNameFile.replaceAll(wordSeparator, " ");
	}
	TheTVDB tvdb = new TheTVDB(APIKEY);
	List<Series> allSeries = tvdb.searchSeries(seriesNameFile, "en");
	String seriesName = "";
	if (allSeries.size() > 0) {
	    Series series = (Series) allSeries.get(0);
	    seriesName = series.getSeriesName();
	    seriesID = series.getId();
	}
	if (isSameSeries(seriesNameFile, seriesName)) {
	    return seriesName;
	}
	return "";
    }

    private static String[] getEpisodeNumbers(String name) {
	String numbersRegex = "";
	if (sortType.equalsIgnoreCase("absolute")) {
	    numbersRegex = "[^0-9(]" + episode + "(-?" + multipartEpisode + ")?" + "[^0-9)]";
	} else {
	    numbersRegex = seasonNumbers;
	}
	Pattern seasonAndEp = Pattern.compile(numbersRegex);
	Matcher sAndEMatch = seasonAndEp.matcher(name);
	String seriesNameFile = "";
	String seriesRegex = "";
	if (sortType.equalsIgnoreCase("absolute")) {
	    seriesRegex = "(.*?)\\s?-?\\s?[^0-9(]" + episode + "(-?" + multipartEpisode + ")?" + "[^0-9)]"
		    + ".*";
	} else {
	    seriesRegex = "(.*?)\\s?-?\\s?" + seasonNumbers + ".*";
	}
	Pattern seriesPattern = Pattern.compile(seriesRegex);
	Matcher seriesMatcher = seriesPattern.matcher(name);
	String[] numsArr = new String[3];
	if (sAndEMatch.find()) {
	    if (seriesMatcher.find()) {
		seriesNameFile = seriesMatcher.group(1);
		seriesNameFile = seriesNameFile
			.replaceAll(props.getProperty("wordSeparators").replaceAll(";", "|"), " ").trim();
		String[] wordSeparators = props.getProperty("wordSeparators").split(";");
		for(String wordSeparator : wordSeparators) {
		    seriesNameFile = seriesNameFile.replaceAll(wordSeparator, " ");
		}
		Pattern digits = Pattern.compile("[0-9]{3,4}");
		Matcher digitMatch = digits.matcher(seriesNameFile);
		while (digitMatch.find()) {
		    String matchedDigits = digitMatch.group();
		    if (!seriesNameFile.contains(matchedDigits) && name.contains(matchedDigits)) {
			sAndEMatch.find(sAndEMatch.end() - 1);
		    }
		}
	    }
	    numsArr[0] = sAndEMatch.group(seasonGroup);
	    numsArr[0] = numsArr[0].trim().replaceFirst("^(?i)s*", "");
	    if (numsArr[0].length() > 1) {
		numsArr[0] = numsArr[0].trim().replaceFirst("^(?i)0*", "");
	    }
	    numsArr[1] = sAndEMatch.group(episodeGroup);
	    numsArr[1] = numsArr[1].trim().replaceFirst("^(?i)[ex]*", "");
	    if (numsArr[1].length() > 1) {
		numsArr[1] = numsArr[1].trim().replaceFirst("^(?i)0*", "");
	    }
	    numsArr[2] = sAndEMatch.group(multipartEpisodeGroup);
	    if (numsArr[2] != null && !numsArr[2].isEmpty()) {
		numsArr[2] = numsArr[2].trim().replaceFirst("^[ex]?0*", "");
	    }
	    return numsArr;
	}
	return null;
    }

    private static String getEpisodeTitle(String show, String season, String episode) {
	int seasonNum = Integer.valueOf(season).intValue();
	int episodeNum = Integer.valueOf(episode).intValue();
	if (batchEpisodes == null) {
	    TheTVDB tvdb = new TheTVDB(APIKEY);
	    if (!seriesID.isEmpty()) {
		Episode epDetails = null;
		if (sortType.equalsIgnoreCase("default")) {
		    epDetails = tvdb.getEpisode(seriesID, seasonNum, episodeNum, "en");
		} else if (sortType.equalsIgnoreCase("dvd")) {
		    epDetails = tvdb.getDVDEpisode(seriesID, seasonNum, episodeNum, "en");
		} else if (sortType.equalsIgnoreCase("absolute")) {
		    log("Renaming according to absolute numbering scheme is not yet supported", true);
		}
		if (epDetails != null) {
		    return epDetails.getEpisodeName();
		}
		log("Episode details for " + show + " - " + season + "x" + episode + " could not be retrieved", true);
		return "";
	    }
	    return "";
	}
	if (batchEpisodes != null) {
	    for (Episode ep : batchEpisodes) {
		if (ep.getSeriesId().equals(seriesID)) {
		    if (sortType.equalsIgnoreCase("default")) {
			if (ep.getSeasonNumber() == seasonNum && ep.getEpisodeNumber() == episodeNum) {
			    return ep.getEpisodeName();
			}
			continue;
		    }
		    if (sortType.equalsIgnoreCase("dvd")) {
			String dvdSeason = ep.getDvdSeason();
			String dvdEpisode = ep.getDvdEpisodeNumber();
			int dvdSeasonNum = -1;
			int dvdEpisodeNum = -1;
			if (!dvdSeason.isEmpty()) {
			    if (dvdSeason.matches("[0-9]*")) {
				dvdSeasonNum = Integer.valueOf(dvdSeason).intValue();
			    } else {
				dvdSeasonNum = Double.valueOf(dvdSeason).intValue();
			    }
			}
			if (!dvdEpisode.isEmpty()) {
			    if (dvdEpisode.matches("[0-9]*")) {
				dvdEpisodeNum = Integer.valueOf(dvdEpisode).intValue();
			    } else {
				dvdEpisodeNum = Double.valueOf(dvdEpisode).intValue();
			    }
			}
			if (!dvdSeason.isEmpty() && !dvdEpisode.isEmpty() && dvdSeasonNum == seasonNum
				&& dvdEpisodeNum == episodeNum) {
			    return ep.getEpisodeName();
			}
			continue;
		    }
		    if (sortType.equalsIgnoreCase("absolute")) {
			log("Renaming according to absolute numbering scheme is not yet supported", true);
		    }
		    continue;
		}
		log("The episode list does not contain episodes for " + show + ", but for the show with ID: "
			+ ep.getSeriesId(), true);
		return "";
	    }
	    log("The episode " + show + " - " + season + "x" + episode + " is not contained in the episode list", true);
	    return "";
	}
	log("The episode list for " + show + " - " + season + "x" + episode + " was not retrieved", true);
	return "";
    }

    private static List<Episode> getEpisodes(String show, String season) {
	TheTVDB tvdb = new TheTVDB(APIKEY);
	if (!seriesID.isEmpty()) {
	    List<Episode> epDetails = null;
	    if (season.isEmpty()) {
		epDetails = tvdb.getAllEpisodes(seriesID, "en");
	    } else {
		epDetails = tvdb.getSeasonEpisodes(seriesID, Integer.valueOf(season).intValue(), "en");
	    }
	    if (epDetails != null) {
		return epDetails;
	    }
	    log("Episode list for " + show + " and " + season + " could not be retrieved", true);
	    return null;
	}
	return null;
    }

    private static boolean checkFileExtension(String name) {
	String[] nameArr = name.split("\\.");
	String ext = "";
	if (nameArr.length > 0) {
	    ext = nameArr[nameArr.length - 1].toLowerCase();
	}
	String[] validExts = props.getProperty("validExtensions").split(";");
	boolean validExt = false;
	for (int i = 0; i < validExts.length && !validExt; i++) {
	    validExts[i] = validExts[i].trim();
	    validExt = ext.matches(validExts[i]);
	}
	return validExt;
    }

    private static boolean isSameSeries(String seriesName, String otherSeriesName) {
	seriesName = seriesName.replaceAll("\\(.*?\\)|(?i)[^a-z0-9]", "");
	otherSeriesName = seriesName.replaceAll("\\(.*?\\)|(?i)[^a-z0-9]", "");
	return seriesName.equalsIgnoreCase(otherSeriesName);
    }

    private static String handleAliases(String filename, boolean pre) {
	String changedFilename = filename;
	String aliases = "";
	if (pre) {
	    aliases = String.valueOf(configPath) + preExecFile;
	} else {
	    aliases = String.valueOf(configPath) + postExecFile;
	}
	try {
	    File aliasFile = new File(aliases);
	    if (aliasFile.exists()) {
		BufferedReader reader = new BufferedReader(new FileReader(aliasFile));
		String curLine = reader.readLine();
		while (curLine != null) {
		    if (!curLine.matches("#.*")) {
			String[] line = curLine.split("=", 2);
			String regex = line[0];
			String alias = line[1];
			changedFilename = changedFilename.replaceFirst("(?i)" + regex, alias);
		    }
		    curLine = reader.readLine();
		}
		reader.close();
	    }
	} catch (FileNotFoundException noFile) {
	    log("Alias file " + aliases + "can not be read", true);
	    StackTraceElement[] stacktrace = noFile.getStackTrace();
	    String stString = String.valueOf(noFile.toString()) + "\n";
	    for(StackTraceElement ste: stacktrace) {
		stString = String.valueOf(stString) + "\t" + ste.toString() + "\n";
	    }
	    log(stString, true);
	} catch (Exception other) {
	    log("An unexpected error has occurred, please see the stacktrace for more info", true);
	    StackTraceElement[] stacktrace = other.getStackTrace();
	    String stString = String.valueOf(other.toString()) + "\n";
	    for(StackTraceElement ste: stacktrace) {
		stString = String.valueOf(stString) + "\t" + ste.toString() + "\n";
	    }
	    log(stString, true);
	}
	if (changedFilename != null && !changedFilename.isEmpty()) {
	    return changedFilename;
	}
	return filename;
    }

    private static void log(String message, boolean error) {
	if (error) {
	    if (!quiet) {
		System.err.println(message);
	    }
	    message = "ERROR:\n" + message;
	} else {
	    if (verbose) {
		System.out.println(message);
	    }
	    message = "INFO:\n" + message;
	}
	BufferedWriter logWriter = null;
	try {
	    File log = new File(String.valueOf(configPath) + logFile);
	    if (!log.exists()) {
		File logDir = log.getParentFile();
		if (!logDir.exists()) {
		    if (!logDir.mkdirs()) {
			System.err
				.println("ERROR:\nThe directory for the log file " + logFile + " could not be created");
		    }
		}
		log.createNewFile();
	    }
	    logWriter = new BufferedWriter(new FileWriter(log, true));
	} catch (IOException IO) {
	    System.err.println("ERROR:\nThe log file " + logFile + " could not be created");
	    IO.printStackTrace();
	} catch (Exception other) {
	    System.err.println("ERROR:\nAn unexpected error has occurred, please see the stacktrace for more info");
	    other.printStackTrace();
	}
	try {
	    logWriter.newLine();
	    logWriter.write(message);
	} catch (IOException logWriteIO) {
	    System.err.println("ERROR:\nThe log message could not be written to the log " + logFile);
	    logWriteIO.printStackTrace();
	}
	try {
	    logWriter.close();
	} catch (IOException e) {
	    System.err.println("ERROR:\nThe writer for the log file " + logFile + " could not be closed");
	    e.printStackTrace();
	}
    }

    public static void reset() {
	configPath = String.valueOf(System.getProperty("user.home", ".")) + "/.SeriesRenamer";
	targetDir = "";
	quiet = false;
	verbose = false;
	recursive = false;
	format = "<SeriesName> - <SeasonNumber>x<EpisodeNumber>[-<MultipartEpNum>][ - <EpisodeTitle>][-<MultipartEpTitle>]";
	batchEpisodes = null;
	sortType = "default";
	state = -1;
	fullSeries = false;
	fullSeason = false;
    }
}
