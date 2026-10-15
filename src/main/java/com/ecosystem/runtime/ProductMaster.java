package com.ecosystem.runtime;

import com.ecosystem.runtime.debug.InvocationDebug;
import com.ecosystem.runtime.validation.PropertiesValidationService;
import com.ecosystem.plugin.business.BusinessLogic;
import com.ecosystem.plugin.customer.PostScoreSuper;
import com.ecosystem.utils.GlobalSettings;
import com.ecosystem.utils.PropertiesPathResolver;
import com.ecosystem.utils.JSONDecode;

import com.ecosystem.utils.JSONFlattener;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.ecosystem.utils.log.LogManager;
import com.ecosystem.utils.log.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.security.RolesAllowed;
import java.io.IOException;
import java.net.URLDecoder;
import java.text.ParseException;
import java.util.Map;
import java.util.UUID;

import static com.ecosystem.utils.GenerateUUID.generateUUID;

@CrossOrigin(origins = "*")
@RolesAllowed({"ADMIN", "USER"})
@RestController()
@SecurityScheme(type = SecuritySchemeType.APIKEY)
@ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successful",
                content = { @Content(mediaType = "application/json",
                        schema = @Schema(implementation = ProductMaster.class)) }),
        @ApiResponse(responseCode = "400", description = "Error",
                content = @Content),
        @ApiResponse(responseCode = "404", description = "Error",
                content = @Content) })
@Tag(name = "Predictors", description = "Review model domain details, refresh model parameter loading and perform predictions. " +
        "There are two primary approaches to invoking a prediction for scoring via a model namely; " +
        "Invoke model and return a JSON response that can be used in any application," +
        " invoke model and deliver result onto a KAFKA topic of your choosing. " +
        " Model can also be tested by dynamically loading a MOJO, mostly used for testing purposes." +
        "The predictor parameters are broken into two types namely, requiring all parameters via API or requiring a lookup key via API and extracting parameters from a data source." +
        "Use this format for input params: \n " +
        "{'name':'predict1', 'mojo':'model_mojo.zip','dbparam':false,'input': ['x','y'],'value': ['val_x', 'val_y']}" +
        "\nUse this approach for inputs from data source:\n" +
        "{'name':'predict1', 'mojo':'model_mojo.zip','dbparam':true, lookup:{key:'customer',value:1234567890}} " +
        "\nIf there is post-scoring logic, then ise this configuration:\n" +
        "{'name':'predict1', 'mojo': '1','mab': {'class':'mabone', 'epsilon':0.4},'dbparam': true, lookup:{key:'customer',value: 1234567890}, param: {key:'value_field', value:30}}\n")

public class ProductMaster extends ProductMasterSuper {

    private static final Logger LOGGER = LogManager.getLogger(ProductMaster.class.getName());

    public ProductMaster() {
        super();
    }

