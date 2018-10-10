/*
 * (C) Copyright 2018 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Gethin James
 */
package org.nuxeo.ai.model.export;

import static java.util.Collections.emptyList;
import static org.nuxeo.ai.AIConstants.EXPORT_ACTION_NAME;
import static org.nuxeo.ai.AIConstants.EXPORT_FEATURES_PARAM;
import static org.nuxeo.ai.AIConstants.EXPORT_SPLIT_PARAM;
import static org.nuxeo.ai.model.AiDocumentTypeConstants.CORPUS_JOBID;
import static org.nuxeo.ai.model.AiDocumentTypeConstants.CORPUS_TYPE;
import static org.nuxeo.ai.pipes.functions.PropertyUtils.IMAGE_TYPE;
import static org.nuxeo.ai.pipes.functions.PropertyUtils.NAME_PROP;
import static org.nuxeo.ai.pipes.functions.PropertyUtils.TEXT_TYPE;
import static org.nuxeo.ai.pipes.functions.PropertyUtils.TYPE_PROP;
import static org.nuxeo.ai.pipes.functions.PropertyUtils.propsToTypedList;
import static org.nuxeo.ecm.core.schema.FacetNames.HIDDEN_IN_NAVIGATION;
import static org.nuxeo.elasticsearch.ElasticSearchConstants.AGG_CARDINALITY;
import static org.nuxeo.elasticsearch.ElasticSearchConstants.AGG_MISSING;
import static org.nuxeo.elasticsearch.ElasticSearchConstants.AGG_SIZE_PROP;
import static org.nuxeo.elasticsearch.ElasticSearchConstants.AGG_TYPE_TERMS;

import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.search.aggregations.Aggregation;
import org.nuxeo.ai.model.AiDocumentTypeConstants;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.bulk.BulkService;
import org.nuxeo.ecm.core.bulk.message.BulkCommand;
import org.nuxeo.ecm.platform.query.api.Bucket;
import org.nuxeo.ecm.platform.query.core.AggregateDescriptor;
import org.nuxeo.elasticsearch.aggregate.AggregateEsBase;
import org.nuxeo.elasticsearch.aggregate.AggregateFactory;
import org.nuxeo.elasticsearch.api.ElasticSearchService;
import org.nuxeo.elasticsearch.api.EsResult;
import org.nuxeo.elasticsearch.query.NxQueryBuilder;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.DefaultComponent;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Exports data
 */
public class DatasetExportServiceImpl extends DefaultComponent implements DatasetExportService, DatasetStatsService {

    public static final PathRef PARENT_PATH = new PathRef("/" + CORPUS_TYPE);

    public static final Properties EMPTY_PROPS = new Properties();

    public static final String NUXEO_FOLDER = "Folder";

    public static final String STATS_TOTAL = "total";

    public static final String STATS_COUNT = "count";

    public static final String DEFAULT_NUM_BUCKETS = "20";

    /**
     * Make an Aggregate using AggregateFactory.
     */
    protected static AggregateEsBase<? extends Aggregation, ? extends Bucket> makeAggregate(String type, String field,
                                                                                            Properties properties) {
        AggregateDescriptor descriptor = new AggregateDescriptor();
        descriptor.setId(aggKey(field, type));
        descriptor.setDocumentField(field);
        descriptor.setType(type);
        properties.forEach((key, value) -> descriptor.setProperty((String) key, (String) value));
        return AggregateFactory.create(descriptor, null);
    }

    protected static String aggKey(String propName, String s) {
        return s + "_" + propName;
    }

    @Override
    public String export(CoreSession session, String nxql,
                         Collection<String> inputProperties, Collection<String> outputProperties, int split) {

        validateParams(nxql, inputProperties, outputProperties);

        if (split < 1 || split > 100) {
            throw new IllegalArgumentException("Dataset split value is a percentage between 1 and 100");
        }

        List<Map<String, String>> inputs = propsToTypedList(inputProperties);
        List<Map<String, String>> outputs = propsToTypedList(outputProperties);

        DocumentModel corpus = createCorpus(session, nxql, inputs, outputs, split);

        List<String> featuresList = new ArrayList<>(inputProperties);
        featuresList.addAll(outputProperties);
        BulkCommand bulkCommand = new BulkCommand.Builder(EXPORT_ACTION_NAME, nxql)
                .repository(session.getRepositoryName())
                .user(session.getPrincipal().getName())
                .param(EXPORT_FEATURES_PARAM, String.join(",", featuresList))
                .param(EXPORT_SPLIT_PARAM, String.valueOf(split)).build();
        String bulkId = Framework.getService(BulkService.class).submit(bulkCommand);
        corpus.setPropertyValue(CORPUS_JOBID, bulkId);
        session.saveDocument(corpus);
        return bulkId;
    }

    /**
     * Validate if the specified params are correct.
     */
    protected void validateParams(String nxql, Collection<String> inputProperties, Collection<String> outputProperties) {
        if (StringUtils.isBlank(nxql)
                || inputProperties == null || inputProperties.isEmpty()
                || outputProperties == null || outputProperties.isEmpty()) {
            throw new IllegalArgumentException("nxql and properties are required parameters");
        }
        if (!nxql.toUpperCase().contains("WHERE")) {
            throw new IllegalArgumentException("You cannot use an unbounded nxql query, please add a WHERE clause.");
        }
    }

