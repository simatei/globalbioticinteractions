package org.eol.globi.data;

import com.healthmarketscience.jackcess.Database;
import com.healthmarketscience.jackcess.DatabaseBuilder;
import com.healthmarketscience.jackcess.Table;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.NotImplementedException;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.eol.globi.domain.TaxonomyProvider;
import org.eol.globi.domain.Term;
import org.eol.globi.domain.TermImpl;
import org.eol.globi.service.ResourceService;
import org.eol.globi.service.TermLookupService;
import org.eol.globi.service.TermLookupServiceException;
import org.eol.globi.util.HttpUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CMECSService implements TermLookupService {

    private static Log LOG = LogFactory.getLog(CMECSService.class);

    private Map<String, Term> termMap = null;

    private final ResourceService<URI> service;

    public CMECSService() {
        this(new ResourceServiceDefault());
    }

    public CMECSService(ResourceService<URI> resourceServiceDefault) {
        this.service = resourceServiceDefault;
    }

    @Override
    public List<Term> lookupTermByName(String name) throws TermLookupServiceException {
        if (termMap == null) {
            try {
                termMap = buildTermMap(getService());
            } catch (IOException e) {
                throw new TermLookupServiceException("failed to instantiate terms", e);
            }
        }
        Term term = termMap.get(StringUtils.lowerCase(StringUtils.trim(name)));
        return term == null ? Collections.emptyList() : Collections.singletonList(term);
    }

    private static Map<String, Term> buildTermMap(ResourceService<URI> service) throws IOException {
        LOG.info(CMECSService.class.getSimpleName() + " instantiating...");
        URI uri = URI.create("https://cmecscatalog.org/cmecs/documents/cmecs4.accdb");
        LOG.info("CMECS data [" + uri + "] downloading ...");

        URI resourceURI = service.getResourceURI(uri);

        if (resourceURI == null) {
            throw new IOException("failed to access [" + uri + "]");
        }
        File mdbFile;
        try {
            mdbFile = new File(resourceURI);
        } catch (IllegalArgumentException ex) {
            throw new IOException("failed to access [" + uri + "] via [" + resourceURI + "]", ex);
        }

        Database db = new DatabaseBuilder()
                .setFile(mdbFile)
                .setReadOnly(true)
                .open();

        Map<String, Term> aquaticSettingsTerms = new HashMap<>();

        Table table = db.getTable("Aquatic Setting");
        Map<String, Object> row;
        while ((row = table.getNextRow()) != null) {
            Integer id = (Integer) row.get("AquaticSetting_Id");
            String name = (String) row.get("AquaticSettingName");
            String termId = TaxonomyProvider.ID_CMECS + id;
            aquaticSettingsTerms.put(StringUtils.lowerCase(StringUtils.strip(name)), new TermImpl(termId, name));
        }
        LOG.info(CMECSService.class.getSimpleName() + " instantiated.");
        return aquaticSettingsTerms;
    }

    public ResourceService<URI> getService() {
        return service;
    }

    private static class ResourceServiceDefault implements ResourceService<URI> {

        @Override
        public InputStream getResource(URI resourceName) throws IOException {
            throw new NotImplementedException();
        }

        @Override
        public URI getResourceURI(URI resourceName) {
            URI resourceURI = null;
            HttpGet get = new HttpGet(resourceName);
            try {
                HttpResponse execute = HttpUtil.getHttpClient().execute(get);
                File cmecs = File.createTempFile("cmecs", "accdb");
                cmecs.deleteOnExit();
                IOUtils.copy(execute.getEntity().getContent(), new FileOutputStream(cmecs));
                LOG.info("CMECS data [" + resourceName + "] downloaded.");
                resourceURI = cmecs.toURI();
            } catch (IOException e) {
                LOG.warn("failed to access [" + resourceName + "]", e);
            }

            return resourceURI;
        }
    }
}