    /**
     * Primary Scoring Endpoint for Inference.
     * Supports both legacy single-campaign mode and multi-campaign mode.
     *
     * Multi-Campaign Mode: If a campaign is specified and exists in activeContexts,
     * the request is routed to the isolated campaign context.
     *
     * Legacy Mode: Falls back to the original ecosystemMaster for backwards compatibility.
     *
     * @param request JSON payload
     * @return Prediction result
     */
    @Operation(summary = "If no parameters are provided then the current use-case name is used and user is none. "
            +" Invocation payload: {\"campaign\":\"name\",\"subcampaign\":\"none\"," +
            "\"customer\":\"1111\",\"channel\":\"app\",\"numberoffers\":1,\"userid\":\"test\",\"params\":\"{}\"} " +
            "Note that the params will be different when a dynamic interaction model is used.",
            requestBody = @RequestBody(
                    description = "JSON payload for model invocation. The 'params' field is an escaped JSON string.",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(type = "object"),
                            examples = {
                                    @ExampleObject(
                                            name = "Basic Invocation",
                                            summary = "Standard invocation",
                                            value = "{\"campaign\":\"name\",\"subcampaign\":\"none\",\"customer\":\"1111\",\"channel\":\"app\",\"numberoffers\":1,\"userid\":\"test\",\"params\":\"{}\"}"
                                    ),
                                    @ExampleObject(
                                            name = "Invocation with Params",
                                            summary = "Invocation with dynamic parameters",
                                            value = "{\"campaign\":\"name\",\"subcampaign\":\"none\",\"customer\":\"1111\",\"channel\":\"app\",\"numberoffers\":1,\"userid\":\"test\",\"params\":\"{\\\"input\\\":[\\\"x\\\",\\\"y\\\"],\\\"value\\\":[\\\"val_x\\\",\\\"val_y\\\"]}\"}"
                                    ),
                                    @ExampleObject(
                                            name = "Debug Invocation",
                                            summary = "Return pipeline diagnostics in response.debug",
                                            value = "{\"campaign\":\"name\",\"subcampaign\":\"none\",\"customer\":\"1111\",\"channel\":\"app\",\"numberoffers\":1,\"userid\":\"test\",\"params\":\"{}\",\"debug\":\"true\"}"
                                    )
                            }
                    )
            )
    )
    @PostMapping("/invocations")
    public String invoke(
            @RequestHeader Map<String, String> headers,
            @org.springframework.web.bind.annotation.RequestBody String request
    ) {
        LOGGER.info("/invocations API");
        JSONObject predictResult = new JSONObject();
        boolean debugRequested = false;

        try {
            JSONObject inpObj = new JSONObject(request);
            debugRequested = InvocationDebug.isTruthy(inpObj.opt("debug"));
            if (debugRequested) {
                InvocationDebug.enable();
            }

            /************ Validate and use defaults ***********/
            String campaign = settings.getProjectDeploymentID();
            if (inpObj.has("campaign"))
                campaign = String.valueOf(inpObj.get("campaign"));
            InvocationDebug.setCampaignId(campaign);

            // Check if this campaign exists in multi-campaign mode
            if (campaignExists(campaign)) {
                LOGGER.info("/invocations: Routing to multi-campaign context: " + campaign);
                return finalizeInvocationResponse(invokeWithContext(campaign, inpObj));
            }

            // Fall back to legacy single-campaign mode
            LOGGER.info("/invocations: Using legacy single-campaign mode for: " + campaign);
            return finalizeInvocationResponse(invokeLegacy(inpObj, campaign));

        } catch (Exception e) {
            e.printStackTrace();
            InvocationDebug.record("ProductMaster", "E000", e.getMessage(), e);
            predictResult.put("ErrorMessage", e.getMessage());
            this.UPDATE = setFinal(false, predictResult);
            return finalizeInvocationResponse(predictResult.toString());
        } finally {
            if (debugRequested) {
                InvocationDebug.clear();
            }
        }
    }

    private String finalizeInvocationResponse(String responseJson) {
        if (!InvocationDebug.isEnabled() || responseJson == null || responseJson.isBlank()) {
            return responseJson;
        }
        try {
            JSONObject result = new JSONObject(responseJson);
            InvocationDebug.attach(result);
            return result.toString();
        } catch (Exception e) {
            LOGGER.warn("ProductMaster:finalizeInvocationResponse: could not attach debug payload: " + e.getMessage());
            return responseJson;
        }
    }

    private void recordNestedPredictErrors(JSONObject predictResult) {
        if (!InvocationDebug.isEnabled() || predictResult == null || !predictResult.has("predict_result")) {
            return;
        }
        try {
            JSONObject nested = predictResult.getJSONObject("predict_result");
            if (nested.has("ErrorMessage")) {
                InvocationDebug.record(
                        "PredictPipeline",
                        "E001",
                        String.valueOf(nested.get("ErrorMessage")));
            }
        } catch (Exception ignored) {
            // predict_result may be absent or non-object
        }
    }

    /**
     * Execute prediction using a multi-campaign context.
     */
    private String invokeWithContext(String campaignId, JSONObject inpObj) {
        JSONObject predictResult = new JSONObject();

        try {
            CampaignAwarePredictor predictor = getCampaignPredictor(campaignId);
            if (predictor == null) {
                predictResult.put("ErrorMessage", "Campaign context not found: " + campaignId);
                InvocationDebug.record("ProductMaster", "E001", "Campaign context not found: " + campaignId);
                return predictResult.toString();
            }

            CampaignContext context = getCampaignContext(campaignId);
            GlobalSettings contextSettings = context.getSettings();

            String subcampaign = campaignId;
            if (inpObj.has("subcampaign"))
                subcampaign = String.valueOf(inpObj.get("subcampaign"));

            String channel = "api";
            if (inpObj.has("channel"))
                channel = String.valueOf(inpObj.get("channel"));

            int numberoffers = 1;
            if (inpObj.has("numberoffers"))
                numberoffers = Integer.parseInt(String.valueOf(inpObj.get("numberoffers")));

            String userid = "api";
            if (inpObj.has("userid"))
                userid = String.valueOf(inpObj.get("userid"));

            String params = "{}";
            if (inpObj.has("params"))
                params = (String) String.valueOf(inpObj.get("params"));

            String customer = "none";
            if (inpObj.has("customer"))
                customer = String.valueOf(inpObj.get("customer"));

            JSONObject paramsParams = new JSONObject();
            try {
                String in_params = URLDecoder.decode(params);
                if (in_params.startsWith("\"")) in_params = in_params.substring(1, in_params.length() - 1).replaceAll("\\\\", "");
                paramsParams = new JSONObject(in_params);
            } catch (org.json.JSONException e) {
                LOGGER.info("/invocations malformed params JSON input: " + params);
                InvocationDebug.record("ProductMaster", "E002", "Malformed params JSON", e);
                return paramsParams.put("ErrorMessage", e).toString();
            }

            InvocationDebug.snapshotRequest(inpObj, paramsParams);

            // Setup parameters
            JSONObject param = new JSONObject();
            String uuid = generateUUID();
            param.put("uuid", uuid);
            param.put("UPDATE", this.UPDATE);
            LOGGER.info("/invocations:UUID: " + uuid + " campaign: " + campaignId + " (multi-campaign)");

            param.put("name", campaignId);
            param.put("customer", customer);
            param.put("campaign", campaignId);
            param.put("subcampaign", subcampaign);
            param.put("channel", channel);
            param.put("subname", subcampaign);
            param.put("resultcount", numberoffers);
            param.put("userid", userid);
            param.put("mojo", "1");

            JSONObject inParam = new JSONObject(param.toString());
            param.put("api_params", inParam);

            param.put("in_params", paramsParams);
            if (paramsParams.has("input")) {
                param.put("input", paramsParams.getJSONArray("input"));
                param.put("value", paramsParams.getJSONArray("value"));
                param.put("lookup", new JSONObject().put("value", customer).put("key", "customer"));
                param.put("dbparam", false);
            } else {
                param.put("dbparam", true);
                param = ValidateParams.getLookupFromParams(contextSettings, param, customer);
            }

            if (!paramsParams.has("mab")) {
                JSONObject mabParam = new JSONObject();
                mabParam.put("class", "mabone");
                mabParam.put("epsilon", contextSettings.getEpsilon());
                param.put("mab", mabParam);
            } else {
                param.put("mab", paramsParams.getJSONObject("mab"));
            }

            InvocationDebug.snapshotPipelineParams(param);

            // Execute prediction with campaign context
            predictResult = predictor.getPredictionResult(param);
            InvocationDebug.snapshotPipelineParams(param);
            if (param.has("in_params")) predictResult.put("in_params", param.getJSONObject("in_params"));
            if (predictResult.has("ErrorMessage")) {
                predictResult.put("error", 1);
            }
            recordNestedPredictErrors(predictResult);
            predictResult.remove("predict_result");

            String detail = "full";
            if (paramsParams.has("detail"))
                detail = paramsParams.getString("detail");

            // Handle special cases
            if (detail.contains("spam") || subcampaign.contains("spam")) {
                predictResult = extractSpamResult(predictResult);
            }

            if (paramsParams.has("flatten_json") && Boolean.valueOf(String.valueOf(paramsParams.opt("flatten_json")))) {
                predictResult = JSONFlattener.flatten(predictResult);
            }

        } catch (Exception e) {
            e.printStackTrace();
            InvocationDebug.record("ProductMaster", "E000", e.getMessage(), e);
            predictResult.put("ErrorMessage", e.getMessage());
        }

        this.UPDATE = setFinal(false, predictResult);
        return predictResult.toString();
    }

    /**
     * Execute prediction using legacy single-campaign mode.
     */
    private String invokeLegacy(JSONObject inpObj, String campaign) {
        JSONObject predictResult = new JSONObject();

        try {
            String subcampaign = campaign;
            if (inpObj.has("subcampaign"))
                subcampaign = String.valueOf(inpObj.get("subcampaign"));

            String channel = "api";
            if (inpObj.has("channel"))
                channel = String.valueOf(inpObj.get("channel"));

            int numberoffers = 1;
            if (inpObj.has("numberoffers"))
                numberoffers = Integer.parseInt(String.valueOf(inpObj.get("numberoffers")));

            String userid = "api";
            if (inpObj.has("userid"))
                userid = String.valueOf(inpObj.get("userid"));

            String params = "{}";
            if (inpObj.has("params"))
                params = String.valueOf(inpObj.get("params").toString());

            String customer = "none";
            if (inpObj.has("customer"))
                customer = String.valueOf(inpObj.get("customer"));

            JSONObject paramsParams = new JSONObject();
            try {
                String in_params = URLDecoder.decode(params);
                if (in_params.startsWith("\"")) in_params = in_params.substring(1, in_params.length() - 1).replaceAll("\\\\", "");
                paramsParams = new JSONObject(in_params);
            } catch (org.json.JSONException e) {
                LOGGER.info("/offerRecommendations malformed params JSON input: " + params);
                InvocationDebug.record("ProductMaster", "E002", "Malformed params JSON", e);
                return paramsParams.put("ErrorMessage", e).toString().intern();
            }

            InvocationDebug.snapshotRequest(inpObj, paramsParams);

            /************ Setup values from input params that will be placed in **********/
            JSONObject param = new JSONObject();
            String uuid = generateUUID();
            param.put("uuid", uuid);
            param.put("UPDATE", this.UPDATE);
            LOGGER.info("/invocations:UUID: " + uuid + " predictor: " + campaign + " (legacy)");

            param.put("name", campaign);
            param.put("customer", customer);
            param.put("campaign", campaign);
            param.put("subcampaign", subcampaign);
            param.put("channel", channel);
            param.put("subname", subcampaign);
            param.put("resultcount", numberoffers);
            param.put("userid", userid);
            param.put("mojo", "1");

            JSONObject inParam = new JSONObject(param.toString());
            param.put("api_params", inParam);

            param.put("in_params", paramsParams);
            if (paramsParams.has("input")) {
                param.put("input", paramsParams.getJSONArray("input"));
                param.put("value", paramsParams.getJSONArray("value"));
                param.put("lookup", new JSONObject().put("value", customer).put("key", "customer"));
                param.put("dbparam", false);
            } else {
                param.put("dbparam", true);
                param = ValidateParams.getLookupFromParams(settings, param, customer);
            }

            if (!paramsParams.has("mab")) {
                JSONObject mabParam = new JSONObject();
                mabParam.put("class", "mabone");
                mabParam.put("epsilon", settings.getEpsilon());
                param.put("mab", mabParam);
            } else {
                param.put("mab", paramsParams.getJSONObject("mab"));
            }

            InvocationDebug.snapshotPipelineParams(param);

            /**************** Primary prediction from EcosystemMaster.getPredictionResult **************/
            predictResult = ecosystemMaster.getPredictionResult(mongoClient, param);
            InvocationDebug.snapshotPipelineParams(param);
            if (param.has("in_params")) predictResult.put("in_params", param.getJSONObject("in_params"));
            if (predictResult.has("ErrorMessage")) {
                predictResult.put("error", 1);
            }
            recordNestedPredictErrors(predictResult);
            predictResult.remove("predict_result");

            String detail = "full";
            if (paramsParams.has("detail"))
                detail = paramsParams.getString("detail");

            /**************** Special prediction approaches: Spam **************/
            if (detail.contains("spam") || subcampaign.contains("spam")) {
                predictResult = extractSpamResult(predictResult);
            }

            /**************** Final step to decide if json needs to be flattened **************/
            if (paramsParams.has("flatten_json") && Boolean.valueOf(String.valueOf(paramsParams.opt("flatten_json")))) {
                predictResult = JSONFlattener.flatten(predictResult);
            }

        } catch (Exception e) {
            e.printStackTrace();
            InvocationDebug.record("ProductMaster", "E000", e.getMessage(), e);
            predictResult.put("ErrorMessage", e.getMessage());
        }

        this.UPDATE = setFinal(false, predictResult);
        return predictResult.toString();
    }

    /**
     * Extract spam-specific result from prediction.
     */
    private JSONObject extractSpamResult(JSONObject predictResult) {
        JSONObject newResult = new JSONObject();
        try {
            newResult.put("uuid", predictResult.getJSONArray("final_result").getJSONObject(0).getJSONObject("result_full").get("uuid"));
            newResult.put("offer", predictResult.getJSONArray("final_result").getJSONObject(0).getJSONObject("result_full").get("offer"));
            newResult.put("ham_confidence", predictResult.getJSONArray("final_result").getJSONObject(0).getJSONObject("result_full").get("ham_confidence"));
            newResult.put("spam_confidence", predictResult.getJSONArray("final_result").getJSONObject(0).getJSONObject("result_full").get("spam_confidence"));
            newResult.put("spam", predictResult.getJSONArray("final_result").getJSONObject(0).getJSONObject("result_full").get("spam"));
        } catch (Exception e) {
            LOGGER.warn("extractSpamResult: Could not extract spam fields: " + e.getMessage());
        }
        return newResult;
    }

    // ==================== Multi-Campaign Management Endpoints ====================

    /**
     * Create, refresh, load, or delete a campaign.
     * POST /refresh with JSON body containing campaign and operation options.
     *
     * Operations:
     * - Create/Refresh: {"campaign":"...", "properties":"..."}
     * - Create with JSON: {"campaign":"...", "properties":"...", "json":"..."}
     * - Load from file: {"campaign":"...", "load":true}
     * - Delete (memory only): {"campaign":"...", "delete":true}
     * - Delete with files: {"campaign":"...", "delete":true, "deleteFiles":true}
     * - Load all persisted: {"loadAll":true}
     */
    @Operation(summary = "Create, refresh, load, or delete a campaign. " +
            "Properties are persisted to ecosystem-{campaign}.properties. " +
            "To create/refresh: {\"campaign\":\"my_campaign\",\"properties\":\"predictor.name=my_campaign\\nmojo.key=model.zip\"} " +
            "To load from file: {\"campaign\":\"my_campaign\",\"load\":true} " +
            "To delete: {\"campaign\":\"my_campaign\",\"delete\":true,\"deleteFiles\":false}",
            requestBody = @RequestBody(
                    description = "JSON payload for campaign management",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(type = "object"),
                            examples = {
                                    @ExampleObject(
                                            name = "Create Campaign",
                                            summary = "Create or refresh a campaign with properties (persisted to file)",
                                            value = "{\"campaign\":\"my_campaign\",\"properties\":\"predictor.name=my_campaign\\nmojo.key=model.zip\\nplugin.postscore=com.ecosystem.plugin.customer.PostScoreBasic\"}"
                                    ),
                                    @ExampleObject(
                                            name = "Create with JSON",
                                            summary = "Create campaign with properties and JSON config",
                                            value = "{\"campaign\":\"my_campaign\",\"properties\":\"predictor.name=my_campaign\\nmojo.key=model.zip\",\"json\":\"{\\\"key\\\":\\\"value\\\"}\"}"
                                    ),
                                    @ExampleObject(
                                            name = "Load from File",
                                            summary = "Load campaign from persisted properties file",
                                            value = "{\"campaign\":\"my_campaign\",\"load\":true}"
                                    ),
                                    @ExampleObject(
                                            name = "Load All Persisted",
                                            summary = "Load all campaigns from persisted files",
                                            value = "{\"loadAll\":true}"
                                    ),
                                    @ExampleObject(
                                            name = "Delete Campaign (Memory)",
                                            summary = "Delete campaign from memory, keep files",
                                            value = "{\"campaign\":\"my_campaign\",\"delete\":true}"
                                    ),
                                    @ExampleObject(
                                            name = "Delete Campaign (With Files)",
                                            summary = "Delete campaign from memory and delete files",
                                            value = "{\"campaign\":\"my_campaign\",\"delete\":true,\"deleteFiles\":true}"
                                    )
                            }
                    )
            )
    )
    @PostMapping("/refresh")
    public String refreshCampaign(
            @RequestHeader Map<String, String> headers,
            @org.springframework.web.bind.annotation.RequestBody String request
    ) {
        LOGGER.info("/refresh POST API");

        try {
            JSONObject inpObj = new JSONObject(request);

            // Check for load all persisted campaigns
            if (inpObj.optBoolean("loadAll", false)) {
                LOGGER.info("/refresh: Loading all persisted campaigns");
                return loadAllPersistedCampaigns().toString();
            }

            // Check if this is a campaign-specific request
            if (inpObj.has("campaign")) {
                String campaignId = inpObj.getString("campaign");

                // Check for delete operation
                if (inpObj.optBoolean("delete", false)) {
                    boolean deleteFiles = inpObj.optBoolean("deleteFiles", false);
                    LOGGER.info("/refresh: Deleting campaign: " + campaignId + " (deleteFiles=" + deleteFiles + ")");
                    return deleteCampaign(campaignId, deleteFiles).toString();
                }

                // Check for load from file operation
                if (inpObj.optBoolean("load", false)) {
                    LOGGER.info("/refresh: Loading campaign from file: " + campaignId);
                    return loadCampaignFromFile(campaignId).toString();
                }

                // Create or refresh campaign with properties
                if (inpObj.has("properties")) {
                    String propertiesContent = inpObj.getString("properties");
                    String jsonContent = inpObj.optString("json", null);
                    LOGGER.info("/refresh: Creating/refreshing campaign: " + campaignId);
                    return createOrRefreshCampaign(campaignId, propertiesContent, jsonContent, true).toString();
                }

                // No properties provided - error
                JSONObject error = new JSONObject();
                error.put("success", false);
                error.put("error", "Properties content required for campaign creation. " +
                         "Use {\"campaign\":\"...\",\"properties\":\"...\"} or {\"campaign\":\"...\",\"load\":true}");
                return error.toString();
            }

            // No campaign specified - in multi-campaign mode, load all persisted campaigns
            // This is safer than falling back to legacy refresh which loads from default ecosystem.properties
            LOGGER.info("/refresh: No campaign specified - loading all persisted campaigns (multi-campaign mode)");
            return loadAllPersistedCampaigns().toString();

        } catch (Exception e) {
            LOGGER.error("/refresh error: " + e.getMessage());
            e.printStackTrace();
            JSONObject error = new JSONObject();
            error.put("success", false);
            error.put("error", e.getMessage());
            return error.toString();
        }
    }

    /**
     * List all persisted campaign files (not necessarily loaded in memory).
     */
    @Operation(summary = "List all persisted campaign property files")
    @GetMapping("/campaigns/persisted")
    public String getPersistedCampaigns(@RequestHeader Map<String, String> headers) {
        LOGGER.info("/campaigns/persisted GET API");
        com.ecosystem.utils.GlobalSettingsUpdate settingsUpdate = new com.ecosystem.utils.GlobalSettingsUpdate();
        java.util.List<String> persisted = settingsUpdate.listPersistedCampaigns();

        JSONObject result = new JSONObject();
        result.put("persisted_campaigns", new JSONArray(persisted));
        result.put("total", persisted.size());

        // Also show which are currently active in memory
        JSONArray active = new JSONArray();
        for (String campaignId : persisted) {
            if (campaignExists(campaignId)) {
                active.put(campaignId);
            }
        }
        result.put("active_in_memory", active);
        return result.toString();
    }

    /**
     * Non-secret snapshot for verifying multi-campaign path resolution and loaded context.
     */
    @Operation(summary = "Verify properties paths and optional campaign execution snapshot (no credentials)")
    @GetMapping("/campaigns/execution-test")
    public String getCampaignExecutionTest(
            @RequestHeader Map<String, String> headers,
            @RequestParam(required = false) String campaignId
    ) {
        LOGGER.info("/campaigns/execution-test GET API");
        JSONObject out = new JSONObject();
        out.put("properties_base_directory", PropertiesPathResolver.getPropertiesBaseDirectoryString());
        out.put("ecosystem_properties_path", PropertiesPathResolver.getLegacyPropertiesFilePath());
        JSONArray active = new JSONArray();
        for (String id : getActiveCampaignIds()) {
            active.put(id);
        }
        out.put("active_campaigns", active);
        if (campaignId != null && !campaignId.isEmpty()) {
            CampaignContext ctx = getCampaignContext(campaignId);
            JSONObject snap = new JSONObject();
            snap.put("loaded", ctx != null);
            if (ctx != null) {
                GlobalSettings gs = ctx.getSettings();
                snap.put("predictor_name", gs.getProjectDeploymentID());
                snap.put("plugin_post_score", gs.getPluginPostScore());
                snap.put("plugin_pre_score", gs.getPluginPreScore());
                snap.put("models_loaded", ctx.getTotalModels());
                com.ecosystem.utils.GlobalSettingsUpdate su = new com.ecosystem.utils.GlobalSettingsUpdate();
                snap.put("campaign_properties_path", su.getCampaignPropertiesFile(campaignId));
                snap.put("campaign_json_path", su.getCampaignJsonFile(campaignId));
                snap.put("campaign_settings_param_key", PostScoreSuper.CAMPAIGN_SETTINGS_KEY);
            }
            out.put("campaign_snapshot", snap);
        }
        return out.toString();
    }

    /**
     * Structured validation of properties, plugins, and data connections (Mongo/Cassandra probes use limit-10 reads; no credentials or document bodies).
     */
    @Operation(summary = "Validate properties and data connections for legacy deployment or a single campaign")
    @GetMapping("/validation/properties")
    public String getPropertiesValidation(
            @RequestHeader Map<String, String> headers,
            @RequestParam(required = false) String campaignId
    ) {
        LOGGER.info("/validation/properties GET API campaignId=" + campaignId);
        try {
            return PropertiesValidationService.validate(this, campaignId).toString();
        } catch (Exception e) {
            LOGGER.error("/validation/properties error: " + e.getMessage());
            JSONObject err = new JSONObject();
            err.put("overall_ok", false);
            err.put("timestamp", System.currentTimeMillis());
            err.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return err.toString();
        }
    }

    /**
     * List all active campaigns.
     */
    @Operation(summary = "List all active campaigns and their status")
    @GetMapping("/campaigns")
    public String getCampaigns(@RequestHeader Map<String, String> headers) {
        LOGGER.info("/campaigns GET API");
        return listCampaigns().toString();
    }

    /**
     * Get status of a specific campaign.
     */
    @Operation(summary = "Get status of a specific campaign")
    @GetMapping("/campaigns/{campaignId}")
    public String getCampaignStatus(
            @RequestHeader Map<String, String> headers,
            @PathVariable String campaignId
    ) {
        LOGGER.info("/campaigns/" + campaignId + " GET API");

        CampaignContext context = getCampaignContext(campaignId);
        if (context == null) {
            JSONObject error = new JSONObject();
            error.put("success", false);
            error.put("error", "Campaign not found: " + campaignId);
            return error.toString();
        }

        return context.getStatus().toString();
    }

    /**
     * Delete a specific campaign.
     */
    @Operation(summary = "Delete a specific campaign and release its resources")
    @DeleteMapping("/campaigns/{campaignId}")
    public String deleteCampaignEndpoint(
            @RequestHeader Map<String, String> headers,
            @PathVariable String campaignId
    ) {
        LOGGER.info("/campaigns/" + campaignId + " DELETE API");
        return deleteCampaign(campaignId, true).toString();
    }

    /**
     * Trigger learning for a specific campaign or all campaigns.
     */
    @Operation(summary = "Trigger learning for a specific campaign. If campaign not specified, triggers legacy single-campaign learning.",
            requestBody = @RequestBody(
                    description = "Optional JSON body to specify campaign",
                    required = false,
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    name = "Campaign Learning",
                                    value = "{\"campaign\":\"my_campaign\"}"
                            )
                    )
            )
    )
    @PostMapping("/learning")
    public String triggerLearning(
            @RequestHeader Map<String, String> headers,
            @org.springframework.web.bind.annotation.RequestBody(required = false) String request
    ) {
        LOGGER.info("/learning POST API");

        try {
            if (request != null && !request.isEmpty()) {
                JSONObject inpObj = new JSONObject(request);
                if (inpObj.has("campaign")) {
                    String campaignId = inpObj.getString("campaign");
                    LOGGER.info("/learning: Triggering learning for campaign: " + campaignId);
                    return triggerCampaignLearning(campaignId);
                }
            }

            // Fall back to legacy learning
            return learning();

        } catch (Exception e) {
            LOGGER.error("/learning error: " + e.getMessage());
            JSONObject error = new JSONObject();
            error.put("success", false);
            error.put("error", e.getMessage());
            return error.toString();
        }
    }

    /**
     * Trigger learning for a specific campaign context.
     */
    private String triggerCampaignLearning(String campaignId) {
        CampaignContext context = getCampaignContext(campaignId);
        if (context == null) {
            JSONObject error = new JSONObject();
            error.put("success", false);
            error.put("error", "Campaign not found: " + campaignId);
            return error.toString();
        }

        try {
            GlobalSettings contextSettings = context.getSettings();
            JSONObject paramDoc = context.getRollingEcosystemRewards().checkCorpora(contextSettings);

            if (paramDoc.isEmpty()) {
                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("message", "No dynamic corpora configured for learning");
                result.put("campaign_id", campaignId);
                return result.toString();
            }

            String algo = paramDoc.getJSONObject("randomisation").getString("approach");
            LOGGER.info("triggerCampaignLearning: Executing " + algo + " for campaign: " + campaignId);

            switch (algo) {
                case "binaryThompson":
                case "epsilonGreedy":
                    context.getRollingEcosystemRewards().process(paramDoc);
                    break;
                case "naiveBayes":
                    context.getRollingNaiveBayes().process(paramDoc);
                    break;
                case "behaviorAlgos":
                    context.getRollingBehavior().process(paramDoc);
                    break;
                case "Network":
                    context.getRollingNetwork().process(paramDoc);
                    break;
                case "QLearning":
                    context.getRollingQLearning().process(paramDoc);
                    break;
                default:
                    LOGGER.warn("Unknown algorithm: " + algo);
            }

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "Learning completed");
            result.put("campaign_id", campaignId);
            result.put("algorithm", algo);
            return result.toString();

        } catch (Exception e) {
            LOGGER.error("triggerCampaignLearning error for " + campaignId + ": " + e.getMessage());
            JSONObject error = new JSONObject();
            error.put("success", false);
            error.put("error", "Learning failed: " + e.getMessage());
            error.put("campaign_id", campaignId);
            return error.toString();
        }
    }

    /**
     * Update responses based on predictions.
     *
     * @param documentJSON documentJSON
     * @return Result
     */
    @Operation(description = "Update response based on recommendation accepted (Async): " +
            "{\"uuid\": \"dcb54a23-0737-4768-845d-48162598c0f7\", \"offers_accepted\": [{\"offer_name\": \"OFFER_A\"}], \"channel_name\": \"app\"}",
            summary = "Update response based on predictions accepted",
            requestBody = @RequestBody(
                    description = "JSON payload containing the UUID of the prediction and the offers accepted.",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(type = "object"),
                            examples = @ExampleObject(
                                    name = "Accepted Offer Response",
                                    value = "{\"uuid\": \"dcb54a23-0737-4768-845d-48162598c0f7\", \"offers_accepted\": [{\"offer_name\": \"OFFER_A\"}], \"channel_name\": \"app\"}"
                            )
                    )
            )
    )
    @PostMapping("/response")
    public String processResponse(@RequestHeader Map<String, String> headers,
                                  @org.springframework.web.bind.annotation.RequestBody String documentJSON) throws Exception {
        LOGGER.info("/response POST API");
        String response = "Success";

        try {
            ecosystemResponse.putResponseReturnDetailAsync(JSONDecode.decode(documentJSON));
        } catch (Exception e) {
            e.printStackTrace();
            JSONObject error = new JSONObject().put("ErrorMessage", e.getMessage());
            response = error.toString();
        }

        return "{\"message\": \"" + response + "\"}";
    }

    /**
     * Business logic service.
     *
     * @param params JSONObject with params
     * @return Result
     */
    @Operation(description = "Access the business logic or other calculations.",
            summary = "Business logic",
            requestBody = @RequestBody(
                    description = "JSON payload for business logic execution. The required parameters depend on the specific business logic being invoked.",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(type = "object"),
                            examples = @ExampleObject(
                                    name = "Example Business Logic Payload",
                                    value = "{\"logic_name\": \"some_calculation\", \"param1\": \"value1\", \"param2\": 123}"
                            )
                    )
            )
    )
    @RequestMapping(value = "/business", method = RequestMethod.POST)
    public String processBusiness(@RequestHeader Map<String, String> headers,
                                  @org.springframework.web.bind.annotation.RequestBody String params) throws Exception {
        LOGGER.info("/business POST API");
        String response = "Success";

        try {
            JSONObject paramsObj = new JSONObject(params);

            this.UPDATE = setFinal(false, new JSONObject());
            paramsObj.put("UPDATE", this.UPDATE);

            return BusinessLogic.getValues(paramsObj).toString();

        } catch (Exception e) {
            e.printStackTrace();
            JSONObject error = new JSONObject().put("ErrorMessage", e.getMessage());
            response = error.toString();
            return "{\"ErrorMessage\": \"" + response + "\"}";
        }

    }

    /**
     * Update responses based on predictions.
     *
     * @param documentJSON documentJSON
     * @return Result
     */
    @Operation(description = "Update response based on recommendation accepted and return valid response: " +
            "{\"uuid\": \"dcb54a23-0737-4768-845d-48162598c0f7\", \"offers_accepted\": [{\"offer_name\": \"OFFER_A\"}], \"channel_name\": \"app\"}",
            summary = "Update response based on predictions accepted",
            requestBody = @RequestBody(
                    description = "JSON payload containing the UUID of the prediction and the offers accepted.",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(type = "object"),
                            examples = @ExampleObject(
                                    name = "Accepted Offer Response",
                                    value = "{\"uuid\": \"dcb54a23-0737-4768-845d-48162598c0f7\", \"offers_accepted\": [{\"offer_name\": \"OFFER_A\"}], \"channel_name\": \"app\"}"
                            )
                    )
            )
    )
    @RequestMapping(value = "/responseResult", method = RequestMethod.POST)
    public String processResponseResult(@RequestHeader Map<String, String> headers,
                                        @org.springframework.web.bind.annotation.RequestBody String documentJSON) throws Exception {
        LOGGER.info("/responseResult POST API");
        String response = "Error";

        try {
            JSONObject responseObj = ecosystemResponse.putResponseReturnDetail(JSONDecode.decode(documentJSON));
            if (responseObj.has("uuid"))
                response = responseObj.getString("uuid");
            else
                response = "Error: UUID not found.";
        } catch (Exception e) {
            e.printStackTrace();
            JSONObject error = new JSONObject().put("ErrorMessage", "Validate that uuid is available in log. " + e.getMessage());
            response = error.toString();
        }

        return "{\"message\": \"" + response + "\"}";
    }

    /**************************************************************************************************************/

    /**
     * Prediction case is determined from properties file setup: mojo's, feature store, and other settings.
     * <p>
     * Recommender Case:
     * From paramsParams - balance enquiry example: {msisdn:0828811817,in_balance:50,voice_balance:12,data_balance:400,n_offers:1}
     * <p>
     * Recommender Case:
     * {'name':'rechargerecommender', 'mojo':'1','mab':{'class':'mabone', 'epsilon':0.4},'dbparam':true, lookup:{key:'msisdn',value:849999330}, param:{key:'in_recharge', value:100}, resultcount:2}
     *
     * @param campaign     campaign
     * @param subcampaign  subcampaign
     * @param customer     customer
     * @param channel      channel
     * @param numberoffers numberoffers
     * @return Result
     */
    @Operation(summary = "Provide offers that form part of a campaign for particular customer. When no feature store is present, use this in params: {'input': ['key1','key2'],'value': ['value1', 'value2']}")
    @RequestMapping(value = "/offerRecommendations",  method = RequestMethod.GET)
    @ResponseStatus(HttpStatus.OK)
    public String getOfferRecommendations(@RequestHeader Map<String, String> headers,
                                          @RequestParam(name = "campaign", defaultValue = "") String campaign,
                                          @RequestParam(name = "subcampaign", defaultValue = "", required = false) String subcampaign,
                                          @RequestParam(name = "customer", defaultValue = "") String customer,
                                          @RequestParam(name = "channel", defaultValue = "") String channel,
                                          @RequestParam(name = "numberoffers", defaultValue = "", required = false) int numberoffers,
                                          @RequestParam(name = "userid", defaultValue = "") String userid,
                                          @RequestParam(name = "params", defaultValue = "", required = false) String params) throws Exception {
        LOGGER.info("/offerRecommendations API");

        /************ Validate and use defaults ***********/
        if (campaign == null || campaign.isEmpty()) {
            campaign = settings.getProjectDeploymentID();
        }

        // Check if this campaign exists in multi-campaign mode
        if (campaignExists(campaign)) {
            LOGGER.info("/offerRecommendations: Routing to multi-campaign context: " + campaign);
            return getOfferRecommendationsWithContext(campaign, subcampaign, customer, channel, numberoffers, userid, params);
        }

        // Fall back to legacy single-campaign mode
        LOGGER.info("/offerRecommendations: Using legacy single-campaign mode for: " + campaign);
        JSONObject predictResult = new JSONObject();
        JSONObject paramsParams = new JSONObject();

        try {

            try {
                String in_params = URLDecoder.decode(params);
                if (in_params.startsWith("\"")) in_params = in_params.substring(1, in_params.length() - 1).replaceAll("\\\\", "");
                paramsParams = new JSONObject(in_params);
            } catch (org.json.JSONException e) {
                LOGGER.info("/offerRecommendations malformed params JSON input: " + params);
                return paramsParams.put("ErrorMessage", e).toString().intern();
            }

            /************ Setup values from input params that will be placed in **********/
            JSONObject param = new JSONObject();
            String uuid = generateUUID();
            /** param.put("headers", headers); */
            param.put("uuid", uuid);
            param.put("UPDATE", this.UPDATE);
            LOGGER.info("/invocations:UUID: " + uuid + " predictor: " + campaign);

            param.put("name", campaign);
            param.put("customer", customer);
            param.put("campaign", campaign);
            param.put("subcampaign", subcampaign);
            param.put("channel", channel);
            param.put("subname", subcampaign);
            param.put("resultcount", numberoffers);
            param.put("userid", userid);
            param.put("mojo", "1");

            /* this is needed to not cause a stack overflow as adding current value of json object */
            JSONObject inParam = new JSONObject(param.toString());
            param.put("api_params", inParam);

            /************ Set defaults for model and paramneters from database ***********/
            param.put("in_params", paramsParams);
            if (paramsParams.has("input")) {
                param.put("input", paramsParams.getJSONArray("input"));
                param.put("value", paramsParams.getJSONArray("value"));
                param.put("lookup", new JSONObject().put("value", customer).put("key", "customer"));
                param.put("dbparam", false);
            } else {
                param.put("dbparam", true);
                param = ValidateParams.getLookupFromParams(settings, param, customer);
            }

            /************ Obtain default epsilon from properties or obtain from input params ***********/
            if (!paramsParams.has("mab")) {
                JSONObject mabParam = new JSONObject();
                mabParam.put("class", "mabone");
                mabParam.put("epsilon", settings.getEpsilon());
                param.put("mab", mabParam);
            } else {
                param.put("mab", paramsParams.getJSONObject("mab"));
            }

            /**************** Primary prediction from EcosystemMaster.getPredictionResult **************/
            predictResult = ecosystemMaster.getPredictionResult(mongoClient, param);
            if (param.has("in_params")) predictResult.put("in_params", param.getJSONObject("in_params"));
            if (predictResult.has("ErrorMessage")) {
                predictResult.put("error", 1);
            }
            predictResult.remove("predict_result");

            String detail = "full";
            if (paramsParams.has("detail"))
                detail = paramsParams.getString("detail");

            /**************** Special prediction approaches: Spam **************/
            if (detail.contains("spam") || subcampaign.contains("spam")) {
                predictResult = extractSpamResult(predictResult);
            }

            /**************** Final step to decide if json needs to be flattened **************/
            if (paramsParams.has("flatten_json") && Boolean.valueOf(String.valueOf(paramsParams.opt("flatten_json")))) {
                predictResult = JSONFlattener.flatten(predictResult);
            }

        } catch (Exception e) {
            e.printStackTrace();
            predictResult.put("ErrorMessage", e.getMessage());
        }

        this.UPDATE = setFinal(false, predictResult);

        return predictResult.toString();

    }

    /**
     * Execute offer recommendations using a multi-campaign context.
     */
    private String getOfferRecommendationsWithContext(String campaignId, String subcampaign, String customer,
                                                      String channel, int numberoffers, String userid,
                                                      String params) {
        JSONObject predictResult = new JSONObject();

        try {
            CampaignAwarePredictor predictor = getCampaignPredictor(campaignId);
            if (predictor == null) {
                predictResult.put("ErrorMessage", "Campaign context not found: " + campaignId);
                return predictResult.toString();
            }

            CampaignContext context = getCampaignContext(campaignId);
            GlobalSettings contextSettings = context.getSettings();

            JSONObject paramsParams = new JSONObject();
            try {
                String in_params = URLDecoder.decode(params);
                if (in_params.startsWith("\"")) in_params = in_params.substring(1, in_params.length() - 1).replaceAll("\\\\", "");
                paramsParams = new JSONObject(in_params);
            } catch (org.json.JSONException e) {
                LOGGER.info("/offerRecommendations malformed params JSON input: " + params);
                return paramsParams.put("ErrorMessage", e).toString();
            }

            JSONObject param = new JSONObject();
            String uuid = generateUUID();
            param.put("uuid", uuid);
            param.put("UPDATE", this.UPDATE);
            LOGGER.info("/offerRecommendations:UUID: " + uuid + " campaign: " + campaignId + " (multi-campaign)");

            param.put("name", campaignId);
            param.put("customer", customer);
            param.put("campaign", campaignId);
            param.put("subcampaign", subcampaign);
            param.put("channel", channel);
            param.put("subname", subcampaign);
            param.put("resultcount", numberoffers);
            param.put("userid", userid);
            param.put("mojo", "1");

            JSONObject inParam = new JSONObject(param.toString());
            param.put("api_params", inParam);

            param.put("in_params", paramsParams);
            if (paramsParams.has("input")) {
                param.put("input", paramsParams.getJSONArray("input"));
                param.put("value", paramsParams.getJSONArray("value"));
                param.put("lookup", new JSONObject().put("value", customer).put("key", "customer"));
                param.put("dbparam", false);
            } else {
                param.put("dbparam", true);
                param = ValidateParams.getLookupFromParams(contextSettings, param, customer);
            }

            if (!paramsParams.has("mab")) {
                JSONObject mabParam = new JSONObject();
                mabParam.put("class", "mabone");
                mabParam.put("epsilon", contextSettings.getEpsilon());
                param.put("mab", mabParam);
            } else {
                param.put("mab", paramsParams.getJSONObject("mab"));
            }

            predictResult = predictor.getPredictionResult(param);
            if (param.has("in_params")) predictResult.put("in_params", param.getJSONObject("in_params"));
            if (predictResult.has("ErrorMessage")) {
                predictResult.put("error", 1);
            }
            predictResult.remove("predict_result");

            String detail = "full";
            if (paramsParams.has("detail"))
                detail = paramsParams.getString("detail");

            if (detail.contains("spam") || subcampaign.contains("spam")) {
                predictResult = extractSpamResult(predictResult);
            }

            if (paramsParams.has("flatten_json") && Boolean.valueOf(String.valueOf(paramsParams.opt("flatten_json")))) {
                predictResult = JSONFlattener.flatten(predictResult);
            }

        } catch (Exception e) {
            e.printStackTrace();
            predictResult.put("ErrorMessage", e.getMessage());
        }

        this.UPDATE = setFinal(false, predictResult);
        return predictResult.toString();
    }

    /**
     * Update offers taken up by customers/msisdn.
     *
     * @deprecated Use {@code POST /invocations} for scoring and {@code POST /response}
     *             (singular) with {@code uuid} + {@code offer_name} for closed-loop feedback.
     * @param documentJSON documentJSON
     * @return Result
     */
    @Deprecated
    @Operation(
            deprecated = true,
            description = "DEPRECATED. Migrate scoring to POST /invocations; for accept/feedback "
                    + "use POST /response with uuid + offer_name (do not use PUT /offerRecommendations). "
                    + "Legacy async accept body example: "
                    + "{\"uuid\": \"dcb54a23-0737-4768-845d-48162598c0f7\", \"offers_accepted\": [{\"offer_name\": \"OFFER_A\"}], \"channel_name\": \"app\"}",
            summary = "DEPRECATED — use POST /invocations then POST /response")
    @RequestMapping(value = "/offerRecommendations",  method = RequestMethod.PUT)
    @ResponseStatus(HttpStatus.OK)
    public String putOfferRecommendations(@RequestHeader Map<String, String> headers,
                                          @RequestParam(name = "document", defaultValue = "") String documentJSON) throws IOException, ParseException {
        LOGGER.info("/offerRecommendations PUT API (deprecated; use POST /invocations + POST /response)");
        LOGGER.info(documentJSON);

        String response = "Success";
        try {
            ecosystemResponse.putResponseReturnDetailAsync(JSONDecode.decode(documentJSON));
        } catch (Exception e) {
            e.printStackTrace();
            JSONObject error = new JSONObject().put("ErrorMessage", "Validate that uuid is available in log. " + e.getMessage());
            response = error.toString();
        }

        return "{\"message\": \"" + response + "\"}";
    }

    /**
     * Update offers taken up by customers/msisdn.
     *
     * @deprecated Use {@code POST /invocations} for scoring and {@code POST /response}
     *             (singular) with {@code uuid} + {@code offer_name} for closed-loop feedback.
     * @param documentJSON documentJSON
     * @return Result
     */
    @Deprecated
    @Operation(
            deprecated = true,
            description = "DEPRECATED. Migrate scoring to POST /invocations; for accept/feedback "
                    + "use POST /response with uuid + offer_name (do not use PUT /offerRecommendationsResult). "
                    + "Legacy accept body example: "
                    + "{\"uuid\": \"dcb54a23-0737-4768-845d-48162598c0f7\", \"offers_accepted\": [{\"offer_name\": \"OFFER_A\"}], \"channel_name\": \"app\"}",
            summary = "DEPRECATED — use POST /invocations then POST /response")
    @RequestMapping(value = "/offerRecommendationsResult",  method = RequestMethod.PUT)
    @ResponseStatus(HttpStatus.OK)
    public String putOfferRecommendationsResult(@RequestHeader Map<String, String> headers,
                                                @RequestParam(name = "document", defaultValue = "") String documentJSON) throws IOException, ParseException {
        LOGGER.info("/offerRecommendationsResult PUT API (deprecated; use POST /invocations + POST /response)");
        LOGGER.info(documentJSON);

        String response;
        try {
            JSONObject responseObj = ecosystemResponse.putResponseReturnDetail(JSONDecode.decode(documentJSON));
            if (responseObj != null)
                response = responseObj.getString("uuid");
            else
                response = "none";
        } catch (Exception e) {
            e.printStackTrace();
            JSONObject error = new JSONObject().put("ErrorMessage", "Validate that uuid is available in log. " + e.getMessage());
            response = error.toString();
        }

        return "{\"message\": \"" + response + "\"}";
    }

    /**
     * Score model from pre-loaded mojo as set in the properties file
     *
     * @param valueJSON Example: {'name':'predict1', 'kafka':{'TOPIC_NAME':'ecosystem1','log':'true'},'mojo':'1', 'input':['x','y'], 'value':['val_x','val_y']} OR {'name':'predict1', 'kafka':{'TOPIC_NAME':'ecosystem1','log':'true'}, 'mojo':'1', 'dbparam':true, lookup:{key:'customer',value:'1234567890'} }
     * @param detail    Detail to return: all, basic or none
     * @return Result
     */
    @Operation(description = "Perform prediction on pre-loaded model with detail and push onto Kafka topic: none, basic or all. Perform a database lookup if properties file has been set. " +
            "Example parameter: Example: {'name':'predict1', 'kafka':{'TOPIC_NAME':'ecosystem1','log':'true'},'mojo':'1', 'input':['x','y'], 'value':['val_x','val_y']} OR {'name':'predict1', 'kafka':{'TOPIC_NAME':'ecosystem1','log':'true'}, 'mojo':'1', 'dbparam':true, lookup:{key:'customer',value:'1234567890'} }" +
            "", summary = "Perform prediction on pre-loaded model with detail and push onto Kafka topic")
    @RequestMapping(value = "/predictorResponsePreLoadKafKa", method = RequestMethod.GET)
    @ResponseStatus(HttpStatus.OK)
    public String predictorResponsePreLoadKafKa(
            @RequestHeader Map<String, String> headers,
            @RequestParam(name = "value",
                    defaultValue = "{'name': 'predict1', 'kafka': {'TOPIC_NAME': 'ecosystem1', 'log': 'true'},'mojo': '1', 'input': ['x', 'y'], 'value': ['val_x', 'val_y']} " +
                            "OR {'name': 'predict1', 'kafka': {'TOPIC_NAME': 'ecosystem1','log': 'true'}, 'mojo': '1', 'dbparam': true, lookup: {key: 'customer', value: '1234567890'} }") String valueJSON,
            @RequestParam(name = "detail",
                    defaultValue = "none") String detail) {
        LOGGER.info("/predictorResponsePreLoadKafKa params: " + valueJSON);
        try {
            JSONObject params = new JSONObject(JSONDecode.decode(valueJSON));
            this.UPDATE = setFinal(false, params);
            params.put("UPDATE", this.UPDATE);
            return ecosystemMaster.getPredictionResultToKafka(mongoClient, params);
        } catch (Exception e) {
            LOGGER.error("PredictorMaster:predictorResponsePreLoadKafKa:E000: Param error: " + e);
            return "{\"ErrorMessage\": \"PredictorMaster:predictorResponsePreLoadKafKa:E000-1: Parameter error.\"}";
        }

    }

    /**
     * Score model from pre-loaded mojo as set in the properties file
     *
     * @param valueJSON Example: {'name':'predict1', 'mojo':'1','dbparam':true, lookup:{key:'customer_id',value:724578004}} OR if parameter is not
     * from database use: {'name':'predict1', 'mojo':'1..3','dbparam':false, 'input':['x','y'], 'value':['val_x','val_y']}
     * Use x:n to define the number of predictions to return as primary result, if the overall probability is not used.
     * Optional 'resultcount':3  if not present in parameter, then return one item
     * @param detail    Detail to return: all, basic or none
     * @return Result
     */
    @Operation(description = "Perform prediction on pre-loaded model with detail: none, basic or all. Perform a database lookup if properties file has been set. " +
            "The predictor parameters are broken into two types namely, requiring all parameters via API or requiring a lookup key via API and extracting parameters from a data source. Use this format for input prams only:" +
            "{'name': 'predict1', 'mojo': 1, 'dbparam': false, 'input': ['x','y'], 'value': ['val_x', 'val_y'], lookup: {'key': '', 'value': ''}}" +
            "Use this approach for inputs from data source:" +
            "{'name': 'predict1', 'mojo': 'model_mojo.zip', 'dbparam': true, lookup: {key: 'customer', value: 1234567890}} " +
            "If there is post-scoring logic, then ise this configuration:" +
            "{'name': 'predict1', 'mojo': '1', 'mab': {'class': 'mabone', 'epsilon': 0.4},'dbparam': true, lookup: {key: 'customer', value: 1234567890}, param: {key: 'value_field', value: 30}}" +
            "", summary = "Perform prediction on pre-loaded model with detail.")
    @RequestMapping(value = "/predictorResponsePreLoad", method = RequestMethod.GET)
    @ResponseStatus(HttpStatus.OK)
    public String predictorResponsePreLoad(
            @RequestHeader Map<String, String> headers,
            @RequestParam(name = "value",
                    defaultValue = "{'name': 'predict1', 'mojo': '1..16', 'dbparam': false, 'input': ['x','y'], 'value': ['val_x', 'val_y'], 'x': 1} " +
                            "OR {'name': 'predict1', 'mojo': '1','dbparam': true, lookup: {key: 'customer',value: '1234567890'}} + " +
                            "OR {'name': 'predict1', 'mojo': '1','mab': {'class': 'mabone', 'epsilon': 0.4},'dbparam': true, lookup: {key: 'customer',value: 1234567890}, param: {key: 'value_field', value: 30}, resultcount: 3}") String valueJSON,
            @RequestParam(name = "detail",
                    defaultValue = "all") String detail) throws Exception {
        LOGGER.info("/predictorResponsePreLoad params: " + valueJSON);
        String uuid = UUID.randomUUID().toString();
        JSONObject params = new JSONObject(JSONDecode.decode(valueJSON));
        params.put("uuid", uuid);
        params.put("UPDATE", this.UPDATE);
        this.UPDATE = setFinal(false, params);
        return ecosystemMaster.getPredictionResult(mongoClient, params).toString();
    }

}