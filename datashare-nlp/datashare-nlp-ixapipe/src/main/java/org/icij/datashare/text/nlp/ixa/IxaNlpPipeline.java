package org.icij.datashare.text.nlp.ixa;

import java.io.*;
import java.util.*;
import java.util.function.Function;
import static java.util.Arrays.asList;

import com.google.common.io.Files;

import ixa.kaflib.*;

import org.icij.datashare.text.Language;
import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.nlp.NlpStage.*;
import org.icij.datashare.text.nlp.AbstractNlpPipeline;
import org.icij.datashare.text.nlp.Annotation;
import org.icij.datashare.text.nlp.NlpStage;
import org.icij.datashare.text.nlp.ixa.models.IxaModels;


/**
 * {@link org.icij.datashare.text.nlp.NlpPipeline}
 * {@link org.icij.datashare.text.nlp.AbstractNlpPipeline}
 * {@link Type#IXA}
 *
 * <a href="http://ixa2.si.ehu.es/ixa-pipes">Ixa Pipes</a>
 * <a href="https://github.com/ixa-ehu/ixa-pipe-tok">Ixa Pipe Tok</a>
 * <a href="https://github.com/ixa-ehu/ixa-pipe-pos">Ixa Pipe Pos</a>
 * <a href="https://github.com/ixa-ehu/ixa-pipe-nerc">Ixa Pipe Nerc</a>
 *
 * Created by julien on 9/22/16.
 */
public class IxaNlpPipeline extends AbstractNlpPipeline {

    private static final Map<Language, Set<NlpStage>> SUPPORTED_STAGES =
            new HashMap<Language, Set<NlpStage>>(){{
                put(ENGLISH, new HashSet<>(asList(TOKEN, POS, NER)));
                put(SPANISH, new HashSet<>(asList(TOKEN, POS, NER)));
                put(FRENCH,  new HashSet<>(asList(TOKEN, POS)));
                put(GERMAN,  new HashSet<>(asList(TOKEN, POS, NER)));
                put(DUTCH,   new HashSet<>(asList(TOKEN, POS, NER)));
                put(ITALIAN, new HashSet<>(asList(TOKEN, POS, NER)));
                put(BASQUE,  new HashSet<>(asList(TOKEN, POS, NER)));
            }};

    private static final String VERSION_TOK = "1.8.5";
    private static final String VERSION_POS = "1.5.1";
    private static final String VERSION_NER = "1.6.1";

    private static final String  KAF_VERSION            = "v1.naf";
    private static final String  DEFAULT_NORMALIZE      = "default"; // alpino, ancora, ctag, default, ptb, tiger, tutpenn
    private static final String  DEFAULT_UNTOKENIZABLE  = "no";      // yes, no
    private static final String  DEFAULT_HARD_PARAGRAPH = "no";      // yes, no
    private static final boolean DEFAULT_MULTIWORDS     = false;
    private static final boolean DEFAULT_DICTAG         = false;


    // Part-of-Speech annotators
    private Map<Language, eus.ixa.ixa.pipe.pos.Annotate> posTagger;

    // Named Entity Recognition annotators
    private Map<Language, eus.ixa.ixa.pipe.nerc.Annotate> nerFinder;

    // Annotator loading functions (per NlpStage)
    private final Map<NlpStage, Function<Language, Boolean>> annotatorLoader;


    public IxaNlpPipeline(Properties properties) {
        super(properties);

        // TOKEN <-- POS <-- NER
        stageDependencies.get(POS).add(TOKEN);
        stageDependencies.get(NER).add(POS);

        annotatorLoader = new HashMap<NlpStage, Function<Language, Boolean>>(){{
            put(POS, IxaNlpPipeline.this::loadPosTagger);
            put(NER, IxaNlpPipeline.this::loadNameFinder);
        }};

        posTagger = new HashMap<>();
        nerFinder = new HashMap<>();
    }


