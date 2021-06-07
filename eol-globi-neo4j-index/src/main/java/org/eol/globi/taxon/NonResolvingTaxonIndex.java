package org.eol.globi.taxon;

import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.queryParser.QueryParser;
import org.eol.globi.data.NodeFactoryException;
import org.eol.globi.data.TaxonIndex;
import org.eol.globi.domain.PropertyAndValueDictionary;
import org.eol.globi.domain.Taxon;
import org.eol.globi.domain.TaxonNode;
import org.eol.globi.service.QueryUtil;
import org.eol.globi.service.TaxonUtil;
import org.eol.globi.util.NodeUtil;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.index.IndexHits;

import java.util.Map;
import java.util.function.Predicate;

public class NonResolvingTaxonIndex implements TaxonIndex {
    private final GraphDatabaseService graphDbService;
    private final Index<Node> taxons;

    private static final String[] RANKS = new String[]{"kingdom", "phylum", "class", "order", "family", "genus", "species"};

    public NonResolvingTaxonIndex(GraphDatabaseService graphDbService) {
        this.graphDbService = graphDbService;
        this.taxons = NodeUtil.forNodes(graphDbService, "taxons");
    }

    @Override
    public TaxonNode getOrCreateTaxon(Taxon taxon) throws NodeFactoryException {
        return taxon == null ? null : doGetOrIndexTaxon(taxon);
    }

    private TaxonNode doGetOrIndexTaxon(Taxon taxon) throws NodeFactoryException {
        TaxonNode taxonNode = findTaxon(taxon);
        if (taxonNode == null) {
            taxonNode = TaxonUtil.isResolved(taxon)
                    ? createAndIndexTaxon(taxon, taxon)
                    : addNoMatchTaxon(taxon);
        }
        return taxonNode == null ? createAndIndexTaxon(taxon, taxon) : taxonNode;
    }

    @Override
    public TaxonNode findTaxonById(String externalId) {
        return findTaxonByKey(PropertyAndValueDictionary.EXTERNAL_ID, externalId, subj -> true);
    }

    @Override
    public TaxonNode findTaxonByName(String name) throws NodeFactoryException {
        return findTaxonByKey(PropertyAndValueDictionary.NAME, name, subj -> true);
    }

    private TaxonNode findTaxonByKey(String key, String value, Predicate<Taxon> selector) {
        TaxonNode firstMatchingTaxon = null;
        if (StringUtils.isNotBlank(value)) {
            String query = key + ":\"" + QueryParser.escape(value) + "\"";
            try (Transaction transaction = graphDbService.beginTx()) {
                IndexHits<Node> matchingTaxa = taxons.query(query);
                Node matchingTaxon;
                while (matchingTaxa.hasNext()) {
                    matchingTaxon = matchingTaxa.next();
                    if (matchingTaxon != null) {
                        TaxonNode taxonCandidate = new TaxonNode(matchingTaxon);
                        if (selector.test(taxonCandidate)) {
                            firstMatchingTaxon = taxonCandidate;
                            break;
                        }
                    }
                }
                matchingTaxa.close();
                transaction.success();
            }
        }
        return firstMatchingTaxon;
    }

    TaxonNode findTaxon(Taxon taxon) throws NodeFactoryException {
        TaxonNode taxon1 = null;
        if (taxon != null) {
            String externalId = taxon.getExternalId();
            if (StringUtils.isBlank(externalId)) {
                String name = taxon.getName();
                if (StringUtils.length(name) > 1) {
                    taxon1 = findTaxonByKey(PropertyAndValueDictionary.NAME, name, new ExcludeHomonyms(taxon));
                }
            } else {
                taxon1 = findTaxonByKey(PropertyAndValueDictionary.EXTERNAL_ID, externalId, new ExcludeHomonyms(taxon));
            }
        }
        return taxon1;
    }

    private void indexOriginalNameForTaxon(String name, Taxon taxon, TaxonNode taxonNode) throws NodeFactoryException {
        if (!StringUtils.equals(taxon.getName(), name)) {
            if (isNonEmptyTaxonNameOrId(name)) {
                if (findTaxonByName(name) == null) {
                    indexTaxonByProperty(taxonNode, PropertyAndValueDictionary.NAME, name);
                }
            }
        }
    }

