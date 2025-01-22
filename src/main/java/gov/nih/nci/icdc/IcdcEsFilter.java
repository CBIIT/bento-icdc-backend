package gov.nih.nci.icdc;

// import gov.nih.nci.bento.constants.Const;
import com.google.gson.*;
import gov.nih.nci.bento.model.AbstractPrivateESDataFetcher;
// import gov.nih.nci.bento.model.search.yaml.YamlQueryFactory;
import gov.nih.nci.bento.service.ESService;
import graphql.schema.idl.RuntimeWiring;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.client.Request;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static graphql.schema.idl.TypeRuntimeWiring.newTypeWiring;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import kong.unirest.UnirestException;
import org.yaml.snakeyaml.Yaml;

@Component
public class IcdcEsFilter extends AbstractPrivateESDataFetcher {
    private static final Logger logger = LogManager.getLogger(IcdcEsFilter.class);
    // private final YamlQueryFactory yamlQueryFactory;

    // parameters used in queries
    final String PAGE_SIZE = "first";
    final String OFFSET = "offset";
    final String ORDER_BY = "order_by";
    final String SORT_DIRECTION = "sort_direction";
    final String FILTER_TEXT = "filter_text";

    final String PROGRAMS_END_POINT = "/programs/_search";
    final String PROGRAMS_COUNT_END_POINT = "/programs/_count";
    final String STUDIES_END_POINT = "/studies/_search";
    final String STUDIES_COUNT_END_POINT = "/studies/_count";

    final String CASES_END_POINT = "/cases/_search";
    final String CASES_COUNT_END_POINT = "/cases/_count";
    final String SAMPLES_END_POINT = "/samples/_search";
    final String SAMPLES_COUNT_END_POINT = "/samples/_count";
    final String FILES_END_POINT = "/files/_search";
    final String FILES_COUNT_END_POINT = "/files/_count";
    final String NODES_END_POINT = "/model_nodes/_search";
    final String NODES_COUNT_END_POINT = "/model_nodes/_count";
    final String PROPERTIES_END_POINT = "/model_properties/_search";
    final String PROPERTIES_COUNT_END_POINT = "/model_properties/_count";
    final String VALUES_END_POINT = "/model_values/_search";
    final String VALUES_COUNT_END_POINT = "/model_values/_count";
    final String GS_ABOUT_END_POINT = "/about_page/_search";
    final String GS_MODEL_END_POINT = "/data_model/_search";

    final int GS_LIMIT = 10;
    final String GS_END_POINT = "endpoint";
    final String GS_COUNT_ENDPOINT = "count_endpoint";
    final String GS_RESULT_FIELD = "result_field";
    final String GS_COUNT_RESULT_FIELD = "count_result_field";
    final String GS_SEARCH_FIELD = "search_field";
    final String GS_COLLECT_FIELDS = "collect_fields";
    final String GS_SORT_FIELD = "sort_field";
    final String GS_CATEGORY_TYPE = "type";
    final String GS_ABOUT = "about";
    final String GS_HIGHLIGHT_FIELDS = "highlight_fields";
    final String GS_HIGHLIGHT_DELIMITER = "$";
    final Set<String> RANGE_PARAMS = Set.of();

    final String GS_ABOUT_PG_SORT_FIELD = "title";
    final String GS_MODEL_PAGE_SORT_FIELD = "node_name";

    final String RAW_MANIFEST_YAML_URL = "https://raw.githubusercontent.com/CBIIT/icdc-model-tool/develop/model-desc/icdc-manifest-props.yml";
    final String EXPORT_PROPS = "ExportProps";
    final String PROPERTY = "property";
    final String DISPLAY = "display";
    static final Map<String, String> STATIC_PROPS;
    static {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        map.put("file_name", "name");
        map.put("drs_uri", "drs_uri");
        map.put("clinical_study_designation", "Study Code");
        map.put("case_id", "Case ID");
        STATIC_PROPS = Collections.unmodifiableMap(map);
    }

    @Autowired
    ESService esService;

    private Gson gson = new GsonBuilder().serializeNulls().create();

    public IcdcEsFilter(ESService esService) {
        super(esService);
        // yamlQueryFactory = new YamlQueryFactory(esService);
    }

    @Override
    public RuntimeWiring buildRuntimeWiring() {
        return RuntimeWiring.newRuntimeWiring()
                .type(newTypeWiring("QueryType")
                        // .dataFetchers(yamlQueryFactory.createYamlQueries(Const.ES_ACCESS_TYPE.PRIVATE))
                        .dataFetcher("searchCases", env -> {
                            Map<String, Object> args = env.getArguments();
                            return searchCases(args);
                        })
                        .dataFetcher("caseOverview", env -> {
                            Map<String, Object> args = env.getArguments();
                            return caseOverview(args);
                        })
                        .dataFetcher("sampleOverview", env -> {
                            Map<String, Object> args = env.getArguments();
                            return sampleOverview(args);
                        })
                        .dataFetcher("fileOverview", env -> {
                            Map<String, Object> args = env.getArguments();
                            return fileOverview(args);
                        })
                        .dataFetcher("globalSearch", env -> {
                            Map<String, Object> args = env.getArguments();
                            return globalSearch(args);
                        })
                        .dataFetcher("createManifest", env -> {
                            Map<String, Object> args = env.getArguments();
                            return createManifest(args);
                        })
                )
                .build();
    }

