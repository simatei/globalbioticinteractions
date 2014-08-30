package org.eol.globi.service;

import java.util.ArrayList;
import java.util.List;

public class TaxonEnricherFactory {
    public static TaxonEnricher createTaxonEnricher() {
        TaxonEnricherImpl taxonEnricher = new TaxonEnricherImpl();
        List<PropertyEnricher> services = new ArrayList<PropertyEnricher>() {
            {
                //add(new EOLOfflineService());
                add(new EnvoService());
                add(new FunctionalGroupService());
                add(new EOLService());
                add(new WoRMSService());
                //add(new ITISService());
                add(new GulfBaseService());
                add(new AtlasOfLivingAustraliaService());
            }
        };
        taxonEnricher.setServices(services);
        return taxonEnricher;
    }
}