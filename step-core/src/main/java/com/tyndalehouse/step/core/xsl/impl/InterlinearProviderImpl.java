/*******************************************************************************
 * Copyright (c) 2012, Directors of the Tyndale STEP Project
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without 
 * modification, are permitted provided that the following conditions 
 * are met:
 *
 * Redistributions of source code must retain the above copyright 
 * notice, this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright 
 * notice, this list of conditions and the following disclaimer in 
 * the documentation and/or other materials provided with the 
 * distribution.
 * Neither the name of the Tyndale House, Cambridge (www.TyndaleHouse.com)  
 * nor the names of its contributors may be used to endorse or promote 
 * products derived from this software without specific prior written 
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS 
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT 
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS 
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE 
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, 
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, 
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; 
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER 
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT 
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING 
 * IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF 
 * THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
package com.tyndalehouse.step.core.xsl.impl;

import static com.tyndalehouse.step.core.utils.StringConversionUtils.getAnyKey;
import static com.tyndalehouse.step.core.utils.StringUtils.areAnyBlank;
import static com.tyndalehouse.step.core.utils.StringUtils.isBlank;
import static com.tyndalehouse.step.core.utils.StringUtils.split;
import static java.lang.String.format;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tyndalehouse.step.core.service.VocabularyService;
import com.tyndalehouse.step.core.utils.JSwordUtils;
import com.tyndalehouse.step.core.utils.StringConversionUtils;
import org.crosswire.jsword.book.Book;
import org.crosswire.jsword.book.BookData;
import org.crosswire.jsword.book.BookException;
import org.crosswire.jsword.book.BookMetaData;
import org.crosswire.jsword.book.OSISUtil;
import org.crosswire.jsword.passage.*;
import org.crosswire.jsword.versification.Testament;
import org.crosswire.jsword.versification.Versification;
import org.crosswire.jsword.versification.VersificationsMapper;
import org.crosswire.jsword.versification.system.Versifications;
import org.jdom2.Content;
import org.jdom2.Element;
import org.jdom2.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tyndalehouse.step.core.exceptions.StepInternalException;
import com.tyndalehouse.step.core.service.jsword.JSwordVersificationService;
import com.tyndalehouse.step.core.utils.StringUtils;
import com.tyndalehouse.step.core.xsl.InterlinearProvider;

/**
 * This object is not purposed to be used as a singleton. It builds up textual information on initialisation,
 * and is specific to requests. On initialisation, the OSIS XML is retrieved and iterated through to find all
 * strong/morph candidates
 *
 * @author chrisburrell
 */
public class InterlinearProviderImpl implements InterlinearProvider {

    /**
     * The Constant LOGGER.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(InterlinearProviderImpl.class);

    /**
     * contains the set of tags that may contain biblical text, all lower case
     */
    private static final Set<String> VALID_TEXT_ELEMENTS = new HashSet<String>();
    public static final String NO_VERSE = "NO_VERSE";

    /**
     * limited accuracy tries to do a location look up by using the verse number as part of the key.
     */
    private final Map<DualKey<String, String>, Deque<Word>> limitedAccuracy = new HashMap<DualKey<String, String>, Deque<Word>>();
    private final boolean originalLanguage;
    private boolean disabled = false;
    private Versification versification;
    // a temporary, non-thread-safe, transient, working variable, which keeps track of the verse we're in.
    private Verse currentVerse;
    private Book currentBook;
    private Map<String, String> hebrewDirectMapping;
    private Map<String, String> hebrewIndirectMappings;
    private Testament testament;
    private Versification masterVersification;
    private VocabularyService vocabProvider;
    private boolean stripAccents = false;
    private boolean stripVowels = false;

    static {
        VALID_TEXT_ELEMENTS.add("divinename");
        VALID_TEXT_ELEMENTS.add("a");
        VALID_TEXT_ELEMENTS.add("foreign");
        VALID_TEXT_ELEMENTS.add("hi");
        VALID_TEXT_ELEMENTS.add("name");
        VALID_TEXT_ELEMENTS.add("q");
        VALID_TEXT_ELEMENTS.add("w");
        VALID_TEXT_ELEMENTS.add("seg");
        VALID_TEXT_ELEMENTS.add("transChange");
    }