    private Map<String, Object> searchCases(Map<String, Object> params) throws IOException {
        // cast case_ids param to lowercase to standardize user input
        Map<String, Object> formattedParams = formatParams(params, "case_ids", "case_id_lc");
        
        final String AGG_NAME = "agg_name";
        final String AGG_ENDPOINT = "agg_endpoint";
        final String WIDGET_QUERY = "widgetQueryName";
        final String FILTER_COUNT_QUERY = "filterCountQueryName";

        // Query related values
        final List<Map<String, String>> TERM_AGGS = new ArrayList<>();
        TERM_AGGS.add(Map.of(
                AGG_NAME, "program",
                WIDGET_QUERY, "caseCountByProgram",
                FILTER_COUNT_QUERY, "filterCaseCountByProgram",
                AGG_ENDPOINT, CASES_END_POINT
        ));
        TERM_AGGS.add(Map.of(
                AGG_NAME, "study",
                WIDGET_QUERY, "caseCountByStudyCode",
                FILTER_COUNT_QUERY, "filterCaseCountByStudyCode",
                AGG_ENDPOINT, CASES_END_POINT
        ));
        TERM_AGGS.add(Map.of(
                AGG_NAME, "study_type",
                WIDGET_QUERY, "caseCountByStudyType",
                FILTER_COUNT_QUERY, "filterCaseCountByStudyType",
                AGG_ENDPOINT, CASES_END_POINT
        ));
        TERM_AGGS.add(Map.of(
                AGG_NAME, "biobank",
                WIDGET_QUERY, "caseCountByBiobank",
                FILTER_COUNT_QUERY, "filterCaseCountByBiobank",
                AGG_ENDPOINT, CASES_END_POINT
        ));
        TERM_AGGS.add(Map.of(
                AGG_NAME, "study_participation",
                WIDGET_QUERY, "caseCountByStudyParticipation",
                FILTER_COUNT_QUERY, "filterCaseCountByStudyParticipation",
                AGG_ENDPOINT, CASES_END_POINT
        ));
        TERM_AGGS.add(Map.of(
                AGG_NAME, "breed",
                WIDGET_QUERY, "caseCountByBreed",
                FILTER_COUNT_QUERY, "filterCaseCountByBreed",
                AGG_ENDPOINT, CASES_END_POINT
        ));
        TERM_AGGS.add(Map.of(
                AGG_NAME, "diagnosis",
                WIDGET_QUERY, "caseCountByDiagnosis",
                FILTER_COUNT_QUERY, "filterCaseCountByDiagnosis",
                AGG_ENDPOINT, CASES_END_POINT
        ));
        TERM_AGGS.add(Map.of(
                AGG_NAME, "disease_site",
                WIDGET_QUERY, "caseCountByDiseaseSite",
                FILTER_COUNT_QUERY, "filterCaseCountByDiseaseSite",
                AGG_ENDPOINT, CASES_END_POINT
        ));
        TERM_AGGS.add(Map.of(
                AGG_NAME, "stage_of_disease",
                WIDGET_QUERY,"caseCountByStageOfDisease",
                FILTER_COUNT_QUERY, "filterCaseCountByStageOfDisease",
                AGG_ENDPOINT, CASES_END_POINT
        ));
        TERM_AGGS.add(Map.of(
                AGG_NAME, "response_to_treatment",
                WIDGET_QUERY,"caseCountByResponseToTreatment",
                FILTER_COUNT_QUERY, "filterCaseCountByResponseToTreatment",
                AGG_ENDPOINT, CASES_END_POINT
        ));
        TERM_AGGS.add(Map.of(
                AGG_NAME, "sex",
                WIDGET_QUERY, "caseCountByGender",
                FILTER_COUNT_QUERY, "filterCaseCountBySex",
                AGG_ENDPOINT, CASES_END_POINT
        ));
        TERM_AGGS.add(Map.of(
                AGG_NAME, "neutered_status",
                WIDGET_QUERY, "caseCountByNeuteredStatus",
                FILTER_COUNT_QUERY, "filterCaseCountByNeuteredStatus",
                AGG_ENDPOINT, CASES_END_POINT
        ));

        TERM_AGGS.add(Map.of(
                AGG_NAME, "sample_site",
                WIDGET_QUERY, "caseCountBySampleSite",
                FILTER_COUNT_QUERY, "filterCaseCountBySampleSite",
                AGG_ENDPOINT, SAMPLES_END_POINT
        ));
        TERM_AGGS.add(Map.of(
                AGG_NAME, "sample_type",
                WIDGET_QUERY, "caseCountBySampleType",
                FILTER_COUNT_QUERY, "filterCaseCountBySampleType",
                AGG_ENDPOINT, SAMPLES_END_POINT
        ));
        TERM_AGGS.add(Map.of(
                AGG_NAME, "sample_pathology",
                WIDGET_QUERY, "caseCountBySamplePathology",
                FILTER_COUNT_QUERY, "filterCaseCountBySamplePathology",
                AGG_ENDPOINT, SAMPLES_END_POINT
        ));
        TERM_AGGS.add(Map.of(
                AGG_NAME, "file_association",
                WIDGET_QUERY, "caseCountByFileAssociation",
                FILTER_COUNT_QUERY, "filterCaseCountByFileAssociation",
                AGG_ENDPOINT, FILES_END_POINT
        ));
        TERM_AGGS.add(Map.of(
                AGG_NAME, "file_type",
                WIDGET_QUERY, "caseCountByFileType",
                FILTER_COUNT_QUERY, "filterCaseCountByFileType",
                AGG_ENDPOINT, FILES_END_POINT
        ));
        TERM_AGGS.add(Map.of(
                AGG_NAME, "file_format",
                WIDGET_QUERY, "caseCountByFileFormat",
                FILTER_COUNT_QUERY, "filterCaseCountByFileFormat",
                AGG_ENDPOINT, FILES_END_POINT
        ));

        List<String> agg_names = new ArrayList<>();
        for (var agg: TERM_AGGS) {
            agg_names.add(agg.get(AGG_NAME));
        }
        final String[] TERM_AGG_NAMES = agg_names.toArray(new String[TERM_AGGS.size()]);

        Map<String, Object> query = esService.buildFacetFilterQuery(formattedParams, Set.of(), Set.of("first", FILTER_TEXT));
        if (!formattedParams.get(FILTER_TEXT).toString().isEmpty()) {
            String filterText = formattedParams.get(FILTER_TEXT).toString();
            query = buildTableFilterQuery(filterText, null, query);
        }
        Request sampleCountRequest = new Request("GET", SAMPLES_COUNT_END_POINT);
        sampleCountRequest.setJsonEntity(gson.toJson(query));
        JsonObject sampleCountResult = esService.send(sampleCountRequest);
        int numberOfSamples = sampleCountResult.get("count").getAsInt();

        Request fileCountRequest = new Request("GET", FILES_COUNT_END_POINT);
        fileCountRequest.setJsonEntity(gson.toJson(query));
        JsonObject fileCountResult = esService.send(fileCountRequest);
        int numberOfFiles = fileCountResult.get("count").getAsInt();

        Request studyFileCountRequest = new Request("GET", FILES_COUNT_END_POINT);
        Map<String, Object> studyFileParam = new HashMap<>(formattedParams);
        studyFileParam.put("file_level", List.of("study"));
        Map<String, Object> studyFileQuery = esService.buildFacetFilterQuery(studyFileParam, Set.of(), Set.of("first"));
        studyFileCountRequest.setJsonEntity(gson.toJson(studyFileQuery));
        JsonObject studyFileCountResult = esService.send(studyFileCountRequest);
        int numberOfStudyFiles = studyFileCountResult.get("count").getAsInt();

        Request caseCountRequest = new Request("GET", CASES_COUNT_END_POINT);
        caseCountRequest.setJsonEntity(gson.toJson(query));
        JsonObject caseCountResult = esService.send(caseCountRequest);
        int numberOfCases = caseCountResult.get("count").getAsInt();

        // Get aggregations
        Map<String, Object> aggQuery = esService.addAggregations(query, TERM_AGG_NAMES);
        Request caseRequest = new Request("GET", CASES_END_POINT);
        caseRequest.setJsonEntity(gson.toJson(aggQuery));
        JsonObject caseResult = esService.send(caseRequest);
        Map<String, JsonArray> aggs = esService.collectTermAggs(caseResult, TERM_AGG_NAMES);

        Map<String, Object> data = new HashMap<>();
        data.put("numberOfPrograms", aggs.get("program").size());
        data.put("numberOfStudies", aggs.get("study").size());
        data.put("numberOfCases", numberOfCases);
        data.put("numberOfSamples", numberOfSamples);
        data.put("numberOfFiles", numberOfFiles);
        data.put("numberOfStudyFiles", numberOfStudyFiles);
        data.put("numberOfAliquots", 0);
        data.put("volumeOfData", getVolumeOfData(formattedParams, "file_size", FILES_END_POINT));

        data.put("programsAndStudies", programsAndStudies(formattedParams));

        // widgets data and facet filter counts
        for (var agg: TERM_AGGS) {
            String field = agg.get(AGG_NAME);
            String widgetQueryName = agg.get(WIDGET_QUERY);
            String filterCountQueryName = agg.get(FILTER_COUNT_QUERY);
            String endpoint = agg.get(AGG_ENDPOINT);
            // subjectCountByXXXX
            List<Map<String, Object>> widgetData;
            if (endpoint.equals(CASES_END_POINT)) {
                widgetData = getGroupCountHelper(aggs.get(field));
                data.put(widgetQueryName, widgetData);
            } else {
                widgetData = subjectCountBy(field, formattedParams, endpoint);;
                data.put(widgetQueryName, widgetData);
            }
            // filterSubjectCountByXXXX
            if (formattedParams.containsKey(field) && ((List<String>)formattedParams.get(field)).size() > 0) {
                List<Map<String, Object>> filterCount = filterSubjectCountBy(field, formattedParams, endpoint);;
                data.put(filterCountQueryName, filterCount);
            } else {
                data.put(filterCountQueryName, widgetData);
            }
        }

        return data;
    }

