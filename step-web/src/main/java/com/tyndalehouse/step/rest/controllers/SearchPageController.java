package com.tyndalehouse.step.rest.controllers;

import com.tyndalehouse.step.core.models.AbstractComplexSearch;
import com.tyndalehouse.step.core.models.ClientSession;
import com.tyndalehouse.step.core.models.InterlinearMode;
import com.tyndalehouse.step.core.models.LexiconSuggestion;
import com.tyndalehouse.step.core.models.OsisWrapper;
import com.tyndalehouse.step.core.models.SearchToken;
import com.tyndalehouse.step.core.models.search.SearchResult;
import com.tyndalehouse.step.core.service.AppManagerService;
import com.tyndalehouse.step.core.service.LanguageService;
import com.tyndalehouse.step.core.utils.StringUtils;
import com.tyndalehouse.step.core.utils.language.ContemporaryLanguageUtils;
import com.yammer.metrics.annotation.Timed;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.*;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * @author chrisburrell
 */
@Singleton
public class SearchPageController extends HttpServlet {
    private static final Pattern COMMA_SEPARATORS = Pattern.compile(",");
    private static String DEV_TOKEN = "UA-36285759-2";
    private static String LIVE_TOKEN = "UA-36285759-1";
    private static Logger LOGGER = LoggerFactory.getLogger(SearchPageController.class);
    private final SearchController search;
    private final ModuleController modules;
    private final BibleController bible;
    private final LanguageService languageService;
    private final AppManagerService appManagerService;
    private final Provider<ObjectMapper> objectMapper;
    private final Provider<ClientSession> clientSessionProvider;

    @Inject
    public SearchPageController(final SearchController search,
                                final ModuleController modules,
                                final BibleController bible,
                                final LanguageService languageService,
                                final AppManagerService appManagerService,
                                Provider<ObjectMapper> objectMapper,
                                Provider<ClientSession> clientSessionProvider) {
        this.search = search;
        this.modules = modules;
        this.bible = bible;
        this.languageService = languageService;
        this.appManagerService = appManagerService;
        this.objectMapper = objectMapper;
        this.clientSessionProvider = clientSessionProvider;
    }

    @Override
    @Timed(name = "home-page", group = "pages", rateUnit = TimeUnit.SECONDS, durationUnit = TimeUnit.MILLISECONDS)
    protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException {
        if(!checkLanguage()) {
            //do redirect
            //clear the lang cookie
            final Cookie[] cookies = request.getCookies();
            if(cookies != null) {
            for(Cookie c  : cookies) {
                if("lang".equals(c.getName())) {
                    c.setMaxAge(0);
                    response.addCookie(c);
                }
            }
            doRedirect(response);
                return;
            }
            return;
        }

        AbstractComplexSearch text;
        try {
            //if we have a 'reference' and/or 'version' parameter, redirect to that page
            final String oldVersion = request.getParameter("version");
            final String oldReference = request.getParameter("reference");
            if (StringUtils.isNotBlank(oldVersion) || StringUtils.isNotBlank(oldReference)) {
                doRedirect(response, oldReference, oldVersion);
                return;
            }

            text = doSearch(request);
            setupRequestContext(request, text);
            setupResponseContext(response);
        } catch (Exception exc) {
            LOGGER.warn(exc.getMessage(), exc);
        } finally {
            request.getRequestDispatcher("/start.jsp").include(request, response);
        }
    }

    private boolean checkLanguage() {
        Locale userLocale = this.clientSessionProvider.get().getLocale();
        if(userLocale.getLanguage() == null) {
            return true;
        }
        return this.languageService.isSupported(userLocale.getLanguage(), userLocale.getCountry());
    }