    private boolean isNonEmptyTaxonNameOrId(String name) {
        return StringUtils.isNotBlank(name)
                && !StringUtils.equals(PropertyAndValueDictionary.NO_MATCH, name)
                && !StringUtils.equals(PropertyAndValueDictionary.AMBIGUOUS_MATCH, name)
                && !StringUtils.equals(PropertyAndValueDictionary.NO_NAME, name);
    }

    private void indexOriginalExternalIdForTaxon(String externalId, Taxon taxon, TaxonNode taxonNode) throws NodeFactoryException {
        if (!StringUtils.equals(taxon.getExternalId(), externalId)) {
            if (isNonEmptyTaxonNameOrId(externalId) && findTaxonById(externalId) == null) {
                indexTaxonByProperty(taxonNode, PropertyAndValueDictionary.EXTERNAL_ID, externalId);
            }
        }
    }

    IndexHits<Node> findCloseMatchesForTaxonName(String taxonName) {
        return QueryUtil.query(taxonName, PropertyAndValueDictionary.NAME, taxons);
    }

    private void indexTaxonByProperty(TaxonNode taxonNode, String propertyName, String propertyValue) {
        try (Transaction tx = taxonNode.getUnderlyingNode().getGraphDatabase().beginTx()) {
            taxons.add(taxonNode.getUnderlyingNode(), propertyName, propertyValue);
            tx.success();
        }
    }

    GraphDatabaseService getGraphDbService() {
        return graphDbService;
    }

    TaxonNode createAndIndexTaxon(Taxon origTaxon, Taxon taxon) throws NodeFactoryException {
        TaxonNode taxonNode;
        try (Transaction transaction = graphDbService.beginTx()) {
            Node node = graphDbService.createNode();
            taxonNode = new TaxonNode(node, taxon.getName());

            TaxonNode copiedTaxon = (TaxonNode) TaxonUtil.copy(taxon, taxonNode);
            if (isNonEmptyTaxonNameOrId(taxonNode.getName())) {
                for (String rank : RANKS) {
                    populateRankIds(taxon, node, rank);
                    populateRankNames(taxon, node, rank);
                }
            }
            addToIndeces(copiedTaxon, taxon.getName());

            indexOriginalNameForTaxon(origTaxon.getName(), taxon, taxonNode);
            indexOriginalExternalIdForTaxon(origTaxon.getExternalId(), taxon, taxonNode);

            transaction.success();
        }
        return taxonNode;
    }

    private void populateRankNames(Taxon taxon, Node node, String rank) {
        Map<String, String> pathNameMap = TaxonUtil.toPathNameMap(taxon);
        String name = pathNameMap.get(rank);
        if (StringUtils.isNotBlank(name)) {
            node.setProperty(rank + "Name", name);
        }
    }

    private void populateRankIds(Taxon taxon, Node node, String rank) {
        Map<String, String> pathIdMap = TaxonUtil.toPathIdMap(taxon);
        String id = pathIdMap.get(rank);
        if (StringUtils.isNotBlank(id)) {
            node.setProperty(rank + "Id", id);
        }
    }


    private void addToIndeces(TaxonNode taxon, String indexedName) {
        if (isNonEmptyTaxonNameOrId(indexedName)) {
            taxons.add(taxon.getUnderlyingNode(), PropertyAndValueDictionary.NAME, indexedName);
        }

        String externalId = taxon.getExternalId();
        if (isNonEmptyTaxonNameOrId(externalId)) {
            taxons.add(taxon.getUnderlyingNode(), PropertyAndValueDictionary.EXTERNAL_ID, externalId);
        }
    }

    protected TaxonNode addNoMatchTaxon(Taxon origTaxon) throws NodeFactoryException {
        Taxon noMatchTaxon = TaxonUtil.copy(origTaxon);

        noMatchTaxon.setName(isNonEmptyTaxonNameOrId(origTaxon.getName())
                ? origTaxon.getName()
                : PropertyAndValueDictionary.NO_NAME);

        noMatchTaxon.setExternalId(isNonEmptyTaxonNameOrId(origTaxon.getExternalId())
                ? origTaxon.getExternalId()
                : PropertyAndValueDictionary.NO_MATCH);

        return createAndIndexTaxon(origTaxon, noMatchTaxon);
    }


}