    @Override
    public Map<Language, Set<NlpStage>> supportedStages() {
        return SUPPORTED_STAGES;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean initialize(Language language) {
        if ( ! super.initialize(language)) {
            return false;
        }
        asList(POS, NER)
                .forEach( stage -> annotatorLoader.get(stage).apply(language) );
        return true;
    }

    @Override
    protected Optional<Annotation> process(String input, String hash, Language language) {
        Annotation annotation = new Annotation(hash, getType(), language);
        // KAF document annotated by IXA annotators
        KAFDocument kafDocument = new KAFDocument(language.toString(), KAF_VERSION);

        // Tokenize input
        LOGGER.info("Tokenizing" + " - " + Thread.currentThread().getName());
        ByteArrayInputStream inputStream = new ByteArrayInputStream(input.getBytes(getEncoding()));
        boolean tokenized = tokenize(inputStream, kafDocument, hash, language);
        if ( ! tokenized) {
            LOGGER.error("Failed tokenizing - " + Thread.currentThread().getName());
            return Optional.empty();
        }

        // Pos-tag input
        LOGGER.info("Pos-tagging" + " - " + Thread.currentThread().getName());
        boolean postagged  = postag(kafDocument, hash, language);
        if ( ! postagged) {
            LOGGER.error("Failed Pos-tagging - " + Thread.currentThread().getName());
            return Optional.of(annotation);
        }

        // Feed annotation
        for (int s = kafDocument.getFirstSentence(); s <= kafDocument.getNumSentences(); s++) {
            List<Term> sentenceTerms = kafDocument.getSentenceTerms(s);
            for(Term term : sentenceTerms) {
                WF wfBegin = term.getWFs().get(0);
                WF wfEnd   = term.getWFs().get(term.getWFs().size()-1);
                int tokenBegin = wfBegin.getOffset();
                int tokenEnd   = wfEnd.getOffset() + wfEnd.getLength();
                annotation.add(TOKEN, tokenBegin, tokenEnd);
                if (targetStages.contains(POS)) {
                    String posTag = term.getPos();
                    annotation.add(POS, tokenBegin, tokenEnd, posTag);
                }
            }
            Term termBegin     = sentenceTerms.get(0);
            Term termEnd       = sentenceTerms.get(sentenceTerms.size() - 1);
            WF   wfBegin       = termBegin.getWFs().get(0);
            WF   wfEnd         = termEnd.getWFs().get(termEnd.getWFs().size() - 1);
            int  sentenceBegin = wfBegin.getOffset();
            int  sentenceEnd   = wfEnd.getOffset() + wfEnd.getLength();
            annotation.add(SENTENCE, sentenceBegin, sentenceEnd);
        }

        // Ner input
        if (targetStages.contains(NER)) {
            LOGGER.info("Name-finding" + " - " + Thread.currentThread().getName());
            boolean nerred = recognize(kafDocument, hash, language);
            if ( ! nerred) {
                LOGGER.error("Failed Name-finding - " + Thread.currentThread().getName());
                return Optional.of(annotation);
            }
            // Feed annotation
            for (Entity entity : kafDocument.getEntities()) {
                List<Term> terms     = entity.getTerms();
                Term       termBegin = terms.get(0);
                Term       termEnd   = terms.get(terms.size() - 1);
                WF         wfBegin   = termBegin.getWFs().get(0);
                WF         wfEnd     = termEnd.getWFs().get(termEnd.getWFs().size() - 1);
                String     cat       = entity.getType();
                int        nerBegin  = wfBegin.getOffset();
                int        nerEnd    = wfEnd.getOffset() + wfEnd.getLength();
                annotation.add(NER, nerBegin, nerEnd, cat);
            }
        }

        return Optional.of(annotation);
    }


    public boolean tokenize(InputStream inputStream, KAFDocument kafDocument, String hash, Language language) {
        String lang = language.toString();
        Properties properties = tokenAnnotatorProperties(lang, DEFAULT_NORMALIZE, DEFAULT_UNTOKENIZABLE, DEFAULT_HARD_PARAGRAPH);
        try {
            KAFDocument.LinguisticProcessor newLp = kafDocument.addLinguisticProcessor("text", "ixa-pipe-tok-" + lang, VERSION_TOK);
            // Tokenize input stream
            try (BufferedReader buffReader = new BufferedReader(new InputStreamReader(inputStream, getEncoding()))) {
                eus.ixa.ixa.pipe.tok.Annotate annotator = new eus.ixa.ixa.pipe.tok.Annotate(buffReader, properties);
                newLp.setBeginTimestamp();
                annotator.tokenizeToKAF(kafDocument);
                newLp.setEndTimestamp();
                return true;
            }
        } catch (IOException e) {
            LOGGER.error("Failed IO operation", e);
            return false;
        }
    }

    private Properties tokenAnnotatorProperties(final String lang, final String normalize, final String untokenizable, final String hardParagraph) {
        final Properties annotateProperties = new Properties();
        annotateProperties.setProperty("language",      lang);
        annotateProperties.setProperty("normalize",     normalize);
        annotateProperties.setProperty("untokenizable", untokenizable);
        annotateProperties.setProperty("hardParagraph", hardParagraph);
        return annotateProperties;
    }


    /**
     * Load pos tagger (language-specific)
     *
     * @return true if successfully loaded; false otherwise
     */
    private boolean loadPosTagger(Language language) {
        if ( posTagger.containsKey(language) ) {
            return true;
        }
        try {
            LOGGER.info("Loading POS annotator for " + language.toString().toUpperCase() + " - " + Thread.currentThread().getName());
            String     lang       = language.toString();
            String     model      = IxaModels.PATH.get(POS).get(language).toString();
            String     lemmaModel = IxaModels.PATH.get(LEMMA).get(language).toString();
            String     dictag     = Boolean.toString(DEFAULT_DICTAG);
            String     multiwords = Boolean.toString(DEFAULT_MULTIWORDS);
            if(asList(SPANISH, GALICIAN).contains(language)) {
                dictag     = Boolean.toString(true);
                multiwords = Boolean.toString(true);
            }
            Properties properties = posAnnotatorProperties(model, lemmaModel, lang, multiwords, dictag);
            posTagger.put(language, new eus.ixa.ixa.pipe.pos.Annotate(properties));

        } catch (IOException e) {
            return false;
        }
        return true;
    }

    private boolean postag(KAFDocument kafDocument, String hash, Language language) {
        String  model     = IxaModels.PATH.get(POS).get(language).toString();
        String  modelName = Files.getNameWithoutExtension(model);
        KAFDocument.LinguisticProcessor newLp = kafDocument.addLinguisticProcessor("terms", "ixa-pipe-pos-" + modelName, VERSION_POS);
        newLp.setBeginTimestamp();
        posTagger.get(language).annotatePOSToKAF(kafDocument);
        newLp.setEndTimestamp();
        return true;
    }

    private Properties posAnnotatorProperties(String model, String lemmatizerModel, String language, String multiwords, String dictag) {
        final Properties annotateProperties = new Properties();
        annotateProperties.setProperty("model", model);
        annotateProperties.setProperty("lemmatizerModel", lemmatizerModel);
        annotateProperties.setProperty("language", language);
        annotateProperties.setProperty("multiwords", multiwords);
        annotateProperties.setProperty("dictag", dictag);
        return annotateProperties;
    }


    /**
     * Load ner finder (language-specific)
     *
     * @return true if successfully loaded; false otherwise
     */
    private boolean loadNameFinder(Language language) {
        if (nerFinder.containsKey(language)) {
            return true;
        }
        try {
            LOGGER.info("Loading NER annotator for " + language.toString().toUpperCase() + " - " + Thread.currentThread().getName());
            String     lang          = language.toString();
            String     model         = IxaModels.PATH.get(NER).get(language).toString();
            String     lexer         = eus.ixa.ixa.pipe.nerc.train.Flags.DEFAULT_LEXER;
            String     dictTag       = eus.ixa.ixa.pipe.nerc.train.Flags.DEFAULT_DICT_OPTION;
            String     dictPath      = eus.ixa.ixa.pipe.nerc.train.Flags.DEFAULT_DICT_PATH;
            String     clearFeatures = eus.ixa.ixa.pipe.nerc.train.Flags.DEFAULT_FEATURE_FLAG;
            Properties properties    = nerAnnotatorProperties(model, lang, lexer, dictTag, dictPath, clearFeatures);
            nerFinder.put(language, new eus.ixa.ixa.pipe.nerc.Annotate(properties));

        } catch (IOException e) {
            return false;
        }
        return true;
    }

    private boolean recognize(KAFDocument kafDocument, String hash, Language language) {
        String model     = IxaModels.PATH.get(NER).get(language).toString();
        String modelName = Files.getNameWithoutExtension(model);
        KAFDocument.LinguisticProcessor newLp = kafDocument.addLinguisticProcessor("entities", "ixa-pipe-nerc-" + modelName, VERSION_NER);
        try {
            // Recognize named entities
            newLp.setBeginTimestamp();
            nerFinder.get(language).annotateNEs(kafDocument);
            newLp.setEndTimestamp();
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    private Properties nerAnnotatorProperties(String model, String language, String lexer, String dictTag, String dictPath, String clearFeatures) {
        Properties annotateProperties = new Properties();
        annotateProperties.setProperty("model", model);
        annotateProperties.setProperty("language", language);
        annotateProperties.setProperty("ruleBasedOption", lexer);
        annotateProperties.setProperty("dictTag", dictTag);
        annotateProperties.setProperty("dictPath", dictPath);
        annotateProperties.setProperty("clearFeatures", clearFeatures);
        return annotateProperties;
    }

    @Override
    public Optional<String> getPosTagSet() {
        return Optional.empty();
    }

}