    private void doRedirect(final HttpServletResponse response, final String oldReference, final String oldVersion) {
        try {
            response.setStatus(301);
            response.setHeader("Location", String.format("http://%s/?q=%s", appManagerService.getAppDomain(), getUrlFragmentForPassage(oldVersion, oldReference)));
            response.setHeader("Connection", "close");
        } catch (Exception ex) {
            LOGGER.error("Failed to operate redirect", ex);
            return;
        }
    }

    private void doRedirect(final HttpServletResponse response) {
        try {
            response.setStatus(301);
            response.setHeader("Location", String.format("http://%s", appManagerService.getAppDomain()));
            response.setHeader("Connection", "close");
        } catch (Exception ex) {
            LOGGER.error("Failed to operate redirect", ex);
            return;
        }
    }

    /**
     * Sets up default attributes on response
     *
     * @param resp the response
     */
    private void setupResponseContext(final HttpServletResponse resp) {
        resp.setContentType("text/html");
        resp.setCharacterEncoding("UTF-8");
    }

    /**
     * Sets up the request context for use in the JSTL parsing
     *
     * @param req  the request
     * @param data the osisWrapper
     * @throws IOException
     */
    private void setupRequestContext(final HttpServletRequest req, final AbstractComplexSearch data) throws IOException {
        //global settings
        //set the language attributes once
        final Locale userLocale = this.clientSessionProvider.get().getLocale();
        req.setAttribute("languageCode", userLocale.getLanguage());
        req.setAttribute("languageName", ContemporaryLanguageUtils.capitaliseFirstLetter(userLocale
                .getDisplayLanguage(userLocale)).replace("\"", ""));
        req.setAttribute("languageComplete", this.languageService.isCompleted(userLocale.getLanguage()));
        req.setAttribute("ltr", ComponentOrientation.getOrientation(userLocale).isLeftToRight());
        req.setAttribute("versions", objectMapper.get().writeValueAsString(modules.getAllModules()));
        req.setAttribute("searchType", data.getSearchType().name());
        req.setAttribute("versionList", getVersionList(data.getMasterVersion(), data.getExtraVersions()));
        req.setAttribute("languages", this.languageService.getAvailableLanguages());

        //specific to passages
        if (data instanceof OsisWrapper) {
            final OsisWrapper osisWrapper = (OsisWrapper) data;
            req.setAttribute("passageText", osisWrapper.getValue());
            osisWrapper.setValue(null);
            req.setAttribute("passageModel", objectMapper.get().writeValueAsString(osisWrapper));
            populateMetaPassage(req, osisWrapper);
            populateSiblingChapters(req, osisWrapper);
        } else if (data instanceof SearchResult) {
            final SearchResult results = (SearchResult) data;
            req.setAttribute("searchResults", results.getResults());
            req.setAttribute("definitions", results.getDefinitions());
            req.setAttribute("filter", results.getStrongHighlights());
            req.setAttribute("numResults", results.getTotal());
            req.setAttribute("sort", results.getOrder());
            results.setResults(null);
            req.setAttribute("passageModel", objectMapper.get().writeValueAsString(results));
            populateMetaSearch(req, results);
        }

        //set the analytics token
        req.setAttribute("stepDomain", appManagerService.getAppDomain());
        req.setAttribute("analyticsToken", Boolean.TRUE.equals(Boolean.getBoolean("step.development")) ? DEV_TOKEN : LIVE_TOKEN);
    }

    /**
     * Returns a list of versions used in the search
     *
     *
     * @param masterVersion the first version
     * @param extraVersions the extra versions
     * @return the properly formatted list
     */
    private String[] getVersionList(final String masterVersion, final String extraVersions) {
        if (StringUtils.isBlank(masterVersion)) {
            return new String[0];
        }

        if (StringUtils.isBlank(extraVersions)) {
            return new String[] { masterVersion} ;
        }

        final String[] extras = StringUtils.split(extraVersions, ",");
        final String[] allVersions = new String[extras.length + 1];
        allVersions[0] = masterVersion;
        int ii = 1;
        for(String e : extras) {
            allVersions[ii++] = e;
        }
        return allVersions;
    }

