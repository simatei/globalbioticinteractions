package org.eol.globi.data;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.ObjectNode;
import org.eol.globi.domain.InteractType;
import org.eol.globi.domain.RelTypes;
import org.eol.globi.domain.Specimen;
import org.eol.globi.domain.SpecimenNode;
import org.eol.globi.domain.Study;
import org.eol.globi.domain.StudyNode;
import org.eol.globi.domain.TaxonNode;
import org.eol.globi.service.DatasetLocal;
import org.eol.globi.util.NodeTypeDirection;
import org.eol.globi.util.NodeUtil;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static junit.framework.TestCase.assertNotNull;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class DatasetImporterForRSSLocalTest extends GraphDBTestCase {

    @Test
    public void importLocalArctosArchive() throws StudyImporterException, IOException {
        DatasetImporter importer = new StudyImporterTestFactory(nodeFactory)
                .instantiateImporter(DatasetImporterForRSS.class);
        DatasetLocal dataset = new DatasetLocal(inStream -> inStream);


        ObjectNode configNode = new ObjectMapper().createObjectNode();
        URL resource = getClass().getResource("/org/eol/globi/data/rss/arctos_issue_461.zip");
        assertNotNull(resource);
        String rssContent = rssContent(resource.toString());
        File directory = new File("target/tmp");
        FileUtils.forceMkdir(directory);
        File rss = File.createTempFile("rss", ".xml", directory);
        FileUtils.writeStringToFile(rss, rssContent, StandardCharsets.UTF_8);
        configNode.put("url", rss.toURI().toString());
        configNode.put("hasDependencies", true);
        dataset.setConfig(configNode);
        importer.setDataset(dataset);
        importStudy(importer);

        List<StudyNode> allStudies = NodeUtil.findAllStudies(getGraphDb());
        assertThat(allStudies.size(), greaterThan(0));
        StudyNode study = allStudies.get(0);

        TaxonNode taxonNode = (TaxonNode) taxonIndex.findTaxonByName("Anaxyrus cognatus");

        Assert.assertNotNull(taxonNode);

        NodeUtil.handleCollectedRelationships(new NodeTypeDirection(study.getUnderlyingNode()), new NodeUtil.RelationshipListener() {
            @Override
            public void on(Relationship relationship) {
                assertThat(relationship.getType().name(), is("COLLECTED"));

                Specimen source = new SpecimenNode(relationship.getEndNode());
                Relationship singleRelationship = ((SpecimenNode) source).getUnderlyingNode().getSingleRelationship(NodeUtil.asNeo4j(InteractType.INTERACTS_WITH), Direction.OUTGOING);

                Specimen target = new SpecimenNode(singleRelationship.getEndNode());
                assertNotNull(target);
                assertNotNull(source);
                Node sourceOrigTaxon = ((SpecimenNode) source)
                        .getUnderlyingNode()
                        .getSingleRelationship(NodeUtil.asNeo4j(RelTypes.ORIGINALLY_DESCRIBED_AS), Direction.OUTGOING)
                        .getEndNode();
                Node targetOrigTaxon = ((SpecimenNode) target)
                        .getUnderlyingNode()
                        .getSingleRelationship(NodeUtil.asNeo4j(RelTypes.ORIGINALLY_DESCRIBED_AS), Direction.OUTGOING)
                        .getEndNode();

                assertThat(new TaxonNode(sourceOrigTaxon).getName(), is("Anaxyrus cognatus"));
                assertThat(new TaxonNode(targetOrigTaxon).getName(), is("Anaxyrus cognatus"));


            }
        });


    }

    @Test
    public void importLocalMCZArchive() throws StudyImporterException, IOException {
        DatasetImporter importer = new StudyImporterTestFactory(nodeFactory)
                .instantiateImporter(DatasetImporterForRSS.class);
        DatasetLocal dataset = new DatasetLocal(inStream -> inStream) {
            @Override
            public InputStream retrieve(URI resourceName) throws IOException {
                if (resourceName.toString().endsWith("mapping.csv")) {
                    return IOUtils.toInputStream("provided_interaction_type_label,provided_interaction_type_id,mapped_to_interaction_type_label,mapped_to_interaction_type_id\n" +
                            "found in association with,,interactsWith,http://purl.obolibrary.org/obo/RO_0002437", StandardCharsets.UTF_8);
                } else {
                    return super.retrieve(resourceName);
                }
            }
        };

        ObjectNode configNode = new ObjectMapper().createObjectNode();
        URL resource = getClass().getResource("/org/eol/globi/data/rss/mcz_issue_659.zip");
        assertNotNull(resource);
        String rssContent = rssContent(resource.toString());
        File directory = new File("target/tmp");
        FileUtils.forceMkdir(directory);
        File rss = File.createTempFile("rss", ".xml", directory);
        FileUtils.writeStringToFile(rss, rssContent, StandardCharsets.UTF_8);
        configNode.put("url", rss.toURI().toString());
        configNode.put("hasDependencies", true);
        dataset.setConfig(configNode);
        importer.setDataset(dataset);
        importStudy(importer);

        List<StudyNode> allStudies = NodeUtil.findAllStudies(getGraphDb());
        assertThat(allStudies.size(), greaterThan(0));

        Map<String, StudyNode> studyMap = new TreeMap<>();
        for (StudyNode study : allStudies) {
            studyMap.put(study.getExternalId(), study);
        }
        StudyNode study = studyMap.get("http://mczbase.mcz.harvard.edu/guid/MCZ:Mamm:61296");

        assertThat(study.getExternalId(), is("http://mczbase.mcz.harvard.edu/guid/MCZ:Mamm:61296"));

        TaxonNode taxonNode = (TaxonNode) taxonIndex.findTaxonByName("Grampus griseus");

        Assert.assertNotNull(taxonNode);

        Set<String> sourceTaxa = new TreeSet<>();
        Set<String> targetTaxa = new TreeSet<>();
        Set<String> targetCatalogNumbers = new TreeSet<>();
        Set<String> sourceCatalogNumbers = new TreeSet<>();

        NodeUtil.handleCollectedRelationships(new NodeTypeDirection(study.getUnderlyingNode()), new NodeUtil.RelationshipListener() {
            @Override
            public void on(Relationship relationship) {
                assertThat(relationship.getType().name(), is("COLLECTED"));

                Specimen source = new SpecimenNode(relationship.getEndNode());
                String sourceCatalogNumber = source.getProperty("catalogNumber");
                if (StringUtils.isNotBlank(sourceCatalogNumber)) {
                    sourceCatalogNumbers.add(sourceCatalogNumber);
                }

                Relationship singleRelationship = ((SpecimenNode) source).getUnderlyingNode().getSingleRelationship(NodeUtil.asNeo4j(InteractType.INTERACTS_WITH), Direction.OUTGOING);

                Specimen target = new SpecimenNode(singleRelationship.getEndNode());


                String catalogNumber = target.getProperty("catalogNumber");
                if (StringUtils.isNotBlank(catalogNumber)) {
                    targetCatalogNumbers.add(catalogNumber);
                }

                assertNotNull(target);
                assertNotNull(source);
                Node sourceOrigTaxon = ((SpecimenNode) source)
                        .getUnderlyingNode()
                        .getSingleRelationship(NodeUtil.asNeo4j(RelTypes.ORIGINALLY_DESCRIBED_AS), Direction.OUTGOING)
                        .getEndNode();

                Node targetOrigTaxon = ((SpecimenNode) target)
                        .getUnderlyingNode()
                        .getSingleRelationship(NodeUtil.asNeo4j(RelTypes.ORIGINALLY_DESCRIBED_AS), Direction.OUTGOING)
                        .getEndNode();

                sourceTaxa.add(new TaxonNode(sourceOrigTaxon).getName());
                targetTaxa.add(new TaxonNode(targetOrigTaxon).getName());


            }
        });

        assertThat(sourceTaxa, hasItem("Grampus griseus"));
        assertThat(sourceCatalogNumbers, hasItem("61296"));

        assertThat(targetTaxa, hasItem("Grampus griseus"));
        assertThat(targetTaxa, hasItem("MCZ:Mamm:61298"));
        assertThat(targetTaxa, Matchers.not(hasItem("MCZ:Mamm:61297")));
        assertThat(targetCatalogNumbers, hasItem("61297"));

    }

    private String rssContent(String resourceURL) {
        return "<rss version=\"2.0\">\n" +
                "    <channel>\n" +
                "        <item ProjUID=\"2\">\n" +
                "            <title> testing</title>\n" +
                "            <type>DWCA</type>\n" +
                "            <recordType>DWCA</recordType>\n" +
                "            <link>\n" +
                "                " + resourceURL + "\n" +
                "            </link>\n" +
                "            <pubDate>Tue, 08 Mar 2016 13:49:08</pubDate>\n" +
                "        </item>\n" +
                "    </channel>\n" +
                "</rss>";
    }

}