package org.pac4j.saml.metadata;

import net.shibboleth.utilities.java.support.component.ComponentInitializationException;
import net.shibboleth.utilities.java.support.resolver.CriteriaSet;
import net.shibboleth.utilities.java.support.resolver.ResolverException;
import net.shibboleth.utilities.java.support.xml.XMLParserException;
import org.opensaml.core.criterion.EntityIdCriterion;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.saml.metadata.IterableMetadataSource;
import org.opensaml.saml.metadata.resolver.MetadataResolver;
import org.opensaml.saml.metadata.resolver.impl.DOMMetadataResolver;
import org.opensaml.saml.metadata.resolver.index.impl.RoleMetadataIndex;
import org.opensaml.saml.saml2.metadata.EntitiesDescriptor;
import org.opensaml.saml.saml2.metadata.EntityDescriptor;
import org.pac4j.core.exception.TechnicalException;
import org.pac4j.core.util.CommonHelper;
import org.pac4j.saml.config.SAML2Configuration;
import org.pac4j.saml.exceptions.SAMLException;
import org.pac4j.saml.util.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;

import javax.annotation.Nullable;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.util.Collections;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Resolve and download idp metadata to form a metadata resolver.
 * <p>
 * The resolver supports proxies using {@link Proxy} when fetching metadata over URL resources.
 *
 * @author Misagh Moayyed
 * @since 1.7
 */
public class SAML2IdentityProviderMetadataResolver implements SAML2MetadataResolver {
    private static final long NO_LAST_MODIFIED = -1;

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    private final Resource idpMetadataResource;
    private final ReentrantLock lock = new ReentrantLock();
    private String idpEntityId;
    private MetadataResolver metadataResolver;
    private long lastModified = NO_LAST_MODIFIED;
    private Proxy proxy = Proxy.NO_PROXY;

    private HostnameVerifier hostnameVerifier;
    private SSLSocketFactory sslSocketFactory;

    public SAML2IdentityProviderMetadataResolver(final SAML2Configuration configuration) {
        this(configuration.getIdentityProviderMetadataResource(), configuration.getIdentityProviderEntityId());
        if (configuration.getSslSocketFactory() != null) {
            setSslSocketFactory(configuration.getSslSocketFactory());
        }
        if (configuration.getHostnameVerifier() != null) {
            setHostnameVerifier(configuration.getHostnameVerifier());
        }
    }

    public SAML2IdentityProviderMetadataResolver(final Resource idpMetadataResource, @Nullable final String idpEntityId) {
        CommonHelper.assertNotNull("identityProviderMetadataResource", idpMetadataResource);
        this.idpMetadataResource = idpMetadataResource;
        this.idpEntityId = idpEntityId;
    }

    public void init() {
        this.metadataResolver = buildMetadataResolver();
        hasChanged();
    }

    @Override
    public final MetadataResolver resolve(final boolean force) {
        if (lock.tryLock()) {
            try {
                var reload = force || hasChanged();
                if (reload) {
                    this.metadataResolver = buildMetadataResolver();
                }
            } finally {
                lock.unlock();
            }
        }
        return metadataResolver;
    }

    boolean hasChanged() {
        long newLastModified;
        try {
            newLastModified = this.idpMetadataResource.lastModified();
        } catch (final Exception e) {
            newLastModified = NO_LAST_MODIFIED;
        }
        final var hasChanged = lastModified != newLastModified;
        logger.debug("lastModified: {} / newLastModified: {} -> hasChanged: {}", lastModified, newLastModified, hasChanged);
        lastModified = newLastModified;
        return hasChanged;
    }

    protected MetadataResolver buildMetadataResolver() {
        var resolver = initializeMetadataResolver();
        determineIdentityProviderEntityId(resolver);
        return resolver;
    }

    public long getLastModified() {
        return lastModified;
    }

    /**
     * If no idpEntityId declared, select first EntityDescriptor entityId as our IDP.
     *
     * @param resolver metadata resolver
     */
    private void determineIdentityProviderEntityId(final IterableMetadataSource resolver) {
        if (this.idpEntityId == null) {
            var it = resolver.iterator();
            if (it.hasNext()) {
                var entityDescriptor = it.next();
                this.idpEntityId = entityDescriptor.getEntityID();
            }
        }

        if (this.idpEntityId == null) {
            throw new SAMLException("No idp entityId found");
        }
    }

    private DOMMetadataResolver initializeMetadataResolver() {
        try (var in = getMetadataResourceInputStream()) {
            var parsedInput = Configuration.getParserPool().parse(in);
            var metadataRoot = parsedInput.getDocumentElement();
            var resolver = new DOMMetadataResolver(metadataRoot);
            resolver.setIndexes(Collections.singleton(new RoleMetadataIndex()));
            resolver.setParserPool(Configuration.getParserPool());
            resolver.setFailFastInitialization(true);
            resolver.setRequireValidMetadata(true);
            resolver.setId(resolver.getClass().getCanonicalName());
            resolver.initialize();
            return resolver;
        } catch (final FileNotFoundException e) {
            throw new TechnicalException("Error loading idp metadata", e);
        } catch (final ComponentInitializationException e) {
            throw new TechnicalException("Error initializing idp metadata resolver", e);
        } catch (final XMLParserException e) {
            throw new TechnicalException("Error parsing idp metadata", e);
        } catch (final IOException e) {
            throw new TechnicalException("Error getting idp metadata resource", e);
        }
    }

    protected InputStream getMetadataResourceInputStream() throws IOException {
        if (this.idpMetadataResource instanceof UrlResource) {
            var con = idpMetadataResource.getURL().openConnection(proxy);
            if (con instanceof HttpsURLConnection) {
                HttpsURLConnection connection = (HttpsURLConnection) con;

                if (this.sslSocketFactory != null) {
                    connection.setSSLSocketFactory(this.sslSocketFactory);
                }
                if (this.hostnameVerifier != null) {
                    connection.setHostnameVerifier(this.hostnameVerifier);
                }
            }

            try {
                return con.getInputStream();
            } catch (final Exception e) {
                if (con instanceof HttpURLConnection) {
                    ((HttpURLConnection) con).disconnect();
                }
                throw new TechnicalException("Error getting idp metadata resource", e);
            }
        }

        return this.idpMetadataResource.getInputStream();
    }

    @Override
    public String getEntityId() {
        final var md = getEntityDescriptorElement();
        if (md instanceof EntitiesDescriptor) {
            return ((EntitiesDescriptor) md).getEntityDescriptors().get(0).getEntityID();
        }
        if (md instanceof EntityDescriptor) {
            return ((EntityDescriptor) md).getEntityID();
        }
        throw new SAMLException("No idp entityId found");
    }

    @Override
    public String getMetadata() {
        if (getEntityDescriptorElement() != null) {
            return Configuration.serializeSamlObject(getEntityDescriptorElement()).toString();
        }
        throw new TechnicalException("Metadata cannot be retrieved because entity descriptor is null");
    }

    @Override
    public final XMLObject getEntityDescriptorElement() {
        try {
            return resolve().resolveSingle(new CriteriaSet(new EntityIdCriterion(this.idpEntityId)));
        } catch (final ResolverException e) {
            throw new SAMLException("Error initializing idpMetadataProvider", e);
        }
    }

    public void setProxy(final Proxy proxy) {
        this.proxy = proxy;
    }

    public void setHostnameVerifier(final HostnameVerifier hostnameVerifier) {
        this.hostnameVerifier = hostnameVerifier;
    }

    public void setSslSocketFactory(final SSLSocketFactory sslSocketFactory) {
        this.sslSocketFactory = sslSocketFactory;
    }
}
