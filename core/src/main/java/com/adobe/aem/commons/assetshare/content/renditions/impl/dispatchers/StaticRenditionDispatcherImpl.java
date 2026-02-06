/*
 * Asset Share Commons
 *
 * Copyright (C) 2019 Adobe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.adobe.aem.commons.assetshare.content.renditions.impl.dispatchers;

import com.adobe.aem.commons.assetshare.content.AssetModel;
import com.adobe.aem.commons.assetshare.content.renditions.*;
import com.adobe.aem.commons.assetshare.content.renditions.download.DownloadExtensionResolver;
import com.adobe.aem.commons.assetshare.content.renditions.download.impl.AssetRenditionDownloadRequest;
import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.DamConstants;
import com.day.cq.dam.api.Rendition;
import com.day.cq.dam.api.RenditionPicker;
import com.day.cq.dam.commons.util.DamUtil;
import org.apache.commons.io.FilenameUtils;               // <-- Snyk-recognized normalizer
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static com.adobe.aem.commons.assetshare.content.renditions.AssetRenditionParameters.*;
import static org.osgi.framework.Constants.SERVICE_RANKING;

@Component(
        property = {
                SERVICE_RANKING + ":Integer=" + -20000
        }
)
@Designate(
        ocd = StaticRenditionDispatcherImpl.Cfg.class,
        factory = true
)
public class StaticRenditionDispatcherImpl extends AbstractRenditionDispatcherImpl implements AssetRenditionDispatcher {
    private static final Logger log = LoggerFactory.getLogger(StaticRenditionDispatcherImpl.class);

    private static final String OSGI_PROPERTY_VALUE_DELIMITER = "=";
    private static final String DAM_ROOT = "/content/dam";

    private Cfg cfg;

    private ConcurrentHashMap<String, Pattern> mappings;

    @Reference(
            policy = ReferencePolicy.DYNAMIC,
            policyOption = ReferencePolicyOption.GREEDY
    )
    private volatile AssetRenditions assetRenditions;

    @Reference(
            cardinality = ReferenceCardinality.OPTIONAL,
            policy = ReferencePolicy.DYNAMIC,
            policyOption = ReferencePolicyOption.GREEDY
    )
    private volatile AssetRenditionTracker assetRenditionTracker;

    @Reference(
            cardinality = ReferenceCardinality.OPTIONAL,
            policy = ReferencePolicy.DYNAMIC,
            policyOption = ReferencePolicyOption.GREEDY
    )
    private volatile DownloadExtensionResolver downloadExtensionResolver;

    @Override
    public String getLabel() {
        return cfg.label();
    }

    @Override
    public String getName() {
        return cfg.name();
    }

    @Override
    public Map<String, String> getOptions() {
        return assetRenditions.getOptions(mappings);
    }

    @Override
    public boolean isHidden() {
        return cfg.hidden();
    }

    @Override
    public Set<String> getRenditionNames() {
        if (mappings == null) {
            return Collections.emptySet();
        } else {
            return mappings.keySet();
        }
    }

    @Override
    public List<String> getTypes() {
        if (cfg.types() != null) {
            return Arrays.asList(cfg.types());
        }

        return Collections.emptyList();
    }

    @Override
    public void dispatch(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException, ServletException {
        final Asset asset = DamUtil.resolveToAsset(request.getResource());
        final AssetRenditionParameters parameters = new AssetRenditionParameters(request);

        final Rendition rendition = findRendition(asset, parameters);

        if (rendition != null) {
            if (log.isDebugEnabled()) {
                log.debug("Serving internal static rendition [ {} ] with resolved rendition name [ {} ] through internal Sling Forward",
                        rendition.getPath(),
                        parameters.getRenditionName());
            }

            if (assetRenditionTracker != null) {
                assetRenditionTracker.track(this, request, parameters, rendition.getPath());
            }

            response.setHeader("Content-Type", rendition.getMimeType());

            final String rawPath = rendition.getPath();
            final String normalizedJcr = ResourceUtil.normalize(rawPath);
            final String normalizedFsLike = FilenameUtils.normalize(normalizedJcr);

            if (normalizedFsLike == null || !normalizedFsLike.startsWith(DAM_ROOT)) {
                response.sendError(javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST, "Invalid rendition path.");
                return;
            }
            final ResourceResolver rr = request.getResourceResolver();
            final Resource safeRenditionResource = rr.getResource(normalizedFsLike);
            if (safeRenditionResource == null) {
                response.sendError(javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST, "Rendition resource not found.");
                return;
            }
            request.getRequestDispatcher(normalizedFsLike).include(
                    new AssetRenditionDownloadRequest(request,
                            "GET",
                            safeRenditionResource,
                            new String[]{},
                            null,
                            ""), response);

        } else {
            throw new ServletException(String.format("Could not locate rendition [ %s ] for asset [ %s ]",
                    parameters.getRenditionName(), asset.getPath()));
        }
    }

    @Override
    public AssetRendition getRendition(final AssetModel assetModel, final AssetRenditionParameters parameters) {
        final Rendition rendition = findRendition(assetModel.getAsset(), parameters);

        if (rendition == null) {
            return null;
        }

        if (log.isDebugEnabled()) {
            log.debug("Downloading asset rendition [ {} ] for resolved rendition name [ {} ]",
                    rendition.getPath(),
                    parameters.getRenditionName());
        }

        if (assetRenditionTracker != null && parameters.getOtherProperties().get(TRACK, true)) {
            assetRenditionTracker.track(this, assetModel, parameters, rendition.getPath());
        }

        return new AssetRendition(rendition.getPath(), rendition.getSize(), rendition.getMimeType());
    }


    @Override
    public ValueMap getRenditionDetails(AssetModel assetModel, AssetRenditionParameters parameters) {

        parameters.setOtherProperty(TRACK, false);
        final AssetRendition assetRendition = getRendition(assetModel, parameters);
        parameters.setOtherProperty(TRACK, null);

        ValueMap details = new ValueMapDecorator(new HashMap<>());
        details.put(RENDITION_DETAILS_SIZE, assetRendition.getSize());
        details.put(RENDITION_DETAILS_MIME_TYPE, assetRendition.getMimeType());

        String extension = null;
        if (downloadExtensionResolver != null) {
            extension = downloadExtensionResolver.resolve(assetModel, assetRendition);
        } else {
            final Rendition r = findRendition(assetModel.getAsset(), parameters);
            String staticRenditionName = r.getName();
            if (DamConstants.ORIGINAL_FILE.equalsIgnoreCase(r.getName())) {
                staticRenditionName = assetModel.getName();
            }

            extension = StringUtils.substringAfterLast(staticRenditionName, ".");
        }

        if (StringUtils.isNotBlank(extension)) {
            details.put(RENDITION_DETAILS_EXTENSION, extension);
        }

        return details;
    }


    @Override
    public boolean accepts(AssetModel assetModel, String renditionName) {
        return getRenditionNames().contains(renditionName);
    }

    private Rendition findRendition(final Asset asset, final AssetRenditionParameters parameters) {
        return asset.getRendition(new PatternRenditionPicker(mappings.get(parameters.getRenditionName())));
    }

    @Activate
    protected void activate(Cfg cfg) {
        this.cfg = cfg;

        this.mappings = super.parseMappingsAsPatterns(cfg.rendition_mappings());
    }

    @ObjectClassDefinition(name = "Asset Share Commons - Rendition Dispatcher - Static Renditions")
    public @interface Cfg {
        @AttributeDefinition
        String webconsole_configurationFactory_nameHint() default "{name} [ {label} ] @ {service.ranking}";

        @AttributeDefinition(
                name = "Name",
                description = "The system name of this Rendition Dispatcher. This should be unique across all AssetRenditionDispatcher instances."
        )
        String name() default "static";

        @AttributeDefinition(
                name = "Label",
                description = "The human-friendly name of this AssetRenditionDispatcher and may be displayed to authors."
        )
        String label() default "Static Renditions";

        @AttributeDefinition(
                name = "Rendition types",
                description = "The types of renditions this configuration will return. Ideally all renditions in this configuration apply types specified here. This is used to drive and scope the Asset Renditions displays in Authoring datasources. OOTB types are: `image` and `video`"
        )
        String[] types() default {};

        @AttributeDefinition(
                name = "Hide renditions",
                description = "Hide if not intended to be exposed to authors.",
                type = AttributeType.BOOLEAN
        )
        boolean hidden() default false;

        @AttributeDefinition(
                name = "Static rendition mappings",
                description = "In the form: <renditionName>" + OSGI_PROPERTY_VALUE_DELIMITER + "<renditionPickerPattern>"
        )
        String[] rendition_mappings() default {};

        @AttributeDefinition(
                name = "Service ranking",
                description = "The larger the number, the higher the precedence.",
                type = AttributeType.INTEGER
        )
        int service_ranking() default 0;
    }

    /** Picks the first rendition that matches the provided pattern. */
    protected class PatternRenditionPicker implements RenditionPicker {
        private final Pattern pattern;

        public PatternRenditionPicker(Pattern pattern) {
            this.pattern = pattern;
        }

        /**
         * @param asset the asset whose renditions should be searched.
         *
         * @return the rendition whose name matches the provided pattern, or null if non match.
         */
        @Override
        public Rendition getRendition(Asset asset) {
            if (pattern == null) {
                return null;
            }

            return asset.getRenditions().stream()
                    .filter(r -> pattern.matcher(r.getName()).matches())
                    .findFirst()
                    .orElse(null);
        }
    }
}
