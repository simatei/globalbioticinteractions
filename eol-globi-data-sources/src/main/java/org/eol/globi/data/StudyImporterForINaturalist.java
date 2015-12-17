package org.eol.globi.data;

import com.Ostermiller.util.CSVParser;
import com.Ostermiller.util.LabeledCSVParser;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.eol.globi.domain.InteractType;
import org.eol.globi.domain.Location;
import org.eol.globi.domain.Specimen;
import org.eol.globi.domain.Study;
import org.eol.globi.domain.Taxon;
import org.eol.globi.domain.TaxonImpl;
import org.eol.globi.domain.TaxonomyProvider;
import org.eol.globi.util.ExternalIdUtil;
import org.eol.globi.util.HttpUtil;
import org.eol.globi.util.ResourceUtil;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.ISODateTimeFormat;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class StudyImporterForINaturalist extends BaseStudyImporter {
    private static final Log LOG = LogFactory.getLog(StudyImporterForINaturalist.class);
    public static final String TYPE_IGNORED_URI_DEFAULT = "https://rawgit.com/globalbioticinteractions/inaturalist/master/interaction_types_ignored.csv";
    public static final String TYPE_MAP_URI_DEFAULT = "https://rawgit.com/globalbioticinteractions/inaturalist/master/interaction_types.csv";

    public static final String INATURALIST_URL = "http://inaturalist.org";


    private final Map<Long, String> unsupportedInteractionTypes = new TreeMap<Long, String>();
    private String typeIgnoredURI;
    private String typeMapURI;
    public static final String PREFIX_OBSERVATION_FIELD = "http://www.inaturalist.org/observation_fields/";

    public StudyImporterForINaturalist(ParserFactory parserFactory, NodeFactory nodeFactory) {
        super(parserFactory, nodeFactory);
        setTypeMapURI(TYPE_MAP_URI_DEFAULT);
        setTypeIgnoredURI(TYPE_IGNORED_URI_DEFAULT);
    }

    public static Map<Integer, InteractType> buildTypeMap(String resource, InputStream is) throws IOException {
        LabeledCSVParser parser = new LabeledCSVParser(new CSVParser(is));
        Map<Integer, InteractType> typeMap = new TreeMap<Integer, InteractType>();
        while (parser.getLine() != null) {
            String inatIdString = parser.getValueByLabel("observation_field_id");
            Integer inatId = null;
            String prefix = "http://www.inaturalist.org/observation_fields/";
            if (StringUtils.startsWith(inatIdString, prefix)) {
                inatId = Integer.parseInt(inatIdString.replace(prefix, ""));
            }

            if (inatId == null) {
                LOG.warn("failed to map observation field id [" + inatIdString + "] in line [" + resource + ":" + parser.lastLineNumber() + "]");
            } else {
                String interactionTypeId = parser.getValueByLabel("interaction_type_id");
                InteractType interactType = InteractType.typeOf(interactionTypeId);
                if (interactType == null) {
                    LOG.warn("failed to map interaction type [" + interactionTypeId + "] in line [" + resource + ":" + parser.lastLineNumber() + "]");
                } else {
                    typeMap.put(inatId, interactType);
                }
            }
        }
        return typeMap;
    }

    public static List<Integer> buildTypesIgnored(InputStream is) throws IOException {
        LabeledCSVParser parser = new LabeledCSVParser(new CSVParser(is));
        List<Integer> typeMap1 = new ArrayList<Integer>();
        while (parser.getLine() != null) {
            String inatIdString = parser.getValueByLabel("observation_field_id");
            if (StringUtils.startsWith(inatIdString, PREFIX_OBSERVATION_FIELD)) {
                typeMap1.add(Integer.parseInt(inatIdString.replace(PREFIX_OBSERVATION_FIELD, "")));
            }
        }
        return typeMap1;
    }

    public static Taxon parseTaxon(JsonNode taxonNode) {
        String name = null;
        String externalId = null;

        JsonNode nameNode = taxonNode.get("name");
        if (nameNode != null && !nameNode.isNull()) {
            name = nameNode.asText();
        }

        JsonNode targetTaxa = taxonNode.get("taxon_scheme_taxa");
        if (targetTaxa != null) {
            for (JsonNode targetTaxonNode : targetTaxa) {
                JsonNode taxonScheme = targetTaxonNode.get("taxon_scheme_id");
                if (taxonScheme != null && "27".equals(taxonScheme.asText())) {
                    JsonNode schemeId = targetTaxonNode.get("source_identifier");
                    if (schemeId != null && !schemeId.isNull()) {
                        externalId = TaxonomyProvider.ID_PREFIX_GBIF + schemeId.asText();
                    }
                }
            }
        }
        return (name == null && externalId == null) ? null : new TaxonImpl(name, externalId);
    }

    @Override
    public Study importStudy() throws StudyImporterException {
        unsupportedInteractionTypes.clear();
        retrieveDataParseResults();
        if (unsupportedInteractionTypes.size() > 0) {
            StringBuilder unsupportedInteractions = new StringBuilder();
            for (Map.Entry<Long, String> entry : unsupportedInteractionTypes.entrySet()) {
                unsupportedInteractions.append("([")
                        .append(entry.getKey())
                        .append("], [")
                        .append(entry.getValue())
                        .append("]) ");
            }
            String msg = "found unsupported (observationId, observationFieldNameId) pairs: " + unsupportedInteractions.toString();
            throw new StudyImporterException(msg);
        }
        return null;
    }

    protected String getSourceString() {
        String description = "http://iNaturalist.org is a place where you can record what you see in nature, meet other nature lovers, and learn about the natural world. ";
        return description + ReferenceUtil.createLastAccessedString(INATURALIST_URL);
    }

    private int retrieveDataParseResults() throws StudyImporterException {
        List<Integer> typesIgnored;
        try {
            typesIgnored = buildTypesIgnored(ResourceUtil.asInputStream(getTypeIgnoredURI(), null));
        } catch (IOException e) {
            throw new StudyImporterException("failed to load ignored interaction types from [" + getTypeIgnoredURI() + "]");
        }
        Map<Integer, InteractType> typeMap;
        try {
            typeMap = buildTypeMap(getTypeMapURI(), ResourceUtil.asInputStream(getTypeMapURI(), null));
        } catch (IOException e) {
            throw new StudyImporterException("failed to load interaction mapping from [" + getTypeMapURI() + "]");
        }

        int totalInteractions = 0;
        int previousResultCount = 0;
        int pageNumber = 1;
        do {
            String uri = "http://www.inaturalist.org/observation_field_values.json?type=taxon&page=" + pageNumber + "&per_page=100&quality_grade=research";
            HttpGet httpGet = new HttpGet(uri);
            try {
                httpGet.addHeader("accept", "application/json");
                HttpResponse response = HttpUtil.getHttpClient().execute(httpGet);
                if (response.getStatusLine().getStatusCode() != 200) {
                    throw new StudyImporterException("failed to execute query to [" + uri + "]: status code [" + response.getStatusLine().getStatusCode() + "]");
                }
                previousResultCount = parseJSON(response.getEntity().getContent(),
                        typesIgnored,
                        typeMap);
                pageNumber++;
                totalInteractions += previousResultCount;
            } catch (IOException e) {
                throw new StudyImporterException("failed to import iNaturalist", e);
            } finally {
                httpGet.releaseConnection();
            }

        } while (previousResultCount > 0);
        return totalInteractions;
    }

    protected int parseJSON(InputStream retargetAsStream, List<Integer> typesIgnored, Map<Integer, InteractType> typeMap) throws StudyImporterException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode array;
        try {
            array = mapper.readTree(retargetAsStream);
        } catch (IOException e) {
            throw new StudyImporterException("error parsing inaturalist json", e);
        }
        if (!array.isArray()) {
            throw new StudyImporterException("expected json array, but found object");
        }
        for (int i = 0; i < array.size(); i++) {
            try {
                parseSingleInteractions(array.get(i), typesIgnored, typeMap);
            } catch (NodeFactoryException e) {
                throw new StudyImporterException("failed to parse inaturalist interactions", e);
            } catch (IOException e) {
                throw new StudyImporterException("failed to parse inaturalist interactions", e);
            }
        }
        return array.size();
    }

    @Override
    public void setFilter(ImportFilter importFilter) {

    }

    private void parseSingleInteractions(JsonNode jsonNode, List<Integer> typesIgnored, Map<Integer, InteractType> typeMap) throws NodeFactoryException, StudyImporterException, IOException {

        Taxon targetTaxon = null;
        Taxon sourceTaxon = null;
        if (jsonNode.has("taxon") && jsonNode.has("observation")) {
            targetTaxon = parseTaxon(jsonNode.get("taxon"));
            JsonNode observation = jsonNode.get("observation");
            if (jsonNode.has("taxon")) {
                sourceTaxon = parseTaxon(observation.get("taxon"));
            }
        }
        long observationId = jsonNode.get("observation_id").getLongValue();
        if (targetTaxon == null) {
            LOG.debug("skipping interaction with missing target taxon name for observation [" + observationId + "]");
        } else if (sourceTaxon == null) {
            LOG.warn("cannot create interaction with missing source taxon name for observation with id [" + observationId + "]");
        } else {
            JsonNode observationField = jsonNode.get("observation_field");
            String interactionDataType = observationField.get("datatype").getTextValue();
            String interactionTypeName = observationField.get("name").getTextValue();
            Integer interactionTypeId = observationField.get("id").getIntValue();
            if (typesIgnored.contains(interactionTypeId)) {
                LOG.debug("ignoring taxon observation field type [" + interactionTypeName + "] with id [" + interactionTypeId + "] for observation with id [" + observationId + "]");
            } else {
                InteractType interactType = typeMap.get(interactionTypeId);
                if (interactType == null) {
                    unsupportedInteractionTypes.put(observationId, interactionTypeName + ":" + interactionTypeId);
                    LOG.debug("no interaction type associated with observation field type [" + interactionTypeName + "] with id [" + interactionTypeId + "] for observation with id [" + observationId + "]");
                } else {
                    handleObservation(jsonNode, targetTaxon, observationId, interactionDataType, interactType, interactionTypeName, sourceTaxon);
                }
            }
        }

    }

    private void handleObservation(JsonNode jsonNode, Taxon targetTaxon, long observationId, String interactionDataType, InteractType interactionTypeId, String interactionTypeName, Taxon sourceTaxon) throws StudyImporterException, NodeFactoryException {
        JsonNode observation = jsonNode.get("observation");
        importInteraction(targetTaxon, observationId, interactionDataType, interactionTypeId, observation, sourceTaxon, interactionTypeName);
    }

    private void importInteraction(Taxon targetTaxon, long observationId, String interactionDataType, InteractType interactionTypeId, JsonNode observation, Taxon sourceTaxon, String interactionTypeName) throws StudyImporterException, NodeFactoryException {
        Study study = nodeFactory.getOrCreateStudy2(TaxonomyProvider.ID_PREFIX_INATURALIST + observationId, getSourceString(), null);
        Date observationDate = getObservationDate(study, observationId, observation);

        createAssociation(observationId, interactionDataType, interactionTypeId, observation, targetTaxon, sourceTaxon, study, observationDate);
        StringBuilder citation = buildCitation(observation, interactionTypeName, targetTaxon.getName(), sourceTaxon.getName(), observationDate);
        String url = ExternalIdUtil.urlForExternalId(TaxonomyProvider.ID_PREFIX_INATURALIST + observationId);
        citation.append(ReferenceUtil.createLastAccessedString(url));
        study.setCitationWithTx(citation.toString());
        study.setExternalId(url);
    }

    private StringBuilder buildCitation(JsonNode observationNode, String interactionType, String targetTaxonName, String sourceTaxonName, Date observationDate) {
        StringBuilder citation = new StringBuilder();
        if (observationNode.has("user")) {
            JsonNode userNode = observationNode.get("user");
            String user = userNode.has("name") ? userNode.get("name").getTextValue() : "";
            String login = userNode.has("login") ? userNode.get("login").getTextValue() : "";
            citation.append(StringUtils.isBlank(user) ? login : user);
            citation.append(". ");
        }
        if (observationDate != null) {
            SimpleDateFormat format = new SimpleDateFormat("yyyy");
            citation.append(format.format(observationDate));
            citation.append(". ");
        }
        citation.append(sourceTaxonName);
        citation.append(" ");
        citation.append(StringUtils.lowerCase(interactionType));
        citation.append(" ");
        citation.append(targetTaxonName);
        citation.append(". iNaturalist.org. ");
        return citation;
    }

    private Date getObservationDate(Study study, long observationId, JsonNode observation) {
        DateTime dateTime = null;
        String timeObservedAtUtc = observation.get("time_observed_at_utc").getTextValue();
        timeObservedAtUtc = timeObservedAtUtc == null ? observation.get("observed_on").getTextValue() : timeObservedAtUtc;
        if (timeObservedAtUtc == null) {
            getLogger().warn(study, "failed to retrieve observation time for observation [" + observationId + "]");
        } else {
            dateTime = parseUTCDateTime(timeObservedAtUtc);
        }
        return dateTime == null ? null : dateTime.toDate();
    }

    private Specimen createAssociation(long observationId, String interactionDataType, InteractType interactType, JsonNode observation, Taxon targetTaxon, Taxon sourceTaxonName, Study study, Date observationDate) throws StudyImporterException, NodeFactoryException {
        Specimen sourceSpecimen = getSourceSpecimen(observationId, interactionDataType, sourceTaxonName, study);
        setBasisOfRecord(sourceSpecimen);
        Specimen targetSpecimen = nodeFactory.createSpecimen(study, targetTaxon);
        setBasisOfRecord(targetSpecimen);

        sourceSpecimen.interactsWith(targetSpecimen, interactType);
        setCollectionDate(sourceSpecimen, targetSpecimen, observationDate);
        setCollectionDate(sourceSpecimen, sourceSpecimen, observationDate);

        Location location = parseLocation(observation);
        sourceSpecimen.caughtIn(location);
        targetSpecimen.caughtIn(location);

        return sourceSpecimen;
    }

    private void setBasisOfRecord(Specimen sourceSpecimen) throws NodeFactoryException {
        sourceSpecimen.setBasisOfRecord(nodeFactory.getOrCreateBasisOfRecord("http://rs.tdwg.org/dwc/dwctype/HumanObservation", "HumanObservation"));
    }

    private Location parseLocation(JsonNode observation) throws NodeFactoryException {
        Location location = null;
        String latitudeString = observation.get("latitude").getTextValue();
        String longitudeString = observation.get("longitude").getTextValue();
        if (latitudeString != null && longitudeString != null) {
            double latitude = Double.parseDouble(latitudeString);
            double longitude = Double.parseDouble(longitudeString);
            location = nodeFactory.getOrCreateLocation(latitude, longitude, null);

        }
        return location;
    }

    private void setCollectionDate(Specimen sourceSpecimen, Specimen targetSpecimen, Date observationDate) throws NodeFactoryException {
        nodeFactory.setUnixEpochProperty(sourceSpecimen, observationDate);
        nodeFactory.setUnixEpochProperty(targetSpecimen, observationDate);
    }

    private Specimen getSourceSpecimen(long observationId, String interactionDataType, Taxon sourceTaxon, Study study) throws StudyImporterException, NodeFactoryException {
        if (!"taxon".equals(interactionDataType)) {
            throw new StudyImporterException("expected [taxon] as observation_type datatype, but found [" + interactionDataType + "]");
        }
        Specimen sourceSpecimen = nodeFactory.createSpecimen(study, sourceTaxon);
        sourceSpecimen.setExternalId(TaxonomyProvider.ID_PREFIX_INATURALIST + observationId);

        return sourceSpecimen;
    }

    private DateTime parseUTCDateTime(String timeObservedAtUtc) {
        return ISODateTimeFormat.dateTimeParser().parseDateTime(timeObservedAtUtc).withZone(DateTimeZone.UTC);
    }

    @Override
    public boolean shouldCrossCheckReference() {
        return false;
    }

    public void setTypeIgnoredURI(String typeIgnoredURI) {
        this.typeIgnoredURI = typeIgnoredURI;
    }

    public String getTypeIgnoredURI() {
        return typeIgnoredURI;
    }

    public void setTypeMapURI(String typeMapURI) {
        this.typeMapURI = typeMapURI;
    }

    public String getTypeMapURI() {
        return typeMapURI;
    }
}