    private double getVolumeOfData(Map<String, Object> params, String fieldName, String indexName) throws IOException {
        Map<String, Object> query = esService.buildFacetFilterQuery(params);
        query = esService.addSumAggregation(query, fieldName);
        Request request = new Request("GET", indexName);
        request.setJsonEntity(gson.toJson(query));
        JsonObject jsonObject = esService.send(request);
        return esService.retrieveSumAgg(jsonObject, fieldName);
    }

    private List<Map<String, Object>> programsAndStudies(Map<String, Object> params) throws IOException {
        final String category = "program";
        final String subCategory = "study_code";

        String[] subCategories = new String[] { subCategory };
        Map<String, Object> query = esService.buildFacetFilterQuery(params);
        String[] AGG_NAMES = new String[] {category};
        query = esService.addAggregations(query, AGG_NAMES);
        esService.addSubAggregations(query, category, subCategories);
        Request request = new Request("GET", CASES_END_POINT);
        request.setJsonEntity(gson.toJson(query));
        JsonObject jsonObject = esService.send(request);
        Map<String, JsonArray> aggs = esService.collectTermAggs(jsonObject, AGG_NAMES);
        JsonArray buckets = aggs.get(category);

        List<Map<String, Object>> data = new ArrayList<>();
        for (JsonElement group: buckets) {
            List<Map<String, Object>> studies = new ArrayList<>();

            for (JsonElement studyElement: group.getAsJsonObject().get(subCategory).getAsJsonObject().get("buckets").getAsJsonArray()) {
                JsonObject study = studyElement.getAsJsonObject();
                int size = study.get("doc_count").getAsInt();
                studies.add(Map.of(
                        "study", study.get("key").getAsString(),
                        "caseSize", size
                ));
            }
            data.add(Map.of("program", group.getAsJsonObject().get("key").getAsString(),
                    "caseSize", group.getAsJsonObject().get("doc_count").getAsInt(),
                    "studies", studies
            ));
        }
        return data;
    }

    private List<Map<String, Object>> subjectCountBy(String category, Map<String, Object> params, String endpoint) throws IOException {
        Map<String, Object> query = esService.buildFacetFilterQuery(params, RANGE_PARAMS, Set.of(PAGE_SIZE));
        return getGroupCount(category, query, endpoint);
    }

    private List<Map<String, Object>> filterSubjectCountBy(String category, Map<String, Object> params, String endpoint) throws IOException {
        Map<String, Object> query = esService.buildFacetFilterQuery(params, RANGE_PARAMS, Set.of(PAGE_SIZE, category));
        return getGroupCount(category, query, endpoint);
    }

    private List<Map<String, Object>> getGroupCount(String category, Map<String, Object> query, String endpoint) throws IOException {
        String[] AGG_NAMES = new String[] {category};
        query = esService.addAggregations(query, AGG_NAMES);
        Request request = new Request("GET", endpoint);
        request.setJsonEntity(gson.toJson(query));
        JsonObject jsonObject = esService.send(request);
        Map<String, JsonArray> aggs = esService.collectTermAggs(jsonObject, AGG_NAMES);
        JsonArray buckets = aggs.get(category);
        return getGroupCountHelper(buckets);
    }

    private List<Map<String, Object>> getGroupCountHelper(JsonArray buckets) throws IOException {
        List<Map<String, Object>> data = new ArrayList<>();
        for (JsonElement group: buckets) {
            data.add(Map.of("group", group.getAsJsonObject().get("key").getAsString(),
                    "count", group.getAsJsonObject().get("doc_count").getAsInt()
            ));

        }
        return data;
    }

