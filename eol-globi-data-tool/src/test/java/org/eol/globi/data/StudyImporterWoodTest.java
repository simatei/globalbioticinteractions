package org.eol.globi.data;

import org.apache.commons.io.IOUtils;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

public class StudyImporterWoodTest extends GraphDBTestCase {

    @Test
    public void importLines() throws IOException {
        final List<Map<String, String>> maps = new ArrayList<Map<String, String>>();

        StudyImporterWood.importLinks(IOUtils.toInputStream(firstFewLines()), new InteractionListener() {

            @Override
            public void newLink(final Map<String, String> properties) {
                maps.add(properties);
            }
        });
        assertThat(maps.size(), is(5));
        Map<String, String> firstLink = maps.get(0);
        assertThat(firstLink.get("source_taxon_external_id"), is("ITIS:93294"));
        assertThat(firstLink.get("source_taxon_name"), is("Amphipoda"));
        assertThat(firstLink.get("target_taxon_external_id"), is("ITIS:10824"));
        assertThat(firstLink.get("target_taxon_name"), is("Pilayella littoralis"));
        assertStaticInfo(firstLink);

        Map<String, String> secondLink = maps.get(1);
        assertThat(secondLink.get("source_taxon_external_id"), is(nullValue()));
        assertThat(secondLink.get("source_taxon_name"), is("Phytoplankton complex"));
        assertStaticInfo(secondLink);
    }

    protected void assertStaticInfo(Map<String, String> firstLink) {
        assertThat(firstLink.get("study_source_citation"), is("Wood SA, Russell R, Hanson D, Williams RJ, Dunne JA (2015) Data from: Effects of spatial scale of sampling on food web structure. Dryad Digital Repository. http://dx.doi.org/10.5061/dryad.g1qr6"));
        assertThat(firstLink.get("study_citation"), is("Wood SA, Russell R, Hanson D, Williams RJ, Dunne JA (2015) Effects of spatial scale of sampling on food web structure. Ecology and Evolution, online in advance of print. http://dx.doi.org/10.1002/ece3.1640"));
        assertThat(firstLink.get("study_doi"), is("doi:10.1002/ece3.1640"));
        assertThat(firstLink.get("study_url"), is("http://dx.doi.org/10.1002/ece3.1640"));
        assertThat(firstLink.get("locality_name"), is("Sanak Island, Alaska, USA"));
        assertThat(firstLink.get("locality_id"), is("GEONAMES:5873327"));
        assertThat(firstLink.get("longitude"), is("-162.70889"));
        assertThat(firstLink.get("latitude"), is("54.42972"));
        assertThat(firstLink.get("interaction_type_id"), is("RO:0002439"));
        assertThat(firstLink.get("interaction_type_name"), is("preysOn"));
    }

    private String firstFewLines() {
        return "\"WebID\",\"WebScale\",\"WebUnit\",\"PredTSN\",\"PreyTSN\",\"PredName\",\"PreyName\"\n" +
                "9,\"T\",\"22\",\"93294\",\"10824\",\"Amphipoda\",\"Pilayella littoralis\"\n" +
                "9,\"T\",\"22\",\"san267\",\"2286\",\"Phytoplankton complex\",\"Bacillariophyta\"\n" +
                "9,\"T\",\"22\",\"93294\",\"11334\",\"Amphipoda\",\"Fucus\"\n" +
                "9,\"T\",\"22\",\"70395\",\"11334\",\"Littorina\",\"Fucus\"\n" +
                "9,\"T\",\"22\",\"92283\",\"11334\",\"Sphaeromatidae\",\"Fucus\"\n";
    }
}