    /**
     * sets up the interlinear provider with the correct version and text scope.
     *
     * @param versificationService   versification service
     * @param version                the version to use to set up the interlinear
     * @param versifiedKey           the text scope reference, defining the bounds of the lookup
     * @param hebrewDirectMapping    the hebrew overriding mappings
     * @param hebrewIndirectMappings the mappings used if no other mapping is found
     */
    public InterlinearProviderImpl(Versification masterVersification, JSwordVersificationService versificationService,
                                   final String version, final Key versifiedKey, final Map<String, String> hebrewDirectMapping,
                                   final Map<String, String> hebrewIndirectMappings, final VocabularyService vocabProvider,
                                   boolean stripGreekAccents, boolean stripHebrewAccents, boolean stripVowels) {
        this.masterVersification = masterVersification;
        this.vocabProvider = vocabProvider;

        // first check whether the values passed in are correct
        if (areAnyBlank(version)) {
            this.originalLanguage = false;
            return;
        }

        this.hebrewIndirectMappings = hebrewIndirectMappings;
        this.hebrewDirectMapping = hebrewDirectMapping;
        this.currentBook = versificationService.getBookFromVersion(version);
        this.versification = versificationService.getVersificationForVersion(currentBook);
        if (this.currentBook == null) {
            throw new StepInternalException(format("Couldn't look up book: [%s]", version));
        }

        //mark the book as original language
        this.originalLanguage = JSwordUtils.isAncientBook(currentBook);
        final boolean ancientHebrewBook = JSwordUtils.isAncientHebrewBook(currentBook);
        this.stripAccents = stripGreekAccents && JSwordUtils.isAncientGreekBook(currentBook) ||
                stripHebrewAccents && ancientHebrewBook;
        this.stripVowels = ancientHebrewBook && this.stripAccents && stripVowels;

        BookData bookData;
        try {
            setTestamentType(versifiedKey);

            bookData = getBookDataWithVerse0(versifiedKey);
            scanForTextualInformation(bookData.getOsisFragment());
        } catch (final BookException e) {
            throw new StepInternalException(e.getMessage(), e);
        }

        this.disabled = this.limitedAccuracy.size() == 0;
    }

    /**
     * For verse 0, we can't simply lookup verse 0, because that doesn't return the pre-verse content which sits in verse 1
     * so instead we need to replace the key to have verse 1 instead. For all purposes, such as verses 0-1, or verse 1, etc.
     * then we continue as normal.
     *
     * @param versifiedKey the key from the original versification
     * @return a bookdata with the correct verse
     */
    private BookData getBookDataWithVerse0(final Key versifiedKey) {
        final Iterator<Key> iterator = versifiedKey.iterator();
        Verse v = (Verse) iterator.next();
        if (v != null && !iterator.hasNext()) {
            //then we're basically looking at a single verse... in this special case
            // we need to check it doesn't not map to verse 0.
            //if it did, we will need to return verse with 1.
            final VerseKey mappedVerse = VersificationsMapper.instance().mapVerse(v, this.versification);
            if (mappedVerse.getCardinality() == 1) {
                final Verse next = (Verse) mappedVerse.iterator().next();
                if (next.getVerse() == 0) {
                    return new BookData(this.currentBook,
                            new Verse(this.versification, next.getBook(), next.getChapter(), next.getVerse() + 1));
                }
            }
        }

        return new BookData(this.currentBook, versifiedKey);
    }

    /**
     * package private version for testing purposes.
     */
    InterlinearProviderImpl() {
        // exposing package private constructor
        this.originalLanguage = false;
    }

    @Override
    public String getWord(final String verseNumber, final String strong, final String morph) {
        // we use a linked hashset, because we want the behaviour of a set while we add to it,
        // but at the end, we will want to return the elements in order
        LOGGER.trace("Retrieving word for verse [{}], strong [{}], morph [{}]", verseNumber,
                strong, morph);

        final Set<String> results = new LinkedHashSet<String>();
        if (isBlank(strong)) {
            // we might as well return, as we have no information to go on
            return "";
        }

        // the keys passed in may have multiple references and morphologies, therefore, we need to lookup
        // multiple items.
        final String[] strongs = StringUtils.split(strong);

        //create the versified key, and convert to the bit we want
        Key key = null;
        try {
            if (verseNumber != null) {
                final Verse inputVerse = VerseFactory.fromString(this.masterVersification, verseNumber);
                key = VersificationsMapper.instance().mapVerse(inputVerse, this.versification);
            }
        } catch (NoSuchVerseException e) {
            LOGGER.error(e.getMessage(), e);
            return "";
        }

        // There are at most strongs.length words, and we might have morphological data to help
        for (final String s : strongs) {

            // find corresponding strong:
            LOGGER.debug("Finding strong key [{}]", s);
            final String strongKey = getAnyKey(s);

            results.add(getWord(key, strongKey));
        }

        return convertToString(results);
    }

    /**
     * Takes a set, and outputs the strings concatenated together (and separated by a space.
     *
     * @param results the results that should be converted to a string
     * @return a String containing results to be displayed
     */
    private String convertToString(final Set<String> results) {
        final Iterator<String> iterator = results.iterator();
        final StringBuilder sb = new StringBuilder(results.size() * 16);

        // add the first word without a space
        if (iterator.hasNext()) {
            sb.append(iterator.next());
        }

        // add spaces between each element now
        while (iterator.hasNext()) {
            sb.append(' ');
            sb.append(iterator.next());
        }

        String actualText = sb.toString();

        if (stripVowels) {
            return StringConversionUtils.unAccent(actualText);
        } else if (stripAccents) {
            return StringConversionUtils.unAccentLeavingVowels(actualText);
        } else {
            return actualText;
        }
    }