    private List<Map<String, Object>> caseOverview(Map<String, Object> params) throws IOException {
        Map<String, Object> formattedParams = formatParams(params, "case_ids", "case_id_lc");

        final String[][] PROPERTIES = new String[][]{
                new String[]{"case_id", "case_id_kw"},
                new String[]{"case_id_lc", "case_id_lc"},
                new String[]{"study_code", "study_code"},
                new String[]{"study_type", "study_type"},
                new String[]{"cohort", "cohort"},
                new String[]{"breed", "breed"},
                new String[]{"diagnosis", "diagnosis"},
                new String[]{"stage_of_disease", "stage_of_disease"},
                new String[]{"age", "age"},
                new String[]{"sex", "sex"},
                new String[]{"neutered_status", "neutered_status"},
                new String[]{"weight", "weight"},
                new String[]{"response_to_treatment", "response_to_treatment"},
                new String[]{"disease_site", "disease_site"},
                new String[]{"files", "files"},
                new String[]{"other_cases", "other_cases"},
                new String[]{"individual_id", "individual_id"},
                new String[]{"primary_disease_site", "disease_site"},
                new String[]{"date_of_diagnosis", "date_of_diagnosis"},
                new String[]{"histology_cytopathology", "histology_cytopathology"},
                new String[]{"histological_grade", "histological_grade"},
                new String[]{"pathology_report", "pathology_report"},
                new String[]{"treatment_data", "treatment_data"},
                new String[]{"follow_up_data", "follow_up_data"},
                new String[]{"concurrent_disease", "concurrent_disease"},
                new String[]{"concurrent_disease_type", "concurrent_disease_type"},
                new String[]{"arm", "arm"}
        };

        String defaultSort = "case_id_lc"; // Default sort order

        Map<String, String> mapping = Map.ofEntries(
                Map.entry("study_code", "study_code"),
                Map.entry("study_type", "study_type"),
                Map.entry("cohort", "cohort"),
                Map.entry("breed", "breed"),
                Map.entry("diagnosis", "diagnosis"),
                Map.entry("stage_of_disease", "stage_of_disease"),
                Map.entry("disease_site", "disease_site"),
                Map.entry("age", "age"),
                Map.entry("sex", "sex"),
                Map.entry("neutered_status", "neutered_status"),
                Map.entry("weight", "weight"),
                Map.entry("response_to_treatment", "response_to_treatment"),
                Map.entry("other_cases", "other_cases"),
                Map.entry("case_id", "case_id_kw"),
                Map.entry("case_id_lc", "case_id_lc")
        );

        return overview(CASES_END_POINT, formattedParams, PROPERTIES, defaultSort, mapping);
    }

    private List<Map<String, Object>> sampleOverview(Map<String, Object> params) throws IOException {
        Map<String, Object> formattedParams = formatParams(params, "case_ids", "case_id_lc");

        final String[][] PROPERTIES = new String[][]{
                new String[]{"sample_id", "sample_ids"},
                new String[]{"case_id", "case_ids"},
                new String[]{"case_id_lc", "case_id_lc"},
                new String[]{"breed", "breed"},
                new String[]{"diagnosis", "diagnosis"},
                new String[]{"sample_site", "sample_site"},
                new String[]{"sample_type", "sample_type"},
                new String[]{"sample_pathology", "sample_pathology"},
                new String[]{"tumor_grade", "tumor_grade"},
                new String[]{"sample_chronology", "sample_chronology"},
                new String[]{"percentage_tumor", "percentage_tumor"},
                new String[]{"necropsy_sample", "necropsy_sample"},
                new String[]{"sample_preservation", "sample_preservation"},
                new String[]{"files", "files"},
                new String[]{"physical_sample_type", "physical_sample_type"},
                new String[]{"general_sample_pathology", "general_sample_pathology"},
                new String[]{"tumor_sample_origin", "tumor_sample_origin"},
                new String[]{"comment", "comment"},
                new String[]{"individual_id", "individual_id"},
                new String[]{"other_cases", "other_cases"},
                new String[]{"patient_age_at_enrollment", "patient_age_at_enrollment"},
                new String[]{"sex", "sex"},
                new String[]{"neutered_indicator", "neutered_indicator"},
                new String[]{"weight", "weight"},
                new String[]{"primary_disease_site", "primary_disease_site"},
                new String[]{"stage_of_disease", "stage_of_disease"},
                new String[]{"date_of_diagnosis", "date_of_diagnosis"},
                new String[]{"histology_cytopathology", "histology_cytopathology"},
                new String[]{"histological_grade", "histological_grade"},
                new String[]{"best_response", "best_response"},
                new String[]{"pathology_report", "pathology_report"},
                new String[]{"treatment_data", "treatment_data"},
                new String[]{"follow_up_data", "follow_up_data"},
                new String[]{"concurrent_disease", "concurrent_disease"},
                new String[]{"concurrent_disease_type", "concurrent_disease_type"},
                new String[]{"cohort_description", "cohort_description"},
                new String[]{"arm", "arm"}
        };

        String defaultSort = "sample_ids"; // Default sort order

        Map<String, String> mapping = Map.ofEntries(
                Map.entry("sample_id", "sample_ids"),
                Map.entry("case_id", "case_ids"),
                Map.entry("breed", "breed"),
                Map.entry("diagnosis", "diagnosis"),
                Map.entry("sample_site", "sample_site"),
                Map.entry("sample_type", "sample_type"),
                Map.entry("sample_pathology", "sample_pathology"),
                Map.entry("tumor_grade", "tumor_grade"),
                Map.entry("sample_chronology", "sample_chronology"),
                Map.entry("percentage_tumor", "percentage_tumor"),
                Map.entry("necropsy_sample", "necropsy_sample"),
                Map.entry("sample_preservation", "sample_preservation"),
                Map.entry("case_id_lc", "case_id_lc")
        );

        return overview(SAMPLES_END_POINT, formattedParams, PROPERTIES, defaultSort, mapping);
    }