    /**
     * Create a corpus document for the data export.
     */
    public DocumentModel createCorpus(CoreSession session, String query,
                                      List<Map<String, String>> inputs, List<Map<String, String>> outputs, int split) {
        DocumentModel doc = session.createDocumentModel(getRootFolder(session), "corpor1", CORPUS_TYPE);
        doc.setPropertyValue(AiDocumentTypeConstants.CORPUS_QUERY, query);
        doc.setPropertyValue(AiDocumentTypeConstants.CORPUS_SPLIT, split);
        doc.setPropertyValue(AiDocumentTypeConstants.CORPUS_INPUTS, (Serializable) inputs);
        doc.setPropertyValue(AiDocumentTypeConstants.CORPUS_OUTPUTS, (Serializable) outputs);
        return session.createDocument(doc);
    }

    /**
     * Create the root folder if it doesn't exist
     */
    protected String getRootFolder(CoreSession session) {
        if (!session.exists(PARENT_PATH)) {
            DocumentModel doc = session.createDocumentModel("/", CORPUS_TYPE, NUXEO_FOLDER);
            doc.addFacet(HIDDEN_IN_NAVIGATION);
            session.createDocument(doc);
        }
        return PARENT_PATH.toString();
    }

    @Override
    public Collection<Statistic> getStatistics(CoreSession session, String nxql,
                                               Collection<String> inputProperties, Collection<String> outputProperties) {
        validateParams(nxql, inputProperties, outputProperties);
        List<String> featuresList = new ArrayList<>(inputProperties);
        featuresList.addAll(outputProperties);
        List<Map<String, String>> featuresWithType = propsToTypedList(featuresList);

        List<Statistic> stats = new ArrayList<>();
        NxQueryBuilder qb = new NxQueryBuilder(session).nxql(nxql).limit(0);
        Long total = getOverallStats(featuresWithType, stats, qb);
        if (total < 1) {
            return emptyList();
        }
        qb = new NxQueryBuilder(session).nxql(notNullNxql(nxql, featuresList)).limit(0);
        getValidStats(featuresWithType, total, stats, qb);
        return stats;
    }

    /**
     * Get the stats for the smaller dataset of valid values.
     */
    protected void getValidStats(List<Map<String, String>> featuresWithType,
                                 long total, List<Statistic> stats, NxQueryBuilder qb) {
        for (Map<String, String> prop : featuresWithType) {
            String propName = prop.get(NAME_PROP);
            switch (prop.get(TYPE_PROP)) {
                case TEXT_TYPE:
                    Properties termProps = new Properties();
                    termProps.setProperty(AGG_SIZE_PROP, DEFAULT_NUM_BUCKETS);
                    qb.addAggregate(makeAggregate(AGG_TYPE_TERMS, propName, termProps));
                    qb.addAggregate(makeAggregate(AGG_CARDINALITY, propName, EMPTY_PROPS));
                    break;
                case IMAGE_TYPE:
                    qb.addAggregate(makeAggregate(AGG_CARDINALITY, contentProperty(propName), EMPTY_PROPS));
                default:
                    // Only 2 types at the moment, we would need numeric type in the future.
            }
        }

        EsResult esResult = Framework.getService(ElasticSearchService.class).queryAndAggregate(qb);
        stats.addAll(esResult.getAggregates().stream().map(Statistic::from).collect(Collectors.toList()));
        stats.add(Statistic.of(STATS_COUNT, STATS_COUNT, STATS_COUNT, null,
                               esResult.getElasticsearchResponse().getHits().getTotalHits()));
    }

    /**
     * Gets the overall stats for the dataset, before considering if the fields are valid.
     */
    protected Long getOverallStats(List<Map<String, String>> featuresWithType, List<Statistic> stats, NxQueryBuilder qb) {

        for (Map<String, String> prop : featuresWithType) {
            String propName = prop.get(NAME_PROP);
            switch (prop.get(TYPE_PROP)) {
                case TEXT_TYPE:
                    qb.addAggregate(makeAggregate(AGG_MISSING, propName, EMPTY_PROPS));
                    break;
                case IMAGE_TYPE:
                    qb.addAggregate(makeAggregate(AGG_MISSING, contentProperty(propName), EMPTY_PROPS));
                default:
                    // Only 2 types at the moment, we would need numeric type in the future.
            }
        }
        EsResult esResult = Framework.getService(ElasticSearchService.class).queryAndAggregate(qb);
        stats.addAll(esResult.getAggregates().stream().map(Statistic::from).collect(Collectors.toList()));
        Long total = esResult.getElasticsearchResponse().getHits().getTotalHits();
        stats.add(Statistic.of(STATS_TOTAL, STATS_TOTAL, STATS_TOTAL, null, total));
        return total;
    }

    protected String contentProperty(String propName) {
        return propName + ".digest";
    }

    protected String notNullNxql(String nxql, List<String> featuresList) {
        StringBuilder buffy = new StringBuilder(nxql);
        featuresList.forEach(f -> buffy.append(" AND ").append(f).append(" IS NOT NULL"));
        return buffy.toString();
    }
}