    /**
     * returns words based on strong and verse number only.
     *
     * @param equivalentVerses the verse number
     * @param strong           the strong reference
     * @return a word that matches or the empty string
     */
    String getWord(final Key equivalentVerses, final String strong) {
        if (strong != null && equivalentVerses != null) {

            //Key may be made up of several keys
            Iterator<Key> keyIterator = equivalentVerses.iterator();
            while (keyIterator.hasNext()) {
                Verse v = (Verse) keyIterator.next();
                String osisID = v.getVerse() == 0 ? NO_VERSE : v.getOsisID();
                
                final DualKey<String, String> key = new DualKey<String, String>(strong, osisID);
                final Deque<Word> list = this.limitedAccuracy.get(key);
                if (list != null && !list.isEmpty()) {
                    return retrieveWord(list);
                }
            }
            return lookupMappings(strong);
        } else if(strong != null) {
            //then we know we have a null verse, so assume we're in pre-verse mode...
            final DualKey<String, String> key = new DualKey<String, String>(strong, NO_VERSE);
            final Deque<Word> list = this.limitedAccuracy.get(key);
            if (list != null && !list.isEmpty()) {
                return retrieveWord(list);
            }            
        }

        // it is important to return an empty string here
        return "";
    }

    /**
     * Lookup mappings, if the strong number is there, then it is used
     *
     * @param strong the strong
     * @return the string
     */
    private String lookupMappings(final String strong) {
        // we ignore mapping lookups for anything greek or hebrew...
        if (originalLanguage) {
            return "";
        }

        // currently only supporting OLD Testament
        final boolean isOT = this.testament == Testament.OLD;
        if (isOT) {
            final String direct = this.hebrewDirectMapping.get(strong);
            if (direct != null) {
                return direct;
            }

            final String indirect = this.hebrewIndirectMappings.get(strong);
            if (indirect != null) {
                return indirect;
            }
        }

        //else look up from vocab provider
        String englishVocab = this.vocabProvider.getEnglishVocab(isOT ? 'H' + strong : 'G' + strong);
        if (englishVocab != null) {
            return "#" + englishVocab;
        }

        return "";
    }

    /**
     * Retrieves the first word from the list, and removes from the list. If the word is PARTIAL, then
     * retrieves the next one too, and concatenates
     *
     * @param list a dequue containing all the items in question
     * @return the string
     */
    private String retrieveWord(final Deque<Word> list) {
        Word word = list.removeFirst();
        if (!word.isPartial()) {
            return word.getText();
        }

        final StringBuilder text = new StringBuilder(32);
        while (word != null && word.isPartial()) {
            text.append(word.getText());
            text.append(", ");

            // increment to next word
            word = list.pollFirst();
        }

        // append the last word
        if (word != null) {
            text.append(word.getText());
        }
        return text.toString();
    }

    /**
     * setups all the initial textual information for fast retrieval during XSL transformation.
     *
     * @param element element to start with.
     */
    @SuppressWarnings("unchecked")
    private void scanForTextualInformation(final Element element) {
        // check to see if we've hit a new verse, if so, we update the verse
        updateVerseRef(element);

        // check to see if we've hit a node of interest
        if (element.getName().equals(OSISUtil.OSIS_ELEMENT_W)) {
            extractTextualInfoFromNode(element);
            return;
        }

        // iterate through all children and call recursively
        Object data;
        Element ele;
        final Iterator<Content> contentIter = element.getContent().iterator();
        while (contentIter.hasNext()) {
            data = contentIter.next();
            if (data instanceof Element) {
                ele = (Element) data;
                scanForTextualInformation(ele);
            }
        }
    }

    /**
     * Gets the OSIS id if any
     *
     * @param element the osis element
     */
    private void updateVerseRef(final Element element) {
        final boolean isVerseMarker = OSISUtil.OSIS_ELEMENT_VERSE.equals(element.getName());
        if (isVerseMarker) {
            final String osisId = element.getAttributeValue(OSISUtil.OSIS_ATTR_OSISID);
            if (osisId != null)
                try {
                    currentVerse = VerseFactory.fromString(this.versification, osisId);
                } catch (NoSuchVerseException ex) {
                    LOGGER.trace("Unable to convert ref - probably not a verse reference.", ex);
                }
        }
    }