    private List<Map<String, Object>> fileOverview(Map<String, Object> params) throws IOException {
        Map<String, Object> formattedParams = formatParams(params, "case_ids", "case_id_lc");

        // Following String array of arrays should be in form of "GraphQL_field_name", "ES_field_name"
        final String[][] PROPERTIES = new String[][]{
                new String[]{"file_name", "file_name"},
                new String[]{"file_type", "file_type"},
                new String[]{"association", "file_association"},
                new String[]{"file_description", "file_description"},
                new String[]{"file_format", "file_format"},
                new String[]{"file_size", "file_size"},
                new String[]{"case_id", "case_ids"},
                new String[]{"case_id_lc", "case_id_lc"},
                new String[]{"breed", "breed"},
                new String[]{"diagnosis", "diagnosis"},
                new String[]{"study_code", "study_code"},
                new String[]{"file_uuid", "file_uuids"},
                new String[]{"sample_id", "sample_ids"},
                new String[]{"sample_site", "sample_site"},
                new String[]{"physical_sample_type", "physical_sample_type"},
                new String[]{"general_sample_pathology", "general_sample_pathology"},
                new String[]{"tumor_sample_origin", "tumor_sample_origin"},
                new String[]{"summarized_sample_type", "summarized_sample_type"},
                new String[]{"specific_sample_pathology", "specific_sample_pathology"},
                new String[]{"date_of_sample_collection", "date_of_sample_collection"},
                new String[]{"tumor_grade", "tumor_grade"},
                new String[]{"sample_chronology", "sample_chronology"},
                new String[]{"percentage_tumor", "percentage_tumor"},
                new String[]{"necropsy_sample", "necropsy_sample"},
                new String[]{"sample_preservation", "sample_preservation"},
                new String[]{"comment", "comment"},
                new String[]{"individual_id", "individual_id"},
                new String[]{"patient_age_at_enrollment", "patient_age_at_enrollment"},
                new String[]{"sex", "sex"},
                new String[]{"neutered_indicator", "neutered_indicator"},
                new String[]{"weight", "weight"},
                new String[]{"primary_disease_site", "primary_disease_site"},
                new String[]{"stage_of_disease", "stage_of_disease"},
                new String[]{"date_of_diagnosis", "date_of_diagnosis"},
                new String[]{"histology_cytopathology", "histology_cytopathology"},
                new String[]{"histological_grade", "histological_grade"},
                new String[]{"best_response", "best_response"},
                new String[]{"pathology_report", "pathology_report"},
                new String[]{"treatment_data", "treatment_data"},
                new String[]{"follow_up_data", "follow_up_data"},
                new String[]{"concurrent_disease", "concurrent_disease"},
                new String[]{"concurrent_disease_type", "concurrent_disease_type"},
                new String[]{"cohort_description", "cohort_description"},
                new String[]{"arm", "arm"},
                new String[]{"other_cases", "other_cases"}
        };

        String defaultSort = "file_name"; // Default sort order

        Map<String, String> mapping = Map.ofEntries(
                Map.entry("file_name", "file_name"),
                Map.entry("file_type", "file_type"),
                Map.entry("association", "file_association"),
                Map.entry("file_description", "file_description"),
                Map.entry("file_format", "file_format"),
                Map.entry("file_size", "file_size"),
                Map.entry("case_id", "case_ids"),
                Map.entry("breed", "breed"),
                Map.entry("diagnosis", "diagnosis"),
                Map.entry("study_code", "study_code"),
                Map.entry("file_uuid", "file_uuids"),
                Map.entry("access_file", "file_size"),
                Map.entry("case_id_lc", "case_id_lc")
        );

        return overview(FILES_END_POINT, formattedParams, PROPERTIES, defaultSort, mapping);
    }

    private List<Map<String, Object>> overview(String endpoint, Map<String, Object> params, String[][] properties, String defaultSort, Map<String, String> mapping) throws IOException {
        Request request = new Request("GET", endpoint);
        Map<String, Object> query = esService.buildFacetFilterQuery(params, Set.of(), Set.of(PAGE_SIZE, OFFSET, ORDER_BY, SORT_DIRECTION, FILTER_TEXT));
        String order_by = (String)params.get(ORDER_BY);
        String direction = ((String)params.get(SORT_DIRECTION)).toLowerCase();
        String filterText = (String)params.get(FILTER_TEXT);
        if (!filterText.isEmpty()){
            query = buildTableFilterQuery(filterText, properties, query);
        } else {
            query.put("sort", mapSortOrder(order_by, direction, defaultSort, mapping));
        }
        int pageSize = (int) params.get(PAGE_SIZE);
        int offset = (int) params.get(OFFSET);
        List<Map<String, Object>> page = esService.collectPage(request, query, properties, pageSize, offset);
        return page;
    }

    private Map<String, Object> buildTableFilterQuery(String filterText, String[][] properties, Map<String, Object> query) {
        if (filterText == null || filterText.isEmpty()) return Map.of();

        // check if query already contains bool object
        Map<String, Object> boolQuery = (Map<String, Object>) query.getOrDefault("query", Map.of("bool", Map.of()));

        // extract must/filter objects from bool
        List<Object> must = new ArrayList<>((List<Object>) ((Map<String, Object>) boolQuery.getOrDefault("bool", Map.of())).getOrDefault("must", List.of()));
        List<Object> filter = new ArrayList<>((List<Object>) ((Map<String, Object>) boolQuery.getOrDefault("bool", Map.of())).getOrDefault("filter", List.of()));

        Map<String, Object> tableMultiMatch;

        if (properties != null) {
            // get analyzed versions of props defined in indices yaml
            List<String> fields = Arrays.stream(properties)
            .map(property -> property[1] + ".analyzed")
            .collect(Collectors.toList());

            tableMultiMatch = Map.of(
                "multi_match", Map.of(
                    "query", filterText,
                    "fields", fields,
                    "type", "best_fields",
                    "lenient", true
                )
            );
        } else {
            tableMultiMatch = Map.of(
                "multi_match", Map.of(
                    "query", filterText,
                    "lenient", true
                )
            );
        }

        // assemble query
        must.add(tableMultiMatch);
        boolQuery = Map.of(
            "bool", Map.of(
                "must", must,
                "filter", filter
            )
        );
        query.put("query", boolQuery);

        return query;
    }

    private Map<String, String> mapSortOrder(String order_by, String direction, String defaultSort, Map<String, String> mapping) {
        String sortDirection = direction;
        if (!sortDirection.equalsIgnoreCase("asc") && !sortDirection.equalsIgnoreCase("desc")) {
            sortDirection = "asc";
        }

        String sortOrder = defaultSort; // Default sort order
        if (mapping.containsKey(order_by)) {
            sortOrder = mapping.get(order_by);
        } else {
            logger.info("Order: \"" + order_by + "\" not recognized, use default order");
        }
        return Map.of(sortOrder, sortDirection);
    }

    private Map<String, Object> addHighlight(Map<String, Object> query, Map<String, Object> category) {
        Map<String, Object> result = new HashMap<>(query);
        List<String> searchFields = (List<String>)category.get(GS_SEARCH_FIELD);
        Map<String, Object> highlightClauses = new HashMap<>();
        for (String searchFieldName: searchFields) {
            highlightClauses.put(searchFieldName, Map.of());
        }

        result.put("highlight", Map.of(
                        "fields", highlightClauses,
                        "pre_tags", "",
                        "post_tags", "",
                        "fragment_size", 1
                )
        );
        return result;
    }