    /**
     * Creates the fragments for previous and next chapters
     *
     * @param req         the request
     * @param osisWrapper the osiswrapper
     */
    private void populateSiblingChapters(final HttpServletRequest req, final OsisWrapper osisWrapper) {
        req.setAttribute("previousChapter", getUrlFragmentForPassage(osisWrapper.getMasterVersion(), osisWrapper.getPreviousChapter().getOsisKeyId()));
        req.setAttribute("nextChapter", getUrlFragmentForPassage(osisWrapper.getMasterVersion(), osisWrapper.getNextChapter().getOsisKeyId()));
    }

    /**
     * Populates the meta data for a search
     *
     * @param req     the request
     * @param results the results
     */
    private void populateMetaSearch(final HttpServletRequest req, final SearchResult results) {
        try {
            final List<SearchToken> searchTokens = results.getSearchTokens();

            String scope = null;
            StringBuilder keyInfo = new StringBuilder(128);
            StringBuilder versions = new StringBuilder(32);
            for (SearchToken t : searchTokens) {
                final String tokenType = t.getTokenType();
                final String token = t.getToken();
                final String infoSeparator = " | ";
                if (SearchToken.VERSION.equals(tokenType)) {
                    versions.append(token);
                    versions.append(infoSeparator);
                } else if (SearchToken.REFERENCE.equals(tokenType)) {
                    scope = token;
                } else if (SearchToken.SUBJECT_SEARCH.equals(tokenType)) {
                    keyInfo.append(token);
                    versions.append(infoSeparator);
                } else if (SearchToken.NAVE_SEARCH.equals(tokenType)) {
                    keyInfo.append(token);
                    versions.append(infoSeparator);
                } else if (SearchToken.NAVE_SEARCH_EXTENDED.equals(tokenType)) {
                    keyInfo.append(token);
                    versions.append(infoSeparator);
                } else if (SearchToken.TEXT_SEARCH.equals(tokenType)) {
                    keyInfo.append(token);
                    versions.append(infoSeparator);
                } else if (SearchToken.STRONG_NUMBER.equals(tokenType)) {
                    final LexiconSuggestion enhancedTokenInfo = (LexiconSuggestion) t.getEnhancedTokenInfo();

                    keyInfo.append(enhancedTokenInfo.getMatchingForm());
                    keyInfo.append(" - ");
                    keyInfo.append(enhancedTokenInfo.getGloss());
                    keyInfo.append(" - ");
                    keyInfo.append(enhancedTokenInfo.getStepTransliteration());
                    keyInfo.append(" - ");
                    keyInfo.append(enhancedTokenInfo.getStrongNumber());
                    keyInfo.append(infoSeparator);
                } else if (SearchToken.MEANINGS.equals(tokenType)) {
                    keyInfo.append(token);
                    keyInfo.append(infoSeparator);
                } else if (SearchToken.TOPIC_BY_REF.equals(tokenType)) {
                    keyInfo.append(token);
                    keyInfo.append(infoSeparator);

                } else if (SearchToken.RELATED_VERSES.equals(tokenType)) {
                    keyInfo.append(token);
                    keyInfo.append(infoSeparator);
                } else if (SearchToken.EXACT_FORM.equals(tokenType)) {
                    keyInfo.append(token);
                    keyInfo.append(infoSeparator);
                } else if (SearchToken.SYNTAX.equals(tokenType)) {
                    keyInfo.append(token);
                    keyInfo.append(infoSeparator);
                }
            }


            if (scope != null) {
                keyInfo.append(scope);
                keyInfo.append(" | ");
            }

            final String trimmedInfo = keyInfo.toString().trim();
            final String relevantInfo = (keyInfo.length() > 2 ? trimmedInfo.substring(0, trimmedInfo.length() - 2) : trimmedInfo).replaceAll("\\|", ",");
            req.setAttribute("description", ResourceBundle.getBundle("ErrorBundle").getString("search_results_for") + " " + relevantInfo);

            try {
                keyInfo.append(ResourceBundle.getBundle("ErrorBundle", clientSessionProvider.get().getLocale()).getString(results.getSearchType().getLanguageSearchKey()));
            } catch (MissingResourceException ex) {
                //swallow
                LOGGER.warn("Missing resource for {}", results.getSearchType().getLanguageSearchKey(), ex);
                keyInfo.append("Search");
            }

            req.setAttribute("title", wrapTitle(keyInfo.toString(), results.getMasterVersion(), null));
            req.setAttribute("canonicalUrl", req.getParameter("q"));
        } catch (Exception ex) {
            //a page with no title is better than no pages
            LOGGER.error("Unable to ascertain meta data", ex);
        }
    }