    /**
     * retrieves textual information and adds it to the provider.
     *
     * @param element the element to extract information from
     */
    private void extractTextualInfoFromNode(final Element element) {
        final String strong = element.getAttributeValue(OSISUtil.ATTRIBUTE_W_LEMMA);
        final String word = getText(element);

        // do we need to do any manipulation? probably not because we are going to be
        // comparing against other OSIS XML texts which should be formatted in the same way!
        // however, some attributes may contain multiple strongs and morphs tagged to one word.
        // therefore we do need to split the text.
        final String[] strongs = split(strong);

        if (strongs == null) {
            return;
        }

        // there is no way of know which strong goes with which morph, and we only
        // have one phrase anyway
        final List<Word> words = new ArrayList<Word>(2);
        boolean partial = false;
        for (int ii = 0; ii < strongs.length; ii++) {
            final String strongKey = getAnyKey(strongs[ii]);
            if (!isH00(strongKey) && !blacklisted(strongKey)) {
                words.add(addTextualInfo(currentVerse, strongKey, word));
            } else {
                partial = true;
            }
        }

        if (partial) {
            for (final Word w : words) {
                w.setPartial(true);
            }
        }
    }

    /**
     * Gets the text of the element and its children
     *
     * @param element the element
     * @return the text
     */
    private String getText(final Element element) {
        // can contain <a> and <seg>, both of which we need to output
        final StringBuilder sb = new StringBuilder(32);
        getTextRecurively(sb, element);
        return sb.toString();
    }

    /**
     * Gets the text recurively.
     *
     * @param sb      the sb
     * @param content the content
     */
    private void getTextRecurively(final StringBuilder sb, final Content content) {
        if (content instanceof Text) {
            sb.append(((Text) content).getText());
            return;
        }

        if (content instanceof Element) {
            // iterate through all children
            final Element element = (Element) content;
            // we only consider some elements
            if (!VALID_TEXT_ELEMENTS.contains(element.getName().toLowerCase())) {
                return;
            }

            final List<Content> children = element.getContent();
            for (final Content c : children) {
                getTextRecurively(sb, c);
            }
        }
    }

    /**
     * Blacklisted, if the word is contained in a direct mapping for the relevant testament
     *
     * @param strongKey the strong key
     * @return true, if successful
     */
    private boolean blacklisted(final String strongKey) {
        return this.testament == Testament.OLD && this.hebrewDirectMapping.containsKey(strongKey);
    }

    /**
     * Checks if is h00.
     *
     * @param currentStrong a strong number
     * @return true, if is a single H followed by only 0s, which indicates that the strong numbers go with
     * their next occurrence
     */
    private boolean isH00(final String currentStrong) {
        for (int ii = 0; ii < currentStrong.length(); ii++) {
            if (currentStrong.charAt(ii) != '0') {
                return false;
            }
        }

        return true;
    }

    /**
     * Finally, we have some information to add to this provider. We try and add it in an efficient fashion.
     * <p/>
     * So, how do we store this? The most meaningful piece of data is a STRONG number, since it identifies the
     * word that we want to retrieve. Without the strong number, we don't have any information at all.
     * Therefore, the first level of lookup should be by Strong number.
     * <p/>
     * Made package private for testing purposes only.
     *
     * @param verseReference the verse reference that specifies locality (least important factor)
     * @param strongKey      the strong number (identifies the root/meaning of the word)
     * @param word           the word to be stored
     * @return the word that has been added
     */
    Word addTextualInfo(final Verse verseReference, final String strongKey, final String word) {
        final DualKey<String, String> strongVerseKey = new DualKey<String, String>(strongKey, verseReference == null ? NO_VERSE : verseReference.getOsisIDNoSubIdentifier());
        Deque<Word> verseKeyedStrongs = this.limitedAccuracy.get(strongVerseKey);
        if (verseKeyedStrongs == null) {
            verseKeyedStrongs = new LinkedList<Word>();
            this.limitedAccuracy.put(strongVerseKey, verseKeyedStrongs);
        }
        final Word w = new Word(word);
        verseKeyedStrongs.add(w);
        return w;
    }

    /**
     * Sets the testament, to be used to determine the indirect/direct mappings to use when generating the
     * interlinear.
     *
     * @param key the key to the passage being looked up
     */
    private void setTestamentType(final Key key) {
        final Versification v11n = Versifications.instance().getVersification(
                (String) this.currentBook.getBookMetaData().getProperty(BookMetaData.KEY_VERSIFICATION));
        final Passage passage = KeyUtil.getPassage(key);
        this.testament = v11n.getTestament(v11n.getOrdinal(passage.getVerseAt(0)));
    }

    /**
     * @param currentBook the currentBook to set
     */
    void setCurrentBook(final Book currentBook) {
        this.currentBook = currentBook;
    }

    /**
     * @param vocabService sets the vocab service
     */
    void setVocabProvider(final VocabularyService vocabService) {
        this.vocabProvider = vocabService;
    }

    @Override
    public boolean isDisabled() {
        return disabled;
    }
}