    private List paginate(List org, int pageSize, int offset) {
        List<Object> result = new ArrayList<>();
        int size = org.size();
        if (offset <= size -1) {
            int end_index = offset + pageSize;
            if (end_index > size) {
                end_index = size;
            }
            result = org.subList(offset, end_index);
        }
        return result;
    }

    private Map<String, Object> getGlobalSearchQuery(String input, Map<String, Object> category) {
        List<String> searchFields = (List<String>)category.get(GS_SEARCH_FIELD);
        List<Object> searchClauses = new ArrayList<>();
        for (String searchFieldName: searchFields) {
            searchClauses.add(Map.of("match_phrase_prefix", Map.of(searchFieldName, input)));
        }
        Map<String, Object> query = new HashMap<>();
        query.put("query", Map.of("bool", Map.of("should", searchClauses)));
        return query;
    }

    private List<Map<String, String>> searchAboutPage(String input) throws IOException {
        final String ABOUT_PARAGRAPH = "content.paragraph";
        final String ABOUT_ROW = "content.table.body.row";

        Map<String, Object> query = Map.of(
            "query", Map.of(
                "bool", Map.of(
                    "should", List.of(
                        Map.of("match", Map.of(ABOUT_PARAGRAPH, input)),
                        Map.of("match", Map.of(ABOUT_ROW, input))
                    )
                )
            ),
            "highlight", Map.of(
                "fields", Map.of(
                    ABOUT_PARAGRAPH, Map.of(),
                    ABOUT_ROW, Map.of()
                ),
                "pre_tags", GS_HIGHLIGHT_DELIMITER,
                "post_tags", GS_HIGHLIGHT_DELIMITER
            ),
            "size", ESService.MAX_ES_SIZE
        );

        Request request = new Request("GET", GS_ABOUT_END_POINT);
        request.setJsonEntity(gson.toJson(query));
        JsonObject jsonObject = esService.send(request);

        List<Map<String, String>> result = new ArrayList<>();

        for (JsonElement hit : jsonObject.get("hits").getAsJsonObject().get("hits").getAsJsonArray()) {
            JsonObject source = hit.getAsJsonObject().get("_source").getAsJsonObject();
            String page = source.get("page").getAsString();
            String title = source.get("title").getAsString();

            for (String field : List.of(ABOUT_PARAGRAPH, ABOUT_ROW)) {
                if (hit.getAsJsonObject().get("highlight").getAsJsonObject().has(field)) {
                    for (JsonElement highlight : hit.getAsJsonObject().get("highlight").getAsJsonObject().get(field).getAsJsonArray()) {
                        result.add(Map.of(
                            GS_CATEGORY_TYPE, GS_ABOUT,
                            "page", page,
                            "title", title,
                            "text", highlight.getAsString()
                        ));
                    }
                }
            }
        }

        return result;
    }

