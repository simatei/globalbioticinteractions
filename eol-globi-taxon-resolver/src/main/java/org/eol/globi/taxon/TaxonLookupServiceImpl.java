package org.eol.globi.taxon;

import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Fieldable;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.eol.globi.domain.Taxon;
import org.eol.globi.domain.TaxonImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class TaxonLookupServiceImpl implements TaxonLookupService, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(TaxonLookupServiceImpl.class);

    private Directory indexDir;
    private IndexSearcher indexSearcher;
    private int maxHits = Integer.MAX_VALUE;

    public TaxonLookupServiceImpl(Directory indexDir) {
        this.indexDir = indexDir;
    }

    @Override
    public Taxon[] lookupTermsByName(String taxonName) throws IOException {
        return findTaxon(TaxonLookupServiceConstants.FIELD_NAME, taxonName);
    }

    @Override
    public Taxon[] lookupTermsById(String taxonId) throws IOException {
        return findTaxon(TaxonLookupServiceConstants.FIELD_ID, taxonId);
    }

    private Taxon[] findTaxon(String fieldName1, String fieldValue) throws IOException {
        if (indexSearcher == null) {
            indexSearcher = new IndexSearcher(IndexReader.open(indexDir));
        }

        Taxon[] terms = new TaxonImpl[0];
        if (StringUtils.isNotBlank(fieldValue) && indexSearcher != null) {
            PhraseQuery query = new PhraseQuery();
            query.add(new Term(fieldName1, fieldValue));
            TopDocs docs = indexSearcher.search(query, getMaxHits());

            if (docs.totalHits > 0) {
                int maxResults = Math.min(docs.totalHits, getMaxHits());
                terms = new TaxonImpl[maxResults];
                for (int i = 0; i < maxResults; i++) {
                    ScoreDoc scoreDoc = docs.scoreDocs[i];
                    Document foundDoc = indexSearcher.doc(scoreDoc.doc);
                    Taxon term = new TaxonImpl();
                    Fieldable idField = foundDoc.getFieldable(TaxonLookupServiceConstants.FIELD_ID);
                    if (idField != null) {
                        term.setExternalId(idField.stringValue());
                    }
                    Fieldable rankPathField = foundDoc.getFieldable(TaxonLookupServiceConstants.FIELD_RANK_PATH);
                    if (rankPathField != null) {
                        term.setPath(rankPathField.stringValue());
                    }
                    Fieldable rankPathIdsField = foundDoc.getFieldable(TaxonLookupServiceConstants.FIELD_RANK_PATH_IDS);
                    if (rankPathIdsField != null) {
                        term.setPathIds(rankPathIdsField.stringValue());
                    }
                    Fieldable rankPathNamesField = foundDoc.getFieldable(TaxonLookupServiceConstants.FIELD_RANK_PATH_NAMES);
                    if (rankPathNamesField != null) {
                        term.setPathNames(rankPathNamesField.stringValue());
                    }
                    Fieldable commonNamesFields = foundDoc.getFieldable(TaxonLookupServiceConstants.FIELD_COMMON_NAMES);
                    if (commonNamesFields != null) {
                        term.setCommonNames(commonNamesFields.stringValue());
                    }
                    Fieldable fieldName = foundDoc.getFieldable(TaxonLookupServiceConstants.FIELD_RECOMMENDED_NAME);
                    if (fieldName != null) {
                        term.setName(fieldName.stringValue());
                    }
                    Fieldable fieldRank = foundDoc.getFieldable(TaxonLookupServiceConstants.FIELD_RANK);
                    if (fieldRank != null) {
                        term.setRank(fieldRank.stringValue());
                    }
                    terms[i] = term;
                }
            }
        }
        return terms;
    }

    public int getMaxHits() {
        return maxHits;
    }

    public void setMaxHits(int maxHits) {
        this.maxHits = maxHits;
    }

    @Override
    public void close() throws IOException {
        if (indexSearcher != null) {
            indexSearcher.close();
            indexSearcher = null;
        }
        if (indexDir != null) {
            indexDir.close();
        }
    }
}