    /**
     * Returns the title of a passage
     *
     * @param osisWrapper the text already retrieved
     * @return the title
     */

    private void populateMetaPassage(final HttpServletRequest request, final OsisWrapper osisWrapper) {
        try {
            final String preview = this.bible.getPlainTextPreview(osisWrapper.getMasterVersion(), osisWrapper.getOsisId());
            request.setAttribute("title", wrapTitle(osisWrapper.getReference(), osisWrapper.getMasterVersion(), preview));
            request.setAttribute("description", preview);
            request.setAttribute("canonicalUrl", getUrlFragmentForPassage(osisWrapper.getMasterVersion(), osisWrapper.getOsisId()));
        } catch (Exception ex) {
            //a page with no title is better than no pages
            LOGGER.error("Unable to ascertain meta data", ex);
        }
    }

    /**
     * Obtains a URL fragment for a given passage
     *
     * @param version   the master version
     * @param reference the reference
     * @return the url
     */
    private String getUrlFragmentForPassage(final String version, final String reference) {
        final StringBuilder fragment = new StringBuilder(128);

        if (StringUtils.isNotBlank(version)) {
            fragment.append(SearchToken.VERSION).append("=");
            fragment.append(version);
            fragment.append("|");
        }

        if (StringUtils.isNotBlank(reference)) {
            fragment.append(SearchToken.REFERENCE).append("=").append(reference);
        }
        return fragment.toString();
    }

    /**
     * @param keyInfo       key bit of information that should be at the forefront of the URL
     * @param masterVersion
     * @param preview
     * @return
     */
    private String wrapTitle(final String keyInfo, final String masterVersion, final String preview) {
        StringBuilder sb = new StringBuilder();
        sb.append(keyInfo);
        sb.append(" | ");
        sb.append(masterVersion);
        sb.append(" | ");
        sb.append("STEP");
        if (preview != null) {
            sb.append(" | ");
            sb.append(preview);
        }
        return sb.toString();
    }

    private AbstractComplexSearch doSearch(final HttpServletRequest req) {
        AbstractComplexSearch text;
        try {
            text = this.search.masterSearch(
                    req.getParameter("q"),
                    req.getParameter("options"),
                    req.getParameter("display"),
                    req.getParameter("page"),
                    req.getParameter("qFilter"),
                    req.getParameter("sort"),
                    req.getParameter("context"));
        } catch (Exception ex) {
            LOGGER.warn(ex.getMessage(), ex);
            text = getDefaultPassage();
        }
        return text;
    }

    /**
     * Defaults to Matt.1 if can't do anything else
     *
     * @return Matt 1 or something else
     */
    private AbstractComplexSearch getDefaultPassage() {
        AbstractComplexSearch text;
        try {
            text = this.search.masterSearch("reference=Gen.1|version=ESV", "HNV");
        } catch (Exception e) {
            LOGGER.error("Default search failed", e);
            text = new OsisWrapper("", null, new String[]{"en"}, null, "ESV", InterlinearMode.NONE, "");
        }
        return text;
    }
}