    private Map<String, Object> globalSearch(Map<String, Object> params) throws IOException {
        Map<String, Object> result = new HashMap<>();
        String input = (String) params.get("input");
        int size = (int) params.get("first");
        int offset = (int) params.get("offset");
        List<Map<String, Object>> searchCategories = new ArrayList<>();
        searchCategories.add(Map.of(
                GS_END_POINT, STUDIES_END_POINT,
                GS_COUNT_ENDPOINT, STUDIES_COUNT_END_POINT,
                GS_COUNT_RESULT_FIELD, "study_count",
                GS_RESULT_FIELD, "studies",
                GS_SEARCH_FIELD, List.of("program_id", "accession_id",
                        "clinical_study_name", "clinical_study_type", "clinical_study_designation"),
                GS_SORT_FIELD, "accession_id_kw",
                GS_COLLECT_FIELDS, new String[][]{
                        new String[]{"program_id", "program_id"},
                        new String[]{"accession_id", "accession_id"},
                        new String[]{"clinical_study_name", "clinical_study_name"},
                        new String[]{"clinical_study_type", "clinical_study_type"},
                        new String[]{"clinical_study_designation", "clinical_study_designation"}
                },
                GS_CATEGORY_TYPE, "study"
        ));
        searchCategories.add(Map.of(
                GS_END_POINT, SAMPLES_END_POINT,
                GS_COUNT_ENDPOINT, SAMPLES_COUNT_END_POINT,
                GS_COUNT_RESULT_FIELD, "sample_count",
                GS_RESULT_FIELD, "samples",
                GS_SEARCH_FIELD, List.of("sample_ids_txt", "program_name", "clinical_study_designation",
                    "case_ids_txt", "sample_site_txt", "physical_sample_type_txt", "general_sample_pathology_txt"),
                GS_SORT_FIELD, "sample_ids",
                GS_COLLECT_FIELDS, new String[][]{
                        new String[]{"sample_id", "sample_ids_txt"},
                        new String[]{"program_name", "program_name"},
                        new String[]{"clinical_study_designation", "clinical_study_designation"},
                        new String[]{"case_id", "case_ids_txt"},
                        new String[]{"sample_site", "sample_site_txt"},
                        new String[]{"physical_sample_type", "physical_sample_type_txt"},
                        new String[]{"general_sample_pathology", "general_sample_pathology_txt"}
                },
                GS_CATEGORY_TYPE, "sample"
        ));
        searchCategories.add(Map.of(
                GS_END_POINT, CASES_END_POINT,
                GS_COUNT_ENDPOINT, CASES_COUNT_END_POINT,
                GS_COUNT_RESULT_FIELD, "case_count",
                GS_RESULT_FIELD, "cases",
                GS_SEARCH_FIELD, List.of("case_ids", "program_name", "clinical_study_designation",
                    "disease_term", "breed_txt"),
                GS_SORT_FIELD, "case_id_kw",
                GS_COLLECT_FIELDS, new String[][]{
                        new String[]{"case_id", "case_ids"},
                        new String[]{"program_name", "program_name"},
                        new String[]{"clinical_study_designation", "clinical_study_designation"},
                        new String[]{"disease_term", "disease_term"},
                        new String[]{"breed", "breed_txt"}
                },
                GS_CATEGORY_TYPE, "case"
        ));
        searchCategories.add(Map.of(
                GS_END_POINT, FILES_END_POINT,
                GS_COUNT_ENDPOINT, FILES_COUNT_END_POINT,
                GS_COUNT_RESULT_FIELD, "file_count",
                GS_RESULT_FIELD, "files",
                GS_SEARCH_FIELD, List.of( "sample_ids_txt", "file_name_txt",
                        "file_type_txt", "case_ids_txt", "program_name", "clinical_study_designation_txt"),
                GS_SORT_FIELD, "file_name",
                GS_COLLECT_FIELDS, new String[][]{
                        new String[]{"sample_id", "sample_ids_txt"},
                        new String[]{"file_name", "file_name_txt"},
                        new String[]{"file_type", "file_type_txt"},
                        new String[]{"file_association", "file_association"},
                        new String[]{"case_id", "case_ids_txt"},
                        new String[]{"program_name", "program_name"},
                        new String[]{"clinical_study_designation", "clinical_study_designation_txt"}
                },
                GS_CATEGORY_TYPE, "file"
        ));
        searchCategories.add(Map.of(
                GS_END_POINT, PROGRAMS_END_POINT,
                GS_COUNT_ENDPOINT, PROGRAMS_COUNT_END_POINT,
                GS_COUNT_RESULT_FIELD, "program_count",
                GS_RESULT_FIELD, "programs",
                GS_SEARCH_FIELD, List.of("program_name", "program_short_description", "program_acronym",
                        "program_external_url", "program_id"),
                GS_SORT_FIELD, "program_acronym_kw",
                GS_COLLECT_FIELDS, new String[][]{
                        new String[]{"program_id", "program_id"},
                        new String[]{"program_name", "program_name"},
                        new String[]{"program_short_description", "program_short_description"},
                        new String[]{"program_acronym", "program_acronym"},
                        new String[]{"program_external_url", "program_external_url"},
                },
                GS_CATEGORY_TYPE, "program"
        ));
        searchCategories.add(Map.of(
                GS_END_POINT, NODES_END_POINT,
                GS_COUNT_ENDPOINT, NODES_COUNT_END_POINT,
                GS_COUNT_RESULT_FIELD, "model_count",
                GS_RESULT_FIELD, "model",
                GS_SEARCH_FIELD, List.of("node"),
                GS_SORT_FIELD, "node_kw",
                GS_COLLECT_FIELDS, new String[][]{
                        new String[]{"node_name", "node"}
                },
                GS_HIGHLIGHT_FIELDS, new String[][] {
                        new String[]{"highlight", "node"}
                },
                GS_CATEGORY_TYPE, "node"
        ));
        searchCategories.add(Map.of(
                GS_END_POINT, PROPERTIES_END_POINT,
                GS_COUNT_ENDPOINT, PROPERTIES_COUNT_END_POINT,
                GS_COUNT_RESULT_FIELD, "model_count",
                GS_RESULT_FIELD, "model",
                GS_SEARCH_FIELD, List.of("property", "property_description", "property_type", "property_required"),
                GS_SORT_FIELD, "property_kw",
                GS_COLLECT_FIELDS, new String[][]{
                        new String[]{"node_name", "node"},
                        new String[]{"property_name", "property"},
                        new String[]{"property_type", "property_type"},
                        new String[]{"property_required", "property_required"},
                        new String[]{"property_description", "property_description"}
                },
                GS_HIGHLIGHT_FIELDS, new String[][] {
                        new String[]{"highlight", "property"},
                        new String[]{"highlight", "property_description"},
                        new String[]{"highlight", "property_type"},
                        new String[]{"highlight", "property_required"}
                },
                GS_CATEGORY_TYPE, "property"
        ));
        searchCategories.add(Map.of(
                GS_END_POINT, VALUES_END_POINT,
                GS_COUNT_ENDPOINT, VALUES_COUNT_END_POINT,
                GS_COUNT_RESULT_FIELD, "model_count",
                GS_RESULT_FIELD, "model",
                GS_SEARCH_FIELD, List.of("value"),
                GS_SORT_FIELD, "value_kw",
                GS_COLLECT_FIELDS, new String[][]{
                        new String[]{"node_name", "node"},
                        new String[]{"property_name", "property"},
                        new String[]{"property_type", "property_type"},
                        new String[]{"property_required", "property_required"},
                        new String[]{"property_description", "property_description"},
                        new String[]{"value", "value"}
                },
                GS_HIGHLIGHT_FIELDS, new String[][] {
                        new String[]{"highlight", "value"}
                },
                GS_CATEGORY_TYPE, "value"
        ));

        Set<String> combinedCategories = Set.of("model");

        for (Map<String, Object> category: searchCategories) {
            String countResultFieldName = (String) category.get(GS_COUNT_RESULT_FIELD);
            String resultFieldName = (String) category.get(GS_RESULT_FIELD);
            String[][] properties = (String[][]) category.get(GS_COLLECT_FIELDS);
            String[][] highlights = (String[][]) category.get(GS_HIGHLIGHT_FIELDS);
            Map<String, Object> query = getGlobalSearchQuery(input, category);

            // Get count
            Request countRequest = new Request("GET", (String) category.get(GS_COUNT_ENDPOINT));
            countRequest.setJsonEntity(gson.toJson(query));
            JsonObject countResult = esService.send(countRequest);
            int oldCount = (int)result.getOrDefault(countResultFieldName, 0);
            result.put(countResultFieldName, countResult.get("count").getAsInt() + oldCount);

            // Get results
            Request request = new Request("GET", (String)category.get(GS_END_POINT));
            String sortFieldName = (String)category.get(GS_SORT_FIELD);
            query.put("sort", Map.of(sortFieldName, "asc"));
            query = addHighlight(query, category);

            if (combinedCategories.contains(resultFieldName)) {
                query.put("size", ESService.MAX_ES_SIZE);
                query.put("from", 0);
            } else {
                query.put("size", size);
                query.put("from", offset);
            }
            request.setJsonEntity(gson.toJson(query));
            JsonObject jsonObject = esService.send(request);
            List<Map<String, Object>> objects = esService.collectPage(jsonObject, properties, highlights, (int)query.get("size"), 0);

            for (var object: objects) {
                object.put(GS_CATEGORY_TYPE, category.get(GS_CATEGORY_TYPE));
            }

            List<Map<String, Object>> existingObjects = (List<Map<String, Object>>)result.getOrDefault(resultFieldName, null);
            if (existingObjects != null) {
                existingObjects.addAll(objects);
                result.put(resultFieldName, existingObjects);
            } else {
                result.put(resultFieldName, objects);
            }
        }

        List<Map<String, String>> about_results = searchAboutPage(input);
        int about_count = about_results.size();
        // Sort About page result set
        if (about_results != null && about_count > 0) {
            about_results.sort(Comparator.comparing(m -> (String) m.get(GS_ABOUT_PG_SORT_FIELD)));
        }
        result.put("about_count", about_count);
        result.put("about_page", paginate(about_results, size, offset));

        for (String category: combinedCategories) {
            // Sort Model combined result sets
            List<Map<String, Object>> GSModelObjects = (List<Map<String, Object>>)result.get(category);
            if (GSModelObjects != null && GSModelObjects.size() > 0) {
                // replace alphanumeric val and sort result set
                GSModelObjects.sort(Comparator.comparing(m -> ((String) m.get(GS_MODEL_PAGE_SORT_FIELD))
                  .replaceAll("[^a-zA-Z0-9]", " ")
                ));
            }

            List<Object> pagedCategory = paginate((List)result.get(category), size, offset);
            result.put(category, pagedCategory);
        }
        return result;
    }

