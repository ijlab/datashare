package org.icij.datashare.tasks;

import com.google.inject.Inject;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Channel;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Callable;

import static java.lang.String.valueOf;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.text.nlp.Pipeline.Type.parseAll;

public class ResumeNerTask implements Callable<Integer> {
    Logger logger = LoggerFactory.getLogger(getClass());
    private final Pipeline.Type[] nlpPipelines;
    private final Publisher publisher;
    private final Indexer indexer;

    @Inject
    public ResumeNerTask(final Publisher publisher, final Indexer indexer, final PropertiesProvider propertiesProvider) {
        this.publisher = publisher;
        this.indexer = indexer;
        this.nlpPipelines = parseAll(propertiesProvider.getProperties().getProperty("nlpPipelines"));
    }

    @Override
    public Integer call() {
        List<? extends Entity> docsToProcess =
                indexer.search(Document.class).withSource("rootDocument").without(nlpPipelines).execute().collect(toList());
        this.publisher.publish(Channel.NLP, new Message(Message.Type.INIT_MONITORING).add(Message.Field.VALUE, valueOf(docsToProcess.size())));
        docsToProcess.forEach(doc -> this.publisher.publish(Channel.NLP,
                        new Message(Message.Type.EXTRACT_NLP)
                                .add(Message.Field.DOC_ID, doc.getId())
                                .add(Message.Field.R_ID, ((Document)doc).getRootDocument())));
        logger.info("sent {} message for {} files without {} pipeline tags", Message.Type.EXTRACT_NLP, docsToProcess.size(), nlpPipelines);
        return docsToProcess.size();
    }
}