    private static Map<String, Object> formatParams(Map<String, Object> params, String targetParam, String lowercaseParam) {
        Map<String, Object> formattedParams = new HashMap<>();

        params.forEach((key, value) -> {
            if (key.equals(targetParam)) {
                try {
                    ArrayList<String> valuesList = (ArrayList<String>) value;
                    ArrayList<String> lowercase = new ArrayList<>();
                    valuesList.forEach(x -> {
                        lowercase.add(x.toLowerCase());
                    });
                    formattedParams.put(lowercaseParam, lowercase);
                } catch (Exception e) {
                    logger.error(e);
                }
            } else {
                formattedParams.put(key, value);
            }
        });

        return formattedParams;
    }

    private Map<String, Object> getManifestYaml(String url) throws UnirestException {
        HttpResponse<String> response = Unirest.get(url).asString();
        if (response.getStatus() == 200) {
            String yamlData = response.getBody();
            try {
                Yaml yaml = new Yaml();
                return yaml.load(yamlData);
            } catch (Exception e) {
                throw new UnirestException("Failed to parse YAML: " + e.getMessage());
            }
        } else {
            throw new UnirestException("Failed to fetch manifest YAML from URL: " + url + ", status: " + response.getStatus());
        }
    }

    private Map<String, String> parseYamlExportProps(Map<String, Object> yamlData) {
        Map<String, String> exportPropsMap = new LinkedHashMap<>();

        for (Map.Entry<String, Object> yamlEntry: yamlData.entrySet()) {
            Map<String, Object> nodeProps = (Map<String, Object>) yamlEntry.getValue();
            for (Map.Entry<String, Object> propEntry : nodeProps.entrySet()) {
                Object propValue = propEntry.getValue();
                if (propValue instanceof Map) {
                    Map<String, List<Map<String, String>>> propsMap = (Map<String, List<Map<String, String>>>) propValue;
                    for (Map.Entry<String, List<Map<String, String>>> propsMapEntry : propsMap.entrySet()) {
                        String key = propsMapEntry.getKey();
                        List<Map<String, String>> propList = propsMapEntry.getValue();
                        for (Map<String, String> prop : propList) {
                            String property = prop.get(PROPERTY);
                            String display = prop.get(DISPLAY);
                            if (key.equals(EXPORT_PROPS)) {
                                exportPropsMap.put(property, display);
                            }
                        }
                    }
                }
            }
        }
        return exportPropsMap;
    }

    private LinkedHashMap<String, String> combinePropsMaps(Map<String, String> staticProps, Map<String, String> exportProps) {
        LinkedHashMap<String, String> combinedPropsMap = new LinkedHashMap<>();
        for (Map.Entry<String, String> staticEntry : staticProps.entrySet()) {
            combinedPropsMap.put(staticEntry.getKey(), staticEntry.getValue());
        }
        for (Map.Entry<String, String> exportEntry : exportProps.entrySet()) {
            combinedPropsMap.put(exportEntry.getKey(), exportEntry.getValue());
        }
        return combinedPropsMap;
    }

    private String[][] convertMapToPropArray(Map<String, String> map) {
        String[][] propArray = new String[map.size()][2];
        int i = 0;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            propArray[i][0] = entry.getKey();
            propArray[i][1] = entry.getKey();
            i++;
        }
        return propArray;
    }

    private String buildManifestCSV(LinkedHashMap<String, String> manifestProps, List<Map<String, Object>> overviewData) {
        StringBuilder csvBuilder = new StringBuilder();
        String headers = manifestProps.values().stream().collect(Collectors.joining(","));
        csvBuilder.append(headers).append("\n");

        for (Map<String, Object> record : overviewData) {
            List<String> row = new ArrayList<>();
            for (String key : manifestProps.keySet()) {
                Object value = record.get(key);
                if (value != null) {
                    String stringValue = value.toString();
                    if (stringValue.contains(",") || stringValue.contains("\"")) {
                        stringValue = "\"" + stringValue.replace("\"", "\"\"") + "\"";
                    }
                    row.add(stringValue);
                } else {
                    row.add("");
                }
            }
            csvBuilder.append(String.join(",", row)).append("\n");
        }

        return csvBuilder.toString();
    }

    private String createManifest(Map<String, Object> params) throws IOException, UnirestException {
        Map<String, Object> yamlData = getManifestYaml(RAW_MANIFEST_YAML_URL);
        Map<String, String> manifestExportProps = parseYamlExportProps(yamlData);
        LinkedHashMap<String, String> combinedManifestProps = combinePropsMaps(STATIC_PROPS, manifestExportProps);
        
        final String[][] PROPERTIES = convertMapToPropArray(combinedManifestProps);
        String defaultSort = "file_name";
        Map<String, String> mapping = Map.ofEntries(
                Map.entry("file_name", "file_name"),
                Map.entry("file_type", "file_type"),
                Map.entry("file_association", "file_association"),
                Map.entry("file_description", "file_description"),
                Map.entry("file_format", "file_format"),
                Map.entry("file_size", "file_size"),
                Map.entry("case_ids", "case_ids"),
                Map.entry("breed", "breed"),
                Map.entry("diagnosis", "diagnosis"),
                Map.entry("study_code", "study_code"),
                Map.entry("uuid", "uuid"),
                Map.entry("md5sum", "md5sum"),
                Map.entry("individual_id", "individual_id"),
                Map.entry("access_file", "access_file"),
                Map.entry("sample_id", "sample_id")
        );

        List<Map<String, Object>> overview = overview(FILES_END_POINT, params, PROPERTIES, defaultSort, mapping);

        return buildManifestCSV(combinedManifestProps, overview);
    }
}
